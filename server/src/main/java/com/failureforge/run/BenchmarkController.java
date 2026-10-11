package com.failureforge.run;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The benchmark (design doc "Demo and benchmark plan", D-036): planted-bug changes, one per specialist, plus a
 * correct change. Starting it queues one run per case; the summary gives the pitch numbers: bugs caught,
 * false positives, time and LLM tokens per run.
 */
@RestController
@RequestMapping("/api/benchmark")
public class BenchmarkController {

	public record Case(String name, String gerritUrl, String expected, String plantedBug, String ticket) {
	}

	public record CaseResult(String name, String expected, String plantedBug, Long runId, String status,
			List<String> foundBy, boolean caught, int falsePositives, Long seconds, int tokens) {
	}

	public record Summary(Long batch, List<CaseResult> cases, int bugs, int caught, int falsePositives,
			int finished, Long avgSeconds, int totalTokens) {
	}

	private final JdbcClient jdbc;
	private final List<Case> cases;

	public BenchmarkController(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		try (InputStream in = new ClassPathResource("benchmark.json").getInputStream()) {
			this.cases = json.readValue(in, new TypeReference<List<Case>>() { });
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@GetMapping("/cases")
	public List<Case> cases() {
		return cases;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Summary start() {
		long batch = System.currentTimeMillis();
		for (Case c : cases) {
			jdbc.sql("""
					INSERT INTO run (gerrit_url, ticket_text, benchmark_batch, benchmark_case, expected)
					VALUES (:url, :ticket, :batch, :name, :expected)
					""")
				.param("url", c.gerritUrl())
				.param("ticket", c.ticket())
				.param("batch", batch)
				.param("name", c.name())
				.param("expected", c.expected())
				.update();
		}
		return summary(batch);
	}

	/** The latest batch, or an empty summary if the benchmark never ran. */
	@GetMapping
	public Summary latest() {
		Long batch = jdbc.sql("SELECT max(benchmark_batch) FROM run").query(Long.class).optional().orElse(null);
		return batch == null ? new Summary(null, List.of(), 0, 0, 0, 0, null, 0) : summary(batch);
	}

	Summary summary(long batch) {
		List<CaseResult> results = new ArrayList<>();
		for (Case c : cases) {
			var row = jdbc.sql("""
					SELECT id, status, tokens_used, started_at, finished_at FROM run
					WHERE benchmark_batch = :batch AND benchmark_case = :name ORDER BY id LIMIT 1
					""")
				.param("batch", batch)
				.param("name", c.name())
				.query((rs, n) -> new Object[] { rs.getLong("id"), rs.getString("status"), rs.getInt("tokens_used"),
						rs.getTimestamp("started_at"), rs.getTimestamp("finished_at") })
				.optional();
			if (row.isEmpty()) {
				results.add(new CaseResult(c.name(), c.expected(), c.plantedBug(), null, "NOT_STARTED", List.of(), false,
						0, null, 0));
				continue;
			}
			Object[] r = row.get();
			long runId = (Long) r[0];
			String status = (String) r[1];
			// Which specialists produced a counterexample on this run.
			List<String> foundBy = jdbc.sql("""
					SELECT DISTINCT a.specialist FROM attack a WHERE a.run_id = :id AND a.verdict = 'COUNTEREXAMPLE'
					""")
				.param("id", runId)
				.query(String.class)
				.list();
			boolean finished = status.equals("SUCCEEDED") || status.equals("FAILED");
			boolean caught = c.expected().equals("NONE") ? finished && foundBy.isEmpty() : foundBy.contains(c.expected());
			int falsePositives = (int) foundBy.stream().filter(s -> !s.equals(c.expected())).count();
			Long seconds = r[3] != null && r[4] != null
					? Duration.between(((java.sql.Timestamp) r[3]).toInstant(), ((java.sql.Timestamp) r[4]).toInstant()).toSeconds()
					: null;
			results.add(new CaseResult(c.name(), c.expected(), c.plantedBug(), runId, status, foundBy, caught,
					falsePositives, seconds, (Integer) r[2]));
		}
		List<CaseResult> bugCases = results.stream().filter(r -> !r.expected().equals("NONE")).toList();
		List<Long> times = results.stream().map(CaseResult::seconds).filter(Objects::nonNull).toList();
		return new Summary(batch, results, bugCases.size(), (int) bugCases.stream().filter(CaseResult::caught).count(),
				results.stream().mapToInt(CaseResult::falsePositives).sum(),
				(int) results.stream().filter(r -> r.status().equals("SUCCEEDED") || r.status().equals("FAILED")).count(),
				times.isEmpty() ? null : (long) times.stream().mapToLong(Long::longValue).average().orElse(0),
				results.stream().mapToInt(CaseResult::tokens).sum());
	}
}
