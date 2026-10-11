-- Phase 5: benchmark batches (design doc "Demo and benchmark plan", D-036).
-- A batch = one run per benchmark case, all started together.
ALTER TABLE run ADD COLUMN benchmark_batch BIGINT;
ALTER TABLE run ADD COLUMN benchmark_case  TEXT;
-- Which specialist should catch the planted bug (BOUNDARY, STATE, CONCURRENCY, COMPAT), or NONE for a clean change.
ALTER TABLE run ADD COLUMN expected        TEXT;
CREATE INDEX run_benchmark_idx ON run (benchmark_batch) WHERE benchmark_batch IS NOT NULL;
