package com.failureforge.worker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.failureforge.agent.AttackLoop;
import com.failureforge.agent.ChangeContext;
import com.failureforge.agent.CompatCheck;
import com.failureforge.agent.Rule;
import com.failureforge.agent.RuleExtractor;
import com.failureforge.agent.Specialist;
import com.failureforge.ticket.TicketSource;
import com.failureforge.config.FailureForgeProperties;
import com.failureforge.process.ProcessRunner;
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
 * The pipeline for one run: resolve the Gerrit change, check out patch and parent, run the existing tests on
 * both, then the specialists (Compat via japicmp; Boundary, State, Concurrency via generated jqwik tests),
 * and let the verdict engine (plain code) compare everything.
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
	private final ProcessRunner processes;
	private final RuleExtractor rules;
	private final AttackLoop attacks;
	private final CompatCheck compat;
	private final TicketSource tickets;

	public RunPipeline(RunRepository runs, RunEvents events, GerritClient gerrit, GitCheckout git,
			TestRunner testRunner, FailureForgeProperties props, ProcessRunner processes, RuleExtractor rules,
			AttackLoop attacks, CompatCheck compat, TicketSource tickets) {
		this.runs = runs;
		this.events = events;
		this.gerrit = gerrit;
		this.git = git;
		this.testRunner = testRunner;
		this.props = props;
		this.processes = processes;
		this.rules = rules;
		this.attacks = attacks;
		this.compat = compat;
		this.tickets = tickets;
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
			List<Comparison> comparisons = new ArrayList<>(VerdictEngine.compare(
					parent == null ? List.of() : parent.tests(), parentBuilt, patch.tests(), patchBuilt));
			out.accept(summary(patch));
			out.accept(verdictSummary(comparisons));

			// 5. The agent (Phase 2): rules from the ticket + diff, then attacks with generated tests.
			if (props.agent().enabled() && hasParent && patchBuilt) {
				comparisons.addAll(agent(id, run, patchDir, parentDir, change.parentSha(), out));
				comparisons.sort(Comparison.MOST_IMPORTANT_FIRST);
			}

			runs.complete(id, patch.tests(), comparisons, parent == null ? null : parent.buildExitCode(),
					patch.buildExitCode());
			out.accept("FINAL " + verdictSummary(comparisons));
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

	/**
	 * Specialists. Never fails the run: if the LLM is down or confused, the run still reports the
	 * existing-test results, and the log says what went wrong.
	 *
	 * @return the generated checks' comparisons, to show next to the existing ones
	 */
	private List<Comparison> agent(long id, Run run, Path patchDir, Path parentDir, String parentSha,
			Consumer<String> out) {
		List<Comparison> found = new ArrayList<>();

		// Compat specialist first: no LLM, and it needs the classes compiled by the full runs above (D-032).
		step(id, "COMPAT");
		out.accept("===== Compat specialist: does the change break existing callers? (japicmp, no AI) =====");
		try {
			AttackLoop.Result compat = this.compat.check(patchDir, parentDir, out);
			if (compat != null) {
				long ruleId = runs.saveRules(id, List.of(compat.rule())).getFirst();
				runs.saveAttack(id, ruleId, compat);
				found.addAll(compat.comparisons());
				events.statusChanged(id);
			}
		}
		catch (RuntimeException e) {
			log.warn("Compat check failed for run {}", id, e);
			out.accept("[compat] ERROR: " + e.getMessage());
		}

		try {
			step(id, "RULES");
			out.accept("===== Agent: rules the change must guarantee =====");
			ChangeContext context = ChangeContext.read(processes, patchDir, parentSha);
			if (context.changedFiles().isEmpty()) {
				out.accept("[agent] the change touches no main Java code: nothing to attack");
				return found;
			}
			String ticket = tickets.resolve(run, out);
			if (ticket == null) {
				out.accept("[agent] no ticket text was given: rules can only come from the code diff");
			}
			String constraints = run.constraints() == null ? "" : run.constraints();
			if (!constraints.isBlank()) {
				out.accept("[agent] developer constraints from disputes: " + constraints.replace('\n', ' '));
			}
			List<String> falsePositives = runs.falsePositiveRules(run.gerritProject());
			if (!falsePositives.isEmpty()) {
				out.accept("[agent] " + falsePositives.size() + " rule(s) marked FALSE POSITIVE before on this repo will be skipped");
			}
			RuleExtractor.Result extracted = rules.extract(ticket, context, constraints, falsePositives,
					props.agent().maxRules(), out);
			runs.addTokens(id, extracted.tokens());
			List<Long> ruleIds = runs.saveRules(id, extracted.rules());
			for (Rule r : extracted.rules()) {
				out.accept("[agent] " + r.key() + " [" + r.type() + "] " + (r.kept() ? "KEPT   " : "DROPPED") + " "
						+ r.text() + " | quote: \"" + r.sourceQuote() + "\""
						+ (r.kept() ? "" : " | reason: " + r.dropReason()));
			}
			events.statusChanged(id);

			List<Integer> targets = pickTargets(extracted.rules(), props.agent().maxAttacks());
			out.accept("[agent] attacking " + targets.stream()
				.map(i -> extracted.rules().get(i).key() + " (" + Specialist.forRuleType(extracted.rules().get(i).type()).label() + ")")
				.toList() + ": one per specialist first, always/never rules first");

			step(id, "ATTACKING");
			for (int i : targets) {
				Rule rule = extracted.rules().get(i);
				Specialist specialist = Specialist.forRuleType(rule.type());
				out.accept("===== " + specialist.label() + " specialist: attacking " + rule.key() + " =====");
				try {
					AttackLoop.Result result = attacks.attack(specialist, rule, context, constraints, patchDir, parentDir, out);
					runs.addTokens(id, result.tokens());
					runs.saveAttack(id, ruleIds.get(i), result);
					found.addAll(result.comparisons());
					events.statusChanged(id);
				}
				catch (RuntimeException e) {
					log.warn("Attack on {} failed", rule.key(), e);
					out.accept("[agent] attack on " + rule.key() + " failed: " + e.getMessage());
				}
			}
		}
		catch (RuntimeException e) {
			log.warn("Agent failed for run {}", id, e);
			out.accept("[agent] ERROR: " + e.getMessage() + " (the results above still count)");
		}
		return found;
	}

	/**
	 * Which rules to attack: kept, not Compat (japicmp already covers that), one per specialist first so every
	 * kind of attack gets a turn, "always/never" rules before examples, then the rest, up to {@code max}.
	 */
	static List<Integer> pickTargets(List<Rule> rules, int max) {
		List<Integer> candidates = new ArrayList<>();
		for (int i = 0; i < rules.size(); i++) {
			if (rules.get(i).kept() && Specialist.forRuleType(rules.get(i).type()) != Specialist.COMPAT) {
				candidates.add(i);
			}
		}
		candidates.sort(java.util.Comparator.comparingInt((Integer i) -> -AttackLoop.invariantScore(rules.get(i))));
		List<Integer> picked = new ArrayList<>();
		java.util.Set<Specialist> seen = java.util.EnumSet.noneOf(Specialist.class);
		for (int i : candidates) {
			if (seen.add(Specialist.forRuleType(rules.get(i).type()))) {
				picked.add(i);
			}
		}
		for (int i : candidates) {
			if (!picked.contains(i)) {
				picked.add(i);
			}
		}
		return picked.subList(0, Math.min(picked.size(), max));
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
