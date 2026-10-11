package com.failureforge.gerrit;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import com.failureforge.config.FailureForgeProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Talks to the Gerrit REST API. Phase 1 needs only one call: "tell me about this change".
 * Docs: https://gerrit-review.googlesource.com/Documentation/rest-api-changes.html
 */
@Component
public class GerritClient {

	/** Gerrit prefixes every JSON response with this to block a browser attack (XSSI). */
	private static final String XSSI_PREFIX = ")]}'";

	private final RestClient http;
	private final JsonMapper json;
	private final FailureForgeProperties.Gerrit config;

	public GerritClient(JsonMapper json, FailureForgeProperties props) {
		this.http = RestClient.create();
		this.json = json;
		this.config = props.gerrit();
	}

	/** Looks up the change and the requested (or latest) patchset. */
	public GerritChange fetchChange(GerritChangeUrl url) {
		// With credentials Gerrit wants the "/a/" prefix (authenticated API).
		String api = url.baseUrl() + (config.hasCredentials() ? "/a" : "");
		String body;
		try {
			body = http.get()
				// URI.create: restId() is already encoded; a plain String would get encoded twice (%2F -> %252F).
				.uri(URI.create(api + "/changes/" + url.restId() + "?o=ALL_REVISIONS&o=ALL_COMMITS"))
				.headers(this::addAuth)
				.retrieve()
				.body(String.class);
		}
		catch (RestClientResponseException e) {
			throw new IllegalStateException("Gerrit returned HTTP " + e.getStatusCode().value() + " for change "
					+ url.change() + (e.getStatusCode().value() == 404 ? " (not found, or private and no credentials set)" : ""), e);
		}
		if (body == null) {
			throw new IllegalStateException("Gerrit returned an empty response for change " + url.change());
		}
		return parse(url, body.startsWith(XSSI_PREFIX) ? body.substring(XSSI_PREFIX.length()) : body);
	}

	GerritChange parse(GerritChangeUrl url, String body) {
		JsonNode change = json.readTree(body);
		String project = change.path("project").asString();
		int wantedPatchset = url.patchset() != null ? url.patchset() : change.path("current_revision_number").asInt(-1);

		Map.Entry<String, JsonNode> revision = change.path("revisions").properties().stream()
			.filter(e -> wantedPatchset == -1
					? e.getKey().equals(change.path("current_revision").asString())
					: e.getValue().path("_number").asInt() == wantedPatchset)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException(
					"Patchset " + wantedPatchset + " not found on change " + url.change()));

		JsonNode rev = revision.getValue();
		String parentSha = rev.path("commit").path("parents").path(0).path("commit").asString(null);
		return new GerritChange(
				project,
				change.path("_number").asInt(),
				rev.path("_number").asInt(),
				revision.getKey(),
				parentSha,
				rev.path("ref").asString(),
				fetchUrl(url, project, rev),
				change.path("subject").asString(""));
	}

	/** Prefer the clone URL Gerrit advertises; otherwise use the usual {@code <base>/<project>}. */
	private String fetchUrl(GerritChangeUrl url, String project, JsonNode rev) {
		JsonNode fetch = rev.path("fetch");
		return Optional.ofNullable(fetch.path(config.hasCredentials() ? "http" : "anonymous http").path("url").asString(null))
			.or(() -> Optional.ofNullable(fetch.path("anonymous http").path("url").asString(null)))
			.orElse(url.baseUrl() + "/" + project);
	}

	private void addAuth(HttpHeaders headers) {
		if (config.hasCredentials()) {
			headers.set(HttpHeaders.AUTHORIZATION, basicAuthHeader());
		}
	}

	/** Also used by git so a private repo can be cloned without putting the password in the URL. */
	public Optional<String> gitAuthHeader() {
		return config.hasCredentials() ? Optional.of(basicAuthHeader()) : Optional.empty();
	}

	private String basicAuthHeader() {
		String token = config.username() + ":" + (config.password() == null ? "" : config.password());
		return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
	}
}
