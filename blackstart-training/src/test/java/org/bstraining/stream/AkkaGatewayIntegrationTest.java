package org.bstraining.stream;

import akka.Done;
import akka.actor.ActorSystem;
import akka.stream.javadsl.Sink;
import org.bstraining.engine.TrainingEngine;
import org.bstraining.model.OperatorCommand;
import org.bstraining.model.Scenario;
import org.bstraining.model.SimEvent;
import org.bstraining.persistence.SqliteSessionRepository;
import org.bstraining.sim.EmulatedSimulator;
import org.bstraining.sim.EventTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全链路：仿真器 → 随机延迟乱序投递 → Akka SourceQueue →
 * ReorderingFlow(重排) → 训练引擎有序入口（哈希链/持久化）。
 */
class AkkaGatewayIntegrationTest {

    @TempDir
    Path tmp;

    @Test
    void shuffledEventsAreReorderedBeforeEnginePersistence() throws Exception {
        ActorSystem sys = ActorSystem.create("it");
        SqliteSessionRepository repo = new SqliteSessionRepository(tmp.resolve("it.db"));
        Scenario s = org.bstraining.sim.ScenarioFixtures.demo();

        TrainingEngine engine = new TrainingEngine(repo, s, new EmulatedSimulator(), false);
        Sink<SimEvent, java.util.concurrent.CompletionStage<Done>> sink =
                Sink.foreach(e -> engine.orderedTransport().emit(e));
        // 小缓冲 + 短缺口超时，乱序事件可快速重排
        AkkaEventGateway gw = AkkaEventGateway.start(sys, sink, 256,
                scala.concurrent.duration.Duration.apply(120, TimeUnit.MILLISECONDS));

        // 每条事件 1~30ms 随机延迟后投到队列，制造真实网络乱序但不丢包
        ScheduledExecutorService pool = Executors.newScheduledThreadPool(2);
        EventTransport delayed = e -> {
            long d = 1 + (long) (Math.random() * 29);
            pool.schedule(() -> gw.transport().emit(e), d, TimeUnit.MILLISECONDS);
        };
        engine.attachExternalTransport(delayed);

        engine.start("学员", "教员");
        engine.submitCommand(OperatorCommand.CommandType.START_GEN, "gen-A-hydro",
                null, "A水电自启动", "学员");
        engine.submitCommand(OperatorCommand.CommandType.CLOSE_LINE, "line-A-station",
                null, "合A站主变", "学员");

        List<SimEvent> stored = repo.events(engine.sessionId());
        for (int i = 1; i < stored.size(); i++) {
            assertTrue(stored.get(i).seq() > stored.get(i - 1).seq(),
                    "入库事件必须按 seq 严格有序");
        }
        assertTrue(EventHashChain.verify(stored), "乱序重排后哈希链仍须连续");
        assertEquals(2, repo.commands(engine.sessionId()).size());

        pool.shutdownNow();
        gw.complete();
        sys.terminate();
        repo.close();
    }
}
