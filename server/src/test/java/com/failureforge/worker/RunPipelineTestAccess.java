package com.failureforge.worker;

import java.util.List;

import com.failureforge.agent.Rule;

/** Lets tests in other packages reach RunPipeline's package-private target picking. */
public final class RunPipelineTestAccess {

	private RunPipelineTestAccess() {
	}

	public static List<Integer> pickTargets(List<Rule> rules, int max) {
		return RunPipeline.pickTargets(rules, max);
	}
}
