package org.bstraining.model;

import java.time.Instant;
import java.util.List;

/**
 * 潮流快照（不可变）。每个口令记录前/后各一份，复盘可定位到任一口令前后。
 */
public record PowerFlowSnapshot(
        Instant simTime,
        long commandSeq,
        boolean before,
        List<BusState> buses,
        List<GenState> generators,
        List<LineState> lines,
        List<LoadState> loads,
        List<IslandState> islands
) {

    public IslandState islandOf(String busId) {
        String islandId = buses.stream()
                .filter(b -> b.busId().equals(busId)).findFirst()
                .map(BusState::islandId).orElse(null);
        if (islandId == null) {
            return null;
        }
        return islands.stream().filter(i -> i.islandId().equals(islandId)).findFirst()
                .orElse(null);
    }
}
