package org.bstraining.sim;

import org.bstraining.model.*;

import java.time.Instant;
import java.util.List;

/**
 * 教学场景构造器（冻结时刻取构造时刻；生产环境从冻结 JSON 读取）。
 */
public final class ScenarioFixtures {

    public static final String DEMO_ID = "demo-blackstart";
    public static final String ISLAND_ID = "island-misparallel";
    public static final String SAMENAME_ID = "same-name-bus";

    private ScenarioFixtures() {
    }

    /**
     * 标准黑启动复盘场景：A 水电站（黑启动电源）→ 母线充电 → 线路送电 →
     * 带 B 火电厂厂用电 → B 机组并网 → 恢复负荷。BC 线存在隐藏充电故障。
     */
    public static Scenario demo() {
        Bus a110 = new Bus("bus-A-110", "A站110kV I母", "A水电站", VoltageLevel.KV_110);
        Bus a10 = new Bus("bus-A-10", "A站10kV厂用电", "A水电站", VoltageLevel.KV_10);
        Bus b220 = new Bus("bus-B-220", "B站220kV母线", "B火电厂", VoltageLevel.KV_220);
        Bus b10 = new Bus("bus-B-10", "B站10kV厂用电", "B火电厂", VoltageLevel.KV_10);
        Bus c220 = new Bus("bus-C-220", "C站220kV母线", "C变电站", VoltageLevel.KV_220);
        Bus c10 = new Bus("bus-C-10", "C站10kV母线", "C变电站", VoltageLevel.KV_10);

        GeneratorUnit hydro = new GeneratorUnit("gen-A-hydro", "A水电站#1机", "bus-A-110",
                30, 5, 3, true, false);
        GeneratorUnit thermal = new GeneratorUnit("gen-B-thermal", "B火电厂#1机", "bus-B-220",
                200, 60, 2, false, false);

        Line ax = new Line("line-A-station", "A水电站110/10kV主变", "bus-A-110", "bus-A-10",
                0.002, 0.01, 40, false, false);
        Line ab = new Line("line-AB", "AB线220kV(经联络变)", "bus-A-110", "bus-B-220",
                0.01, 0.05, 150, false, false);
        Line bx = new Line("line-B-station", "B站高压厂用变", "bus-B-220", "bus-B-10",
                0.002, 0.01, 80, false, false);
        Line bc = new Line("line-BC", "BC线220kV", "bus-B-220", "bus-C-220",
                0.012, 0.06, 150, false, false);
        Line cx = new Line("line-C-station", "C站2号主变", "bus-C-220", "bus-C-10",
                0.002, 0.01, 80, false, false);

        LoadNode auxB = new LoadNode("load-B-aux", "B火电厂厂用电(重要)", "bus-B-10",
                18, 1, false);
        LoadNode c1 = new LoadNode("load-C-critical", "C站一类负荷", "bus-C-10",
                20, 1, false);
        LoadNode c2 = new LoadNode("load-C-normal", "C站一般负荷", "bus-C-10",
                40, 2, false);
        LoadNode c3 = new LoadNode("load-C-large", "C站大容量工业负荷", "bus-C-10",
                90, 3, false);

        HiddenFault fault = new HiddenFault("fault-BC", "line-BC",
                HiddenFault.FaultKind.LINE_TRIP_ON_ENERGIZE,
                "BC线电缆头绝缘隐患，充电即跳（学员不可见）");

        return new Scenario(DEMO_ID, "黑启动标准复盘场景",
                "A水电黑启动→母线充电→线路送电→B火电厂用电与并网→负荷恢复；BC线含隐藏充电故障",
                1, Instant.parse("2026-01-01T00:00:00Z"),
                List.of(a110, a10, b220, b10, c220, c10),
                List.of(hydro, thermal),
                List.of(ax, ab, bx, bc, cx),
                List.of(auxB, c1, c2, c3),
                List.of(fault),
                ProtectionSettings.typical());
    }

    /**
     * 孤岛误并列场景：两个独立带电岛，经联络线并列。
     * 2 号岛功率缺额大 → 频率走低、相位漂移；若不同期调整直接硬合联络线即误并列跳闸。
     */
    public static Scenario islandMisparallel() {
        Bus x = new Bus("bus-X-220", "X站220kV母线", "X电厂", VoltageLevel.KV_220);
        Bus y = new Bus("bus-Y-220", "Y站220kV母线", "Y电厂", VoltageLevel.KV_220);

        GeneratorUnit gx = new GeneratorUnit("gen-X", "X厂#1机", "bus-X-220",
                60, 50, 4, true, true);
        GeneratorUnit gy = new GeneratorUnit("gen-Y", "Y厂#1机", "bus-Y-220",
                200, 20, 4, true, true);

        Line tie = new Line("line-XY", "XY联络线220kV", "bus-X-220", "bus-Y-220",
                0.008, 0.04, 150, false, true);

        // X 岛平衡：初始出力 50MW = 负荷 50MW → 50.0Hz；
        // Y 岛初始出力仅 20MW、负荷 110MW → 准稳态 48.2Hz，相位随节拍漂移，硬合即误并列
        LoadNode lx = new LoadNode("load-X", "X站综合负荷", "bus-X-220", 50, 2, true);
        LoadNode ly = new LoadNode("load-Y", "Y站综合负荷", "bus-Y-220", 110, 2, true);

        return new Scenario(ISLAND_ID, "双孤岛误并列训练",
                "XY两岛分别带电，Y岛功率缺额；先同期调整再并列，硬合将误并列跳闸",
                1, Instant.parse("2026-01-01T00:00:00Z"),
                List.of(x, y),
                List.of(gx, gy),
                List.of(tie),
                List.of(lx, ly),
                List.of(),
                ProtectionSettings.typical());
    }

    /** 同名母线场景：两座变电站各有一条同名的“10kV I段母线”。 */
    public static Scenario sameNameBus() {
        Bus north10 = new Bus("bus-north-10", "10kV I段母线", "北关变电站", VoltageLevel.KV_10);
        Bus south10 = new Bus("bus-south-10", "10kV I段母线", "南郊变电站", VoltageLevel.KV_10);
        Bus src = new Bus("bus-src-35", "35kV电源母线", "黑启动电源点", VoltageLevel.KV_35);

        GeneratorUnit g = new GeneratorUnit("gen-diesel", "移动柴油发电机", "bus-src-35",
                5, 1, 2, true, false);
        Line ln = new Line("line-to-north", "北线35/10kV", "bus-src-35", "bus-north-10",
                0.004, 0.02, 10, false, false);
        Line ls = new Line("line-to-south", "南线35/10kV", "bus-src-35", "bus-south-10",
                0.004, 0.02, 10, false, false);
        LoadNode ld1 = new LoadNode("load-north", "北关配网负荷", "bus-north-10", 1.5, 1, false);
        LoadNode ld2 = new LoadNode("load-south", "南郊配网负荷", "bus-south-10", 1.5, 1, false);

        return new Scenario(SAMENAME_ID, "同名母线辨识训练",
                "南北两站各含同名“10kV I段母线”，口令必须按站名/设备id区分，防误停误送",
                1, Instant.parse("2026-01-01T00:00:00Z"),
                List.of(src, north10, south10),
                List.of(g),
                List.of(ln, ls),
                List.of(ld1, ld2),
                List.of(),
                ProtectionSettings.typical());
    }
}
