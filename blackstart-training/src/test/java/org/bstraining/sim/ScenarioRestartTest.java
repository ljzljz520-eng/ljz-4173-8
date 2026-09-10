package org.bstraining.sim;

import org.bstraining.model.BusState;
import org.bstraining.model.IslandState;
import org.bstraining.model.EventType;
import org.bstraining.model.OperatorCommand;
import org.bstraining.model.PowerFlowSnapshot;
import org.bstraining.model.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交付测试 3：场景重启。
 * 操作后状态改变；restart() 必须精确恢复冻结初始网架/机组/负荷状态，
 * 且事件流为追加（不清除历史，重启记录在流中可查）。
 */
class ScenarioRestartTest extends SimulatorTestBase {

    private Scenario scenario;

    @BeforeEach
    void setUp() {
        scenario = ScenarioFixtures.demo();
        attach(scenario);
    }

    @Test
    void restartRestoresFrozenInitialStateAndAppendsHistory() {
        int eventsAtStart = events.size();

        // 黑启动若干步：启动 A 水电、充 A 站母线、送 AB 线、合厂用变
        submit(1, OperatorCommand.CommandType.START_GEN, "gen-A-hydro");
        submit(2, OperatorCommand.CommandType.CLOSE_LINE, "line-A-station");
        submit(3, OperatorCommand.CommandType.CLOSE_LINE, "line-AB");
        submit(4, OperatorCommand.CommandType.CLOSE_LINE, "line-B-station");

        PowerFlowSnapshot changed = snap();
        assertTrue(bus(changed, "bus-B-10").energized(), "操作后 B 厂用母线应带电");
        assertTrue(changed.generators().stream()
                .anyMatch(g -> g.genId().equals("gen-A-hydro") && g.running()));

        sim.restart();

        // 事件流不清除，重启为追加事件
        assertTrue(events.size() > eventsAtStart, "重启事件应追加，历史不清除");
        assertTrue(events.stream().anyMatch(e -> e.type() == EventType.COMMAND_ACCEPTED
                        && e.message().contains("场景重启")),
                "应存在场景重启记录");
        // 旧事件仍在（原事件不可编辑/删除）
        assertTrue(events.stream().anyMatch(e -> e.type() == EventType.GEN_STARTED),
                "重启前的机组启动事件仍应保留");

        PowerFlowSnapshot initial = snap();
        assertFalse(bus(initial, "bus-A-110").energized(), "重启后 A 110kV 母线应恢复失电");
        assertFalse(bus(initial, "bus-B-10").energized(), "重启后 B 厂用母线应恢复失电");
        assertTrue(initial.generators().stream()
                        .noneMatch(g -> g.genId().equals("gen-A-hydro") && g.running()),
                "重启后 A 水电应恢复停机");
        assertTrue(initial.lines().stream().noneMatch(l ->
                        l.lineId().startsWith("line-") && l.closed()),
                "所有线路应恢复初始分位");
        assertEquals(0, initial.islands().stream().filter(IslandState::energized).count(),
                "重启后应全黑，无带电岛");

        // 重启后重新执行黑启动应成功（状态机可确定性复现）
        submit(5, OperatorCommand.CommandType.START_GEN, "gen-A-hydro");
        assertTrue(bus(snap(), "bus-A-110").energized(), "重启后重新黑启动，A 母线应带电");
    }

    @Test
    void hiddenFaultReappearsAfterRestart() {
        // 隐藏故障在重启后必须仍然存在（冻结场景的一部分）
        submit(1, OperatorCommand.CommandType.START_GEN, "gen-A-hydro");
        submit(2, OperatorCommand.CommandType.CLOSE_LINE, "line-A-station");
        submit(3, OperatorCommand.CommandType.CLOSE_LINE, "line-AB");
        sim.restart();
        submit(4, OperatorCommand.CommandType.START_GEN, "gen-A-hydro");
        submit(5, OperatorCommand.CommandType.CLOSE_LINE, "line-A-station");
        submit(6, OperatorCommand.CommandType.CLOSE_LINE, "line-AB");
        submit(7, OperatorCommand.CommandType.CLOSE_LINE, "line-B-station");
        // 走到 C：先需要 B 机组或直接充电 B-C；直接合 BC 触发隐藏故障
        submit(8, OperatorCommand.CommandType.CLOSE_LINE, "line-BC");
        assertTrue(hasType(8, EventType.LINE_TRIP_FAULT),
                "重启后隐藏故障仍应按冻结场景触发");
    }

    private BusState bus(PowerFlowSnapshot s, String id) {
        return s.buses().stream().filter(b -> b.busId().equals(id)).findFirst().orElseThrow();
    }
}
