package com.failureforge.gerrit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GerritChangeUrlTest {

	@Test
	void modernLinkWithProject() {
		var url = GerritChangeUrl.parse("https://review.gerrithub.io/c/amarula/chatgpt-code-review-gerrit-plugin/+/1251299");
		assertThat(url).isEqualTo(new GerritChangeUrl("https://review.gerrithub.io",
				"amarula/chatgpt-code-review-gerrit-plugin", 1251299, null));
		assertThat(url.restId()).isEqualTo("amarula%2Fchatgpt-code-review-gerrit-plugin~1251299");
	}

	@Test
	void patchsetAndFileView() {
		assertThat(GerritChangeUrl.parse("https://g.example.com/c/proj/+/42/3").patchset()).isEqualTo(3);
		assertThat(GerritChangeUrl.parse("https://g.example.com/c/proj/+/42/3/src/A.java").patchset()).isEqualTo(3);
		assertThat(GerritChangeUrl.parse("https://g.example.com/c/proj/+/42/").patchset()).isNull();
	}

	@Test
	void contextPathIsKeptInBaseUrl() {
		var url = GerritChangeUrl.parse("https://corp.example.com/gerrit/c/platform/foo/+/7");
		assertThat(url.baseUrl()).isEqualTo("https://corp.example.com/gerrit");
		assertThat(url.project()).isEqualTo("platform/foo");
	}

	@Test
	void shortAndOldStyleLinks() {
		assertThat(GerritChangeUrl.parse("https://g.example.com/c/42"))
			.isEqualTo(new GerritChangeUrl("https://g.example.com", null, 42, null));
		assertThat(GerritChangeUrl.parse("https://g.example.com/#/c/42/"))
			.isEqualTo(new GerritChangeUrl("https://g.example.com", null, 42, null));
		assertThat(GerritChangeUrl.parse("https://g.example.com/42"))
			.isEqualTo(new GerritChangeUrl("https://g.example.com", null, 42, null));
		assertThat(GerritChangeUrl.parse("https://g.example.com/42").restId()).isEqualTo("42");
	}

	@Test
	void rejectsNonGerritLinks() {
		assertThatThrownBy(() -> GerritChangeUrl.parse("not a url")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> GerritChangeUrl.parse("ftp://g.example.com/c/42")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> GerritChangeUrl.parse("https://jira.example.com/browse/ABC-1"))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
