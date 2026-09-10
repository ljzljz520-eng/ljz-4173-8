package org.bstraining.model;

import java.time.Instant;

/** 训练会话。冻结场景以 JSON 复制存档，与场景库解耦。 */
public record TrainingSession(
        Long id,
        String trainee,
        String instructor,
        String scenarioId,
        int restartCount,
        Instant startedAt,
        Instant endedAt,
        String frozenScenarioJson
) {
}
