package org.bstraining.model;

public record GenState(
        String genId,
        boolean running,
        double outputMw,
        String islandId
) {
}
