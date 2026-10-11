import type { Run } from './api'

/** The pipeline as a row of steps; the server's run.step says where a running run is. */
const STEPS: { key: string; label: string }[] = [
  { key: 'RESOLVING', label: 'Resolve change' },
  { key: 'CLONING', label: 'Checkout' },
  { key: 'TESTING_PATCH', label: 'Tests: patch' },
  { key: 'TESTING_PARENT', label: 'Tests: parent' },
  { key: 'COMPAT', label: 'Compat' },
  { key: 'RULES', label: 'Rules (AI)' },
  { key: 'ATTACKING', label: 'Attacks' },
  { key: 'DONE', label: 'Verdict' },
]

export function PipelineSteps({ run }: { run: Run }) {
  const current =
    run.status === 'SUCCEEDED' ? STEPS.length - 1 : run.status === 'QUEUED' ? -1 : STEPS.findIndex((s) => s.key === run.step)
  return (
    <ol className="steps" aria-label="Pipeline progress">
      {STEPS.map((s, i) => {
        let state = 'todo'
        if (run.status === 'SUCCEEDED' || i < current) state = 'done'
        else if (i === current) state = run.status === 'FAILED' ? 'failed' : 'active'
        if (run.status === 'FAILED' && current === -1 && i === 0) state = 'failed'
        return (
          <li key={s.key} className={`step step-${state}`}>
            <span className="step-dot" aria-hidden="true">
              {state === 'done' ? '✓' : state === 'failed' ? '!' : i + 1}
            </span>
            <span className="step-label">{s.label}</span>
          </li>
        )
      })}
    </ol>
  )
}
