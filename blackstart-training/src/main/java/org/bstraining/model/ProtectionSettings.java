package org.bstraining.model;

/**
 * 保护与同期限制（冻结于场景内，规则引擎与仿真器共同引用）。
 *
 * @param fMinHz          频率下限（标幺 Hz）
 * @param fMaxHz          频率上限
 * @param vMinPu          母线电压下限
 * @param vMaxPu          母线电压上限
 * @param syncDfMaxHz     同期并列频差门槛
 * @param syncDvMaxPu     同期并列压差门槛
 * @param syncDangleDeg   同期并列角差门槛（SYNC_TIE 经同期装置）
 * @param hardCloseDangleDeg 强合/未同期合环角差门槛（超过即按误并列冲击保护跳闸）
 * @param underFreqShedHz 低频减载动作频率
 */
public record ProtectionSettings(
        double fMinHz,
        double fMaxHz,
        double vMinPu,
        double vMaxPu,
        double syncDfMaxHz,
        double syncDvMaxPu,
        double syncDangleDeg,
        double hardCloseDangleDeg,
        double underFreqShedHz
) {

    public static ProtectionSettings typical() {
        return new ProtectionSettings(49.8, 50.2, 0.95, 1.07,
                0.20, 0.10, 20.0, 10.0, 49.0);
    }
}
