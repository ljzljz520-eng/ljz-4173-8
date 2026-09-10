package org.bstraining.sim;

import org.bstraining.model.SimEvent;

/**
 * 仿真事件出口（端口）。Akka 接入层提供 Source 侧实现；
 * 测试可替换为乱序注入实现。
 */
@FunctionalInterface
public interface EventTransport {
    void emit(SimEvent event);
}
