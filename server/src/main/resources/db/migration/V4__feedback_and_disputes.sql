-- Phase 4: developer feedback (Accept / Dispute / False Positive), dispute re-runs, Gerrit review posting.

-- Constraints the developer gave in disputes ("amount is validated upstream, never negative"). They are fed to
-- rule extraction and the test writer of the re-run (and of later disputes on it).
ALTER TABLE run ADD COLUMN constraints TEXT;
-- The run this one re-checks after a dispute.
ALTER TABLE run ADD COLUMN disputed_run_id BIGINT REFERENCES run (id) ON DELETE SET NULL;
-- When the findings were posted to Gerrit as a review comment.
ALTER TABLE run ADD COLUMN gerrit_posted_at TIMESTAMPTZ;

-- One developer reaction to one finding (D-012 "feedback" table).
CREATE TABLE feedback (
    id          BIGSERIAL PRIMARY KEY,
    run_id      BIGINT      NOT NULL REFERENCES run (id) ON DELETE CASCADE,
    rule_key    TEXT        NOT NULL,
    rule_text   TEXT        NOT NULL,
    repo        TEXT,                      -- Gerrit project, for per-repo false-positive memory
    type        TEXT        NOT NULL,      -- ACCEPT, DISPUTE, FALSE_POSITIVE
    text        TEXT,                      -- the developer's constraint or note
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX feedback_run_idx ON feedback (run_id);
CREATE INDEX feedback_repo_idx ON feedback (repo, type);
