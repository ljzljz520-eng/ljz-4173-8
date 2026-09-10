package org.bstraining.engine;

import org.bstraining.model.OperatorCommand;
import org.bstraining.model.PowerFlowSnapshot;
import org.bstraining.model.Scenario;
import org.bstraining.persistence.SqliteSessionRepository;
import org.bstraining.sim.ScenarioFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 复盘：任一口令前/后快照可定位；从冻结场景重放结果与存档一致。 */
class ReplayServiceTest {

    @TempDir
    Path tmp;

    @Test
    void snapshotAtEachCommandAndRebuildMatches() {
        SqliteSessionRepository repo = new SqliteSessionRepository(tmp.resolve("r.db"));
        Scenario s = ScenarioFixtures.demo();
        TrainingEngine engine = new TrainingEngine(repo, s);
        long sid = engine.start("学员", "教员");

        long n1 = engine.submitCommand(OperatorCommand.CommandType.START_GEN, "gen-A-hydro",
                null, "启动A水电", "学员").seq();
        long n2 = engine.submitCommand(OperatorCommand.CommandType.CLOSE_LINE, "line-A-station",
                null, "合A站主变", "学员").seq();
        long n3 = engine.submitCommand(OperatorCommand.CommandType.CLOSE_LINE, "line-AB",
                null, "送AB线", "学员").seq();
        long n4 = engine.submitCommand(OperatorCommand.CommandType.CLOSE_LINE, "line-B-station",
                null, "合B厂用变", "学员").seq();
        long n5 = engine.submitCommand(OperatorCommand.CommandType.RESTORE_LOAD, "load-B-aux",
                null, "恢复B厂用电", "学员").seq();

        ReplayService replay = new ReplayService(repo);
        for (long seq : List.of(n1, n2, n3, n4, n5)) {
            Optional<PowerFlowSnapshot> before = replay.storedSnapshot(sid, seq, true);
            Optional<PowerFlowSnapshot> after = replay.storedSnapshot(sid, seq, false);
            assertTrue(before.isPresent(), "口令 " + seq + " 前快照应可定位");
            assertTrue(after.isPresent(), "口令 " + seq + " 后快照应可定位");
        }

        // 重放与存档交叉校验（关键电气量）
        List<String> diffs = replay.verifyRebuild(sid, n5);
        assertTrue(diffs.isEmpty(), "重放与存档应一致，差异: " + diffs);
        assertTrue(replay.verifyHashChain(sid), "哈希链应完整");
        repo.close();
    }

    @Test
    void frozenScenarioIsReplayedFromArchiveNotLibrary() {
        SqliteSessionRepository repo = new SqliteSessionRepository(tmp.resolve("r2.db"));
        Scenario s = ScenarioFixtures.demo();
        TrainingEngine engine = new TrainingEngine(repo, s);
        long sid = engine.start("学员", "教员");
        engine.submitCommand(OperatorCommand.CommandType.START_GEN, "gen-A-hydro",
                null, "启动", "学员");

        Scenario frozen = new ReplayService(repo).frozenScenario(sid);
        assertFalse(frozen.hiddenFaults().isEmpty(),
                "复盘使用的是会话冻结场景（含隐藏故障），与场景库后续修改无关");
        repo.close();
    }
}
