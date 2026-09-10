package org.bstraining.model;

import java.time.Instant;

/**
 * 口令记录（原口令不可编辑）。beforeSnapshotId/afterSnapshotId 指向持久化的快照 JSON 行。
 */
public record CommandRecord(
        long seq,
        OperatorCommand command,
        Instant submittedAt,
        Instant simTime,
        String beforeSnapshotJson,
        String afterSnapshotJson
) {
}
