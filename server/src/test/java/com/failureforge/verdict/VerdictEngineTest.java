package com.failureforge.verdict;

import static com.failureforge.run.TestCaseResult.Status.ERROR;
import static com.failureforge.run.TestCaseResult.Status.FAILED;
import static com.failureforge.run.TestCaseResult.Status.PASSED;
import static com.failureforge.run.TestCaseResult.Status.SKIPPED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.failureforge.run.TestCaseResult;
import com.failureforge.run.TestCaseResult.Status;
import org.junit.jupiter.api.Test;

class VerdictEngineTest {

	private static TestCaseResult t(String name, Status status) {
		return new TestCaseResult("CartTest", name, status, 1, status == PASSED ? null : "boom " + name);
	}

	@Test
	void theVerdictTable() {
		assertThat(VerdictEngine.verdict(PASSED, true, FAILED)).isEqualTo(Verdict.COUNTEREXAMPLE);
		assertThat(VerdictEngine.verdict(PASSED, true, ERROR)).isEqualTo(Verdict.COUNTEREXAMPLE);
		assertThat(VerdictEngine.verdict(FAILED, true, FAILED)).isEqualTo(Verdict.PRE_EXISTING);
		assertThat(VerdictEngine.verdict(ERROR, true, FAILED)).isEqualTo(Verdict.PRE_EXISTING);
		assertThat(VerdictEngine.verdict(PASSED, true, PASSED)).isEqualTo(Verdict.NOTHING_FOUND);
		assertThat(VerdictEngine.verdict(FAILED, true, PASSED)).isEqualTo(Verdict.FIXED);
		assertThat(VerdictEngine.verdict(null, true, FAILED)).isEqualTo(Verdict.NEW_TEST);
		assertThat(VerdictEngine.verdict(null, false, FAILED)).isEqualTo(Verdict.COULDNT_CHECK);
		assertThat(VerdictEngine.verdict(PASSED, true, SKIPPED)).isEqualTo(Verdict.COULDNT_CHECK);
		assertThat(VerdictEngine.verdict(SKIPPED, true, PASSED)).isEqualTo(Verdict.COULDNT_CHECK);
		assertThat(VerdictEngine.verdict(PASSED, true, Status.FLAKY)).isEqualTo(Verdict.FLAKY);
		assertThat(VerdictEngine.verdict(Status.FLAKY, true, FAILED)).isEqualTo(Verdict.FLAKY);
	}

	@Test
	void comparesBothSidesAndPutsCounterexamplesFirst() {
		List<TestCaseResult> parent = List.of(t("a", PASSED), t("b", PASSED), t("gone", PASSED), t("c", FAILED));
		List<TestCaseResult> patch = List.of(t("a", PASSED), t("b", FAILED), t("c", PASSED), t("added", PASSED));

		List<Comparison> result = VerdictEngine.compare(parent, true, patch, true);

		assertThat(result).extracting(Comparison::name, Comparison::verdict).containsExactly(
				org.assertj.core.groups.Tuple.tuple("b", Verdict.COUNTEREXAMPLE),
				org.assertj.core.groups.Tuple.tuple("c", Verdict.FIXED),
				org.assertj.core.groups.Tuple.tuple("a", Verdict.NOTHING_FOUND),
				org.assertj.core.groups.Tuple.tuple("added", Verdict.NEW_TEST),
				org.assertj.core.groups.Tuple.tuple("gone", Verdict.REMOVED));
		assertThat(result.getFirst().message()).isEqualTo("boom b");
	}

	@Test
	void brokenPatchBuildMeansCouldntCheckNotRemoved() {
		List<Comparison> result = VerdictEngine.compare(List.of(t("a", PASSED)), true, List.of(), false);
		assertThat(result).extracting(Comparison::verdict).containsExactly(Verdict.COULDNT_CHECK);
	}

	@Test
	void brokenParentBuildMeansCouldntCheckNotNew() {
		List<Comparison> result = VerdictEngine.compare(List.of(), false, List.of(t("a", FAILED)), true);
		assertThat(result).extracting(Comparison::verdict).containsExactly(Verdict.COULDNT_CHECK);
	}
}
