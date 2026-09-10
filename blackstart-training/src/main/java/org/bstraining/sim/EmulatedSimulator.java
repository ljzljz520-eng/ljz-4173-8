package org.bstraining.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bstraining.model.*;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * 内置教学仿真器（非真实电网控制）。
 *
 * <p>模型范围（均为教学级简化）：
 * <ul>
 *   <li>拓扑：闭合线路 + 带电母线构成电气岛（无向图连通分量），岛内含任意台运行机组即带电；</li>
 *   <li>频率：f = 50 + (Pgen - Pload) / 调差系数，周期节拍内按转动惯量向准稳态过渡；
 *       低于 underFreqShedHz 触发低频减载（按优先级切负荷）；</li>
 *   <li>电压：以带电岛内最近运行机组为参考 1.0p.u.，沿闭合路径按 Σ(r·P+x·Q=0 简化为 z·P) 降落；</li>
 *   <li>同期：并列点两侧频差/压差/角差门槛在冻结的保护设置中；CLOSE_LINE 强合使用更严的硬合门槛，
 *       越限即按误并列冲击保护跳闸（ISLAND_MERGED 后紧接 MISPARALLEL_TRIP，联络线再跳开）；</li>
 *   <li>隐藏故障：机组自启动失败、线路充电即跳，仅在命中操作时暴露。</li>
 * </ul>
 * 全部状态来源于冻结场景的副本，重启可精确复现初始状态。</p>
 */
public final class EmulatedSimulator implements SimulatorPort {

    /** 单机教学调差：每 1MW 不平衡对应的 Hz 偏移系数。 */
    static final double DROOP_HZ_PER_MW = 0.02;
    /** 低频减载每次节拍切除轮次（优先级由低到高）。 */
    static final double TICK_SECONDS = 0.5;
    /** 节拍内频率向准稳态收敛比例。 */
    static final double CONVERGE = 0.35;

    private static final ObjectMapper M = JsonSupport.mapper();

    private String sessionId;
    private Scenario scenario;
    private EventTransport transport = e -> { };

    private final SimClock clock = new SimClock(java.time.Instant.parse("2026-01-01T00:00:00Z"));

    // ---- 可变运行态（全部由冻结场景初始化，restart 可重建）----
    private final Map<String, Boolean> genRunning = new HashMap<>();
    private final Map<String, Double> genOutput = new HashMap<>();
    private final Map<String, Boolean> lineClosed = new HashMap<>();
    private final Map<String, Boolean> loadConnected = new HashMap<>();
    private final Map<String, Double> busAngle = new HashMap<>();
    private final Map<String, Double> busFreq = new HashMap<>();

    private long nextSeq = 1;

    @Override
    public void attach(String sessionId, Scenario frozenScenario) {
        this.sessionId = sessionId;
        this.scenario = frozenScenario;
        resetState();
        SimEvent init = mkEvent(null, EventType.COMMAND_ACCEPTED, null,
                "会话[" + sessionId + "]已绑定冻结场景《" + frozenScenario.name() + "》，开始黑启动训练",
                null);
        transport.emit(init);
    }

    @Override
    public void setTransport(EventTransport transport) {
        this.transport = Objects.requireNonNull(transport);
    }

    @Override
    public void restart() {
        resetState();
        SimEvent r = mkEvent(null, EventType.COMMAND_ACCEPTED, null,
                "场景重启：网架/机组/电源/保护/隐藏故障恢复到冻结初始状态（事件流不清除，追加重启记录）",
                null);
        transport.emit(r);
    }

