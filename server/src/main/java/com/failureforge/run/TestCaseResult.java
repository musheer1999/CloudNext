package com.failureforge.run;

/** One test case from a JUnit XML report. */
public record TestCaseResult(String className, String name, Status status, long durationMs, String message) {

	public enum Status {
		PASSED, FAILED, ERROR, SKIPPED
	}
}
