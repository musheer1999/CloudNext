package com.failureforge.ticket;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** {@code GET /api/ingest?url=...}: the plain text FailureForge reads from a JIRA issue or Confluence page. */
@RestController
public class IngestController {

	private final TicketSource tickets;

	public IngestController(TicketSource tickets) {
		this.tickets = tickets;
	}

	@GetMapping("/api/ingest")
	public Map<String, String> preview(@RequestParam String url) {
		try {
			return Map.of("text", tickets.preview(url));
		}
		catch (RuntimeException e) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not read " + url + ": " + e.getMessage());
		}
	}
}
