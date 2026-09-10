package org.bstraining.model;

/** 电气岛状态（简化潮流/动态）。 */
public record IslandState(
        String islandId,
        boolean energized,
        double frequencyHz,
        double generationMw,
        double loadMw
) {
}
