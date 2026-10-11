import { useCallback, useEffect, useState } from 'react'
import { getBenchmark, startBenchmark, type BenchmarkSummary, type Specialist } from './api'
import { SpecialistChip } from './SpecialistChip'

/** Planted-bug changes (one per specialist) + a correct change: the pitch numbers (design doc "benchmark"). */
export function BenchmarkView() {
  const [summary, setSummary] = useState<BenchmarkSummary | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(() => {
    getBenchmark()
      .then((s) => {
        setSummary(s)
        setError(null)
      })
      .catch((e: Error) => setError(e.message))
  }, [])

  useEffect(() => {
    load()
    const timer = setInterval(load, 5000)
    return () => clearInterval(timer)
  }, [load])

  async function start() {
    setBusy(true)
    try {
      setSummary(await startBenchmark())
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const running = summary !== null && summary.batch !== null && summary.finished < summary.cases.length
  return (
    <div className="run-view">
      <header className="run-header">
        <div className="run-title">
          <h2>Benchmark</h2>
          {running && <span className="badge badge-info">RUNNING</span>}
        </div>
        <p className="run-subject">
          Changes with a planted bug, one per specialist, plus one correct change. Does FailureForge catch the bugs,
          and does it stay quiet on the correct change?
        </p>
        <div className="row">
          <button className="primary" disabled={busy || running} onClick={start}>
            {running ? 'Benchmark running…' : summary?.batch ? 'Run the benchmark again' : 'Run the benchmark'}
          </button>
          {summary?.batch && (
            <span className="muted">Last started {new Date(summary.batch).toLocaleString()}</span>
          )}
        </div>
      </header>
      {error && <p className="error">{error}</p>}

      {summary?.batch && (
        <>
          <div className="stats hero-stats">
            <div className="stat stat-big">
              <span className="stat-value">
                {summary.caught}
                <span className="stat-of"> / {summary.bugs}</span>
              </span>
              <span className="stat-label">planted bugs caught</span>
            </div>
            <div className="stat stat-big">
              <span className="stat-value">{summary.falsePositives}</span>
              <span className="stat-label">false positives</span>
            </div>
            <div className="stat stat-big">
              <span className="stat-value">{summary.avgSeconds !== null ? formatTime(summary.avgSeconds) : '—'}</span>
              <span className="stat-label">average time per run</span>
            </div>
            <div className="stat stat-big">
              <span className="stat-value">{summary.totalTokens.toLocaleString()}</span>
              <span className="stat-label">LLM tokens in total</span>
            </div>
          </div>
          <p className="muted">
            {summary.finished} of {summary.cases.length} runs finished. Model: local Qwen3-8B on a laptop (the office
            GPU model will be faster).
          </p>

          <div className="table-wrap">
            <table className="tests">
              <thead>
                <tr>
                  <th>Change</th>
                  <th>Should be caught by</th>
                  <th>Found by</th>
                  <th>Result</th>
                  <th className="num">Time</th>
                </tr>
              </thead>
              <tbody>
                {summary.cases.map((c) => (
                  <tr key={c.name}>
                    <td>
                      <div className="test-name">
                        {c.runId ? <a href={`#/runs/${c.runId}`}>{c.name}</a> : c.name}
                      </div>
                      <div className="test-class">{c.plantedBug}</div>
                    </td>
                    <td>
                      {c.expected === 'NONE' ? (
                        <span className="muted">nobody (no bug)</span>
                      ) : (
                        <SpecialistChip specialist={c.expected as Specialist} />
                      )}
                    </td>
                    <td>
                      {c.foundBy.length ? (
                        <div className="chips">
                          {c.foundBy.map((s) => (
                            <SpecialistChip key={s} specialist={s as Specialist} />
                          ))}
                        </div>
                      ) : (
                        <span className="muted">—</span>
                      )}
                    </td>
                    <td>
                      <CaseResult status={c.status} caught={c.caught} expected={c.expected} falsePositives={c.falsePositives} />
                    </td>
                    <td className="num">{c.seconds !== null ? formatTime(c.seconds) : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  )
}

function CaseResult(props: { status: string; caught: boolean; expected: string; falsePositives: number }) {
  if (props.status !== 'SUCCEEDED' && props.status !== 'FAILED') {
    return <span className="badge badge-info">{props.status.replace('_', ' ')}</span>
  }
  if (props.expected === 'NONE') {
    return props.caught ? (
      <span className="badge badge-good">✓ QUIET (CORRECT)</span>
    ) : (
      <span className="badge badge-bad">✗ FALSE ALARM</span>
    )
  }
  return (
    <span className="chips">
      {props.caught ? <span className="badge badge-good">✓ CAUGHT</span> : <span className="badge badge-bad">✗ MISSED</span>}
      {props.falsePositives > 0 && <span className="badge badge-warn">+{props.falsePositives} OTHER</span>}
    </span>
  )
}

function formatTime(seconds: number) {
  return `${Math.floor(seconds / 60)}m ${String(seconds % 60).padStart(2, '0')}s`
}
