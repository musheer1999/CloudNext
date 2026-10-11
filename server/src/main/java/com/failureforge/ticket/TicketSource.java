package com.failureforge.ticket;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.failureforge.config.FailureForgeProperties;
import com.failureforge.run.Run;
import com.failureforge.run.RunRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Collects what the change is supposed to do (design doc "Ingest", D-033): the pasted ticket text, the JIRA
 * issue behind the JIRA link (summary + description) and the Confluence pages behind the wiki links, all as
 * plain text. Uses the standard JIRA / Confluence REST APIs, the same on Samsung's servers and public ones.
 * A source that can't be read is logged and skipped; it never fails the run.
 */
@Component
public class TicketSource {

	private static final int MAX_CHARS_PER_SOURCE = 6_000;
	private static final Pattern JIRA_KEY = Pattern.compile("^(.*?)/browse/([A-Z][A-Z0-9_]+-\\d+)");
	private static final Pattern PAGE_ID = Pattern.compile("[?&]pageId=(\\d+)|/pages/(\\d+)");
	private static final Pattern DISPLAY = Pattern.compile("^(.*?)/display/([^/]+)/([^/?#]+)");

	private final RestClient http = RestClient.create();
	private final JsonMapper json;
	private final RunRepository runs;
	private final FailureForgeProperties.Atlassian config;

	public TicketSource(JsonMapper json, RunRepository runs, FailureForgeProperties props) {
		this.json = json;
		this.runs = runs;
		this.config = props.atlassian();
	}

	/** @return everything known about the intent, or {@code null} if nothing was given */
	public String resolve(Run run, Consumer<String> out) {
		List<String> parts = new ArrayList<>();
		if (run.ticketText() != null && !run.ticketText().isBlank()) {
			parts.add(run.ticketText().strip());
		}
		if (run.jiraUrl() != null) {
			try {
				String issue = jiraIssue(run.jiraUrl());
				out.accept("[ingest] read JIRA issue " + run.jiraUrl() + " (" + issue.length() + " characters)");
				parts.add(issue);
			}
			catch (RuntimeException e) {
				out.accept("[ingest] could not read the JIRA issue (" + e.getMessage() + "); continuing without it");
			}
		}
		for (String wiki : run.wikiUrls()) {
			try {
				String page = confluencePage(wiki);
				out.accept("[ingest] read wiki page " + wiki + " (" + page.length() + " characters)");
				parts.add(page);
			}
			catch (RuntimeException e) {
				out.accept("[ingest] could not read the wiki page " + wiki + " (" + e.getMessage() + "); continuing without it");
			}
		}
		if (parts.isEmpty()) {
			return null;
		}
		String all = String.join("\n\n", parts);
		if (run.jiraUrl() != null || !run.wikiUrls().isEmpty()) {
			runs.updateTicketText(run.id(), all); // the portal shows exactly what the AI read
		}
		return all;
	}

	/** For the portal's "Fetch" button: the text of one JIRA issue or wiki page. */
	public String preview(String url) {
		return JIRA_KEY.matcher(url.strip()).find() ? jiraIssue(url) : confluencePage(url);
	}

	/** {@code https://host[/jira]/browse/KEY-1} -> "JIRA KEY-1: summary\n\ndescription" via REST API v2. */
	String jiraIssue(String url) {
		Matcher m = JIRA_KEY.matcher(url.strip());
		if (!m.find()) {
			throw new IllegalArgumentException("not a JIRA issue link (expected .../browse/KEY-123)");
		}
		JsonNode fields = get(m.group(1) + "/rest/api/2/issue/" + m.group(2) + "?fields=summary,description")
			.path("fields");
		return cut("JIRA " + m.group(2) + ": " + fields.path("summary").asString("") + "\n\n"
				+ jiraMarkupToText(fields.path("description").asString("")));
	}

	/** Confluence page by {@code ?pageId=}, {@code /pages/123} or {@code /display/SPACE/Title}, as plain text. */
	String confluencePage(String url) {
		String u = url.strip();
		String base = u.contains("/display/") ? u.substring(0, u.indexOf("/display/"))
				: u.contains("/pages/") ? u.substring(0, u.indexOf("/pages/"))
						: u.contains("/spaces/") ? u.substring(0, u.indexOf("/spaces/")) : null;
		Matcher id = PAGE_ID.matcher(u);
		JsonNode page;
		if (id.find()) {
			String pageId = id.group(1) != null ? id.group(1) : id.group(2);
			if (base == null) {
				base = u.substring(0, u.indexOf("/pages/") >= 0 ? u.indexOf("/pages/") : u.indexOf('?'));
			}
			page = get(base + "/rest/api/content/" + pageId + "?expand=body.storage");
		}
		else {
			Matcher d = DISPLAY.matcher(u);
			if (!d.find()) {
				throw new IllegalArgumentException("not a Confluence page link");
			}
			String title = URLDecoder.decode(d.group(3).replace('+', ' '), StandardCharsets.UTF_8);
			JsonNode results = get(d.group(1) + "/rest/api/content?spaceKey=" + enc(d.group(2)) + "&title="
					+ enc(title) + "&expand=body.storage").path("results");
			if (results.isEmpty()) {
				throw new IllegalArgumentException("page not found");
			}
			page = results.path(0);
		}
		return cut("WIKI " + page.path("title").asString("") + ":\n"
				+ htmlToText(page.path("body").path("storage").path("value").asString("")));
	}

	private JsonNode get(String url) {
		String body = http.get()
			.uri(URI.create(url))
			.headers(h -> {
				h.set(HttpHeaders.ACCEPT, "application/json");
				if (config.token() != null && !config.token().isBlank()) {
					h.set(HttpHeaders.AUTHORIZATION, config.username() == null || config.username().isBlank()
							? "Bearer " + config.token() // JIRA/Confluence Data Center personal access token
							: "Basic " + Base64.getEncoder().encodeToString((config.username() + ":" + config.token())
								.getBytes(StandardCharsets.UTF_8)));
				}
			})
			.retrieve()
			.body(String.class);
		return json.readTree(body);
	}

	static String jiraMarkupToText(String s) {
		return s.replaceAll("\\{code(:[^}]*)?}|\\{noformat}|\\{quote}", "")
			.replaceAll("(?m)^h[1-6]\\.\\s*", "")
			.replaceAll("\\[([^|\\]]+)\\|[^\\]]+]", "$1")
			.replaceAll("\r", "")
			.strip();
	}

	static String htmlToText(String html) {
		return html.replaceAll("(?i)<br\\s*/?>|</p>|</li>|</h[1-6]>|</tr>", "\n")
			.replaceAll("(?i)<li[^>]*>", "- ")
			.replaceAll("<[^>]+>", "")
			.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
			.replace("&quot;", "\"").replace("&#39;", "'")
			.replaceAll("[ \\t]+", " ")
			.replaceAll("\\n\\s*\\n+", "\n")
			.strip();
	}

	private static String enc(String s) {
		return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static String cut(String s) {
		return s.length() <= MAX_CHARS_PER_SOURCE ? s : s.substring(0, MAX_CHARS_PER_SOURCE) + "\n... (cut)";
	}
}
