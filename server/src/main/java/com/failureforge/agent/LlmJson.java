package com.failureforge.agent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pulls the JSON object or the Java code block out of an LLM answer, which may wrap it in prose or fences. */
final class LlmJson {

	private LlmJson() {
	}

	static JsonNode object(JsonMapper json, String answer) {
		int start = answer.indexOf('{');
		int end = answer.lastIndexOf('}');
		if (start < 0 || end <= start) {
			throw new IllegalArgumentException("no JSON object in the answer");
		}
		return json.readTree(answer.substring(start, end + 1));
	}

	/** The content of the first ```java fence, or the whole answer if there is none. */
	static String javaCode(String answer) {
		int fence = answer.indexOf("```");
		if (fence < 0) {
			return answer.strip();
		}
		int lineEnd = answer.indexOf('\n', fence);
		int close = answer.indexOf("```", lineEnd + 1);
		if (lineEnd < 0) {
			return answer.strip();
		}
		return (close < 0 ? answer.substring(lineEnd + 1) : answer.substring(lineEnd + 1, close)).strip();
	}
}
