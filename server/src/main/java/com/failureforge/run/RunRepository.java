package com.failureforge.run;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.failureforge.verdict.Comparison;
import com.failureforge.verdict.Verdict;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * All SQL for runs, logs and test results. Plain SQL via {@link JdbcClient} (D-019): what you read is what runs.
 */
@Repository
public class RunRepository {

	private final JdbcClient jdbc;

	public RunRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Puts a new run in the queue and returns its id. */
	public long enqueue(String gerritUrl, String jiraUrl, List<String> wikiUrls) {
		return jdbc.sql("""
				INSERT INTO run (gerrit_url, jira_url, wiki_urls)
				VALUES (:gerritUrl, :jiraUrl, :wikiUrls)
				RETURNING id
				""")
			.param("gerritUrl", gerritUrl)
			.param("jiraUrl", jiraUrl)
			.param("wikiUrls", wikiUrls.toArray(String[]::new))
			.query(Long.class)
			.single();
	}

	/**
	 * Atomically takes the oldest queued run and marks it RUNNING.
	 * {@code FOR UPDATE SKIP LOCKED} means two workers polling at the same time never get the same run.
	 */
	public Optional<Run> claimNext() {
		return jdbc.sql("""
				UPDATE run SET status = 'RUNNING', step = 'RESOLVING', started_at = now()
				WHERE id = (
				    SELECT id FROM run WHERE status = 'QUEUED'
				    ORDER BY created_at, id
				    FOR UPDATE SKIP LOCKED
				    LIMIT 1)
				RETURNING *
				""")
			.query(RUN_MAPPER)
			.optional();
	}

	/** Runs left RUNNING by a worker that crashed or was stopped can never finish; mark them failed. */
	public int failAbandonedRuns() {
		return jdbc.sql("""
				UPDATE run SET status = 'FAILED', step = NULL, finished_at = now(),
				       error = 'Worker stopped while this run was in progress'
				WHERE status = 'RUNNING'
				""")
			.update();
	}

	public void updateStep(long runId, String step) {
		jdbc.sql("UPDATE run SET step = :step WHERE id = :id").param("step", step).param("id", runId).update();
	}

	public void saveChangeInfo(long runId, String project, int change, int patchset, String patchSha,
			String parentSha, String subject) {
		jdbc.sql("""
				UPDATE run SET gerrit_project = :project, gerrit_change = :change, patchset = :patchset,
				       patch_sha = :patchSha, parent_sha = :parentSha, subject = :subject
				WHERE id = :id
				""")
			.param("project", project)
			.param("change", change)
			.param("patchset", patchset)
			.param("patchSha", patchSha)
			.param("parentSha", parentSha)
			.param("subject", subject)
			.param("id", runId)
			.update();
	}

	/**
	 * Stores the patch's test results and the parent-vs-patch verdicts, and marks the run SUCCEEDED,
	 * in one transaction.
	 *
	 * @param parentBuildExit {@code null} if the parent wasn't tested (e.g. the change has no parent)
	 */
	@Transactional
	public void complete(long runId, List<TestCaseResult> tests, List<Comparison> comparisons,
			Integer parentBuildExit, int patchBuildExit) {
		for (Comparison c : comparisons) {
			jdbc.sql("""
					INSERT INTO test_comparison (run_id, class_name, name, parent_status, patch_status, verdict, message)
					VALUES (:runId, :className, :name, :parentStatus, :patchStatus, :verdict, :message)
					""")
				.param("runId", runId)
				.param("className", c.className())
				.param("name", c.name())
				.param("parentStatus", c.parentStatus() == null ? null : c.parentStatus().name())
				.param("patchStatus", c.patchStatus() == null ? null : c.patchStatus().name())
				.param("verdict", c.verdict().name())
				.param("message", c.message())
				.update();
		}
		jdbc.sql("""
				UPDATE run SET counterexamples = :counterexamples, fixed = :fixed, pre_existing = :preExisting,
				       parent_build_exit = :parentBuildExit, patch_build_exit = :patchBuildExit
				WHERE id = :id
				""")
			.param("counterexamples", count(comparisons, Verdict.COUNTEREXAMPLE))
			.param("fixed", count(comparisons, Verdict.FIXED))
			.param("preExisting", count(comparisons, Verdict.PRE_EXISTING))
			.param("parentBuildExit", parentBuildExit)
			.param("patchBuildExit", patchBuildExit)
			.param("id", runId)
			.update();
		for (TestCaseResult t : tests) {
			jdbc.sql("""
					INSERT INTO test_case (run_id, class_name, name, status, duration_ms, message)
					VALUES (:runId, :className, :name, :status, :durationMs, :message)
					""")
				.param("runId", runId)
				.param("className", t.className())
				.param("name", t.name())
				.param("status", t.status().name())
				.param("durationMs", t.durationMs())
				.param("message", t.message())
				.update();
		}
		jdbc.sql("""
				UPDATE run SET status = 'SUCCEEDED', step = NULL, finished_at = now(),
				       tests_total = :total, tests_failed = :failed, tests_errors = :errors, tests_skipped = :skipped
				WHERE id = :id
				""")
			.param("total", tests.size())
			.param("failed", count(tests, TestCaseResult.Status.FAILED))
			.param("errors", count(tests, TestCaseResult.Status.ERROR))
			.param("skipped", count(tests, TestCaseResult.Status.SKIPPED))
			.param("id", runId)
			.update();
	}

