package org.bstraining.model;

/**
 * 线路（或主变/联络间隔）。rPu/xPu 为简化标幺阻抗，用于沿路径电压降落估算；
 * closed 为开关初始状态（冻结网架）。
 */
public record Line(
        String id,
        String name,
        String fromBusId,
        String toBusId,
        double rPu,
        double xPu,
        double thermalLimitMw,
        boolean initiallyClosed,
        boolean tieLine
) {
    public Line {
        if (rPu < 0 || xPu < 0) {
            throw new IllegalArgumentException("阻抗不能为负: " + id);
        }
    }
}
