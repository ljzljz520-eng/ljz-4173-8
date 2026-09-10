package org.bstraining.rules;

import org.bstraining.model.BusState;
import org.bstraining.model.CandidateFlag;
import org.bstraining.model.CommandRecord;
import org.bstraining.model.EventType;
import org.bstraining.model.IslandState;
import org.bstraining.model.Line;
import org.bstraining.model.OperatorCommand;
import org.bstraining.model.PowerFlowSnapshot;
import org.bstraining.model.ProtectionSettings;
import org.bstraining.model.Scenario;
import org.bstraining.model.SimEvent;
import org.bstraining.model.ViolationKind;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 规则引擎：<b>只标出候选问题</b>，不阻断口令、不代替教员判定。
 *
 * <p>三类候选：
 * <ol>
 *   <li>{@link ViolationKind#PRECONDITION} 越过前置条件（操作顺序/带电条件/同期条件）；</li>
 *   <li>{@link ViolationKind#FREQUENCY_VOLTAGE} 频压越界（口令后快照中越限的岛/母线）；</li>
 *   <li>{@link ViolationKind#COMMUNICATION} 通信遗漏（状态变更口令缺复诵/汇报/联系）。</li>
 * </ol>
 */
public final class RuleEngine {

    private final Scenario scenario;

    public RuleEngine(Scenario scenario) {
        this.scenario = scenario;
    }

    /**
     * 评估一条口令。
     *
     * @param cmd      口令
     * @param before   口令前快照
     * @param after    口令后快照
     * @param events   该口令产生的系统响应事件（按序）
     * @param history  截至当前的全部口令（含本条，用于通信配对核对）
     */
    public List<CandidateFlag> evaluate(OperatorCommand cmd,
                                        PowerFlowSnapshot before,
                                        PowerFlowSnapshot after,
                                        List<SimEvent> events,
                                        List<CommandRecord> history) {
        List<CandidateFlag> out = new ArrayList<>();
        long seq = cmd.seq();
        Instant now = after == null ? Instant.now() : after.simTime();

        // 1) 前置条件
        String pre = checkPrecondition(cmd, before, events);
        if (pre != null) {
            out.add(flag(seq, firstEventId(events), ViolationKind.PRECONDITION,
                    "前置条件", pre, now));
        }

        // 2) 频压越界（口令后快照）
        if (after != null) {
            ProtectionSettings p = scenario.protection();
            for (IslandState isl : after.islands()) {
                if (!isl.energized()) {
                    continue;
                }
                if (isl.frequencyHz() < p.fMinHz() || isl.frequencyHz() > p.fMaxHz()) {
                    out.add(flag(seq, firstEventId(events), ViolationKind.FREQUENCY_VOLTAGE,
                            "频率越界",
                            "电气岛 " + isl.islandId() + " 口令后频率 " + isl.frequencyHz()
                                    + " Hz 越限 [" + p.fMinHz() + ", " + p.fMaxHz() + "]",
                            now));
                }
            }
            for (BusState b : after.buses()) {
                if (!b.energized()) {
                    continue;
                }
                if (b.voltagePu() < p.vMinPu() || b.voltagePu() > p.vMaxPu()) {
                    out.add(flag(seq, firstEventId(events), ViolationKind.FREQUENCY_VOLTAGE,
                            "电压越界",
                            "母线 " + busName(b.busId()) + " 口令后电压 " + b.voltagePu()
                                    + " p.u. 越限 [" + p.vMinPu() + ", " + p.vMaxPu() + "]",
                            now));
                }
            }
        }

        // 3) 通信遗漏
        String comm = checkCommunication(cmd, history);
        if (comm != null) {
            out.add(flag(seq, firstEventId(events), ViolationKind.COMMUNICATION,
                    "通信遗漏", comm, now));
        }
        return out;
    }

    /** 前置条件核对（与仿真器拒绝互为印证：仿真器拒动 + 规则候选双证据）。 */
    private String checkPrecondition(OperatorCommand cmd, PowerFlowSnapshot before,
                                     List<SimEvent> events) {
        boolean rejected = events.stream()
                .anyMatch(e -> e.type() == EventType.COMMAND_REJECTED
                        || e.type() == EventType.GEN_START_FAILED);
        boolean misparallel = events.stream()
                .anyMatch(e -> e.type() == EventType.MISPARALLEL_TRIP);
        boolean syncFail = events.stream()
                .anyMatch(e -> e.type() == EventType.SYNC_CHECK_FAILED);

        if (misparallel || syncFail) {
            return "并列前未满足同期条件（频差/压差/角差越限）即合闸，构成非同期并列风险，"
                    + "请结合系统响应评估后果";
        }
        if (rejected) {
            SimEvent r = events.stream()
                    .filter(e -> e.type() == EventType.COMMAND_REJECTED
                            || e.type() == EventType.GEN_START_FAILED)
                    .findFirst().orElse(null);
            return "操作越过前置条件（系统已拒绝/失败）：" + (r == null ? "" : r.message());
        }

        // 静态顺序/状态条件（系统接受但顺序仍违反黑启动规程的候选）
        if (before == null) {
            return null;
        }
        switch (cmd.type()) {
            case START_GEN -> {
                var g = scenario.generator(cmd.elementId());
                if (!g.blackstartCapable() && !energized(g.busId(), before)) {
                    return "非黑启动机组 " + g.name() + " 启动时其母线无启动电源，"
                            + "越过“先恢复厂用电/启动电源再开辅机”的前置条件";
                }
            }
            case CLOSE_LINE, SYNC_TIE -> {
                Line line = scenario.line(cmd.elementId());
                boolean a = energized(line.fromBusId(), before);
                boolean b = energized(line.toBusId(), before);
                if (!a && !b) {
                    return "线路 " + line.name() + " 两侧均无电即送电，"
                            + "越过“电源启动→母线充电→线路送电”的前置顺序";
                }
                if (a && b && cmd.type() == OperatorCommand.CommandType.CLOSE_LINE) {
                    return "两侧带电线路用普通合闸而非同期并列，存在误并列风险（应先同期校核）";
                }
            }
            case OPEN_LINE -> {
                Line line = scenario.line(cmd.elementId());
                if (!isClosed(line.id(), before)) {
                    return "线路 " + line.name() + " 开关本在分位，拉停前置条件不成立";
                }
            }
            case RESTORE_LOAD -> {
                var ld = scenario.load(cmd.elementId());
                if (!energized(ld.busId(), before)) {
                    return "负荷 " + ld.name() + " 所在母线未带电即恢复负荷，"
                            + "越过“电源→母线→线路→负荷”的恢复顺序";
                }
            }
            default -> {
            }
        }
        return null;
    }

    /**
     * 通信遗漏：每条改变电气状态的口令，前后 {@code WINDOW} 条口令内应有一次
     * 对应的汇报/复诵/联系（REPORT，且口令文本提及设备名）。
     */
    private String checkCommunication(OperatorCommand cmd, List<CommandRecord> history) {
        if (!changesState(cmd.type())) {
            return null;
        }
        final int window = 3;
        Set<String> mentioned = new HashSet<>();
        for (CommandRecord r : history) {
            if (Math.abs(r.command().seq() - cmd.seq()) <= window
                    && r.command().type() == OperatorCommand.CommandType.REPORT
                    && r.command().spoken() != null) {
                mentioned.add(r.command().spoken());
            }
        }
        if (mentioned.isEmpty()) {
            return "口令“" + cmd.type().cn + "”前后 " + window
                    + " 条记录内未见复诵/汇报/联系（REPORT），疑似通信环节遗漏";
        }
        return null;
    }

    private boolean changesState(OperatorCommand.CommandType t) {
        return t != OperatorCommand.CommandType.REPORT;
    }

    private boolean energized(String busId, PowerFlowSnapshot snap) {
        return snap.buses().stream()
                .anyMatch(b -> b.busId().equals(busId) && b.energized());
    }

    private boolean isClosed(String lineId, PowerFlowSnapshot snap) {
        return snap.lines().stream()
                .anyMatch(l -> l.lineId().equals(lineId) && l.closed());
    }

    private String busName(String busId) {
        return scenario.buses().stream().filter(b -> b.id().equals(busId)).findFirst()
                .map(b -> b.name() + "(" + b.substation() + ")").orElse(busId);
    }

    private String firstEventId(List<SimEvent> events) {
        return events.isEmpty() ? null : events.get(0).eventId();
    }

    private CandidateFlag flag(long seq, String eventId, ViolationKind kind,
                               String rule, String detail, Instant at) {
        return new CandidateFlag(null, seq, eventId, kind, rule, detail,
                CandidateFlag.Status.OPEN, at);
    }
}
