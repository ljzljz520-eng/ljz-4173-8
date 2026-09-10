package org.bstraining.model;

/** 标准电压等级（仅用于展示与建模）。 */
public enum VoltageLevel {
    KV_500(500), KV_220(220), KV_110(110), KV_35(35), KV_10(10), KV_6_3(6.3);

    private final double kv;

    VoltageLevel(double kv) {
        this.kv = kv;
    }

    public double kv() {
        return kv;
    }
}
