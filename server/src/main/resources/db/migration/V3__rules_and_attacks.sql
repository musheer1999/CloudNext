-- Phase 2 steps 2c-2e: the ticket text, the rules the AI derives, and the attacks (generated tests) on them.

-- What the change is supposed to do, pasted in the portal (until JIRA is wired up on the office laptop).
ALTER TABLE run ADD COLUMN ticket_text TEXT;

-- A rule the change must guarantee (D-008: each must quote its source, or it is dropped).
CREATE TABLE rule (
    id             BIGSERIAL PRIMARY KEY,
    run_id         BIGINT NOT NULL REFERENCES run (id) ON DELETE CASCADE,
    rule_key       TEXT   NOT NULL,           -- R1, R2, ... as the AI numbered them
    text           TEXT   NOT NULL,
    type           TEXT   NOT NULL,           -- boundary, state, concurrency, compat
    source_quote   TEXT,
    source         TEXT,                       -- ticket, diff, ...
    code_location  TEXT,
    status         TEXT   NOT NULL,           -- KEPT, or DROPPED when its quote can't be found
    drop_reason    TEXT
);
CREATE INDEX rule_run_idx ON rule (run_id);

-- One attempt to break one rule with a generated test.
CREATE TABLE attack (
    id                BIGSERIAL PRIMARY KEY,
    run_id            BIGINT NOT NULL REFERENCES run (id) ON DELETE CASCADE,
    rule_id           BIGINT NOT NULL REFERENCES rule (id) ON DELETE CASCADE,
    specialist        TEXT   NOT NULL,        -- BOUNDARY (State, Concurrency, Compat in later phases)
    test_class        TEXT,                    -- fully qualified name of the generated test
    test_code         TEXT,
    compile_attempts  INTEGER NOT NULL DEFAULT 0,
    parent_status     TEXT,                    -- PASSED/FAILED/ERROR, NULL = didn't run there
    patch_status      TEXT,
    verdict           TEXT,                    -- same values as test_comparison.verdict
    minimal_input     TEXT,                    -- shrunk counterexample from jqwik, if any
    message           TEXT,
    status            TEXT   NOT NULL         -- DONE, COULDNT_COMPILE, ERROR
);
CREATE INDEX attack_run_idx ON attack (run_id);
