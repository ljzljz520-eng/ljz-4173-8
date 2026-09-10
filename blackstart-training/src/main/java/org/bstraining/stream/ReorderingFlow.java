package org.bstraining.stream;

import akka.stream.Attributes;
import akka.stream.FlowShape;
import akka.stream.Inlet;
import akka.stream.Outlet;
import akka.stream.stage.AbstractInHandler;
import akka.stream.stage.AbstractOutHandler;
import akka.stream.stage.GraphStage;
import akka.stream.stage.GraphStageLogic;
import akka.stream.stage.TimerGraphStageLogic;
import org.bstraining.model.SimEvent;

import scala.concurrent.duration.FiniteDuration;

import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

/**
 * 按 seq 重排仿真事件的 Akka Streams 流。
 *
 * <ul>
 *   <li>持续输出 seq = expected 的事件；乱序到达的事件放入小顶堆；</li>
 *   <li>出现序号缺口时启动 gapTimeout 定时器，超时则放弃等待（判定丢失），
 *       把堆内已有最小序号之后的连续事件全部冲出（带告警日志），保证不卡死；</li>
 *   <li>重复 seq 直接丢弃；</li>
 *   <li>支持背压（下游不消费则不向上游拉取）。</li>
 * </ul>
 */
public final class ReorderingFlow extends GraphStage<FlowShape<SimEvent, SimEvent>> {

    public static final FiniteDuration DEFAULT_GAP_TIMEOUT =
            FiniteDuration.apply(300, TimeUnit.MILLISECONDS);

    private final Inlet<SimEvent> in = Inlet.create("ReorderingFlow.in");
    private final Outlet<SimEvent> out = Outlet.create("ReorderingFlow.out");
    private final FlowShape<SimEvent, SimEvent> shape = FlowShape.of(in, out);

    private final FiniteDuration gapTimeout;

    public ReorderingFlow(FiniteDuration gapTimeout) {
        this.gapTimeout = gapTimeout;
    }

    public ReorderingFlow() {
        this(DEFAULT_GAP_TIMEOUT);
    }

    @Override
    public FlowShape<SimEvent, SimEvent> shape() {
        return shape;
    }

    @Override
    public GraphStageLogic createLogic(Attributes inheritedAttributes) {
        return new Logic();
    }

    private final class Logic extends TimerGraphStageLogic {

        private static final String GAP_KEY = "gap";

        private long expected = 1;
        private final Queue<SimEvent> buffer = new PriorityQueue<>(
                java.util.Comparator.comparingLong(SimEvent::seq));
        private boolean upstreamFinished = false;

        Logic() {
            super(shape);

            setHandler(in, new AbstractInHandler() {
                @Override
                public void onPush() {
                    SimEvent e = grab(in);
                    ingest(e);
                    pump();
                    if (!isTimerActive(GAP_KEY) && !buffer.isEmpty()
                            && buffer.peek().seq() > expected) {
                        scheduleOnce(GAP_KEY, gapTimeout);
                    }
                    if (!hasBeenPulled(in) && !upstreamFinished) {
                        tryPull(in);
                    }
                }

                @Override
                public void onUpstreamFinish() {
                    upstreamFinished = true;
                    // 上游结束：不再等待任何缺失序号，从堆内最小序号继续冲出
                    if (!buffer.isEmpty()) {
                        expected = buffer.peek().seq();
                    }
                    pump();
                }
            });

            setHandler(out, new AbstractOutHandler() {
                @Override
                public void onPull() {
                    pump();
                    if (!hasBeenPulled(in) && !upstreamFinished) {
                        tryPull(in);
                    }
                }
            });
        }

        @Override
        public void preStart() {
            pull(in);
        }

        @Override
        public void onTimer(Object timerKey) {
            if (GAP_KEY.equals(timerKey)) {
                // 缺口等待超时：把堆内现有事件按序全部冲出（跳过丢失序号）
                if (!buffer.isEmpty()) {
                    SimEvent next = buffer.peek();
                    org.slf4j.LoggerFactory.getLogger(ReorderingFlow.class).warn(
                            "事件序号 {} 等待超时，跳空至 {}（疑似仿真事件丢失）",
                            expected, next.seq());
                    expected = next.seq();
                    pump();
                }
            }
        }

        private void ingest(SimEvent e) {
            if (e.seq() < expected) {
                org.slf4j.LoggerFactory.getLogger(ReorderingFlow.class)
                        .info("丢弃重复/过期事件 seq={}", e.seq());
                return;
            }
            boolean dup = buffer.stream().anyMatch(x -> x.seq() == e.seq());
            if (dup) {
                org.slf4j.LoggerFactory.getLogger(ReorderingFlow.class)
                        .info("丢弃重复 seq={}", e.seq());
                return;
            }
            buffer.add(e);
        }

        /** 把可用的连续事件推给下游。 */
        private void pump() {
            while (isAvailable(out) && !buffer.isEmpty() && buffer.peek().seq() == expected) {
                SimEvent e = buffer.poll();
                expected = e.seq() + 1;
                push(out, e);
            }
            if (buffer.isEmpty() || buffer.peek().seq() != expected) {
                cancelTimer(GAP_KEY);
            }
            if (upstreamFinished && buffer.isEmpty()) {
                completeStage();
            }
        }
    }
}
