package com.failureforge.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.failureforge.config.FailureForgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one place FailureForge talks to an LLM (D-004, D-028). Speaks the OpenAI-compatible
 * {@code POST <base-url>/chat/completions} API, which both Ollama (dev) and the office LLM serve,
 * so switching is configuration only.
 * <p>
 * The LLM never decides anything here: callers validate what it returns (D-005).
 */
@Component
public class LlmClient {

	private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
	/** Reasoning models may put their thinking inside these tags; we only want the answer. */
	private static final Pattern THINK = Pattern.compile("(?s)<think>.*?</think>");

	private final RestClient http;
	private final JsonMapper json;
	private final FailureForgeProperties.Llm config;

	public LlmClient(JsonMapper json, FailureForgeProperties props) {
		this.json = json;
		this.config = props.llm();
		HttpClient.Builder client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20));
		if (config.bypassProxy()) {
			client.proxy(HttpClient.Builder.NO_PROXY);
		}
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client.build());
		factory.setReadTimeout(Duration.ofSeconds(config.timeoutSeconds()));
		this.http = RestClient.builder().requestFactory(factory).build();
	}

	public record Answer(String text, int totalTokens, Duration took) {
	}

	/** Sends one system + user message and returns the answer text (thinking removed). */
	public Answer chat(String system, String user) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", config.model());
		body.put("messages", List.of(
				Map.of("role", "system", "content", system),
				Map.of("role", "user", "content", user + (config.promptSuffix() == null ? "" : config.promptSuffix()))));
		body.put("max_tokens", config.maxTokens());
		body.put("temperature", config.temperature());
		body.put("stream", false);
		if (config.extraBody() != null) {
			body.putAll(config.extraBody());
		}

		long start = System.nanoTime();
		String raw = http.post()
			.uri(URI.create(config.baseUrl().replaceAll("/+$", "") + "/chat/completions"))
			.contentType(MediaType.APPLICATION_JSON)
			.headers(h -> {
				if (config.apiKey() != null && !config.apiKey().isBlank()) {
					h.set(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey());
				}
			})
			.body(json.writeValueAsString(body))
			.retrieve()
			.body(String.class);
		Duration took = Duration.ofNanos(System.nanoTime() - start);

		JsonNode root = json.readTree(raw);
		String text = root.path("choices").path(0).path("message").path("content").asString("");
		text = THINK.matcher(text).replaceAll("").strip();
		int tokens = root.path("usage").path("total_tokens").asInt(0);
		log.info("LLM {} answered in {} s, {} tokens", config.model(), took.toSeconds(), tokens);
		return new Answer(text, tokens, took);
	}

	public String model() {
		return config.model();
	}
}
