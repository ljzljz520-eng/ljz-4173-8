package org.bstraining.model;

/**
 * 学员口令 / 模拟操作，按提交顺序记录。
 *
 * @param seq         会话内单调递增序号（提交时刻分配）
 * @param type        口令类型
 * @param elementId   目标设备（母线/机组/线路/负荷 id）
 * @param targetBusId 同期合环等需要第二侧母线时填写
 * @param spoken      学员原始口令文本（含发令/复诵/汇报/联系）
 * @param actor       发令人/受令人角色
 * @param receivedAt  仿真侧接收时间（乱序测试用，null 表示立即）
 */
public record OperatorCommand(
        long seq,
        CommandType type,
        String elementId,
        String targetBusId,
        String spoken,
        String actor,
        java.time.Instant receivedAt
) {

    public enum CommandType {
        START_GEN("启动机组"),
        RAISE_GEN("加出力"),
        CLOSE_LINE("合环/充电线(线路送电)"),
        SYNC_TIE("同期并列联络线"),
        OPEN_LINE("拉停线路"),
        RESTORE_LOAD("恢复负荷"),
        DISCONNECT_LOAD("切除负荷"),
        RESYNC_ALIGN("同期调整(调频/调压到同期点)"),
        /** 通信类口令：不产生电气操作，仅用于复诵、汇报、联系完整性核对 */
        REPORT("汇报/复诵/联系");

        public final String cn;

        CommandType(String cn) {
            this.cn = cn;
        }
    }
}
