package org.bstraining.sim;

import org.bstraining.model.BusState;
import org.bstraining.model.EventType;
import org.bstraining.model.IslandState;
import org.bstraining.model.OperatorCommand;
import org.bstraining.model.PowerFlowSnapshot;
import org.bstraining.model.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交付测试 2：孤岛误并列。
 * <ol>
 *   <li>双岛各自带电、频率不同；</li>
 *   <li>未经同期直接普通合闸(CLOSE_LINE) → 误并列保护跳闸，联络线回到分位；</li>
 *   <li>同期调整后用 SYNC_TIE → 并列成功，合成一个岛。</li>
 * </ol>
 */
class IslandMisparallelTest extends SimulatorTestBase {

    private Scenario scenario;

    @BeforeEach
    void setUp() {
        scenario = ScenarioFixtures.islandMisparallel();
        attach(scenario);
    }

    @Test
    void twoIslandsEnergizedAtDifferentFrequency() {
        PowerFlowSnapshot s = snap();
        long energizedIslands = s.islands().stream().filter(IslandState::energized).count();
        assertEquals(2, energizedIslands, "初始应为两个带电孤岛");
        double fx = freq(s, "bus-X-220");
        double fy = freq(s, "bus-Y-220");
        assertEquals(50.0, fx, 0.05, "X 岛应平衡在 50Hz");
        assertTrue(fy < 49.0, "Y 岛功率缺额，准稳态频率应低于低频减载，实际=" + fy);
    }

    @Test
    void hardCloseWithoutSyncCausesMisparallelTrip() {
        // 先让相位随节拍漂移，放大角差
        for (int i = 0; i < 6; i++) {
            sim.tick();
        }
        submit(1, OperatorCommand.CommandType.CLOSE_LINE, "line-XY");

        assertTrue(hasType(1, EventType.SYNC_CHECK_FAILED), "应发布同期检查失败");
        assertTrue(hasType(1, EventType.MISPARALLEL_TRIP), "应发生误并列跳闸");
        PowerFlowSnapshot s = snap();
        assertTrue(s.lines().stream().anyMatch(l -> l.lineId().equals("line-XY") && !l.closed()),
                "误并列后联络线应跳回分位");
        assertEquals(2, s.islands().stream().filter(IslandState::energized).count(),
                "跳闸后应恢复为两个岛");
    }

    @Test
    void alignThenSyncTieMergesIslands() {
        // 对 Y 岛做同期调整（补出力+对齐相角），再同期并列
        sim.submit(cmd(1, OperatorCommand.CommandType.RESYNC_ALIGN, "line-XY", "bus-Y-220",
                "Y岛同期调整"));
        PowerFlowSnapshot aligned = snap();
        assertEquals(50.0, freq(aligned, "bus-Y-220"), 0.15,
                "同期调整后 Y 岛频率应回到 50Hz 附近");

        submit(2, OperatorCommand.CommandType.SYNC_TIE, "line-XY");
        assertTrue(hasType(2, EventType.SYNC_CHECK_PASSED), "同期检查应通过");
        assertTrue(hasType(2, EventType.ISLAND_MERGED), "两岛应合并");
        assertFalse(hasType(2, EventType.MISPARALLEL_TRIP), "同期并列不应误跳");

        PowerFlowSnapshot after = snap();
        assertEquals(1, after.islands().stream().filter(IslandState::energized).count(),
                "并列成功后应为单一电气岛");
        BusState x = after.buses().stream().filter(b -> b.busId().equals("bus-X-220")).findFirst().orElseThrow();
        BusState y = after.buses().stream().filter(b -> b.busId().equals("bus-Y-220")).findFirst().orElseThrow();
        assertEquals(x.islandId(), y.islandId(), "两侧母线应在同一岛内");
    }

    private double freq(PowerFlowSnapshot s, String busId) {
        return s.islandOf(busId) == null ? 0.0 : s.islandOf(busId).frequencyHz();
    }
}
