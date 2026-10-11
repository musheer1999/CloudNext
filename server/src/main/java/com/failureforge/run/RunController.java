package com.failureforge.run;

import java.util.List;

import com.failureforge.gerrit.GerritChangeUrl;
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

	public RunController(RunRepository runs, RunEvents events) {
		this.runs = runs;
		this.events = events;
	}

	public record SubmitRequest(@NotBlank String gerritUrl, String jiraUrl, List<String> wikiUrls) {
	}

	public record SubmitResponse(long id) {
	}

	public record RunDetail(Run run, List<TestCaseResult> tests) {
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
		return new SubmitResponse(runs.enqueue(gerritUrl, jiraUrl, wikiUrls));
	}

	@GetMapping
	public List<Run> recent() {
		return runs.findRecent(50);
	}

	@GetMapping("/{id}")
	public RunDetail get(@PathVariable long id) {
		Run run = runs.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		return new RunDetail(run, runs.findTests(id));
	}

	@GetMapping(path = "/{id}/events", produces = "text/event-stream")
	public SseEmitter events(@PathVariable long id) {
		runs.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		return events.subscribe(id);
	}
}
