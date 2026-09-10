package org.bstraining.persistence;

import org.bstraining.engine.TrainingEngine;
import org.bstraining.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 持久化测试：冻结场景随会话存档；口令/事件只追加（触发器禁止改删）；
 * 快照可按口令前/后定位；哈希链完整、篡改即断。
 */
class PersistenceTest {

    @TempDir
    Path tmp;

    @Test
    void eventsAndCommandsAreAppendOnly() throws Exception {
        SqliteSessionRepository repo = new SqliteSessionRepository(tmp.resolve("a.db"));
        Scenario s = org.bstraining.sim.ScenarioFixtures.demo();
        TrainingEngine engine = new TrainingEngine(repo, s);
        long sid = engine.start("学员", "教员");
        engine.submitCommand(OperatorCommand.CommandType.START_GEN, "gen-A-hydro",
                null, "A水电自启动", "学员");

        List<SimEvent> events = repo.events(sid);
        assertFalse(events.isEmpty());
        String firstHash = events.get(0).hash();
        assertNotNull(firstHash);

        try (Connection cx = DriverManager.getConnection("jdbc:sqlite:" + tmp.resolve("a.db"))) {
            assertThrows(java.sql.SQLException.class, () -> {
                try (var st = cx.createStatement()) {
                    st.executeUpdate("UPDATE events SET message='被篡改' WHERE seq=" + events.get(0).seq());
                }
            }, "原事件必须不可编辑");
            assertThrows(java.sql.SQLException.class, () -> {
                try (var st = cx.createStatement()) {
                    st.executeUpdate("DELETE FROM events");
                }
            }, "原事件必须不可删除");
            assertThrows(java.sql.SQLException.class, () -> {
                try (var st = cx.createStatement()) {
                    st.executeUpdate("UPDATE command_records SET seq=999");
                }
            }, "原口令必须不可编辑");
        }
        repo.close();
    }

    @Test
    void snapshotsLocatedBeforeAndAfterCommand() {
        SqliteSessionRepository repo = new SqliteSessionRepository(tmp.resolve("b.db"));
        Scenario s = org.bstraining.sim.ScenarioFixtures.demo();
        TrainingEngine engine = new TrainingEngine(repo, s);
        long sid = engine.start("学员", "教员");

        PowerFlowSnapshot before = engine.simulator().snapshot(1, true);
        engine.submitCommand(OperatorCommand.CommandType.START_GEN, "gen-A-hydro",
                null, "A水电自启动", "学员");

        PowerFlowSnapshot b = repo.snapshot(sid, 1, true).orElseThrow();
        PowerFlowSnapshot a = repo.snapshot(sid, 1, false).orElseThrow();
        assertTrue(b.buses().stream().noneMatch(x -> x.busId().equals("bus-A-110") && x.energized()),
                "口令前 A 母线应失电");
        assertTrue(a.buses().stream().anyMatch(x -> x.busId().equals("bus-A-110") && x.energized()),
                "口令后 A 母线应带电");
        assertEquals(1, repo.commands(sid).size());
        repo.close();
    }

    @Test
    void frozenScenarioIsCopiedPerSessionAndHashChainVerifies() throws Exception {
        SqliteSessionRepository repo = new SqliteSessionRepository(tmp.resolve("c.db"));
        Scenario s = org.bstraining.sim.ScenarioFixtures.demo();
        TrainingEngine engine = new TrainingEngine(repo, s);
        long sid = engine.start("学员", "教员");
        engine.submitCommand(OperatorCommand.CommandType.START_GEN, "gen-A-hydro",
                null, "A水电自启动", "学员");

        TrainingSession ts = repo.findSession(sid).orElseThrow();
        Scenario frozen = org.bstraining.sim.JsonSupport.mapper()
                .readValue(ts.frozenScenarioJson(), Scenario.class);
        assertEquals(s.id(), frozen.id());
        assertTrue(org.bstraining.stream.EventHashChain.verify(repo.events(sid)),
                "哈希链应完整");

        // 直接改库模拟篡改（临时关闭触发器做不到，故改一条新副本验证 verify 逻辑）
        List<SimEvent> evs = repo.events(sid);
        SimEvent tampered = new SimEvent(evs.get(0).eventId(), evs.get(0).seq(),
                evs.get(0).commandSeq(), evs.get(0).type(), evs.get(0).elementId(),
                "被篡改的消息", evs.get(0).payloadJson(), evs.get(0).simTime(),
                evs.get(0).producedAt(), evs.get(0).prevHash(), evs.get(0).hash());
        java.util.List<SimEvent> mutated = new java.util.ArrayList<>(evs);
        mutated.set(0, tampered);
        assertFalse(org.bstraining.stream.EventHashChain.verify(mutated),
                "字段被改后哈希链必须断裂");
        repo.close();
    }

    @Test
    void candidateLifecycleAndSignedEvaluation() {
        SqliteSessionRepository repo = new SqliteSessionRepository(tmp.resolve("d.db"));
        Scenario s = org.bstraining.sim.ScenarioFixtures.demo();
        TrainingEngine engine = new TrainingEngine(repo, s);
        long sid = engine.start("学员", "教员");
        engine.submitCommand(OperatorCommand.CommandType.RESTORE_LOAD, "load-C-critical",
                null, "盲目恢复负荷", "学员");

        CandidateFlag flag = repo.candidates(sid).get(0);
        repo.updateCandidateStatus(sid, flag.id(), CandidateFlag.Status.CONFIRMED);
        assertEquals(CandidateFlag.Status.CONFIRMED,
                repo.candidates(sid).stream().filter(f -> f.id() == flag.id())
                        .findFirst().orElseThrow().status());

        Evaluation ev = new Evaluation(sid, "教员", "顺序意识不足", "无", "先电源后负荷",
                60, true, java.time.Instant.now());
        repo.saveEvaluation(ev);
        assertTrue(repo.evaluation(sid).orElseThrow().signed());
        repo.close();
    }
}
