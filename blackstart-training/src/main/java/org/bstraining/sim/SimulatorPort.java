package org.bstraining.sim;

import org.bstraining.model.OperatorCommand;
import org.bstraining.model.PowerFlowSnapshot;
import org.bstraining.model.Scenario;
import org.bstraining.model.SimEvent;

/**
 * 仿真器适配端口。真实系统中可替换为对接外部仿真服务（如 DTS/OTS 网关）的实现；
 * 本交付物提供 {@link EmulatedSimulator}，不连接任何真实电网控制系统。
 */
public interface SimulatorPort {

    /** 建立会话：冻结场景深拷贝进入仿真器，初始遥测/开关位事件由此发出。 */
    void attach(String sessionId, Scenario frozenScenario);

    /** 当前潮流快照。 */
    PowerFlowSnapshot snapshot(long commandSeq, boolean before);

    /**
     * 提交一条口令/操作。实现须把响应事件（可能多条）交给 {@link EventTransport}，
     * 最后一条必须是 COMMAND_ACCEPTED/COMPLETE/REJECTED 边界事件。
     */
    void submit(OperatorCommand command);

    /** 周期遥测节拍（驱动频率/电压曲线）。 */
    void tick();

    /** 场景重启：恢复到冻结场景初始状态，restartCount 由上层累加。 */
    void restart();

    void setTransport(EventTransport transport);
}
