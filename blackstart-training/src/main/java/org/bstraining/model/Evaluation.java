package org.bstraining.model;

import java.time.Instant;

/**
 * 教员评价（每会话一份，签署后生效）。结论基于系统响应 + 候选清单人工给出。
 */
public record Evaluation(
        Long sessionId,
        String instructor,
        String summary,
        String strengths,
        String improvements,
        int score,
        boolean signed,
        Instant signedAt
) {
}
