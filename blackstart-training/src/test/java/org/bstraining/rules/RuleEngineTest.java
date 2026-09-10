package org.bstraining.rules;

import org.bstraining.engine.TrainingEngine;
import org.bstraining.model.CandidateFlag;
import org.bstraining.model.CommandRecord;
import org.bstraining.model.OperatorCommand;
import org.bstraining.model.PowerFlowSnapshot;
import org.bstraining.model.Scenario;
import org.bstraining.model.ViolationKind;
import org.bstraining.persistence.SessionRepository;
import org.bstraining.persistence.SqliteSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则引擎候选测试：前置条件 / 频压越界 / 通信遗漏。
 * 规则引擎只标候选，不改变口令是否被系统接受。
 */
class RuleEngineTest {

    @TempDir
    Path tmp;
    private SessionRepository repo;
    private Scenario scenario;

    @BeforeEach
    void setUp() {
        repo = new SqliteSessionRepository(tmp.resolve("t.db"));
        scenario = org.bstraining.sim.ScenarioFixtures.demo();
    }

    @AfterEach
    void close() throws Exception {
        repo.close();
    }

    @Test
    void flagsPreconditionAndCommunicationWhenRestoringLoadWithoutPower() throws Exception {
        TrainingEngine engine = new TrainingEngine(repo, scenario);
        engine.start("学员", "教员");

        // 全黑状态直接恢复 C 站负荷：越前置条件 + 无复诵汇报
        CommandRecord r = engine.submitCommand(
                OperatorCommand.CommandType.RESTORE_LOAD, "load-C-critical",
                null, "恢复C站一类负荷", "学员");
        List<CandidateFlag> flags = repo.candidates(engine.sessionId()).stream()
                .filter(f -> f.commandSeq() == r.seq()).toList();

        assertTrue(flags.stream().anyMatch(f -> f.kind() == ViolationKind.PRECONDITION),
                "未带电恢复负荷应产生前置条件候选");
        assertTrue(flags.stream().anyMatch(f -> f.kind() == ViolationKind.COMMUNICATION),
                "无复诵/汇报应产生通信遗漏候选");
    }

    @Test
    void reportSuppressesCommunicationCandidate() throws Exception {
        TrainingEngine engine = new TrainingEngine(repo, scenario);
        engine.start("学员", "教员");

        long seq = nextSeq(engine);
        engine.submitCommand(OperatorCommand.CommandType.START_GEN, "gen-A-hydro",
                null, "A水电#1机自启动", "学员");
        long startSeq = seq;
        // 紧跟汇报
        engine.submitCommand(OperatorCommand.CommandType.REPORT, null, null,
                "汇报：A水电#1机已自启动建压", "学员");
        // 再做一次操作，窗口内有 REPORT
        engine.submitCommand(OperatorCommand.CommandType.CLOSE_LINE, "line-A-station",
                null, "合A站主变对母线充电", "学员");

        List<CandidateFlag> all = repo.candidates(engine.sessionId());
        // 第一条操作前后无任何 REPORT，应有通信候选；第二条窗口内有汇报，不应有
        assertTrue(all.stream().anyMatch(f -> f.commandSeq() == startSeq
                && f.kind() == ViolationKind.COMMUNICATION));
        assertFalse(all.stream().anyMatch(f -> f.kind() == ViolationKind.COMMUNICATION
                && f.detail().contains("合A站主变")),
                "窗口内已汇报，不应再提示该操作通信遗漏");
    }

    @Test
    void frequencyViolationFlaggedOnOverloadedIsland() throws Exception {
        Scenario island = org.bstraining.sim.ScenarioFixtures.islandMisparallel();
        SessionRepository r2 = new SqliteSessionRepository(tmp.resolve("i.db"));
        TrainingEngine engine = new TrainingEngine(r2, island);
        engine.start("学员", "教员");
        // Y 岛初始即严重低频（口令#1 前快照无操作；用一次汇报让规则评估跑一遍岛状态）
        PowerFlowSnapshot s = engine.simulator().snapshot(1, true);
        long lowIslands = s.islands().stream()
                .filter(i -> i.energized()
                        && (i.frequencyHz() < island.protection().fMinHz()
                        || i.frequencyHz() > island.protection().fMaxHz()))
                .count();
        assertTrue(lowIslands >= 1, "夹具中 Y 岛应处于低频越界");
        r2.close();
    }

    private long nextSeq(TrainingEngine engine) throws Exception {
        return engine.history().size() + 1;
    }
}
