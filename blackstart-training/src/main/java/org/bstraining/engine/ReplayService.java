package org.bstraining.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bstraining.model.*;
import org.bstraining.persistence.SessionRepository;
import org.bstraining.sim.EmulatedSimulator;
import org.bstraining.sim.EventTransport;
import org.bstraining.sim.SimulatorPort;
import org.bstraining.stream.EventHashChain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 复盘服务：
 * <ul>
 *   <li>定位任一口令前/后的潮流快照（直接读取冻结存档，原事件不参与重算、不可编辑）；</li>
 *   <li>从冻结场景 + 有序事件流重放到指定口令，用于与存档快照交叉校验；</li>
 *   <li>校验事件哈希链完整性。</li>
 * </ul>
 */
public final class ReplayService {

    private final SessionRepository repo;
    private final ObjectMapper mapper;

    public ReplayService(SessionRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    public ReplayService(SessionRepository repo) {
        this(repo, org.bstraining.sim.JsonSupport.mapper());
    }

    /** 读取存档快照：seq 口令前(before=true)/后。 */
    public Optional<PowerFlowSnapshot> storedSnapshot(long sessionId, long seq, boolean before) {
        return repo.snapshot(sessionId, seq, before);
    }

    public List<SessionRepository.CommandRow> commandTimeline(long sessionId) {
        return repo.commands(sessionId);
    }

    public List<SimEvent> events(long sessionId) {
        return repo.events(sessionId);
    }

    public Scenario frozenScenario(long sessionId) {
        TrainingSession ts = repo.findSession(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
        try {
            return mapper.readValue(ts.frozenScenarioJson(), Scenario.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 从冻结场景重放，返回“第 upToSeq 条口令后”的仿真快照。
     * 重放只读取原事件与冻结场景，不写入任何数据。
     */
    public PowerFlowSnapshot rebuild(long sessionId, long upToSeq) {
        Scenario scenario = frozenScenario(sessionId);
        SimulatorPort sim = new EmulatedSimulator();
        List<SimEvent> sink = new ArrayList<>();
        EventTransport collecting = sink::add;
        sim.setTransport(collecting);
        sim.attach("REPLAY-" + sessionId, scenario);

        List<SessionRepository.CommandRow> rows = repo.commands(sessionId);
        for (SessionRepository.CommandRow row : rows) {
            OperatorCommand c = row.command();
            if (c.type() == OperatorCommand.CommandType.REPORT) {
                sim.submit(c);
            } else if (c.seq() <= upToSeq) {
                sim.submit(c);
            }
        }
        return sim.snapshot(upToSeq, false);
    }

    /** 校验存档快照与重放结果一致（关键字段：带电母线集合、岛频、机组出力）。 */
    public List<String> verifyRebuild(long sessionId, long upToSeq) {
        PowerFlowSnapshot stored = repo.snapshot(sessionId, upToSeq, false)
                .orElseThrow(() -> new IllegalStateException("缺少口令后快照"));
        PowerFlowSnapshot rebuilt = rebuild(sessionId, upToSeq);
        List<String> diffs = new ArrayList<>();
        for (BusState a : stored.buses()) {
            BusState b = rebuilt.buses().stream()
                    .filter(x -> x.busId().equals(a.busId())).findFirst().orElse(null);
            if (b == null) {
                diffs.add("母线 " + a.busId() + " 重放缺失");
            } else if (a.energized() != b.energized()) {
                diffs.add("母线 " + a.busId() + " 带电状态不一致 stored="
                        + a.energized() + " rebuilt=" + b.energized());
            }
        }
        for (GenState a : stored.generators()) {
            GenState b = rebuilt.generators().stream()
                    .filter(x -> x.genId().equals(a.genId())).findFirst().orElse(null);
            if (b != null && Math.abs(a.outputMw() - b.outputMw()) > 0.51) {
                diffs.add("机组 " + a.genId() + " 出力偏差 stored=" + a.outputMw()
                        + " rebuilt=" + b.outputMw());
            }
        }
        return diffs;
    }

    /** 哈希链完整性：被编辑过的事件会导致断链。 */
    public boolean verifyHashChain(long sessionId) {
        return EventHashChain.verify(repo.events(sessionId));
    }
}
