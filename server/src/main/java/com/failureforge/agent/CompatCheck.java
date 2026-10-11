package com.failureforge.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import com.failureforge.run.TestCaseResult;
import com.failureforge.sandbox.DockerTestRunner;
import com.failureforge.sandbox.TestRunner;
import com.failureforge.verdict.Comparison;
import com.failureforge.verdict.Verdict;
import org.springframework.stereotype.Component;

/**
 * The Compat specialist (design doc section 3, D-032). No LLM: japicmp compares the public API compiled from
 * the parent and from the patch. A removed or changed public method breaks code that already calls it.
 */
@Component
public class CompatCheck {

	public static final Rule RULE = new Rule("C1",
			"Code that already uses the public API keeps working: no public class, method or field is removed or changed incompatibly.",
			"compat", "public API of the parent commit vs the patch", "japicmp", "public API", true, null);

	private final TestRunner sandbox;

	public CompatCheck(TestRunner sandbox) {
		this.sandbox = sandbox;
	}

	public AttackLoop.Result check(Path patchDir, Path parentDir, Consumer<String> out) {
		Path oldClasses = parentDir.resolve(DockerTestRunner.REPORTS_DIR_NAME).resolve(DockerTestRunner.CLASSES_DIR);
		Path newClasses = patchDir.resolve(DockerTestRunner.REPORTS_DIR_NAME).resolve(DockerTestRunner.CLASSES_DIR);
		if (!Files.isDirectory(oldClasses) || !Files.isDirectory(newClasses)) {
			out.accept("[compat] no compiled classes from both sides (multi-module or build failed): skipped");
			return null;
		}
		out.accept("[compat] japicmp: comparing the public API of the parent and the patch");
		List<String> breaks = sandbox.apiBreaks(oldClasses, newClasses, out);
		Verdict verdict = breaks.isEmpty() ? Verdict.NOTHING_FOUND : Verdict.COUNTEREXAMPLE;
		String summary = breaks.isEmpty() ? null : String.join("; ", breaks);
		out.accept("[compat] " + (breaks.isEmpty() ? "no incompatible API changes" : breaks.size()
				+ " incompatible API change(s): " + summary));
		Comparison row = new Comparison("japicmp", "publicApiStaysCompatible", TestCaseResult.Status.PASSED,
				breaks.isEmpty() ? TestCaseResult.Status.PASSED : TestCaseResult.Status.FAILED, verdict, summary);
		return new AttackLoop.Result(RULE, Specialist.COMPAT, null, null, 0, AttackLoop.Status.DONE,
				row.parentStatus(), row.patchStatus(), verdict, summary, List.of(row), 0);
	}
}
