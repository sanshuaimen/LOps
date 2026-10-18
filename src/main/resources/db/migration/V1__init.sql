-- V1: the four P1 core tables (P1 plan, task 3).
-- diagnosis_task.status CHECK mirrors com.cifa.task.TaskStateMachine — the class is
-- the authority; keep them in sync when the state machine changes.

CREATE TABLE diagnosis_task (
    id              TEXT PRIMARY KEY,
    status          TEXT NOT NULL CHECK (status IN
                        ('PENDING', 'RUNNING', 'SUCCEEDED', 'UNKNOWN', 'FAILED', 'CANCELLED')),
    mode            TEXT NOT NULL CHECK (mode IN ('agent', 'baseline')),
    repo_alias      TEXT NOT NULL,
    category        TEXT,
    final_diagnosis TEXT,
    stop_reason     TEXT NOT NULL DEFAULT '',
    error_message   TEXT NOT NULL DEFAULT '',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ
);

CREATE INDEX idx_diagnosis_task_status ON diagnosis_task (status);

CREATE TABLE investigation_step (
    id                BIGSERIAL PRIMARY KEY,
    task_id           TEXT NOT NULL REFERENCES diagnosis_task (id),
    sequence          INT  NOT NULL,
    round             INT  NOT NULL DEFAULT 0,
    event_type        TEXT NOT NULL,
    model             TEXT NOT NULL DEFAULT '',
    prompt_version    TEXT NOT NULL DEFAULT '',
    hypothesis        TEXT NOT NULL DEFAULT '',
    tool_name         TEXT NOT NULL DEFAULT '',
    arguments_summary TEXT NOT NULL DEFAULT '',
    evidence_ids      JSONB NOT NULL DEFAULT '[]'::jsonb,
    token_count       INT  NOT NULL DEFAULT 0,
    elapsed_millis    BIGINT NOT NULL DEFAULT 0,
    status            TEXT NOT NULL DEFAULT '',
    stop_reason       TEXT NOT NULL DEFAULT '',
    error             TEXT NOT NULL DEFAULT '',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (task_id, sequence)
);

CREATE INDEX idx_investigation_step_task ON investigation_step (task_id);

CREATE TABLE evidence (
    id           BIGSERIAL PRIMARY KEY,
    task_id      TEXT NOT NULL REFERENCES diagnosis_task (id),
    evidence_id  TEXT NOT NULL,
    type         TEXT NOT NULL,
    locator      TEXT NOT NULL DEFAULT '',
    content      TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    tool_name    TEXT NOT NULL DEFAULT '',
    start_line   INT  NOT NULL DEFAULT -1,
    end_line     INT  NOT NULL DEFAULT -1,
    commit_id    TEXT NOT NULL DEFAULT '',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (task_id, evidence_id)
);

CREATE INDEX idx_evidence_task ON evidence (task_id);

-- Manual promotion only (plan §八): no automated path writes here; audited columns
-- are part of V1 so promotion never needs a schema change.
CREATE TABLE failure_case (
    id                  BIGSERIAL PRIMARY KEY,
    source_task_id      TEXT NOT NULL,
    failure_fingerprint TEXT NOT NULL,
    category            TEXT NOT NULL,
    confirmed_diagnosis TEXT NOT NULL,
    confirmed_action    TEXT NOT NULL DEFAULT '',
    confirmed_by        TEXT NOT NULL,
    confirmed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewer_feedback   TEXT NOT NULL DEFAULT '',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
