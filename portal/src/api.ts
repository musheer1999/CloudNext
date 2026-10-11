// Types and calls for the FailureForge REST API (server/src/main/java/com/failureforge/run/RunController.java).

export type RunStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export interface Run {
  id: number
  gerritUrl: string
  jiraUrl: string | null
  wikiUrls: string[]
  ticketText: string | null
  constraints: string | null
  disputedRunId: number | null
  gerritPostedAt: string | null
  tokensUsed: number
  gerritProject: string | null
  gerritChange: number | null
  patchset: number | null
  patchSha: string | null
  parentSha: string | null
  subject: string | null
  status: RunStatus
  step: string | null
  error: string | null
  testsTotal: number | null
  testsFailed: number | null
  testsErrors: number | null
  testsSkipped: number | null
  counterexamples: number | null
  fixed: number | null
  preExisting: number | null
  parentBuildExit: number | null
  patchBuildExit: number | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

export interface TestCase {
  className: string
  name: string
  status: 'PASSED' | 'FAILED' | 'ERROR' | 'SKIPPED' | 'FLAKY'
  durationMs: number
  message: string | null
}

export type TestStatus = TestCase['status']

export type Verdict =
  | 'COUNTEREXAMPLE'
  | 'PRE_EXISTING'
  | 'FLAKY'
  | 'FIXED'
  | 'NOTHING_FOUND'
  | 'NEW_TEST'
  | 'REMOVED'
  | 'COULDNT_CHECK'

/** One test compared on the parent commit and on the patch (server: verdict/Comparison.java). */
export interface Comparison {
  className: string
  name: string
  parentStatus: TestStatus | null
  patchStatus: TestStatus | null
  verdict: Verdict
  message: string | null
}

/** A rule the AI derived, with the attack on it if there was one (server: RunRepository.RuleView). */
export interface RuleView {
  key: string
  text: string
  type: string
  sourceQuote: string | null
  source: string | null
  codeLocation: string | null
  status: 'KEPT' | 'DROPPED'
  dropReason: string | null
  specialist: Specialist | null
  testClass: string | null
  testCode: string | null
  compileAttempts: number | null
  parentStatus: TestStatus | null
  patchStatus: TestStatus | null
  verdict: Verdict | null
  minimalInput: string | null
  attackStatus: 'DONE' | 'COULDNT_COMPILE' | null
}

export type Specialist = 'BOUNDARY' | 'STATE' | 'CONCURRENCY' | 'COMPAT'

export interface RunDetail {
  run: Run
  tests: TestCase[]
  comparisons: Comparison[]
  rules: RuleView[]
  feedback: Feedback[]
}

export interface LogEvent {
  id: number
  line: string
}

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = await res.json().catch(() => null)
    throw new Error(body?.detail ?? body?.message ?? `HTTP ${res.status}`)
  }
  return res.json() as Promise<T>
}

export async function submitRun(
  gerritUrl: string,
  jiraUrl: string,
  wikiUrls: string[],
  ticketText: string,
): Promise<number> {
  const res = await fetch('/api/runs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ gerritUrl, jiraUrl, wikiUrls, ticketText }),
  })
  return (await json<{ id: number }>(res)).id
}

export const listRuns = () => fetch('/api/runs').then((r) => json<Run[]>(r))

export const getRun = (id: number) => fetch(`/api/runs/${id}`).then((r) => json<RunDetail>(r))

/** Opens the live event stream for a run. Returns a function that closes it. */
export function watchRun(id: number, onLog: (e: LogEvent) => void, onStatus: (run: Run) => void): () => void {
  const source = new EventSource(`/api/runs/${id}/events`)
  source.addEventListener('log', (e) => onLog(JSON.parse((e as MessageEvent).data)))
  source.addEventListener('status', (e) => {
    const run: Run = JSON.parse((e as MessageEvent).data)
    onStatus(run)
    // Finished runs: the server closes the stream; stop the browser from reconnecting.
    if (run.status === 'SUCCEEDED' || run.status === 'FAILED') source.close()
  })
  return () => source.close()
}

export const isFinished = (run: Run) => run.status === 'SUCCEEDED' || run.status === 'FAILED'

export interface Feedback {
  id: number
  ruleKey: string
  ruleText: string
  type: 'ACCEPT' | 'DISPUTE' | 'FALSE_POSITIVE'
  text: string | null
  createdAt: string
}

/** Accept / Dispute / False Positive. A dispute returns the id of the re-run it started. */
export async function sendFeedback(
  runId: number,
  ruleKey: string,
  type: Feedback['type'],
  text: string,
): Promise<number | null> {
  const res = await fetch(`/api/runs/${runId}/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ruleKey, type, text }),
  })
  return (await json<{ rerunId: number | null }>(res)).rerunId
}

export const previewGerritReview = (runId: number) =>
  fetch(`/api/runs/${runId}/gerrit-review`).then((r) => json<{ message: string }>(r))

export const postGerritReview = (runId: number) =>
  fetch(`/api/runs/${runId}/gerrit-review`, { method: 'POST' }).then((r) => json<{ posted: string }>(r))

/** The plain text FailureForge reads from a JIRA issue or Confluence page. */
export const fetchTicket = (url: string) =>
  fetch(`/api/ingest?url=${encodeURIComponent(url)}`).then((r) => json<{ text: string }>(r))

export interface BenchmarkCase {
  name: string
  expected: string
  plantedBug: string
  runId: number | null
  status: string
  foundBy: string[]
  caught: boolean
  falsePositives: number
  seconds: number | null
  tokens: number
}

export interface BenchmarkSummary {
  batch: number | null
  cases: BenchmarkCase[]
  bugs: number
  caught: number
  falsePositives: number
  finished: number
  avgSeconds: number | null
  totalTokens: number
}

export const getBenchmark = () => fetch('/api/benchmark').then((r) => json<BenchmarkSummary>(r))

export const startBenchmark = () => fetch('/api/benchmark', { method: 'POST' }).then((r) => json<BenchmarkSummary>(r))
