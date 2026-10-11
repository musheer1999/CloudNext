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

	/** Runs all tests. */
	default TestRunResult runTests(Path repoDir, Consumer<String> log) {
		return runTests(repoDir, log, null);
	}

	/**
	 * @param testClass if not null, run only this test class (fully qualified), e.g. a generated attack
	 */
	TestRunResult runTests(Path repoDir, Consumer<String> log, String testClass);

	/**
	 * @param buildExitCode exit code of mvn/gradle (non-zero if any test failed, or the build broke)
	 * @param timedOut      the build was killed for taking too long
	 * @param tests         every test case found in the JUnit XML reports
	 */
	record TestRunResult(int buildExitCode, boolean timedOut, List<TestCaseResult> tests) {
	}
}
