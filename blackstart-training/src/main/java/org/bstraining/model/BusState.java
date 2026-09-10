package org.bstraining.model;

/** 潮流快照片中的母线状态。 */
public record BusState(
        String busId,
        boolean energized,
        double voltagePu,
        double angleDeg,
        String islandId
) {
}
