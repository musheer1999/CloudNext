import { useCallback, useEffect, useState } from 'react'
import { listRuns, type Run } from './api'
import { BenchmarkView } from './BenchmarkView'
import { RunView } from './RunView'
import { StatusBadge } from './StatusBadge'
import { SubmitForm } from './SubmitForm'

/** The page lives in the URL (#/runs/12, #/benchmark) so a refresh or a shared link keeps it. */
type Route = { page: 'runs'; id: number | null } | { page: 'benchmark' }

function routeFromHash(): Route {
  if (window.location.hash.startsWith('#/benchmark')) return { page: 'benchmark' }
  const m = window.location.hash.match(/^#\/runs\/(\d+)/)
  return { page: 'runs', id: m ? Number(m[1]) : null }
}

export default function App() {
  const [runs, setRuns] = useState<Run[]>([])
  const [route, setRoute] = useState<Route>(routeFromHash)
  const [loadError, setLoadError] = useState<string | null>(null)

  const refresh = useCallback(() => {
    listRuns()
      .then((r) => {
        setRuns(r)
        setLoadError(null)
      })
      .catch((e: Error) => setLoadError(e.message))
  }, [])

  useEffect(() => {
    refresh()
    const timer = setInterval(refresh, 5000) // keeps statuses in the list fresh
    const onHash = () => setRoute(routeFromHash())
    window.addEventListener('hashchange', onHash)
    return () => {
      clearInterval(timer)
      window.removeEventListener('hashchange', onHash)
    }
  }, [refresh])

  const selected = route.page === 'runs' ? route.id : null

  return (
    <div className="app">
      <header className="topbar">
        <a className="brand" href="#/">
          <span className="brand-mark" aria-hidden="true">
            ⚒
          </span>
          FailureForge
        </a>
        <span className="tagline">Tries to break a code change before production does</span>
        <nav className="nav">
          <a className={route.page === 'runs' ? 'active' : ''} href={selected ? `#/runs/${selected}` : '#/'}>
            Runs
          </a>
          <a className={route.page === 'benchmark' ? 'active' : ''} href="#/benchmark">
            Benchmark
          </a>
        </nav>
      </header>
      <div className="layout">
        <aside className="sidebar">
          <h2 className="section-title">New run</h2>
          <SubmitForm
            onSubmitted={(id) => {
              refresh()
              window.location.hash = `#/runs/${id}`
            }}
          />
          <h2 className="section-title">Recent runs</h2>
          {loadError && <p className="error">Can't reach the API: {loadError}</p>}
          <ul className="run-list">
            {runs.map((run) => (
              <li key={run.id}>
                <a className={`run-item ${run.id === selected ? 'active' : ''}`} href={`#/runs/${run.id}`}>
                  <span className="run-item-top">
                    <span className="run-id">#{run.id}</span>
                    <StatusBadge run={run} />
                  </span>
                  <span className="run-item-title">{run.subject ?? run.gerritUrl}</span>
                </a>
              </li>
            ))}
            {runs.length === 0 && !loadError && <li className="muted">No runs yet.</li>}
          </ul>
        </aside>
        <main className="main">
          {route.page === 'benchmark' ? (
            <BenchmarkView />
          ) : selected ? (
            <RunView key={selected} id={selected} onChange={refresh} />
          ) : (
            <Welcome />
          )}
        </main>
      </div>
    </div>
  )
}

function Welcome() {
  return (
    <div className="welcome">
      <h2>Break the change before production does.</h2>
      <p>
        Paste a Gerrit change and its ticket. FailureForge works out the rules the change must guarantee, has four
        specialists attack them with real tests in a sealed Docker sandbox, and runs every test on the code before
        and after the change. Plain code, not the AI, decides whether the change broke something.
      </p>
      <ol className="welcome-steps">
        <li>
          <strong>Rules</strong> from the ticket, each quoting its source
        </li>
        <li>
          <strong>Attacks</strong> by the Boundary, State, Concurrency and Compat specialists
        </li>
        <li>
          <strong>Verdict</strong>: passes before, fails after = broken by the change
        </li>
      </ol>
    </div>
  )
}
