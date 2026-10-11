package com.failureforge.verdict;

/** Outcome of comparing one test on the parent commit and on the patch (D-007, D-027). */
public enum Verdict {
	/** Passed before the change, fails after it: the change broke what this test checks. */
	COUNTEREXAMPLE,
	/** Fails both before and after: a bug older than this change, or a wrong test. Low confidence. */
	PRE_EXISTING,
	/** Gave different results on re-runs (D-010): not trusted either way, never reported as a bug. */
	FLAKY,
	/** Fails before, passes after: the change fixed it. */
	FIXED,
	/** Passes both before and after. */
	NOTHING_FOUND,
	/** Only exists in the patch (added by the change); its patch result is all we know. */
	NEW_TEST,
	/** Exists before but not after: the change deleted or renamed it. */
	REMOVED,
	/** Skipped, or one side didn't build: no comparison possible. */
	COULDNT_CHECK
}
