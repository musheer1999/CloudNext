package com.failureforge.gerrit;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Gerrit change link pulled apart into its pieces.
 * <p>
 * Accepted shapes (with or without a context path like {@code /gerrit} before {@code /c/}):
 * <pre>
 * https://host/c/my/project/+/12345
 * https://host/c/my/project/+/12345/3        (patchset 3)
 * https://host/c/my/project/+/12345/3/a.java (file view, patchset 3)
 * https://host/c/12345
 * https://host/#/c/12345/                    (old Gerrit UI)
 * https://host/12345
 * </pre>
 *
 * @param baseUrl  Gerrit server root, e.g. {@code https://review.gerrithub.io}
 * @param project  project name, or {@code null} if the link doesn't contain it
 * @param change   change number
 * @param patchset patchset number, or {@code null} for "latest"
 */
public record GerritChangeUrl(String baseUrl, String project, int change, Integer patchset) {

	private static final Pattern AFTER_C = Pattern.compile("^(?:(.+?)/\\+/)?(\\d+)(?:/(\\d+))?(?:/.*)?$");
	private static final Pattern BARE_NUMBER = Pattern.compile("^(.*?)/(\\d+)/?$");

	public static GerritChangeUrl parse(String url) {
		URI uri;
		try {
			uri = URI.create(url.trim());
		}
		catch (IllegalArgumentException e) {
			throw invalid(url);
		}
		if (uri.getScheme() == null || uri.getHost() == null
				|| !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
			throw invalid(url);
		}
		String origin = uri.getScheme() + "://" + uri.getRawAuthority();
		String path = uri.getRawPath() == null ? "" : uri.getRawPath();
		if (uri.getRawFragment() != null && uri.getRawFragment().startsWith("/")) {
			path = stripTrailingSlash(path) + uri.getRawFragment(); // old UI: /#/c/12345/
		}

		int c = path.indexOf("/c/");
		if (c >= 0) {
			String contextPath = path.substring(0, c);
			Matcher m = AFTER_C.matcher(stripTrailingSlash(path.substring(c + 3)));
			if (m.matches()) {
				String project = m.group(1) == null ? null : decode(m.group(1));
				Integer patchset = m.group(3) == null ? null : Integer.valueOf(m.group(3));
				return new GerritChangeUrl(origin + contextPath, project, Integer.parseInt(m.group(2)), patchset);
			}
			throw invalid(url);
		}
		Matcher m = BARE_NUMBER.matcher(path);
		if (m.matches()) {
			return new GerritChangeUrl(origin + m.group(1), null, Integer.parseInt(m.group(2)), null);
		}
		throw invalid(url);
	}

	/** The id Gerrit's REST API expects: {@code project~number} (project URL-encoded), or just the number. */
	public String restId() {
		if (project == null) {
			return String.valueOf(change);
		}
		return URLEncoder.encode(project, StandardCharsets.UTF_8) + "~" + change;
	}

	private static String stripTrailingSlash(String s) {
		return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
	}

	private static String decode(String s) {
		return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
	}

	private static IllegalArgumentException invalid(String url) {
		return new IllegalArgumentException("Not a Gerrit change link: " + url
				+ " (expected something like https://gerrit.example.com/c/project/+/12345)");
	}
}
