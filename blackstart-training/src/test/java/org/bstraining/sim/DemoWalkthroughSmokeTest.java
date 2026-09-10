package org.bstraining.sim;

import org.bstraining.model.EventType;
import org.bstraining.model.OperatorCommand;
import org.bstraining.model.PowerFlowSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标准黑启动先后关系冒烟：
 * 电源启动 → 母线充电 → 线路送电 → 火电厂用电 → 火电机组并网 → 负荷恢复；
 * 乱序操作（先合负荷/先送无电线路）被拒；BC 隐藏故障充电即跳。
 */
class DemoWalkthroughSmokeTest extends SimulatorTestBase {

    @Test
    void walkThroughBlackStartSequence() {
        attach(ScenarioFixtures.demo());

        // 乱序：全黑先送线路、先恢复负荷都应拒绝
        submit(1, OperatorCommand.CommandType.CLOSE_LINE, "line-AB");
        assertTrue(hasType(1, EventType.COMMAND_REJECTED), "无电源送线路应拒绝");
        submit(2, OperatorCommand.CommandType.RESTORE_LOAD, "load-B-aux");
        assertTrue(hasType(2, EventType.COMMAND_REJECTED), "母线无电恢复负荷应拒绝");

        // ① 启动黑启动电源
        submit(3, OperatorCommand.CommandType.START_GEN, "gen-A-hydro");
        assertTrue(hasType(3, EventType.GEN_STARTED));
        // ② 母线充电（A 厂用变）
        submit(4, OperatorCommand.CommandType.CLOSE_LINE, "line-A-station");
        assertTrue(busEnergized("bus-A-10", snap()));
        // ③ 线路送电 AB
        submit(5, OperatorCommand.CommandType.CLOSE_LINE, "line-AB");
        assertTrue(busEnergized("bus-B-220", snap()));
        // B 火电非黑启动机组，此时母线已带电 → 可启动
        submit(6, OperatorCommand.CommandType.START_GEN, "gen-B-thermal");
        assertTrue(hasType(6, EventType.GEN_STARTED), "有厂用启动电源后 B 机应能启动");
        // B 厂用变 + 重要厂用电
        submit(7, OperatorCommand.CommandType.CLOSE_LINE, "line-B-station");
        submit(8, OperatorCommand.CommandType.RESTORE_LOAD, "load-B-aux");
        assertTrue(loadConnected("load-B-aux", snap()));

        // ④ BC 线隐藏故障：充电即跳
        submit(9, OperatorCommand.CommandType.CLOSE_LINE, "line-BC");
        assertTrue(hasType(9, EventType.LINE_TRIP_FAULT), "BC 隐藏故障应在充电时暴露");
        assertFalse(busEnergized("bus-C-220", snap()), "跳闸后 C 站不得带电");

        // 非黑启动机组在无电母线启动应被拒绝（越过启动电源前置条件）
        // （B 机已启动，改在新场景验证；此处验证 A→B 顺序正确已足够）
    }

    private boolean busEnergized(String id, PowerFlowSnapshot s) {
        return s.buses().stream().anyMatch(b -> b.busId().equals(id) && b.energized());
    }

    private boolean loadConnected(String id, PowerFlowSnapshot s) {
        return s.loads().stream().anyMatch(l -> l.loadId().equals(id) && l.connected());
    }
}
