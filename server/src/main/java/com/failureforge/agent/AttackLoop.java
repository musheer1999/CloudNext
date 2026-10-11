package com.failureforge.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.failureforge.config.FailureForgeProperties;
import com.failureforge.run.TestCaseResult;
import com.failureforge.sandbox.TestRunner;
import com.failureforge.verdict.Comparison;
import com.failureforge.verdict.Verdict;
import com.failureforge.verdict.VerdictEngine;
import org.springframework.stereotype.Component;

/**
 * Attacks one rule (design doc sections 3-6): the Boundary specialist writes a jqwik test, the sandbox
 * compiles and runs it on the patch (repairing compile errors up to {@code max-repairs} times), then on the
 * parent, and the verdict engine (plain code) compares the two. The LLM never decides the verdict (D-005).
 */
@Component
public class AttackLoop {

	private static final int MAX_ERROR_LINES = 25;

	private final TestWriter writer;
	private final TestRunner sandbox;
	private final FailureForgeProperties.Agent config;

	public AttackLoop(TestWriter writer, TestRunner sandbox, FailureForgeProperties props) {
		this.writer = writer;
		this.sandbox = sandbox;
		this.config = props.agent();
	}

	public enum Status {
		DONE, COULDNT_COMPILE
	}

	/**
	 * @param minimalInput the failure message on the patch, which the test is told to fill with the input values;
	 *                     jqwik re-runs the shrunk sample last, so this is the smallest counterexample it found
	 */
	public record Result(Rule rule, Specialist specialist, String testClass, String code, int compileAttempts, Status status,
			TestCaseResult.Status parentStatus, TestCaseResult.Status patchStatus, Verdict verdict,
			String minimalInput, List<Comparison> comparisons, int tokens) {
	}

	public Result attack(Specialist specialist, Rule rule, ChangeContext change, String constraints, Path patchDir,
			Path parentDir, Consumer<String> out) {
		ChangeContext.SourceFile target = change.changedFiles().getFirst();
		String packageName = target.packageName();
		String simpleName = "FailureForge" + rule.key().replaceAll("[^A-Za-z0-9]", "") + "Test";
		String testClass = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
		Path relativeFile = Path.of(target.testDir(), simpleName + ".java");
		int tokens = 0;

		out.accept("[agent] " + rule.key() + ": " + specialist.label() + " specialist is writing " + simpleName + " ...");
		TestWriter.Draft draft = writer.write(specialist, rule, change, constraints, packageName, simpleName);
		tokens += draft.tokens();
		out.accept("[agent] " + rule.key() + ": test written by the LLM in " + draft.seconds() + " s");

		// 1. Compile + run on the patch; repair from compiler errors (D-010: at most max-repairs repairs).
		addJqwik(patchDir, out);
		TestRunner.TestRunResult patch = null;
		int attempts = 0;
		while (true) {
			attempts++;
			if (!TestWriter.callsCodeUnderTest(draft.code(), change)) {
				// A test that re-implements the logic instead of calling it proves nothing: send it back.
				if (attempts > config.maxRepairs()) {
					out.accept("[agent] " + rule.key() + ": the test never calls the code under test -> couldn't check");
					return new Result(rule, specialist, testClass, draft.code(), attempts, Status.COULDNT_COMPILE, null, null,
							Verdict.COULDNT_CHECK, null, List.of(), tokens);
				}
				out.accept("[agent] " + rule.key() + ": the test doesn't call the code under test; asking for a fix ("
						+ attempts + "/" + config.maxRepairs() + ")");
				draft = writer.repair(specialist, draft.code(), "The test does not call the code under test ("
						+ String.join(", ", changedClassNames(change)) + "). It must call it and check its result, "
						+ "not re-implement its logic.", packageName, simpleName);
				tokens += draft.tokens();
				continue;
			}
			write(patchDir.resolve(relativeFile), draft.code());
			out.accept("[agent] " + rule.key() + ": compile + run on the PATCH (attempt " + attempts + ")");
			List<String> lines = new ArrayList<>();
			patch = sandbox.runTests(patchDir, line -> {
				out.accept(line);
				lines.add(line);
			}, testClass);
			if (!patch.tests().isEmpty() || patch.buildExitCode() == 0) {
				break; // it compiled (and ran)
			}
			if (attempts > config.maxRepairs()) {
				out.accept("[agent] " + rule.key() + ": still doesn't compile after " + config.maxRepairs()
						+ " repairs -> couldn't check this rule");
				return new Result(rule, specialist, testClass, draft.code(), attempts, Status.COULDNT_COMPILE, null, null,
						Verdict.COULDNT_CHECK, null, List.of(), tokens);
			}
			String errors = compilerErrors(lines);
			out.accept("[agent] " + rule.key() + ": doesn't compile; sending " + errors.lines().count()
					+ " error lines back to the LLM (repair " + attempts + "/" + config.maxRepairs() + ")");
			draft = writer.repair(specialist, draft.code(), errors, packageName, simpleName);
			tokens += draft.tokens();
		}

		// 2. Same test on the parent.
		List<TestCaseResult> parentTests = List.of();
		boolean parentBuilt = true;
		if (parentDir != null) {
			addJqwik(parentDir, out);
			write(parentDir.resolve(relativeFile), draft.code());
			out.accept("[agent] " + rule.key() + ": run the same test on the PARENT");
			TestRunner.TestRunResult parent = sandbox.runTests(parentDir, out, testClass);
			parentTests = parent.tests();
			if (parentTests.isEmpty() && parent.buildExitCode() != 0) {
				// The test uses code the parent doesn't have yet: new code, only the patch result counts.
				out.accept("[agent] " + rule.key() + ": the test doesn't compile on the parent (new code), "
						+ "so only the patch result counts");
			}
		}

		// 3. Verdict: plain code.
		List<Comparison> comparisons = VerdictEngine.compare(parentTests, parentBuilt, patch.tests(), true);
		Comparison worst = comparisons.isEmpty() ? null : comparisons.getFirst();
		Verdict verdict = worst == null ? Verdict.COULDNT_CHECK : worst.verdict();
		String minimal = worst != null && worst.patchStatus() != null && worst.patchStatus() != TestCaseResult.Status.PASSED
				? worst.message() : null;
		out.accept("[agent] " + rule.key() + ": verdict " + verdict
				+ (minimal != null ? " | counterexample: " + minimal : ""));
		return new Result(rule, specialist, testClass, draft.code(), attempts, Status.DONE,
				worst == null ? null : worst.parentStatus(), worst == null ? null : worst.patchStatus(),
				verdict, minimal, comparisons, tokens);
	}

