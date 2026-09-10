package org.bstraining.model;

/** 规则引擎只产生三类候选，不阻断操作、不做最终判定。 */
public enum ViolationKind {
    PRECONDITION("越过前置条件"),
    FREQUENCY_VOLTAGE("频压越界"),
    COMMUNICATION("通信遗漏");

    public final String cn;

    ViolationKind(String cn) {
        this.cn = cn;
    }
}
