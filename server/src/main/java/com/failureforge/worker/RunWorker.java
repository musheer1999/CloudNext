package com.failureforge.worker;

import java.util.Optional;

import com.failureforge.run.Run;
import com.failureforge.run.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background worker: polls the Postgres queue and runs one job at a time (D-019).
 * Several worker processes could poll the same table safely thanks to SKIP LOCKED.
 */
@Component
public class RunWorker {

	private static final Logger log = LoggerFactory.getLogger(RunWorker.class);

	private final RunRepository runs;
	private final RunPipeline pipeline;
	private volatile boolean ready;

	public RunWorker(RunRepository runs, RunPipeline pipeline) {
		this.runs = runs;
		this.pipeline = pipeline;
	}

	/** On startup, runs a previous worker left half-done can never finish: mark them failed. */
	@EventListener(ApplicationReadyEvent.class)
	public void recoverAbandonedRuns() {
		int count = runs.failAbandonedRuns();
		if (count > 0) {
			log.warn("Marked {} abandoned run(s) as FAILED", count);
		}
		ready = true;
	}

	@Scheduled(fixedDelayString = "${failureforge.worker.poll-interval-ms}")
	public void poll() {
		if (!ready) {
			return;
		}
		Optional<Run> next;
		while ((next = runs.claimNext()).isPresent()) {
			log.info("Picked up run {}", next.get().id());
			pipeline.execute(next.get());
		}
	}
}