	private static List<String> changedClassNames(ChangeContext change) {
		return change.changedFiles().stream()
			.map(f -> f.path().substring(f.path().lastIndexOf('/') + 1).replace(".java", ""))
			.toList();
	}

	/**
	 * Attack order: "always/never" rules first (invariants are where boundary bugs hide), then the rest.
	 * Plain keyword scoring, so the order is predictable.
	 */
	public static int invariantScore(Rule rule) {
		String t = (rule.text() + " " + rule.sourceQuote()).toLowerCase();
		int score = 0;
		for (String w : List.of("never", "always", "must", "cannot", "can't", "at most", "at least", "no more than",
				"not more than", "more than", "less than", "negative", "exceed", "limit", "maximum", "minimum")) {
			if (t.contains(w)) {
				score++;
			}
		}
		return score;
	}

	private void addJqwik(Path repo, Consumer<String> out) {
		if (Files.exists(repo.resolve("pom.xml")) && JqwikSupport.addToMaven(repo, config.jqwikVersion())) {
			out.accept("[agent] added jqwik " + config.jqwikVersion() + " to the test dependencies of "
					+ repo.getFileName() + " (FailureForge's copy only)");
		}
	}

	/** The compiler's own error lines (javac via Maven/Gradle), without the build tool's noise. */
	static String compilerErrors(List<String> lines) {
		List<String> errors = lines.stream()
			.filter(l -> l.contains(".java") && (l.contains("ERROR") || l.contains("error:"))
					|| l.matches("^\\s*(symbol|location|required|found|reason):.*"))
			.map(l -> l.replaceAll("^\\[ERROR\\]\\s*", "").replaceAll("/workspace/", ""))
			.distinct()
			.limit(MAX_ERROR_LINES)
			.toList();
		return errors.isEmpty() ? String.join("\n", lines.subList(Math.max(0, lines.size() - MAX_ERROR_LINES), lines.size()))
				: String.join("\n", errors);
	}

	private static void write(Path file, String code) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, code);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
