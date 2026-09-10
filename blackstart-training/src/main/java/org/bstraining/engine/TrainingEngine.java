package org.bstraining.engine;

import org.bstraining.model.*;
import org.bstraining.persistence.SessionRepository;
import org.bstraining.rules.RuleEngine;
import org.bstraining.sim.EmulatedSimulator;
import org.bstraining.sim.EventTransport;
import org.bstraining.sim.SimulatorPort;
import org.bstraining.stream.EventHashChain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 训练编排引擎：口令时序、潮流快照、规则候选、持久化、事件监听。
 *
 * <p>事件入口 {@link #orderedTransport()} 接收的是<b>重排后</b>的有序事件
 * （Akka 接入时由 ReorderingFlow 交付；直连测试时仿真器直接交付）。
 * 口令边界事件 COMMAND_COMPLETE 触发“口令后”快照入库与规则评估。</p>
 */
public final class TrainingEngine {

    /** 顺序控制：未完成上一条口令前不接受下一条（桌面培训单学员操作）。 */
    private final SessionRepository repo;
    private final SimulatorPort simulator;
    private final Scenario frozenScenario;
    private final RuleEngine ruleEngine;
    private final EventHashChain hasher = new EventHashChain();

    private long sessionId;
    private long nextCommandSeq = 1;
    private final List<CommandRecord> commandHistory = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();

    private final Map<Long, List<SimEvent>> pendingEvents = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<Void>> pendingComplete = new ConcurrentHashMap<>();
    private final Object submitLock = new Object();

    public interface Listener {
        default void onEvent(SimEvent event) {
        }

        default void onCommandRecorded(CommandRecord record, List<CandidateFlag> flags) {
        }
    }

    public TrainingEngine(SessionRepository repo, Scenario frozenScenario) {
        this(repo, frozenScenario, new EmulatedSimulator());
    }

    public TrainingEngine(SessionRepository repo, Scenario frozenScenario, SimulatorPort simulator) {
        this(repo, frozenScenario, simulator, true);
    }

    /**
     * @param wireDirect true：仿真器直连有序入口（测试用，事件本就有序）；
     *                   false：外部通过 Akka 网关接入（先取 {@link #orderedTransport()}
     *                   物化流图，再把网关 transport 设给仿真器）。
     */
    public TrainingEngine(SessionRepository repo, Scenario frozenScenario,
                          SimulatorPort simulator, boolean wireDirect) {
        this.repo = repo;
        this.frozenScenario = frozenScenario;
        this.simulator = simulator;
        this.ruleEngine = new RuleEngine(frozenScenario);
        if (wireDirect) {
            this.simulator.setTransport(orderedTransport());
        }
    }

    /** 外部（Akka 网关）接入时，把网关出口连到仿真器。 */
    public void attachExternalTransport(EventTransport simulatorSide) {
        this.simulator.setTransport(simulatorSide);
    }

    public long start(String trainee, String instructor) {
        this.sessionId = repo.createSession(trainee, instructor, frozenScenario);
        simulator.attach("SES-" + sessionId, frozenScenario);
        return sessionId;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public SessionRepository repository() {
        return repo;
    }

    public long sessionId() {
        return sessionId;
    }

    public Scenario scenario() {
        return frozenScenario;
    }

    public SimulatorPort simulator() {
        return simulator;
    }

    public List<CommandRecord> history() {
        return List.copyOf(commandHistory);
    }

    /** 仿真器事件出口：接在此处的事件必须已经过乱序重排。 */
    public EventTransport orderedTransport() {
        return this::handleOrdered;
    }

    /**
     * 提交学员口令。阻塞等待该口令全部系统响应（含 COMMAND_COMPLETE）处理完毕。
     *
     * @return 口令记录（含前/后快照）
     */
    public CommandRecord submitCommand(OperatorCommand.CommandType type, String elementId,
                                       String targetBusId, String spoken, String actor) {
        synchronized (submitLock) {
            long seq = nextCommandSeq++;
            OperatorCommand cmd = new OperatorCommand(seq, type, elementId, targetBusId,
                    spoken, actor, null);
            PowerFlowSnapshot before = simulator.snapshot(seq, true);
            CompletableFuture<CommandRecord> done = new CompletableFuture<>();
            pendingComplete.put(seq, new CompletableFuture<>());
            pendingEvents.put(seq, new ArrayList<>());

            simulator.submit(cmd);
            try {
                pendingComplete.get(seq).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("等待口令响应超时: " + type, e);
            }

            List<SimEvent> evs = pendingEvents.remove(seq);
            CompletableFuture<Void> f = pendingComplete.remove(seq);
            PowerFlowSnapshot after = simulator.snapshot(seq, false);

            repo.appendCommandWithSnapshots(sessionId, cmd, Instant.now(),
                    after.simTime(), before, after);
            CommandRecord record = new CommandRecord(seq, cmd, Instant.now(),
                    after.simTime(), null, null);

            List<CandidateFlag> flags = ruleEngine.evaluate(cmd, before, after, evs, commandHistory);
            List<CandidateFlag> saved = new ArrayList<>();
            for (CandidateFlag flag : flags) {
                long id = repo.addCandidate(sessionId, flag);
                saved.add(flag.withId(id));
            }
            commandHistory.add(record);
            listeners.forEach(l -> l.onCommandRecorded(record, saved));
            done.complete(record);
            return record;
        }
    }

    /** 场景重启：仿真状态复原；历史事件流保留并追加重启记录。 */
    public void restart() {
        repo.incrementRestart(sessionId);
        simulator.restart();
    }

    public void tick() {
        simulator.tick();
    }

    // ------------------------------------------------------------------

    private void handleOrdered(SimEvent raw) {
        SimEvent e = hasher.chain(raw);
        long ord = repo.nextArrivalOrd(sessionId);
        repo.appendEvent(sessionId, e, ord);
        listeners.forEach(l -> l.onEvent(e));
        Long cs = e.commandSeq();
        if (cs != null) {
            List<SimEvent> bucket = pendingEvents.get(cs);
            if (bucket != null) {
                bucket.add(e);
            }
            if (e.type() == EventType.COMMAND_COMPLETE) {
                CompletableFuture<Void> f = pendingComplete.get(cs);
                if (f != null) {
                    f.complete(null);
                }
            }
        }
    }

    public Map<Long, List<SimEvent>> debugPending() {
        return new LinkedHashMap<>(pendingEvents);
    }
}
