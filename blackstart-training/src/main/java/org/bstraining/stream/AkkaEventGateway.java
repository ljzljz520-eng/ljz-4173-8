package org.bstraining.stream;

import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.SourceQueueWithComplete;
import org.bstraining.model.SimEvent;
import org.bstraining.sim.EventTransport;

import java.util.concurrent.CompletionStage;

/**
 * Akka Streams 仿真事件接入网关：
 *
 * <pre>
 *   仿真器 → SourceQueue(事件入口, EventTransport 实现)
 *          → ReorderingFlow(乱序重排, 缺口超时跳空)
 *          → 下游 Sink(持久化 + 引擎处理)
 * </pre>
 */
public final class AkkaEventGateway {

    private final ActorSystem system;
    private final SourceQueueWithComplete<SimEvent> queue;

    private AkkaEventGateway(ActorSystem system, SourceQueueWithComplete<SimEvent> queue) {
        this.system = system;
        this.queue = queue;
    }

    /** 启动流图，返回网关；每个有序事件（重排后）交给 orderedSink。 */
    public static AkkaEventGateway start(ActorSystem system,
                                         Sink<SimEvent, CompletionStage<akka.Done>> orderedSink) {
        return start(system, orderedSink, 1024, ReorderingFlow.DEFAULT_GAP_TIMEOUT);
    }

    public static AkkaEventGateway start(ActorSystem system,
                                         Sink<SimEvent, CompletionStage<akka.Done>> orderedSink,
                                         int bufferSize,
                                         scala.concurrent.duration.FiniteDuration gapTimeout) {
        Pair<SourceQueueWithComplete<SimEvent>, CompletionStage<akka.Done>> mat =
                Source.<SimEvent>queue(bufferSize, OverflowStrategy.backpressure())
                        .via(Flow.fromGraph(new ReorderingFlow(gapTimeout)))
                        .toMat(orderedSink, Keep.both())
                        .run(system);
        return new AkkaEventGateway(system, mat.first());
    }

    /** 供仿真器使用的事件出口。 */
    public EventTransport transport() {
        return queue::offer;
    }

    public void complete() {
        queue.complete();
    }

    public void fail(Throwable t) {
        queue.fail(t);
    }
}