	public void fail(long runId, String error) {
		jdbc.sql("UPDATE run SET status = 'FAILED', step = NULL, finished_at = now(), error = :error WHERE id = :id")
			.param("error", error)
			.param("id", runId)
			.update();
	}

	public Optional<Run> find(long runId) {
		return jdbc.sql("SELECT * FROM run WHERE id = :id").param("id", runId).query(RUN_MAPPER).optional();
	}

	public List<Run> findRecent(int limit) {
		return jdbc.sql("SELECT * FROM run ORDER BY id DESC LIMIT :limit").param("limit", limit).query(RUN_MAPPER).list();
	}

	/** Appends one log line and returns its id (used as the SSE event id). */
	public long appendLog(long runId, String line) {
		return jdbc.sql("INSERT INTO run_log (run_id, line) VALUES (:runId, :line) RETURNING id")
			.param("runId", runId)
			.param("line", line)
			.query(Long.class)
			.single();
	}

	public List<LogLine> findLogs(long runId) {
		return jdbc.sql("SELECT id, line FROM run_log WHERE run_id = :runId ORDER BY id")
			.param("runId", runId)
			.query((rs, n) -> new LogLine(rs.getLong("id"), rs.getString("line")))
			.list();
	}

	public List<TestCaseResult> findTests(long runId) {
		return jdbc.sql("""
				SELECT * FROM test_case WHERE run_id = :runId
				ORDER BY CASE status WHEN 'ERROR' THEN 0 WHEN 'FAILED' THEN 1 WHEN 'SKIPPED' THEN 2 ELSE 3 END,
				         class_name, name
				""")
			.param("runId", runId)
			.query((rs, n) -> new TestCaseResult(rs.getString("class_name"), rs.getString("name"),
					TestCaseResult.Status.valueOf(rs.getString("status")), rs.getLong("duration_ms"),
					rs.getString("message")))
			.list();
	}

	public List<Comparison> findComparisons(long runId) {
		return jdbc.sql("SELECT * FROM test_comparison WHERE run_id = :runId")
			.param("runId", runId)
			.query((rs, n) -> new Comparison(rs.getString("class_name"), rs.getString("name"),
					status(rs.getString("parent_status")), status(rs.getString("patch_status")),
					Verdict.valueOf(rs.getString("verdict")), rs.getString("message")))
			.list()
			.stream()
			.sorted(Comparison.MOST_IMPORTANT_FIRST)
			.toList();
	}

	public record LogLine(long id, String line) {
	}

	private static TestCaseResult.Status status(String s) {
		return s == null ? null : TestCaseResult.Status.valueOf(s);
	}

	private static int count(List<TestCaseResult> tests, TestCaseResult.Status status) {
		return (int) tests.stream().filter(t -> t.status() == status).count();
	}

	private static int count(List<Comparison> comparisons, Verdict verdict) {
		return (int) comparisons.stream().filter(c -> c.verdict() == verdict).count();
	}

	private static final RowMapper<Run> RUN_MAPPER = (rs, n) -> new Run(
			rs.getLong("id"),
			rs.getString("gerrit_url"),
			rs.getString("jira_url"),
			stringList(rs.getArray("wiki_urls")),
			rs.getString("gerrit_project"),
			nullableInt(rs, "gerrit_change"),
			nullableInt(rs, "patchset"),
			rs.getString("patch_sha"),
			rs.getString("parent_sha"),
			rs.getString("subject"),
			RunStatus.valueOf(rs.getString("status")),
			rs.getString("step"),
			rs.getString("error"),
			nullableInt(rs, "tests_total"),
			nullableInt(rs, "tests_failed"),
			nullableInt(rs, "tests_errors"),
			nullableInt(rs, "tests_skipped"),
			nullableInt(rs, "counterexamples"),
			nullableInt(rs, "fixed"),
			nullableInt(rs, "pre_existing"),
			nullableInt(rs, "parent_build_exit"),
			nullableInt(rs, "patch_build_exit"),
			instant(rs.getTimestamp("created_at")),
			instant(rs.getTimestamp("started_at")),
			instant(rs.getTimestamp("finished_at")));

	private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	private static Instant instant(Timestamp ts) {
		return ts == null ? null : ts.toInstant();
	}

	private static List<String> stringList(Array array) throws SQLException {
		return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
	}
}
