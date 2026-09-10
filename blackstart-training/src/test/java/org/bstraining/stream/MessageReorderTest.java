package org.bstraining.stream;

import akka.actor.ActorSystem;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import org.bstraining.model.EventType;
import org.bstraining.model.SimEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import scala.concurrent.duration.Duration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交付测试 1：消息乱序。
 * 仿真器按 seq 产生事件，传输层分块洗牌；ReorderingFlow 必须恢复严格升序。
 */
class MessageReorderTest {

    private static final ActorSystem SYS = ActorSystem.create("reorder-test");

    @AfterAll
    static void shutdown() {
        SYS.terminate();
    }

    private static SimEvent ev(long seq) {
        return new SimEvent(UUID.randomUUID().toString(), seq, null, EventType.TELEMETRY,
                null, "e" + seq, null, Instant.now(), Instant.now(), null, null);
    }

    @Test
    void restoresOrderAfterBlockShuffle() throws Exception {
        List<SimEvent> input = new ArrayList<>();
        // 1..60，用固定随机种子分块洗牌，制造乱序但不丢包
        Random rnd = new Random(42);
        for (long i = 1; i <= 60; i++) {
            input.add(ev(i));
        }
        List<SimEvent> shuffled = new ArrayList<>(input);
        Collections.shuffle(shuffled, rnd);
        assertTrue(isOutOfOrder(shuffled), "前置条件：洗牌后应存在乱序");

        List<SimEvent> out = Source.from(shuffled)
                .via(new ReorderingFlow(Duration.apply(100, TimeUnit.MILLISECONDS)))
                .toMat(Sink.seq(), Keep.right())
                .run(SYS)
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(60, out.size());
        for (int i = 0; i < out.size(); i++) {
            assertEquals(i + 1, out.get(i).seq(),
                    "重排后位置 " + i + " 的序号应为 " + (i + 1));
        }
    }

    @Test
    void skipsGapAfterTimeoutWithoutStalling() throws Exception {
        // 缺 seq=3：1,2 立即可出；4..6 在缺口超时后冲出，流不卡死
        List<SimEvent> input = List.of(ev(1), ev(2), ev(4), ev(5), ev(6));
        List<SimEvent> out = Source.from(input)
                .via(new ReorderingFlow(Duration.apply(120, TimeUnit.MILLISECONDS)))
                .toMat(Sink.seq(), Keep.right())
                .run(SYS)
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(List.of(1L, 2L, 4L, 5L, 6L), out.stream().map(SimEvent::seq).toList());
    }

    @Test
    void shufflingTransportEmitsFullSetUnordered() throws Exception {
        // ShufflingTransport 与仿真器联调：全部事件仍可达且乱序
        List<SimEvent> raw = new ArrayList<>();
        List<SimEvent> afterTransport = new ArrayList<>();
        ShufflingTransport t = new ShufflingTransport(afterTransport::add, 8, new Random(7));
        for (long i = 1; i <= 24; i++) {
            SimEvent e = ev(i);
            raw.add(e);
            t.emit(e);
        }
        t.flush();
        assertEquals(raw.size(), afterTransport.size(), "乱序传输不得丢事件");
        assertEquals(raw.stream().map(SimEvent::seq).sorted().toList(),
                afterTransport.stream().map(SimEvent::seq).sorted().toList());
    }

    private static boolean isOutOfOrder(List<SimEvent> list) {
        for (int i = 1; i < list.size(); i++) {
            if (list.get(i).seq() < list.get(i - 1).seq()) {
                return true;
            }
        }
        return false;
    }
}
