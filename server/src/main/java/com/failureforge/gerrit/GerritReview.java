package com.failureforge.gerrit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.failureforge.config.FailureForgeProperties;
import com.failureforge.process.ProcessRunner;
import com.failureforge.run.Run;
import com.failureforge.run.RunRepository;
import org.springframework.stereotype.Component;

/**
 * Turns a run's findings into a Gerrit review (design doc section 7, D-035): one summary message with every
 * counterexample (rule, source quote, smallest failing input, the test to add) and an inline comment on the
 * first changed line of the changed file. Posted only when a person clicks the button.
 */
@Component
public class GerritReview {

	private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)");

	private final GerritClient gerrit;
	private final RunRepository runs;
	private final ProcessRunner processes;
	private final FailureForgeProperties props;
	private final String portalUrl;

	public GerritReview(GerritClient gerrit, RunRepository runs, ProcessRunner processes, FailureForgeProperties props,
			@org.springframework.beans.factory.annotation.Value("${failureforge.portal-url:http://localhost:5173}") String portalUrl) {
		this.gerrit = gerrit;
		this.runs = runs;
		this.processes = processes;
		this.props = props;
		this.portalUrl = portalUrl;
	}

	/** The review text, also shown in the portal before posting. */
	public String message(Run run) {
		List<RunRepository.RuleView> found = runs.findRules(run.id()).stream()
			.filter(r -> "COUNTEREXAMPLE".equals(r.verdict()))
			.toList();
		StringBuilder m = new StringBuilder();
		if (found.isEmpty()) {
			m.append("FailureForge: no counterexample found.\n\nThe change was checked against ")
				.append(runs.findRules(run.id()).stream().filter(r -> r.testClass() != null || r.verdict() != null).count())
				.append(" rule(s); nothing broke.\n");
		}
		else {
			m.append("FailureForge found ").append(found.size()).append(" counterexample")
				.append(found.size() > 1 ? "s" : "")
				.append(": each check passes on the parent commit and fails on this patchset.\n");
			for (RunRepository.RuleView r : found) {
				m.append("\n").append(r.key()).append(" [").append(r.specialist() == null ? r.type() : r.specialist().toLowerCase())
					.append("] ").append(r.text()).append('\n');
				if (r.sourceQuote() != null) {
					m.append("  Source: \"").append(r.sourceQuote()).append("\" (").append(r.source()).append(")\n");
				}
				if (r.minimalInput() != null) {
					m.append("  Smallest failing input: ").append(r.minimalInput()).append('\n');
				}
				if (r.testClass() != null) {
					m.append("  Test to reproduce (add it to the change): ").append(r.testClass()).append('\n');
				}
			}
		}
		m.append("\nFull report: ").append(portalUrl).append("/#/runs/").append(run.id())
			.append("\nReply in the report with Accept, Dispute (add your constraint) or False Positive.");
		return m.toString();
	}

	public void post(Run run) {
		Map<String, List<Map.Entry<Integer, String>>> inline = new LinkedHashMap<>();
		String file = firstChangedLine(run);
		if (file != null) {
			String[] parts = file.split(":", 2);
			long count = runs.findRules(run.id()).stream().filter(r -> "COUNTEREXAMPLE".equals(r.verdict())).count();
			if (count > 0) {
				inline.put(parts[1], List.of(Map.entry(Integer.parseInt(parts[0]),
						"FailureForge: " + count + " counterexample(s) for this change, see the review message.")));
			}
		}
		gerrit.postReview(GerritChangeUrl.parse(run.gerritUrl()), run.gerritProject(), run.patchSha(), message(run), inline);
		runs.markGerritPosted(run.id());
	}

	/** "line:path" of the first added line in a changed main Java file, from the run's own checkout. */
	private String firstChangedLine(Run run) {
		Path patchDir = props.workDir().resolve("run-" + run.id()).resolve("patch").toAbsolutePath();
		if (!Files.isDirectory(patchDir) || run.parentSha() == null) {
			return null;
		}
		List<String> lines = new ArrayList<>();
		processes.run(List.of("git", "diff", "-U0", "--no-color", run.parentSha(), "HEAD", "--", "*src/main/java/*.java"),
				patchDir, Map.of(), Duration.ofSeconds(30), lines::add);
		String path = null;
		for (String line : lines) {
			if (line.startsWith("+++ b/")) {
				path = line.substring(6);
			}
			Matcher m = HUNK.matcher(line);
			if (path != null && m.find()) {
				return Math.max(1, Integer.parseInt(m.group(1))) + ":" + path;
			}
		}
		return null;
	}
}
