package com.failureforge.run;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live progress for the portal over Server-Sent Events (SSE).
 * <p>
 * Every log line is saved to the database first, then pushed to any browser watching that run.
 * A browser that connects late gets the saved lines replayed, then live ones.
 * <p>
 * Limitation (fine for Phase 1, D-018): subscribers live in this process's memory, so the worker
 * must run in the same process as the API. Splitting them later means Postgres LISTEN/NOTIFY here.
 */
@Component
public class RunEvents {

	private static final Logger log = LoggerFactory.getLogger(RunEvents.class);
	private static final long NO_TIMEOUT = 0L;

	private final RunRepository runs;
	private final Map<Long, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

	public RunEvents(RunRepository runs) {
		this.runs = runs;
	}

	/** Saves a log line for the run and pushes it to watchers. */
	public void log(long runId, String line) {
		long id = runs.appendLog(runId, line);
		send(runId, "log", String.valueOf(id), new LogEvent(id, line));
	}

	/** Tells watchers the run's status/step changed; the portal then re-fetches the run. */
	public void statusChanged(long runId) {
		runs.find(runId).ifPresent(run -> {
			send(runId, "status", null, run);
			if (run.status() == RunStatus.SUCCEEDED || run.status() == RunStatus.FAILED) {
				closeAll(runId);
			}
		});
	}

	public SseEmitter subscribe(long runId) {
		SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
		// Register first, then replay: a line logged in between may arrive twice, and the
		// portal drops duplicates by id. The other order could lose lines.
		List<SseEmitter> list = subscribers.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>());
		list.add(emitter);
		Runnable remove = () -> list.remove(emitter);
		emitter.onCompletion(remove);
		emitter.onTimeout(remove);
		emitter.onError(e -> remove.run());

		try {
			for (RunRepository.LogLine line : runs.findLogs(runId)) {
				emitter.send(SseEmitter.event().name("log").id(String.valueOf(line.id()))
					.data(new LogEvent(line.id(), line.line()), MediaType.APPLICATION_JSON));
			}
			Run run = runs.find(runId).orElseThrow();
			emitter.send(SseEmitter.event().name("status").data(run, MediaType.APPLICATION_JSON));
			if (run.status() == RunStatus.SUCCEEDED || run.status() == RunStatus.FAILED) {
				emitter.complete();
			}
		}
		catch (IOException e) {
			emitter.completeWithError(e);
		}
		return emitter;
	}

	private void send(long runId, String name, String id, Object data) {
		for (SseEmitter emitter : subscribers.getOrDefault(runId, List.of())) {
			try {
				SseEmitter.SseEventBuilder event = SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON);
				if (id != null) {
					event.id(id);
				}
				emitter.send(event);
			}
			catch (IOException | IllegalStateException e) {
				// Browser went away; drop it.
				log.debug("Dropping SSE subscriber for run {}: {}", runId, e.getMessage());
				subscribers.getOrDefault(runId, List.of()).remove(emitter);
			}
		}
	}

	private void closeAll(long runId) {
		List<SseEmitter> list = subscribers.remove(runId);
		if (list != null) {
			list.forEach(SseEmitter::complete);
		}
	}

	public record LogEvent(long id, String line) {
	}
}
