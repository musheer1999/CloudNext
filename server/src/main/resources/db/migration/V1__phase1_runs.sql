-- Phase 1 schema. Flyway runs each V<n>__*.sql file once, in order, and records it.
-- Never edit a migration that has already run; add V2__..., V3__... instead.

-- One analysis of one Gerrit change. Also acts as the job queue (status = 'QUEUED').
CREATE TABLE run (
    id              BIGSERIAL PRIMARY KEY,
    gerrit_url      TEXT        NOT NULL,
    jira_url        TEXT,
    wiki_urls       TEXT[]      NOT NULL DEFAULT '{}',

    -- Filled in by the worker after it asks Gerrit about the change.
    gerrit_project  TEXT,
    gerrit_change   INTEGER,
    patchset        INTEGER,
    patch_sha       TEXT,
    parent_sha      TEXT,
    subject         TEXT,

    status          TEXT        NOT NULL DEFAULT 'QUEUED',  -- QUEUED, RUNNING, SUCCEEDED, FAILED
    step            TEXT,                                   -- RESOLVING, CLONING, TESTING (while RUNNING)
    error           TEXT,

    tests_total     INTEGER,
    tests_failed    INTEGER,
    tests_errors    INTEGER,
    tests_skipped   INTEGER,

    tokens_used     INTEGER     NOT NULL DEFAULT 0,  -- used from Phase 2 (LLM)
    cost            NUMERIC(10, 4) NOT NULL DEFAULT 0,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ
);

-- The worker looks for the oldest queued run; this index keeps that fast.
CREATE INDEX run_queue_idx ON run (created_at) WHERE status = 'QUEUED';

-- Every line of output from a run, so the portal can replay the log after a refresh.
CREATE TABLE run_log (
    id          BIGSERIAL PRIMARY KEY,
    run_id      BIGINT      NOT NULL REFERENCES run (id) ON DELETE CASCADE,
    line        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX run_log_run_idx ON run_log (run_id, id);

-- Result of each existing test case found in the JUnit XML reports.
CREATE TABLE test_case (
    id           BIGSERIAL PRIMARY KEY,
    run_id       BIGINT  NOT NULL REFERENCES run (id) ON DELETE CASCADE,
    class_name   TEXT    NOT NULL,
    name         TEXT    NOT NULL,
    status       TEXT    NOT NULL,  -- PASSED, FAILED, ERROR, SKIPPED
    duration_ms  BIGINT  NOT NULL,
    message      TEXT
);
CREATE INDEX test_case_run_idx ON test_case (run_id);
