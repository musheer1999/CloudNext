package com.failureforge.sandbox;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import com.failureforge.run.TestCaseResult;

/**
 * Runs a repository's existing tests somewhere and reports the results.
 * Implementations: {@link LocalProcessTestRunner} (dev only) now; a Docker runner next (D-009, D-017).
 */
public interface TestRunner {

	TestRunResult runTests(Path repoDir, Consumer<String> log);

	/**
	 * @param buildExitCode exit code of mvn/gradle (non-zero if any test failed, or the build broke)
	 * @param timedOut      the build was killed for taking too long
	 * @param tests         every test case found in the JUnit XML reports
	 */
	record TestRunResult(int buildExitCode, boolean timedOut, List<TestCaseResult> tests) {
	}
}
