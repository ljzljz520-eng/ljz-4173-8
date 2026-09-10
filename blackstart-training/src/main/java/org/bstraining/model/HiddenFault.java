package org.bstraining.model;

/** 隐藏故障（学员不可见，命中操作时才暴露），由场景冻结。 */
public record HiddenFault(
        String id,
        String elementId,
        FaultKind kind,
        String description
) {

    public enum FaultKind {
        /** 机组自启动失败（黑启动电源假可用） */
        GEN_FAIL_TO_START,
        /** 线路/主变充电瞬间保护动作跳闸（绝缘隐患） */
        LINE_TRIP_ON_ENERGIZE
    }
}
