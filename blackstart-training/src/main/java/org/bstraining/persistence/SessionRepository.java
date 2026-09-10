package org.bstraining.persistence;

import org.bstraining.model.CandidateFlag;
import org.bstraining.model.Evaluation;
import org.bstraining.model.OperatorCommand;
import org.bstraining.model.PowerFlowSnapshot;
import org.bstraining.model.Scenario;
import org.bstraining.model.SimEvent;
import org.bstraining.model.TrainingSession;

import java.util.List;
import java.util.Optional;

/** 会话持久化端口。事件/口令只有写入接口，不提供修改或删除。 */
public interface SessionRepository extends AutoCloseable {

    long createSession(String trainee, String instructor, Scenario frozenScenario);

    Optional<TrainingSession> findSession(long id);

    List<TrainingSession> listSessions();

    /** 每会话自增的到达次序（记录物理到达顺序，与 seq 解耦，用于乱序审计）。 */
    long nextArrivalOrd(long sessionId);

    void appendEvent(long sessionId, SimEvent chainedEvent, long arrivalOrd);

    List<SimEvent> events(long sessionId);

    /** 一次性写入口令与其前/后快照（保证原口令行不可变）。 */
    void appendCommandWithSnapshots(long sessionId,
                                    OperatorCommand command,
                                    java.time.Instant submittedAt,
                                    java.time.Instant simTime,
                                    PowerFlowSnapshot before,
                                    PowerFlowSnapshot after);

    List<CommandRow> commands(long sessionId);

    Optional<PowerFlowSnapshot> snapshot(long sessionId, long commandSeq, boolean before);

    long addCandidate(long sessionId, CandidateFlag flag);

    void updateCandidateStatus(long sessionId, long candidateId, CandidateFlag.Status status);

    List<CandidateFlag> candidates(long sessionId);

    void saveEvaluation(Evaluation evaluation);

    Optional<Evaluation> evaluation(long sessionId);

    void incrementRestart(long sessionId);

    /** 命令行复盘/审计辅助。 */
    record CommandRow(OperatorCommand command, java.time.Instant submittedAt,
                      java.time.Instant simTime, PowerFlowSnapshot before, PowerFlowSnapshot after) {
    }
}
