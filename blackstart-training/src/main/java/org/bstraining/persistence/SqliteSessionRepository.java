package org.bstraining.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bstraining.model.CandidateFlag;
import org.bstraining.model.Evaluation;
import org.bstraining.model.EventType;
import org.bstraining.model.OperatorCommand;
import org.bstraining.model.PowerFlowSnapshot;
import org.bstraining.model.ProtectionSettings;
import org.bstraining.model.Scenario;
import org.bstraining.model.SimEvent;
import org.bstraining.model.TrainingSession;
import org.bstraining.model.ViolationKind;
import org.bstraining.sim.JsonSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SQLite 实现。连接单线程使用（桌面端训练引擎内串行化访问）。 */
public final class SqliteSessionRepository implements SessionRepository {

    private final Connection cx;
    private final ObjectMapper mapper = JsonSupport.mapper();

    public SqliteSessionRepository(Path dbFile) {
        try {
            Files.createDirectories(dbFile.toAbsolutePath().getParent());
            this.cx = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
            try (Statement st = cx.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                for (String ddl : Schema.DDL) {
                    st.execute(ddl);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("初始化 SQLite 失败: " + dbFile, e);
        }
    }

    public SqliteSessionRepository() {
        this(Path.of(System.getProperty("user.home"), ".bstraining", "training.db"));
    }

    @Override
    public long createSession(String trainee, String instructor, Scenario frozenScenario) {
        try (PreparedStatement ps = cx.prepareStatement(
                "INSERT INTO sessions(trainee, instructor, scenario_id, restart_count, "
                        + "started_at, ended_at, frozen_scenario_json) VALUES (?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, trainee);
            ps.setString(2, instructor);
            ps.setString(3, frozenScenario.id());
            ps.setInt(4, 0);
            ps.setString(5, Instant.now().toString());
            ps.setString(6, null);
            ps.setString(7, mapper.writeValueAsString(frozenScenario));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<TrainingSession> findSession(long id) {
        try (PreparedStatement ps = cx.prepareStatement("SELECT * FROM sessions WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapSession(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<TrainingSession> listSessions() {
        try (Statement st = cx.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT * FROM sessions ORDER BY id DESC")) {
            List<TrainingSession> out = new ArrayList<>();
            while (rs.next()) {
                out.add(mapSession(rs));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public long nextArrivalOrd(long sessionId) {
        try (PreparedStatement ps = cx.prepareStatement(
                "SELECT COALESCE(MAX(arrival_ord),0)+1 FROM events WHERE session_id=?")) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void appendEvent(long sessionId, SimEvent e, long arrivalOrd) {
        try (PreparedStatement ps = cx.prepareStatement(
                "INSERT INTO events(session_id,event_id,seq,command_seq,type,element_id,message,"
                        + "payload_json,sim_time,produced_at,arrival_ord,prev_hash,hash) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, sessionId);
            ps.setString(2, e.eventId());
            ps.setLong(3, e.seq());
            if (e.commandSeq() == null) {
                ps.setNull(4, java.sql.Types.INTEGER);
            } else {
                ps.setLong(4, e.commandSeq());
            }
            ps.setString(5, e.type().name());
            ps.setString(6, e.elementId());
            ps.setString(7, e.message());
            ps.setString(8, e.payloadJson());
            ps.setString(9, e.simTime().toString());
            ps.setString(10, e.producedAt().toString());
            ps.setLong(11, arrivalOrd);
            ps.setString(12, e.prevHash());
            ps.setString(13, e.hash());
            ps.executeUpdate();
        } catch (Exception ex) {
            throw new IllegalStateException("事件入库失败（原事件不可重复写入）: " + e.eventId(), ex);
        }
    }

    @Override
    public List<SimEvent> events(long sessionId) {
        try (PreparedStatement ps = cx.prepareStatement(
                "SELECT * FROM events WHERE session_id=? ORDER BY seq ASC")) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<SimEvent> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapEvent(rs));
                }
                return out;
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 物理到达顺序（乱序审计用）。 */
    public List<SimEvent> eventsByArrival(long sessionId) {
        try (PreparedStatement ps = cx.prepareStatement(
                "SELECT * FROM events WHERE session_id=? ORDER BY arrival_ord ASC")) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<SimEvent> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapEvent(rs));
                }
                return out;
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void appendCommandWithSnapshots(long sessionId, OperatorCommand command,
                                           Instant submittedAt, Instant simTime,
                                           PowerFlowSnapshot before, PowerFlowSnapshot after) {
        try {
            cx.setAutoCommit(false);
            long beforeId = insertSnapshot(sessionId, before);
            long afterId = insertSnapshot(sessionId, after);
            try (PreparedStatement ps = cx.prepareStatement(
                    "INSERT INTO command_records(session_id,seq,command_json,submitted_at,"
                            + "sim_time,before_snapshot_id,after_snapshot_id) VALUES (?,?,?,?,?,?,?)")) {
                ps.setLong(1, sessionId);
                ps.setLong(2, command.seq());
                ps.setString(3, mapper.writeValueAsString(command));
                ps.setString(4, submittedAt.toString());
                ps.setString(5, simTime.toString());
                ps.setLong(6, beforeId);
                ps.setLong(7, afterId);
                ps.executeUpdate();
            }
            cx.commit();
        } catch (Exception e) {
            rollback();
            throw new IllegalStateException(e);
        } finally {
            setAutoCommitTrue();
        }
    }

    private long insertSnapshot(long sessionId, PowerFlowSnapshot s) throws Exception {
        try (PreparedStatement ps = cx.prepareStatement(
                "INSERT INTO snapshots(session_id,command_seq,before_flag,sim_time,snapshot_json)"
                        + " VALUES (?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, sessionId);
            ps.setLong(2, s.commandSeq());
            ps.setInt(3, s.before() ? 1 : 0);
            ps.setString(4, s.simTime().toString());
            ps.setString(5, mapper.writeValueAsString(s));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Override
    public List<CommandRow> commands(long sessionId) {
        try (PreparedStatement ps = cx.prepareStatement(
                "SELECT cr.*, s1.snapshot_json AS bj, s2.snapshot_json AS aj "
                        + "FROM command_records cr "
                        + "LEFT JOIN snapshots s1 ON cr.before_snapshot_id=s1.id "
                        + "LEFT JOIN snapshots s2 ON cr.after_snapshot_id=s2.id "
                        + "WHERE cr.session_id=? ORDER BY cr.seq ASC")) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<CommandRow> out = new ArrayList<>();
                while (rs.next()) {
                    OperatorCommand cmd = mapper.readValue(
                            rs.getString("command_json"), OperatorCommand.class);
                    PowerFlowSnapshot before = mapper.readValue(
                            rs.getString("bj"), PowerFlowSnapshot.class);
                    PowerFlowSnapshot after = mapper.readValue(
                            rs.getString("aj"), PowerFlowSnapshot.class);
                    out.add(new CommandRow(cmd,
                            Instant.parse(rs.getString("submitted_at")),
                            Instant.parse(rs.getString("sim_time")),
                            before, after));
                }
                return out;
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<PowerFlowSnapshot> snapshot(long sessionId, long commandSeq, boolean before) {
        try (PreparedStatement ps = cx.prepareStatement(
                "SELECT snapshot_json FROM snapshots WHERE session_id=? AND command_seq=? "
                        + "AND before_flag=? ORDER BY id DESC LIMIT 1")) {
            ps.setLong(1, sessionId);
            ps.setLong(2, commandSeq);
            ps.setInt(3, before ? 1 : 0);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapper.readValue(
                        rs.getString("snapshot_json"), PowerFlowSnapshot.class));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public long addCandidate(long sessionId, CandidateFlag flag) {
        try (PreparedStatement ps = cx.prepareStatement(
                "INSERT INTO candidates(session_id,command_seq,event_id,kind,rule,detail,"
                        + "status,raised_at) VALUES (?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, sessionId);
            if (flag.commandSeq() < 0) {
                ps.setNull(2, java.sql.Types.INTEGER);
            } else {
                ps.setLong(2, flag.commandSeq());
            }
            ps.setString(3, flag.eventId());
            ps.setString(4, flag.kind().name());
            ps.setString(5, flag.rule());
            ps.setString(6, flag.detail());
            ps.setString(7, flag.status().name());
            ps.setString(8, flag.raisedAt().toString());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void updateCandidateStatus(long sessionId, long candidateId, CandidateFlag.Status status) {
        try (PreparedStatement ps = cx.prepareStatement(
                "UPDATE candidates SET status=? WHERE id=? AND session_id=?")) {
            ps.setString(1, status.name());
            ps.setLong(2, candidateId);
            ps.setLong(3, sessionId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<CandidateFlag> candidates(long sessionId) {
        try (PreparedStatement ps = cx.prepareStatement(
                "SELECT * FROM candidates WHERE session_id=? ORDER BY id ASC")) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<CandidateFlag> out = new ArrayList<>();
                while (rs.next()) {
                    long cs = rs.getLong("command_seq");
                    if (rs.wasNull()) {
                        cs = -1;
                    }
                    out.add(new CandidateFlag(
                            rs.getLong("id"),
                            cs,
                            rs.getString("event_id"),
                            ViolationKind.valueOf(rs.getString("kind")),
                            rs.getString("rule"),
                            rs.getString("detail"),
                            CandidateFlag.Status.valueOf(rs.getString("status")),
                            Instant.parse(rs.getString("raised_at"))));
                }
                return out;
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void saveEvaluation(Evaluation ev) {
        try (PreparedStatement ps = cx.prepareStatement(
                "INSERT INTO evaluations(session_id,instructor,summary,strengths,improvements,"
                        + "score,signed,signed_at) VALUES (?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(session_id) DO UPDATE SET instructor=excluded.instructor,"
                        + "summary=excluded.summary,strengths=excluded.strengths,"
                        + "improvements=excluded.improvements,score=excluded.score,"
                        + "signed=excluded.signed,signed_at=excluded.signed_at")) {
            ps.setLong(1, ev.sessionId());
            ps.setString(2, ev.instructor());
            ps.setString(3, ev.summary());
            ps.setString(4, ev.strengths());
            ps.setString(5, ev.improvements());
            ps.setInt(6, ev.score());
            ps.setInt(7, ev.signed() ? 1 : 0);
            ps.setString(8, ev.signedAt() == null ? null : ev.signedAt().toString());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<Evaluation> evaluation(long sessionId) {
        try (PreparedStatement ps = cx.prepareStatement(
                "SELECT * FROM evaluations WHERE session_id=?")) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Evaluation(
                        rs.getLong("session_id"),
                        rs.getString("instructor"),
                        rs.getString("summary"),
                        rs.getString("strengths"),
                        rs.getString("improvements"),
                        rs.getInt("score"),
                        rs.getInt("signed") == 1,
                        rs.getString("signed_at") == null ? null
                                : Instant.parse(rs.getString("signed_at"))));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void incrementRestart(long sessionId) {
        try (PreparedStatement ps = cx.prepareStatement(
                "UPDATE sessions SET restart_count=restart_count+1 WHERE id=?")) {
            ps.setLong(1, sessionId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try {
            cx.close();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------

    private TrainingSession mapSession(ResultSet rs) throws Exception {
        return new TrainingSession(
                rs.getLong("id"),
                rs.getString("trainee"),
                rs.getString("instructor"),
                rs.getString("scenario_id"),
                rs.getInt("restart_count"),
                Instant.parse(rs.getString("started_at")),
                rs.getString("ended_at") == null ? null
                        : Instant.parse(rs.getString("ended_at")),
                rs.getString("frozen_scenario_json"));
    }

    private SimEvent mapEvent(ResultSet rs) throws Exception {
        Number cmdSeqNum = (Number) rs.getObject("command_seq");
        Long cmdSeq = cmdSeqNum == null ? null : cmdSeqNum.longValue();
        return new SimEvent(
                rs.getString("event_id"),
                rs.getLong("seq"),
                cmdSeq,
                EventType.valueOf(rs.getString("type")),
                rs.getString("element_id"),
                rs.getString("message"),
                rs.getString("payload_json"),
                Instant.parse(rs.getString("sim_time")),
                Instant.parse(rs.getString("produced_at")),
                rs.getString("prev_hash"),
                rs.getString("hash"));
    }

    private void rollback() {
        try {
            cx.rollback();
        } catch (Exception ignored) {
        }
    }

    private void setAutoCommitTrue() {
        try {
            cx.setAutoCommit(true);
        } catch (Exception ignored) {
        }
    }
}
