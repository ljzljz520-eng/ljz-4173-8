package org.bstraining.model;

import java.time.Instant;
import java.util.List;

/**
 * 冻结的训练场景：网架、可用机组、启动电源、保护限制、隐藏故障。
 * 场景 JSON 随每次训练会话单独存档（复制），场景库后续修改不影响历史会话。
 */
public record Scenario(
        String id,
        String name,
        String description,
        long version,
        Instant frozenAt,
        List<Bus> buses,
        List<GeneratorUnit> generators,
        List<Line> lines,
        List<LoadNode> loads,
        List<HiddenFault> hiddenFaults,
        ProtectionSettings protection
) {

    public Bus bus(String id) {
        return buses.stream().filter(b -> b.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("母线不存在: " + id));
    }

    public GeneratorUnit generator(String id) {
        return generators.stream().filter(g -> g.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("机组不存在: " + id));
    }

    public Line line(String id) {
        return lines.stream().filter(l -> l.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("线路不存在: " + id));
    }

    public LoadNode load(String id) {
        return loads.stream().filter(l -> l.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("负荷不存在: " + id));
    }
}
