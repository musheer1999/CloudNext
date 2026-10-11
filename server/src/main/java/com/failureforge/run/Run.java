package com.failureforge.run;

import java.time.Instant;
import java.util.List;

/** One row of the {@code run} table. */
public record Run(
		long id,
		String gerritUrl,
		String jiraUrl,
		List<String> wikiUrls,
		String gerritProject,
		Integer gerritChange,
		Integer patchset,
		String patchSha,
		String parentSha,
		String subject,
		RunStatus status,
		String step,
		String error,
		Integer testsTotal,
		Integer testsFailed,
		Integer testsErrors,
		Integer testsSkipped,
		Instant createdAt,
		Instant startedAt,
		Instant finishedAt) {
}
