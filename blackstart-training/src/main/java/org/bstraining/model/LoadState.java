package org.bstraining.model;

public record LoadState(
        String loadId,
        boolean connected,
        double mw
) {
}
