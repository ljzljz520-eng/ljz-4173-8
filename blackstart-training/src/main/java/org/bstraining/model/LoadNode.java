package org.bstraining.model;

/**
 * 负荷。priority 数字越小优先级越高（重要厂用电/枢纽负荷先恢复）。
 */
public record LoadNode(
        String id,
        String name,
        String busId,
        double mw,
        int priority,
        boolean initiallyConnected
) {
}
