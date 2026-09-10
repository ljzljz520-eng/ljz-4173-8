package org.bstraining.sim;

import org.bstraining.model.Bus;
import org.bstraining.model.BusState;
import org.bstraining.model.OperatorCommand;
import org.bstraining.model.PowerFlowSnapshot;
import org.bstraining.model.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交付测试 4：同名母线。
 * 两座变电站各有同名“10kV I段母线”，一切操作与记录必须以唯一 id 区分，
 * 不得因名称相同而误停误送。
 */
class SameNameBusTest extends SimulatorTestBase {

    private Scenario scenario;

    @BeforeEach
    void setUp() {
        scenario = ScenarioFixtures.sameNameBus();
        attach(scenario);
    }

    @Test
    void duplicateBusNamesHaveDistinctIds() {
        List<Bus> same = scenario.buses().stream()
                .filter(b -> b.name().equals("10kV I段母线")).toList();
        assertEquals(2, same.size(), "应有两条同名母线");
        assertNotEquals(same.get(0).id(), same.get(1).id(), "同名母线 id 必须不同");
        assertNotEquals(same.get(0).substation(), same.get(1).substation(),
                "同名母线分属不同变电站");
    }

    @Test
    void energizingOneDoesNotEnergizeTheOther() {
        submit(1, OperatorCommand.CommandType.START_GEN, "gen-diesel");
        // 只送北线
        submit(2, OperatorCommand.CommandType.CLOSE_LINE, "line-to-north");

        PowerFlowSnapshot s = snap();
        assertTrue(bus(s, "bus-north-10").energized(), "北关 10kV I段应带电");
        assertFalse(bus(s, "bus-south-10").energized(), "南郊同名母线不得被误充电");
    }

    @Test
    void restoreLoadHitsOnlyTheTargetedBus() {
        submit(1, OperatorCommand.CommandType.START_GEN, "gen-diesel");
        submit(2, OperatorCommand.CommandType.CLOSE_LINE, "line-to-north");
        // 在北关母线恢复负荷
        submit(3, OperatorCommand.CommandType.RESTORE_LOAD, "load-north");
        PowerFlowSnapshot s = snap();
        assertTrue(s.loads().stream().anyMatch(l -> l.loadId().equals("load-north") && l.connected()),
                "北关负荷应已恢复");
        assertTrue(s.loads().stream().anyMatch(l -> l.loadId().equals("load-south") && !l.connected()),
                "南郊同名母线下负荷不得被误恢复");

        // 未带电直接恢复南郊负荷应被拒绝（前置条件），即便两母线同名
        submit(4, OperatorCommand.CommandType.RESTORE_LOAD, "load-south");
        assertTrue(hasType(4, org.bstraining.model.EventType.COMMAND_REJECTED),
                "同名南郊母线未带电，恢复负荷必须被拒");
    }

    private BusState bus(PowerFlowSnapshot s, String id) {
        return s.buses().stream().filter(b -> b.busId().equals(id)).findFirst().orElseThrow();
    }
}
