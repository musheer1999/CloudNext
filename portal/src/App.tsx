import { useCallback, useEffect, useState } from 'react'
import { listRuns, type Run } from './api'
import { RunView } from './RunView'
import { StatusBadge } from './StatusBadge'
import { SubmitForm } from './SubmitForm'

/** The selected run lives in the URL (#/runs/12) so a refresh or a shared link keeps it. */
function selectedFromHash(): number | null {
  const m = window.location.hash.match(/^#\/runs\/(\d+)/)
  return m ? Number(m[1]) : null
}

export default function App() {
  const [runs, setRuns] = useState<Run[]>([])
  const [selected, setSelected] = useState<number | null>(selectedFromHash)
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
    const onHash = () => setSelected(selectedFromHash())
    window.addEventListener('hashchange', onHash)
    return () => {
      clearInterval(timer)
      window.removeEventListener('hashchange', onHash)
    }
  }, [refresh])

  const open = (id: number) => {
    window.location.hash = `#/runs/${id}`
  }

  return (
    <div className="layout">
      <aside className="sidebar">
        <h1 className="brand">FailureForge</h1>
        <p className="tagline">Phase 1: run a Gerrit change's existing tests</p>
        <SubmitForm
          onSubmitted={(id) => {
            refresh()
            open(id)
          }}
        />
        <h2 className="section-title">Recent runs</h2>
        {loadError && <p className="error">Can't reach the API: {loadError}</p>}
        <ul className="run-list">
          {runs.map((run) => (
            <li key={run.id}>
              <button className={`run-item ${run.id === selected ? 'active' : ''}`} onClick={() => open(run.id)}>
                <span className="run-item-top">
                  <span className="run-id">#{run.id}</span>
                  <StatusBadge run={run} />
                </span>
                <span className="run-item-title">{run.subject ?? run.gerritUrl}</span>
              </button>
            </li>
          ))}
          {runs.length === 0 && !loadError && <li className="muted">No runs yet.</li>}
        </ul>
      </aside>
      <main className="main">
        {selected ? (
          <RunView key={selected} id={selected} onChange={refresh} />
        ) : (
          <div className="empty">Submit a Gerrit change link to start a run.</div>
        )}
      </main>
    </div>
  )
}
