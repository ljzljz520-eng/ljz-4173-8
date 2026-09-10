package org.bstraining.model;

import java.time.Instant;

/**
 * 仿真器事件（不可变）。
 * <p>流序保证：同一数据源按 seq 编号；适配层 {@code ReorderingFlow} 负责乱序重排后交付。
 * 原始到达顺序另由持久层记录，事件本身不可编辑，哈希链保证完整性。</p>
 *
 * @param eventId       全局唯一
 * @param seq           数据源单调序号
 * @param commandSeq    关联口令序号；遥测/自发事件为 null
 * @param type          事件类型
 * @param elementId     相关设备 id（可空）
 * @param message       中文系统响应描述
 * @param payloadJson   附加结构化数据（JSON，可空）
 * @param simTime       仿真时间
 * @param producedAt    事件产生时间
 * @param prevHash      会话内前一事件哈希
 * @param hash          本事件 SHA-256(prevHash || canonical(除hash外字段))
 */
public record SimEvent(
        String eventId,
        long seq,
        Long commandSeq,
        EventType type,
        String elementId,
        String message,
        String payloadJson,
        Instant simTime,
        Instant producedAt,
        String prevHash,
        String hash
) {
    /** 流上传输时可能尚未算链；适配器在入库前补算哈希链。 */
    public SimEvent withChain(String prevHash, String hash) {
        return new SimEvent(eventId, seq, commandSeq, type, elementId, message, payloadJson,
                simTime, producedAt, prevHash, hash);
    }
}
