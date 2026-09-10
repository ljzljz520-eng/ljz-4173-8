package org.bstraining.model;

/**
 * 发电机组模型（冻结于场景内）。
 *
 * @param blackstartCapable 是否黑启动机组（自带辅机电源，可自启动）
 * @param initiallyRunning  场景初始时刻是否已运行（外部启动电源/厂用电已带出的机组）
 */
public record GeneratorUnit(
        String id,
        String name,
        String busId,
        double ratedMw,
        double minStableMw,
        double rampMwPerMin,
        boolean blackstartCapable,
        boolean initiallyRunning
) {
}
