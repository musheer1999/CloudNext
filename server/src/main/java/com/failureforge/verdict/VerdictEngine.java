package com.failureforge.verdict;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.failureforge.run.TestCaseResult;
import com.failureforge.run.TestCaseResult.Status;

/**
 * Decides the verdict for every test by comparing parent and patch results (D-005, D-007, D-027).
 * Plain code, no AI: the same inputs always give the same verdicts.
 */
public final class VerdictEngine {

	private VerdictEngine() {
	}

	/**
	 * @param parent      test results on the parent commit
	 * @param parentBuilt false if the parent build broke before tests ran (its results are then unknown)
	 * @param patch       test results on the patch
	 * @param patchBuilt  false if the patch build broke before tests ran
	 */
	public static List<Comparison> compare(List<TestCaseResult> parent, boolean parentBuilt,
			List<TestCaseResult> patch, boolean patchBuilt) {
		Map<String, TestCaseResult> before = byId(parent);
		Map<String, TestCaseResult> after = byId(patch);

		List<Comparison> result = new ArrayList<>();
		for (TestCaseResult p : after.values()) {
			TestCaseResult b = before.get(id(p));
			Status beforeStatus = b == null ? null : b.status();
			result.add(new Comparison(p.className(), p.name(), beforeStatus, p.status(),
					verdict(beforeStatus, parentBuilt, p.status()), p.message()));
		}
		for (TestCaseResult b : before.values()) {
			if (!after.containsKey(id(b))) {
				// Gone from the patch: deleted by the change, or the patch build broke.
				Verdict v = patchBuilt ? Verdict.REMOVED : Verdict.COULDNT_CHECK;
				result.add(new Comparison(b.className(), b.name(), b.status(), null, v, null));
			}
		}
		result.sort(Comparison.MOST_IMPORTANT_FIRST);
		return result;
	}

	static Verdict verdict(Status before, boolean parentBuilt, Status after) {
		if (after == Status.SKIPPED || before == Status.SKIPPED) {
			return Verdict.COULDNT_CHECK;
		}
		if (before == null) {
			// No parent result: either the test is new, or the parent didn't build.
			return parentBuilt ? Verdict.NEW_TEST : Verdict.COULDNT_CHECK;
		}
		boolean passedBefore = before == Status.PASSED;
		boolean passedAfter = after == Status.PASSED;
		if (passedBefore && !passedAfter) {
			return Verdict.COUNTEREXAMPLE;
		}
		if (!passedBefore && !passedAfter) {
			return Verdict.PRE_EXISTING;
		}
		if (!passedBefore) {
			return Verdict.FIXED;
		}
		return Verdict.NOTHING_FOUND;
	}

	private static Map<String, TestCaseResult> byId(List<TestCaseResult> tests) {
		// Keep the first if a report lists a test twice (e.g. parameterized names); order stays stable.
		Map<String, TestCaseResult> map = new LinkedHashMap<>();
		tests.forEach(t -> map.putIfAbsent(id(t), t));
		return map;
	}

	private static String id(TestCaseResult t) {
		return t.className() + "#" + t.name();
	}
}
