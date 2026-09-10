package org.bstraining.model;

/**
 * 母线。id 全局唯一；name 允许重名（"同名母线"测试场景会出现两条同名母线）。
 * 所有口令、事件均以 id 关联，UI 显示 name。
 */
public record Bus(
        String id,
        String name,
        String substation,
        VoltageLevel voltageLevel
) {
}
