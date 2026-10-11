package com.failureforge.run;

import java.util.List;
import java.util.Map;

import com.failureforge.gerrit.GerritChangeUrl;
import com.failureforge.gerrit.GerritReview;
import com.failureforge.verdict.Comparison;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST API used by the portal.
 * <pre>
 * POST /api/runs              submit a Gerrit link (+ JIRA, wiki) -> queued run
 * GET  /api/runs              recent runs
 * GET  /api/runs/{id}         one run with its test results
 * GET  /api/runs/{id}/events  live log + status (Server-Sent Events)
 * </pre>
 */
@RestController
@RequestMapping("/api/runs")
public class RunController {

	private final RunRepository runs;
	private final RunEvents events;
	private final GerritReview review;

	public RunController(RunRepository runs, RunEvents events, GerritReview review) {
		this.runs = runs;
		this.events = events;
		this.review = review;
	}

	public record SubmitRequest(@NotBlank String gerritUrl, String jiraUrl, List<String> wikiUrls, String ticketText) {
	}

	public record SubmitResponse(long id) {
	}

	/** {@code tests} = results on the patch; {@code comparisons} = parent vs patch with a verdict each. */
	public record RunDetail(Run run, List<TestCaseResult> tests, List<Comparison> comparisons,
			List<RunRepository.RuleView> rules, List<RunRepository.Feedback> feedback) {
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public SubmitResponse submit(@Valid @RequestBody SubmitRequest request) {
		String gerritUrl = request.gerritUrl().trim();
		try {
			GerritChangeUrl.parse(gerritUrl); // reject bad links now, not minutes later in the worker
		}
		catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
		}
		List<String> wikiUrls = request.wikiUrls() == null ? List.of()
				: request.wikiUrls().stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
		String jiraUrl = request.jiraUrl() == null || request.jiraUrl().isBlank() ? null : request.jiraUrl().trim();
		String ticket = request.ticketText() == null || request.ticketText().isBlank() ? null : request.ticketText().strip();
		return new SubmitResponse(runs.enqueue(gerritUrl, jiraUrl, wikiUrls, ticket));
	}

	@GetMapping
	public List<Run> recent() {
		return runs.findRecent(50);
	}

	@GetMapping("/{id}")
	public RunDetail get(@PathVariable long id) {
		Run run = runs.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		return new RunDetail(run, runs.findTests(id), runs.findComparisons(id), runs.findRules(id),
				runs.findFeedback(id));
	}

	/** Accept / Dispute / False Positive on one rule's finding (design doc section 8, D-034). */
	public record FeedbackRequest(@NotBlank String ruleKey, @NotBlank String type, String text) {
	}

	/** {@code rerunId} is set for a dispute: the re-run that checks the change again with the new constraint. */
	public record FeedbackResponse(Long rerunId) {
	}

	@PostMapping("/{id}/feedback")
	public FeedbackResponse feedback(@PathVariable long id, @Valid @RequestBody FeedbackRequest request) {
		Run run = runs.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		String type = request.type().trim().toUpperCase();
		if (!List.of("ACCEPT", "DISPUTE", "FALSE_POSITIVE").contains(type)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type must be ACCEPT, DISPUTE or FALSE_POSITIVE");
		}
		String text = request.text() == null ? null : request.text().strip();
		if (type.equals("DISPUTE") && (text == null || text.isEmpty())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"A dispute needs your constraint, e.g. \"the amount is validated upstream and never negative\"");
		}
		RunRepository.RuleView rule = runs.findRules(id).stream().filter(r -> r.key().equals(request.ruleKey()))
			.findFirst()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no rule " + request.ruleKey()));
		runs.saveFeedback(id, rule.key(), rule.text(), run.gerritProject(), type, text);
		return new FeedbackResponse(type.equals("DISPUTE") ? runs.enqueueDispute(run, text) : null);
	}

	/** The review text FailureForge would post, so people can read it before posting. */
	@GetMapping("/{id}/gerrit-review")
	public Map<String, String> gerritReviewPreview(@PathVariable long id) {
		Run run = runs.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		return Map.of("message", review.message(run));
	}

	@PostMapping("/{id}/gerrit-review")
	public Map<String, String> postGerritReview(@PathVariable long id) {
		Run run = runs.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		if (run.status() != RunStatus.SUCCEEDED) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "the run hasn't finished");
		}
		try {
			review.post(run);
		}
		catch (IllegalStateException e) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
		}
		return Map.of("posted", "true");
	}

	@GetMapping(path = "/{id}/events", produces = "text/event-stream")
	public SseEmitter events(@PathVariable long id) {
		runs.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		return events.subscribe(id);
	}
}
