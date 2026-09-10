package org.bstraining.persistence;

/**
 * SQLite 建表语句。
 *
 * <p>不可编辑原则：events 与 command_records 为只追加表（触发器禁止 UPDATE/DELETE）；
 * 口令前/后潮流快照独立存放，口令行在建入时即写定快照引用（before 立即可得，
 * after 在 COMMAND_COMPLETE 前由引擎先创建快照行，再一次性建入口令行），
 * 因此口令行本身永远不会被修改。</p>
 */
public final class Schema {

    private Schema() {
    }

    public static final String[] DDL = {
            """
            CREATE TABLE IF NOT EXISTS sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                trainee TEXT NOT NULL,
                instructor TEXT,
                scenario_id TEXT NOT NULL,
                restart_count INTEGER NOT NULL DEFAULT 0,
                started_at TEXT NOT NULL,
                ended_at TEXT,
                frozen_scenario_json TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS snapshots (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL REFERENCES sessions(id),
                command_seq INTEGER NOT NULL,
                before_flag INTEGER NOT NULL,
                sim_time TEXT NOT NULL,
                snapshot_json TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS command_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL REFERENCES sessions(id),
                seq INTEGER NOT NULL,
                command_json TEXT NOT NULL,
                submitted_at TEXT NOT NULL,
                sim_time TEXT NOT NULL,
                before_snapshot_id INTEGER REFERENCES snapshots(id),
                after_snapshot_id INTEGER REFERENCES snapshots(id),
                UNIQUE(session_id, seq)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL REFERENCES sessions(id),
                event_id TEXT NOT NULL UNIQUE,
                seq INTEGER NOT NULL,
                command_seq INTEGER,
                type TEXT NOT NULL,
                element_id TEXT,
                message TEXT,
                payload_json TEXT,
                sim_time TEXT NOT NULL,
                produced_at TEXT NOT NULL,
                arrival_ord INTEGER NOT NULL,
                prev_hash TEXT,
                hash TEXT NOT NULL,
                UNIQUE(session_id, seq)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS candidates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL REFERENCES sessions(id),
                command_seq INTEGER,
                event_id TEXT,
                kind TEXT NOT NULL,
                rule TEXT NOT NULL,
                detail TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'OPEN',
                raised_at TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS evaluations (
                session_id INTEGER PRIMARY KEY REFERENCES sessions(id),
                instructor TEXT,
                summary TEXT,
                strengths TEXT,
                improvements TEXT,
                score INTEGER,
                signed INTEGER NOT NULL DEFAULT 0,
                signed_at TEXT
            )
            """,
            // ---- 只追加保护 ----
            """
            CREATE TRIGGER IF NOT EXISTS trg_events_no_update
            BEFORE UPDATE ON events
            BEGIN
                SELECT RAISE(ABORT, 'events 为只追加表：原事件不可编辑');
            END
            """,
            """
            CREATE TRIGGER IF NOT EXISTS trg_events_no_delete
            BEFORE DELETE ON events
            BEGIN
                SELECT RAISE(ABORT, 'events 为只追加表：原事件不可删除');
            END
            """,
            """
            CREATE TRIGGER IF NOT EXISTS trg_commands_no_update
            BEFORE UPDATE ON command_records
            BEGIN
                SELECT RAISE(ABORT, 'command_records 为只追加表：原口令不可编辑');
            END
            """,
            """
            CREATE TRIGGER IF NOT EXISTS trg_commands_no_delete
            BEFORE DELETE ON command_records
            BEGIN
                SELECT RAISE(ABORT, 'command_records 为只追加表：原口令不可删除');
            END
            """
    };
}
