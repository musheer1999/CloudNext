-- Phase 2 step 2b: every run tests the parent commit and the patch; plain code decides a verdict per test.

-- One row per test seen on either side.
CREATE TABLE test_comparison (
    id             BIGSERIAL PRIMARY KEY,
    run_id         BIGINT NOT NULL REFERENCES run (id) ON DELETE CASCADE,
    class_name     TEXT   NOT NULL,
    name           TEXT   NOT NULL,
    parent_status  TEXT,            -- PASSED/FAILED/ERROR/SKIPPED, NULL = not present on parent
    patch_status   TEXT,            -- same, NULL = not present on patch
    verdict        TEXT   NOT NULL, -- COUNTEREXAMPLE, PRE_EXISTING, FIXED, NOTHING_FOUND, NEW_TEST, REMOVED, COULDNT_CHECK
    message        TEXT
);
CREATE INDEX test_comparison_run_idx ON test_comparison (run_id);

-- Summary counts so the run list can show them without reading every comparison.
ALTER TABLE run ADD COLUMN counterexamples INTEGER;
ALTER TABLE run ADD COLUMN fixed           INTEGER;
ALTER TABLE run ADD COLUMN pre_existing    INTEGER;
-- Exit code of the build on each side (non-zero with no tests = that side didn't build).
ALTER TABLE run ADD COLUMN parent_build_exit INTEGER;
ALTER TABLE run ADD COLUMN patch_build_exit  INTEGER;
