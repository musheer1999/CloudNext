package com.failureforge.verdict;

import java.util.Comparator;

import com.failureforge.run.TestCaseResult.Status;

/**
 * One test seen on both sides.
 *
 * @param parentStatus {@code null} if the test doesn't exist (or didn't run) on the parent
 * @param patchStatus  {@code null} if the test doesn't exist (or didn't run) on the patch
 * @param message      failure message on the patch side, if any
 */
public record Comparison(String className, String name, Status parentStatus, Status patchStatus, Verdict verdict,
		String message) {

	/** Counterexamples first, then other things worth a look, then the boring ones. */
	public static final Comparator<Comparison> MOST_IMPORTANT_FIRST = Comparator
		.comparingInt((Comparison c) -> c.verdict().ordinal())
		.thenComparing(Comparison::className)
		.thenComparing(Comparison::name);
}
