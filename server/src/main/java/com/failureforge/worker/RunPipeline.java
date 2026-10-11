package com.failureforge.worker;

import java.nio.file.Path;
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

			// 2. Check out exactly that patchset.
			step(id, "CLONING");
			Path dir = props.workDir().resolve("run-" + id).toAbsolutePath().normalize();
			git.checkout(change, dir, gerrit.gitAuthHeader(), out);

			// 3. Run the existing tests.
			step(id, "TESTING");
			TestRunner.TestRunResult result = testRunner.runTests(dir, out);
			if (result.timedOut()) {
				throw new IllegalStateException("Tests timed out after " + props.sandbox().timeoutMinutes() + " minutes");
			}
			if (result.tests().isEmpty() && result.buildExitCode() != 0) {
				throw new IllegalStateException("Build failed before any test ran (exit code " + result.buildExitCode() + ")");
			}

			runs.complete(id, result.tests());
			out.accept(summary(result));
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

	private static String summary(TestRunner.TestRunResult result) {
		long passed = result.tests().stream().filter(t -> t.status() == TestCaseResult.Status.PASSED).count();
		long failed = result.tests().stream().filter(t -> t.status() == TestCaseResult.Status.FAILED
				|| t.status() == TestCaseResult.Status.ERROR).count();
		long skipped = result.tests().stream().filter(t -> t.status() == TestCaseResult.Status.SKIPPED).count();
		return "Done: " + result.tests().size() + " tests, " + passed + " passed, " + failed + " failed, "
				+ skipped + " skipped (build exit code " + result.buildExitCode() + ")";
	}
}
