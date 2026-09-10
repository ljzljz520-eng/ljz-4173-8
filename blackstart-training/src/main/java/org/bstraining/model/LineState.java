package org.bstraining.model;

public record LineState(
        String lineId,
        boolean closed,
        double flowMw
) {
}