    private void resetState() {
        genRunning.clear();
        genOutput.clear();
        lineClosed.clear();
        loadConnected.clear();
        busAngle.clear();
        busFreq.clear();
        for (GeneratorUnit g : scenario.generators()) {
            genRunning.put(g.id(), g.initiallyRunning());
            genOutput.put(g.id(), g.initiallyRunning() ? Math.max(g.minStableMw(), 0.0) : 0.0);
        }
        for (Line l : scenario.lines()) {
            lineClosed.put(l.id(), l.initiallyClosed());
        }
        for (LoadNode ld : scenario.loads()) {
            loadConnected.put(ld.id(), ld.initiallyConnected());
        }
        clock.reset(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        nextSeq = 1;
        Topology t = recompute(List.of());
        t.busAngle.forEach(busAngle::put);
        t.busFreq.forEach(busFreq::put);
    }

    // ------------------------------------------------------------------
    // 口令处理
    // ------------------------------------------------------------------

    @Override
    public void submit(OperatorCommand cmd) {
        transport.emit(mkEvent(cmd.seq(), EventType.COMMAND_ACCEPTED, cmd.elementId(),
                "口令已受理: " + cmd.type().cn + describe(cmd), null));
        List<SimEvent> extra = new ArrayList<>();
        String reject = execute(cmd, extra);
        if (reject != null) {
            transport.emit(mkEvent(cmd.seq(), EventType.COMMAND_REJECTED, cmd.elementId(),
                    "口令被拒/无法执行: " + reject, null));
        } else {
            extra.forEach(transport::emit);
        }
        // 边界：口令完成，上层以此截取“口令后”快照
        transport.emit(mkEvent(cmd.seq(), EventType.COMMAND_COMPLETE, cmd.elementId(),
                "口令处理完成: " + cmd.type().cn, null));
    }

    private String describe(OperatorCommand c) {
        String name = elementName(c.elementId());
        String tgt = c.targetBusId() == null ? "" : " 至 " + elementName(c.targetBusId());
        return " [" + (name == null ? c.elementId() : name) + tgt + "]";
    }

    private String elementName(String id) {
        if (id == null) return null;
        for (Bus b : scenario.buses()) if (b.id().equals(id)) return b.name();
        for (GeneratorUnit g : scenario.generators()) if (g.id().equals(id)) return g.name();
        for (Line l : scenario.lines()) if (l.id().equals(id)) return l.name();
        for (LoadNode ld : scenario.loads()) if (ld.id().equals(id)) return ld.name();
        return id;
    }

    /** @return null 表示执行成功，extra 中为响应事件；非 null 为拒绝原因。 */
    private String execute(OperatorCommand cmd, List<SimEvent> out) {
        return switch (cmd.type()) {
            case START_GEN -> execStartGen(cmd, out);
            case RAISE_GEN -> execRaiseGen(cmd, out);
            case CLOSE_LINE -> execCloseLine(cmd, out, false);
            case SYNC_TIE -> execCloseLine(cmd, out, true);
            case RESYNC_ALIGN -> execResyncAlign(cmd, out);
            case OPEN_LINE -> execOpenLine(cmd, out);
            case RESTORE_LOAD -> execLoad(cmd, out, true);
            case DISCONNECT_LOAD -> execLoad(cmd, out, false);
            case REPORT -> null; // 纯通信口令，无电气操作
        };
    }

    private String execStartGen(OperatorCommand cmd, List<SimEvent> out) {
        GeneratorUnit g;
        try {
            g = scenario.generator(cmd.elementId());
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        if (Boolean.TRUE.equals(genRunning.get(g.id()))) {
            return "机组 " + g.name() + " 已在运行（前置条件：机组停机）";
        }
        if (!g.blackstartCapable() && !busHasPower(g.busId())) {
            return "机组 " + g.name() + " 非黑启动机组，其母线 " + nameOfBus(g.busId())
                    + " 无厂用启动电源（前置条件：母线带电/外部启动电源）";
        }
        boolean hidden = scenario.hiddenFaults().stream()
                .anyMatch(h -> h.elementId().equals(g.id())
                        && h.kind() == HiddenFault.FaultKind.GEN_FAIL_TO_START);
        if (hidden) {
            out.add(mkEvent(cmd.seq(), EventType.GEN_START_FAILED, g.id(),
                    "隐藏故障暴露：机组 " + g.name() + " 自启动失败（启动电源缺陷/辅机故障），母线未带电",
                    faultJson(g.id())));
            return "机组自启动失败（隐藏故障）";
        }
        genRunning.put(g.id(), true);
        double mw = Math.max(g.minStableMw(), g.ratedMw() * 0.3);
        genOutput.put(g.id(), mw);
        applyTopology(cmd.seq(), out, List.of());
        out.add(mkEvent(cmd.seq(), EventType.GEN_STARTED, g.id(),
                "机组 " + g.name() + " 自启动成功，已建压带 " + fmt(mw) + " MW，母线 "
                        + nameOfBus(g.busId()) + " 充电",
                nodeJson("mw", mw)));
        return null;
    }

    private String execRaiseGen(OperatorCommand cmd, List<SimEvent> out) {
        GeneratorUnit g;
        try {
            g = scenario.generator(cmd.elementId());
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        if (!Boolean.TRUE.equals(genRunning.get(g.id()))) {
            return "机组未运行（前置条件：机组已启动）";
        }
        double step = g.rampMwPerMin() * TICK_SECONDS * 60.0 * 0.5; // 一次加出力口令
        double before = genOutput.get(g.id());
        double after = Math.min(g.ratedMw(), before + Math.max(step, g.ratedMw() * 0.1));
        genOutput.put(g.id(), after);
        applyTopology(cmd.seq(), out, List.of());
        out.add(mkEvent(cmd.seq(), EventType.GEN_OUTPUT_CHANGED, g.id(),
                "机组 " + g.name() + " 出力 " + fmt(before) + " → " + fmt(after) + " MW",
                nodeJson("mw", after)));
        return null;
    }

    private String execCloseLine(OperatorCommand cmd, List<SimEvent> out, boolean sync) {
        Line line;
        try {
            line = scenario.line(cmd.elementId());
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        if (Boolean.TRUE.equals(lineClosed.get(line.id()))) {
            return line.name() + " 已在合位（前置条件：开关分位）";
        }
        boolean fromLive = busHasPower(line.fromBusId());
        boolean toLive = busHasPower(line.toBusId());
        if (!fromLive && !toLive) {
            return line.name() + " 两侧均无电（前置条件：至少一侧有充电电源，先启动电源再送线路）";
        }

        // 并列点两侧同期量必须在合环前采集
        double df = Double.NaN, dv = Double.NaN, da = Double.NaN;
        boolean energizedBefore = fromLive && toLive;
        if (energizedBefore) {
            df = Math.abs(freqOf(line.fromBusId()) - freqOf(line.toBusId()));
            dv = Math.abs(voltageBefore(line.fromBusId()) - voltageBefore(line.toBusId()));
            da = Math.abs(norm180(angleOf(line.fromBusId()) - angleOf(line.toBusId())));
        }

        // 隐藏故障：充电即跳
        boolean hiddenFault = scenario.hiddenFaults().stream()
                .anyMatch(h -> h.elementId().equals(line.id())
                        && h.kind() == HiddenFault.FaultKind.LINE_TRIP_ON_ENERGIZE);

        lineClosed.put(line.id(), true);

        if (energizedBefore) {
            // 两电气岛并列：同期校核
            ProtectionSettings p = scenario.protection();
            double angleLimit = sync ? p.syncDangleDeg() : p.hardCloseDangleDeg();
            Map<String, Object> chk = new LinkedHashMap<>();
            chk.put("dfHz", df);
            chk.put("dvPu", dv);
            chk.put("dAngleDeg", da);
            chk.put("syncCommand", sync);
            boolean pass = df <= p.syncDfMaxHz() && dv <= p.syncDvMaxPu() && da <= angleLimit;

            if (pass) {
                out.add(mkEvent(cmd.seq(), EventType.SYNC_CHECK_PASSED, line.id(),
                        "同期检查通过（Δf=" + fmt(df) + "Hz, ΔU=" + fmt(dv)
                                + "p.u., Δδ=" + fmt(da) + "°），允许并列",
                                write(chk)));
                applyTopology(cmd.seq(), out, List.of());
                out.add(mkEvent(cmd.seq(), EventType.ISLAND_MERGED, line.id(),
                        "两电气岛经 " + line.name() + " 同期并列成功", write(chk)));
            } else {
                String mode = sync ? "同期并列" : "未同期强合(硬合)";
                out.add(mkEvent(cmd.seq(), EventType.SYNC_CHECK_FAILED, line.id(),
                        "同期检查不满足仍执行" + mode + "：Δf=" + fmt(df) + "Hz, ΔU=" + fmt(dv)
                                + "p.u., Δδ=" + fmt(da) + "°，门槛 Δf≤" + p.syncDfMaxHz()
                                + ", ΔU≤" + p.syncDvMaxPu() + ", Δδ≤" + angleLimit + "°",
                                write(chk)));
                applyTopology(cmd.seq(), out, List.of());
                out.add(mkEvent(cmd.seq(), EventType.ISLAND_MERGED, line.id(),
                        "两岛已瞬时并入（带冲击）", write(chk)));
                // 误并列冲击保护：联络线跳开，恢复两岛
                lineClosed.put(line.id(), false);
                applyTopology(cmd.seq(), out, List.of());
                out.add(mkEvent(cmd.seq(), EventType.MISPARALLEL_TRIP, line.id(),
                        "非同期合闸冲击，" + line.name() + " 保护动作跳闸，两侧系统解列；"
                                + "强冲击可能导致机组振荡（教员依据系统响应评估后果）",
                        write(chk)));
                return null;
            }
        } else {
            applyTopology(cmd.seq(), out, List.of());
            out.add(mkEvent(cmd.seq(), EventType.LINE_CLOSED, line.id(),
                    (fromLive ? nameOfBus(line.fromBusId()) : nameOfBus(line.toBusId()))
                            + " 侧向 " + line.name() + " 充电，对侧母线 "
                            + (fromLive ? nameOfBus(line.toBusId()) : nameOfBus(line.fromBusId()))
                            + " 带电", null));
        }

        if (hiddenFault) {
            lineClosed.put(line.id(), false);
            applyTopology(cmd.seq(), out, List.of());
            out.add(mkEvent(cmd.seq(), EventType.LINE_TRIP_FAULT, line.id(),
                    "隐藏故障暴露：" + line.name() + " 充电后保护动作跳闸（绝缘/设备隐患），下游失电",
                    faultJson(line.id())));
        }
        return null;
    }

    private String execResyncAlign(OperatorCommand cmd, List<SimEvent> out) {
        // 教学处理：对目标母线所在孤岛进行一次调频逼近同期点，并将其相角对齐到
        // 联络线对侧（elementId=联络线，targetBusId=待调整侧母线）。
        String busId = cmd.targetBusId() != null ? cmd.targetBusId()
                : busIdOfElement(cmd.elementId());
        if (busId == null) {
            return "同期调整需指定目标母线（targetBusId）";
        }
        Topology t = currentTopology();
        Topology.Component c = t.componentOf(busId);
        if (c == null || !c.energized || c.generators.isEmpty()) {
            return "目标母线所在系统无运行机组，无法同期调整";
        }
        // 一次调频：用最大可调机组补足本岛功率缺额
        c.generators.stream().max(Comparator.comparingDouble(GeneratorUnit::ratedMw)).ifPresent(g -> {
            double imbalance = c.loadMw - c.generationMw;
            double cur = genOutput.get(g.id());
            double next = Math.min(g.ratedMw(), Math.max(g.minStableMw(), cur + imbalance));
            genOutput.put(g.id(), next);
        });
        applyTopology(cmd.seq(), out, List.of());
        double f = freqOf(busId);

        // 找联络线对侧参考角
        Double refAngle = null;
        Line tie = scenario.lines().stream()
                .filter(l -> l.id().equals(cmd.elementId())).findFirst().orElse(null);
        if (tie != null) {
            String other = tie.fromBusId().equals(busId) ? tie.toBusId()
                    : tie.fromBusId().equals(busId) ? tie.fromBusId() : null;
            if (other != null) refAngle = angleOf(other);
        }
        boolean freqReady = Math.abs(f - 50.0) <= scenario.protection().syncDfMaxHz() / 2;
        if (freqReady && refAngle != null) {
            String island = c.islandId;
            double target = refAngle;
            t = currentTopology();
            Topology.Component c2 = t.componentOf(busId);
            if (c2 != null) {
                c2.buses.forEach(b -> busAngle.put(b, target));
            }
            double otherSide = tie.fromBusId().equals(busId) ? angleOf(tie.toBusId())
                    : angleOf(tie.fromBusId());
            out.add(mkEvent(cmd.seq(), EventType.SYNC_CHECK_PASSED, busId,
                    "同期调整完成：频率 " + fmt(f) + "Hz，相角已对齐对侧（Δδ≈"
                            + fmt(Math.abs(norm180(target - otherSide))) + "°），可发同期并列口令",
                    null));
        } else {
            out.add(mkEvent(cmd.seq(), EventType.SYNC_CHECK_PASSED, busId,
                    "同期调整：目标系统频率→" + fmt(f) + "Hz"
                            + (freqReady ? "，频差已满足，继续对相角" : "，继续调频")
                            + "（达到同期条件后可发 SYNC_TIE）", null));
        }
        return null;
    }

    private String execOpenLine(OperatorCommand cmd, List<SimEvent> out) {
        Line line;
        try {
            line = scenario.line(cmd.elementId());
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        if (!Boolean.TRUE.equals(lineClosed.get(line.id()))) {
            return line.name() + " 已在分位";
        }
        lineClosed.put(line.id(), false);
        applyTopology(cmd.seq(), out, List.of());
        out.add(mkEvent(cmd.seq(), EventType.LINE_OPENED, line.id(),
                line.name() + " 已拉停，两侧解列（检查各独立小系统频率/电源平衡）", null));
        return null;
    }

    private String execLoad(OperatorCommand cmd, List<SimEvent> out, boolean restore) {
        LoadNode ld;
        try {
            ld = scenario.load(cmd.elementId());
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        boolean connected = Boolean.TRUE.equals(loadConnected.get(ld.id()));
        if (restore) {
            if (connected) {
                return "负荷 " + ld.name() + " 已在供电（前置条件：负荷未接入）";
            }
            if (!busHasPower(ld.busId())) {
                return "负荷 " + ld.name() + " 所在母线 " + nameOfBus(ld.busId())
                        + " 未带电（前置条件：先恢复母线电源，严格按电源→母线→线路→负荷顺序）";
            }
            loadConnected.put(ld.id(), true);
        } else {
            if (!connected) {
                return "负荷 " + ld.name() + " 未接入";
            }
            loadConnected.put(ld.id(), false);
        }
        applyTopology(cmd.seq(), out, List.of());
        out.add(mkEvent(cmd.seq(), restore ? EventType.LOAD_RESTORED : EventType.LOAD_DISCONNECTED,
                ld.id(),
                (restore ? "恢复" : "切除") + "负荷 " + ld.name() + " " + fmt(ld.mw()) + " MW（优先级 "
                        + ld.priority() + "），系统频率 "
                        + fmt(freqOf(ld.busId())) + " Hz",
                nodeJson("mw", ld.mw())));
        return null;
    }

    // ------------------------------------------------------------------
    // 周期节拍
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        clock.advance(Duration.ofMillis((long) (TICK_SECONDS * 1000)));
        Topology t = currentTopology();
        for (Topology.Component c : t.components) {
            if (!c.energized) {
                continue;
            }
            double targetFreq = 50.0 + (c.generationMw - c.loadMw) * DROOP_HZ_PER_MW;
            for (String busId : c.buses) {
                double cur = busFreq.getOrDefault(busId, targetFreq);
                double nf = cur + (targetFreq - cur) * CONVERGE;
                busFreq.put(busId, nf);
                double da = (nf - 50.0) * 360.0 * TICK_SECONDS;
                busAngle.merge(busId, da, Double::sum);
            }
        }
        // 低频减载（按本拍实际频率判定，而非准稳态，体现动态过程）
        List<String> shed = new ArrayList<>();
        double uf = scenario.protection().underFreqShedHz();
        for (Map.Entry<String, Topology.Component> e : currentTopology().byBus.entrySet()) {
            String bid = e.getKey();
            Topology.Component c = e.getValue();
            double actualF = busFreq.getOrDefault(bid, c.energized ? c.frequencyHz : 50.0);
            if (c.energized && actualF < uf) {
                scenario.loads().stream()
                        .filter(l -> Boolean.TRUE.equals(loadConnected.get(l.id())))
                        .filter(l -> c.buses.contains(l.busId()))
                        .min(Comparator.comparingInt(LoadNode::priority).reversed())
                        .ifPresent(l -> {
                            if (!shed.contains(l.id())) {
                                loadConnected.put(l.id(), false);
                                shed.add(l.id());
                            }
                        });
            }
        }
        if (!shed.isEmpty()) {
            // 切负荷后频率取新准稳态，并发布动作事件
            Topology t2 = recompute(List.of());
            t2.busFreq.forEach(busFreq::put);
            t2.busAngle.forEach(busAngle::put);
            shed.forEach(id -> transport.emit(mkEvent(null, EventType.UNDERFREQ_SHED, id,
                    "低频减载动作：切除 " + scenario.load(id).name() + " "
                            + fmt(scenario.load(id).mw()) + " MW",
                    nodeJson("mw", scenario.load(id).mw()))));
        }
        transport.emit(mkEvent(null, EventType.TELEMETRY, null, "周期遥测", telemetryJson()));
    }

    // ------------------------------------------------------------------
    // 拓扑 / 简化潮流
    // ------------------------------------------------------------------

    private static final class Topology {
        static final class Component {
            String islandId;
            boolean energized;
            final List<String> buses = new ArrayList<>();
            final List<GeneratorUnit> generators = new ArrayList<>();
            double generationMw;
            double loadMw;
            double frequencyHz;
        }

        final List<Component> components = new ArrayList<>();
        final Map<String, Component> byBus = new HashMap<>();
        final Map<String, Double> busVoltage = new HashMap<>();
        final Map<String, Double> busAngle = new HashMap<>();
        final Map<String, Double> busFreq = new HashMap<>();
        final Map<String, Double> lineFlow = new HashMap<>();
        final List<SimEvent> events = new ArrayList<>();

        Component componentOf(String busId) {
            return byBus.get(busId);
        }
    }

    private void applyTopology(Long cmdSeq, List<SimEvent> out, List<SimEvent> events) {
        Topology t = recompute(events);
        // 采用新角度/频率
        t.busAngle.forEach(busAngle::put);
        t.busFreq.forEach(busFreq::put);
        out.addAll(t.events);
    }

    private Topology currentTopology() {
        return recompute(List.of());
    }

    /** 全量重算连通分量、发电-负荷平衡、频率、电压、线流，并给出带电状态变化事件。 */
    private Topology recompute(List<SimEvent> seedEvents) {
        Topology t = new Topology();
        t.events.addAll(seedEvents);

        // 无向图（仅闭合线路）
        Map<String, List<String>> adj = new HashMap<>();
        for (Bus b : scenario.buses()) {
            adj.put(b.id(), new ArrayList<>());
        }
        for (Line l : scenario.lines()) {
            if (Boolean.TRUE.equals(lineClosed.get(l.id()))) {
                adj.get(l.fromBusId()).add(l.toBusId());
                adj.get(l.toBusId()).add(l.fromBusId());
            }
        }
        Map<String, Integer> compIndex = new HashMap<>();
        int idx = 0;
        for (Bus start : scenario.buses()) {
            if (compIndex.containsKey(start.id())) continue;
            Topology.Component c = new Topology.Component();
            Deque<String> stack = new ArrayDeque<>();
            stack.push(start.id());
            while (!stack.isEmpty()) {
                String b = stack.pop();
                if (compIndex.containsKey(b)) continue;
                compIndex.put(b, idx);
                c.buses.add(b);
                adj.get(b).forEach(x -> {
                    if (!compIndex.containsKey(x)) stack.push(x);
                });
            }
            t.components.add(c);
            idx++;
        }
        for (Topology.Component c : t.components) {
            c.islandId = "ISL-" + (t.components.indexOf(c) + 1);
            for (String busId : c.buses) {
                t.byBus.put(busId, c);
            }
        }

        // 发电/负荷汇总
        for (Topology.Component c : t.components) {
            for (GeneratorUnit g : scenario.generators()) {
                if (c.buses.contains(g.busId()) && Boolean.TRUE.equals(genRunning.get(g.id()))) {
                    c.generators.add(g);
                    c.generationMw += genOutput.getOrDefault(g.id(), 0.0);
                }
            }
            for (LoadNode l : scenario.loads()) {
                if (c.buses.contains(l.busId()) && Boolean.TRUE.equals(loadConnected.get(l.id()))) {
                    c.loadMw += l.mw();
                }
            }
            c.energized = !c.generators.isEmpty();
            c.frequencyHz = c.energized
                    ? 50.0 + (c.generationMw - c.loadMw) * DROOP_HZ_PER_MW
                    : 0.0;
        }

        // 继承上一拍频率与角度（操作后立刻重算时保持连续性）
        for (Topology.Component c : t.components) {
            if (!c.energized) {
                for (String b : c.buses) {
                    t.busFreq.put(b, 0.0);
                    t.busAngle.put(b, 0.0);
                }
                continue;
            }
            // 保持既有相角基准；新启动系统相角为 0°（后续由频差积分发散）
            String anchor = c.generators.isEmpty() ? c.buses.get(0) : c.generators.get(0).busId();
            double baseAngle = busAngle.getOrDefault(anchor, 0.0);
            // 操作引起的拓扑重算直接取准稳态频率，使口令后快照能立即反映频差越界
            double f = c.frequencyHz;
            for (String b : c.buses) {
                t.busFreq.put(b, f);
                t.busAngle.put(b, busAngle.getOrDefault(b, baseAngle));
            }
        }

        // 电压：从每台运行机组母线 BFS，按闭合线路阻抗×下游负荷估算压降
        for (Bus b : scenario.buses()) {
            t.busVoltage.put(b.id(), 0.0);
        }
        record Q(String bus, double v) { }
        for (GeneratorUnit g : scenario.generators()) {
            if (!Boolean.TRUE.equals(genRunning.get(g.id()))) continue;
            Deque<Q> q = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            q.add(new Q(g.busId(), 1.0));
            while (!q.isEmpty()) {
                Q cur = q.poll();
                if (seen.contains(cur.bus)) continue;
                seen.add(cur.bus);
                double existing = t.busVoltage.get(cur.bus);
                if (cur.v > existing) t.busVoltage.put(cur.bus, cur.v);
                for (Line l : scenario.lines()) {
                    if (!Boolean.TRUE.equals(lineClosed.get(l.id()))) continue;
                    String other = null;
                    if (l.fromBusId().equals(cur.bus)) other = l.toBusId();
                    else if (l.toBusId().equals(cur.bus)) other = l.fromBusId();
                    if (other == null) continue;
                    double downstreamLoad = downstreamMw(other, l, t);
                    double z = Math.hypot(l.rPu(), l.xPu());
                    double drop = Math.min(0.25, z * downstreamLoad / 50.0);
                    t.lineFlow.merge(l.id(), downstreamLoad, Math::max);
                    q.add(new Q(other, cur.v - drop));
                }
            }
        }

        // 带电状态变化事件（仅操作重算时，节拍不补发）
        for (Bus b : scenario.buses()) {
            boolean nowLive = t.busVoltage.getOrDefault(b.id(), 0.0) > 0.0;
            boolean wasLive = busFreq.getOrDefault(b.id(), 0.0) > 0.0;
            if (nowLive && !wasLive && seedEvents.isEmpty()) {
                t.events.add(mkEvent(null, EventType.BUS_ENERGIZED, b.id(),
                        "母线 " + b.name() + " 带电 " + fmt(t.busVoltage.get(b.id())) + " p.u.",
                        null));
            } else if (!nowLive && wasLive && seedEvents.isEmpty()) {
                t.events.add(mkEvent(null, EventType.BUS_DEENERGIZED, b.id(),
                        "母线 " + b.name() + " 失电", null));
            }
        }
        return t;
    }

    /** BFS 下游累计负荷（教学用线流估算）。 */
    private double downstreamMw(String busId, Line via, Topology t) {
        double sum = 0;
        Set<String> seen = new HashSet<>();
        Deque<String> q = new ArrayDeque<>();
        q.add(busId);
        seen.add(via.fromBusId());
        seen.add(via.toBusId());
        seen.remove(busId);
        while (!q.isEmpty()) {
            String b = q.poll();
            if (!seen.add(b)) continue;
            for (LoadNode l : scenario.loads()) {
                if (l.busId().equals(b) && Boolean.TRUE.equals(loadConnected.get(l.id()))) {
                    sum += l.mw();
                }
            }
            for (Line ln : scenario.lines()) {
                if (!Boolean.TRUE.equals(lineClosed.get(ln.id()))) continue;
                if (ln.fromBusId().equals(b)) q.add(ln.toBusId());
                if (ln.toBusId().equals(b)) q.add(ln.fromBusId());
            }
        }
        return sum;
    }

    // ------------------------------------------------------------------
    // 快照
    // ------------------------------------------------------------------

    @Override
    public PowerFlowSnapshot snapshot(long commandSeq, boolean before) {
        Topology t = currentTopology();
        List<BusState> bs = scenario.buses().stream()
                .map(b -> new BusState(b.id(),
                        t.busVoltage.getOrDefault(b.id(), 0.0) > 0.0,
                        round(t.busVoltage.getOrDefault(b.id(), 0.0)),
                        round(t.busAngle.getOrDefault(b.id(), 0.0)),
                        t.componentOf(b.id()) != null && t.componentOf(b.id()).energized
                                ? t.componentOf(b.id()).islandId : null))
                .toList();
        List<GenState> gs = scenario.generators().stream()
                .map(g -> new GenState(g.id(), Boolean.TRUE.equals(genRunning.get(g.id())),
                        round(genOutput.getOrDefault(g.id(), 0.0)),
                        t.componentOf(g.busId()) != null && t.componentOf(g.busId()).energized
                                ? t.componentOf(g.busId()).islandId : null))
                .toList();
        List<LineState> ls = scenario.lines().stream()
                .map(l -> new LineState(l.id(), Boolean.TRUE.equals(lineClosed.get(l.id())),
                        round(t.lineFlow.getOrDefault(l.id(), 0.0))))
                .toList();
        List<LoadState> lds = scenario.loads().stream()
                .map(l -> new LoadState(l.id(), Boolean.TRUE.equals(loadConnected.get(l.id())),
                        round(Boolean.TRUE.equals(loadConnected.get(l.id())) ? l.mw() : 0.0)))
                .toList();
        List<IslandState> isl = t.components.stream()
                .map(c -> new IslandState(c.islandId, c.energized,
                        round(c.energized ? t.busFreq.getOrDefault(c.buses.get(0), c.frequencyHz) : 0.0),
                        round(c.generationMw), round(c.loadMw)))
                .toList();
        return new PowerFlowSnapshot(clock.now(), commandSeq, before, bs, gs, ls, lds, isl);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private boolean busHasPower(String busId) {
        Topology t = currentTopology();
        Topology.Component c = t.componentOf(busId);
        return c != null && c.energized;
    }

    private double freqOf(String busId) {
        Topology.Component c = currentTopology().componentOf(busId);
        return c == null || !c.energized ? 0.0 : busFreq.getOrDefault(busId, c.frequencyHz);
    }

    private double angleOf(String busId) {
        return busAngle.getOrDefault(busId, 0.0);
    }

    private double voltageBefore(String busId) {
        return currentTopology().busVoltage.getOrDefault(busId, 0.0);
    }

    private String busIdOfElement(String elementId) {
        for (GeneratorUnit g : scenario.generators()) if (g.id().equals(elementId)) return g.busId();
        for (LoadNode l : scenario.loads()) if (l.id().equals(elementId)) return l.busId();
        return elementId;
    }

    private String nameOfBus(String id) {
        return scenario.buses().stream().filter(b -> b.id().equals(id)).findFirst()
                .map(Bus::name).orElse(id);
    }

    static double norm180(double deg) {
        double a = deg % 360.0;
        if (a > 180.0) a -= 360.0;
        if (a < -180.0) a += 360.0;
        return a;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private SimEvent mkEvent(Long commandSeq, EventType type, String elementId,
                             String message, String payloadJson) {
        long seq = nextSeq++;
        return new SimEvent(
                UUID.randomUUID().toString(), seq, commandSeq, type, elementId, message,
                payloadJson, clock.now(), java.time.Instant.now(), null, null);
    }

    private String nodeJson(String key, double value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, round(value));
        return write(m);
    }

    private String faultJson(String elementId) {
        return write(Map.of("hiddenFault", true, "elementId", elementId));
    }

    private String telemetryJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        Topology t = currentTopology();
        m.put("islands", t.components.stream()
                .filter(c -> c.energized)
                .map(c -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("islandId", c.islandId);
                    x.put("frequencyHz", round(t.busFreq.getOrDefault(c.buses.get(0), c.frequencyHz)));
                    return x;
                }).toList());
        m.put("buses", scenario.buses().stream().map(b -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("busId", b.id());
            x.put("voltagePu", round(t.busVoltage.getOrDefault(b.id(), 0.0)));
            return x;
        }).toList());
        return write(m);
    }

    private String write(Object o) {
        try {
            return M.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
