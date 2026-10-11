package com.failureforge.gerrit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import com.failureforge.config.FailureForgeProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GerritClientTest {

	/** Trimmed copy of a real GerritHub response (o=ALL_REVISIONS&o=ALL_COMMITS). */
	private static final String CHANGE_JSON = """
			{"project":"amarula/plugin","_number":1251299,"subject":"chore: javadoc",
			 "current_revision":"bbb","current_revision_number":13,
			 "revisions":{
			   "aaa":{"_number":12,"ref":"refs/changes/99/1251299/12",
			          "fetch":{"anonymous http":{"url":"https://review.gerrithub.io/amarula/plugin","ref":"refs/changes/99/1251299/12"}},
			          "commit":{"parents":[{"commit":"p12"}]}},
			   "bbb":{"_number":13,"ref":"refs/changes/99/1251299/13",
			          "fetch":{"anonymous http":{"url":"https://review.gerrithub.io/amarula/plugin","ref":"refs/changes/99/1251299/13"}},
			          "commit":{"parents":[{"commit":"p13"}]}}}}
			""";

	private final GerritClient client = new GerritClient(JsonMapper.builder().build(),
			new FailureForgeProperties(Path.of("work"), null, new FailureForgeProperties.Gerrit("", ""), null));

	@Test
	void picksCurrentPatchsetByDefault() {
		GerritChange change = client.parse(GerritChangeUrl.parse("https://review.gerrithub.io/c/amarula/plugin/+/1251299"), CHANGE_JSON);
		assertThat(change).isEqualTo(new GerritChange("amarula/plugin", 1251299, 13, "bbb", "p13",
				"refs/changes/99/1251299/13", "https://review.gerrithub.io/amarula/plugin", "chore: javadoc"));
	}

	@Test
	void picksPatchsetFromLink() {
		GerritChange change = client.parse(GerritChangeUrl.parse("https://review.gerrithub.io/c/amarula/plugin/+/1251299/12"), CHANGE_JSON);
		assertThat(change.patchset()).isEqualTo(12);
		assertThat(change.patchSha()).isEqualTo("aaa");
		assertThat(change.parentSha()).isEqualTo("p12");
	}

	@Test
	void unknownPatchsetIsAnError() {
		assertThatThrownBy(() -> client.parse(GerritChangeUrl.parse("https://review.gerrithub.io/c/amarula/plugin/+/1251299/99"), CHANGE_JSON))
			.hasMessageContaining("Patchset 99 not found");
	}
}
