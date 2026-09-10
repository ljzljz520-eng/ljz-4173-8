package org.bstraining.model;

import java.time.Instant;

/**
 * 候选问题标记。状态由教员在签署评价时处置（确认/驳回）；
 * 规则引擎仅给出候选，不代替教员判定。
 */
public record CandidateFlag(
        Long id,
        long commandSeq,
        String eventId,
        ViolationKind kind,
        String rule,
        String detail,
        Status status,
        Instant raisedAt
) {

    public enum Status { OPEN, CONFIRMED, DISMISSED }

    public CandidateFlag with(Status status) {
        return new CandidateFlag(id, commandSeq, eventId, kind, rule, detail, status, raisedAt);
    }

    public CandidateFlag withId(long id) {
        return new CandidateFlag(id, commandSeq, eventId, kind, rule, detail, status, raisedAt);
    }
}
