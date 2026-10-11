import type { Specialist } from './api'
import { SPECIALIST } from './verdicts'

/** Identity = colored dot + name in normal text color (never color alone). */
export function SpecialistChip({ specialist }: { specialist: Specialist }) {
  const s = SPECIALIST[specialist]
  return (
    <span className="spec-chip" title={`${s.label} specialist: ${s.attacks}`}>
      <span className={`spec-dot spec-${specialist.toLowerCase()}`} aria-hidden="true" />
      {s.label}
    </span>
  )
}
