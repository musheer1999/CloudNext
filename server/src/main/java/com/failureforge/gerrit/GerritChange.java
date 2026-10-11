package com.failureforge.gerrit;

/**
 * What the worker needs to know about one patchset of a Gerrit change.
 *
 * @param ref      git ref holding the patchset, e.g. {@code refs/changes/99/1251299/13}
 * @param fetchUrl git URL to fetch that ref from
 */
public record GerritChange(String project, int change, int patchset, String patchSha, String parentSha, String ref,
		String fetchUrl, String subject) {
}
