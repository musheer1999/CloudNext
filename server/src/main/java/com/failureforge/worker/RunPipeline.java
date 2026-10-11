package com.failureforge.worker;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.failureforge.config.FailureForgeProperties;
import com.failureforge.gerrit.GerritChange;
import com.failureforge.gerrit.GerritChangeUrl;
import com.failureforge.gerrit.GerritClient;
import com.failureforge.git.GitCheckout;
import com.failureforge.run.Run;
import com.failureforge.run.RunEvents;
import com.failureforge.run.RunRepository;
import com.failureforge.run.TestCaseResult;
import com.failureforge.sandbox.TestRunner;
import com.failureforge.verdict.Comparison;
import com.failureforge.verdict.Verdict;
import com.failureforge.verdict.VerdictEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Phase 1 pipeline for one run: resolve the Gerrit change, check it out, run its existing tests.
 * Phase 2 adds rule extraction, specialists and the verdict engine between "checkout" and "report".
 */
@Component
public class RunPipeline {

	private static final Logger log = LoggerFactory.getLogger(RunPipeline.class);

	private final RunRepository runs;
	private final RunEvents events;
	private final GerritClient gerrit;
	private final GitCheckout git;
	private final TestRunner testRunner;
	private final FailureForgeProperties props;

	public RunPipeline(RunRepository runs, RunEvents events, GerritClient gerrit, GitCheckout git,
			TestRunner testRunner, FailureForgeProperties props) {
		this.runs = runs;
		this.events = events;
		this.gerrit = gerrit;
		this.git = git;
		this.testRunner = testRunner;
		this.props = props;
	}

	public void execute(Run run) {
		long id = run.id();
		Consumer<String> out = line -> events.log(id, line);
		try {
			out.accept("Run #" + id + " started for " + run.gerritUrl());

			// 1. Ask Gerrit which commit this change/patchset is.
			step(id, "RESOLVING");
			GerritChange change = gerrit.fetchChange(GerritChangeUrl.parse(run.gerritUrl()));
			runs.saveChangeInfo(id, change.project(), change.change(), change.patchset(), change.patchSha(),
					change.parentSha(), change.subject());
			out.accept("Change " + change.change() + " patchset " + change.patchset() + " of " + change.project()
					+ ": \"" + change.subject() + "\"");
			out.accept("Patch commit " + change.patchSha() + ", parent " + change.parentSha());

			// 2. Check out the patchset, and its parent next to it (D-007).
			step(id, "CLONING");
			Path runDir = props.workDir().resolve("run-" + id).toAbsolutePath().normalize();
			Path patchDir = runDir.resolve("patch");
			Path parentDir = runDir.resolve("parent");
			git.checkout(change, patchDir, gerrit.gitAuthHeader(), out);
			boolean hasParent = change.parentSha() != null;
			if (hasParent) {
				git.checkoutParent(patchDir, parentDir, change.parentSha(), out);
			}

			// 3. Run the tests on the patch, then on the parent.
			step(id, "TESTING_PATCH");
			out.accept("===== Tests on the PATCH (" + shortSha(change.patchSha()) + ") =====");
			TestRunner.TestRunResult patch = testRunner.runTests(patchDir, out);
			TestRunner.TestRunResult parent = null;
			if (hasParent) {
				step(id, "TESTING_PARENT");
				out.accept("===== Tests on the PARENT (" + shortSha(change.parentSha()) + ") =====");
				parent = testRunner.runTests(parentDir, out);
			}
			else {
				out.accept("This change has no parent commit (first commit in the repo): only the patch is tested.");
			}

			if (patch.timedOut() || (parent != null && parent.timedOut())) {
				throw new IllegalStateException("Tests timed out after " + props.sandbox().timeoutMinutes() + " minutes");
			}
			boolean patchBuilt = built(patch);
			boolean parentBuilt = parent != null && built(parent);
			if (!patchBuilt && !parentBuilt) {
				throw new IllegalStateException("Build failed before any test ran (patch exit code " + patch.buildExitCode()
						+ (parent == null ? "" : ", parent exit code " + parent.buildExitCode()) + ")");
			}
			if (!patchBuilt) {
				out.accept("WARNING: the PATCH doesn't build (exit code " + patch.buildExitCode() + "), but the parent does.");
			}
			if (parent != null && !parentBuilt) {
				out.accept("WARNING: the PARENT doesn't build (exit code " + parent.buildExitCode() + "), so nothing can be compared.");
			}

			// 4. Verdicts: plain code, no AI (D-005).
			List<Comparison> comparisons = VerdictEngine.compare(parent == null ? List.of() : parent.tests(),
					parentBuilt, patch.tests(), patchBuilt);
			runs.complete(id, patch.tests(), comparisons, parent == null ? null : parent.buildExitCode(),
					patch.buildExitCode());
			out.accept(summary(patch));
			out.accept(verdictSummary(comparisons));
		}
		catch (RuntimeException e) {
			log.warn("Run {} failed", id, e);
			String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			out.accept("ERROR: " + message);
			runs.fail(id, message);
		}
		events.statusChanged(id);
	}

	private void step(long id, String step) {
		runs.updateStep(id, step);
		events.statusChanged(id);
	}

	/** A side "built" unless the build stopped before any test ran. */
	private static boolean built(TestRunner.TestRunResult result) {
		return !result.tests().isEmpty() || result.buildExitCode() == 0;
	}

	private static String shortSha(String sha) {
		return sha == null ? "?" : sha.substring(0, Math.min(10, sha.length()));
	}

	private static String verdictSummary(List<Comparison> comparisons) {
		Map<Verdict, Long> counts = new EnumMap<>(Verdict.class);
		comparisons.forEach(c -> counts.merge(c.verdict(), 1L, Long::sum));
		StringBuilder sb = new StringBuilder("Verdicts (parent vs patch):");
		counts.forEach((v, n) -> sb.append(' ').append(v).append('=').append(n));
		long broken = counts.getOrDefault(Verdict.COUNTEREXAMPLE, 0L);
		sb.append(broken > 0 ? " -> the change BROKE " + broken + " test(s)" : " -> the change broke no existing test");
		return sb.toString();
	}

	private static String summary(TestRunner.TestRunResult result) {
		long passed = result.tests().stream().filter(t -> t.status() == TestCaseResult.Status.PASSED).count();
		long failed = result.tests().stream().filter(t -> t.status() == TestCaseResult.Status.FAILED
				|| t.status() == TestCaseResult.Status.ERROR).count();
		long skipped = result.tests().stream().filter(t -> t.status() == TestCaseResult.Status.SKIPPED).count();
		return "Done. Patch: " + result.tests().size() + " tests, " + passed + " passed, " + failed + " failed, "
				+ skipped + " skipped (build exit code " + result.buildExitCode() + ")";
	}
}
