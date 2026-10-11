import type { Specialist, Verdict } from './api'

/** How each parent-vs-patch verdict is shown in the portal. */
export const VERDICT_LABEL: Record<Verdict, { label: string; tone: string; help: string }> = {
  COUNTEREXAMPLE: { label: 'BROKEN BY CHANGE', tone: 'bad', help: 'Passes before the change, fails after it' },
  PRE_EXISTING: { label: 'ALREADY FAILING', tone: 'warn', help: 'Fails before and after: an older bug, or the generated test is wrong' },
  FLAKY: { label: 'FLAKY', tone: 'warn', help: 'Different results on re-runs: never reported as a bug' },
  FIXED: { label: 'FIXED BY CHANGE', tone: 'good', help: 'Failed before the change, passes after it' },
  NEW_TEST: { label: 'NEW TEST', tone: 'info', help: 'Added by this change; only the patch result is known' },
  REMOVED: { label: 'REMOVED', tone: 'neutral', help: 'Existed before the change, gone after it' },
  COULDNT_CHECK: { label: "COULDN'T CHECK", tone: 'neutral', help: 'Skipped, or one side did not build' },
  NOTHING_FOUND: { label: 'HOLDS', tone: 'good', help: 'Passes before and after: the rule holds' },
}

/** The four specialists. Colors are categorical slots 1-4 (validated); the name is always shown next to the dot. */
export const SPECIALIST: Record<Specialist, { label: string; attacks: string }> = {
  BOUNDARY: { label: 'Boundary', attacks: 'limits and edge values' },
  STATE: { label: 'State', attacks: 'unexpected orders of operations' },
  CONCURRENCY: { label: 'Concurrency', attacks: 'calls from many threads at once' },
  COMPAT: { label: 'Compat', attacks: 'breaking existing callers (japicmp)' },
}

/** Rule type from the LLM -> specialist, same mapping as the server's Specialist.forRuleType. */
export function specialistForType(type: string): Specialist {
  const t = type.toLowerCase()
  if (t.includes('concurren') || t.includes('thread') || t.includes('race')) return 'CONCURRENCY'
  if (t.includes('state') || t.includes('sequence') || t.includes('order')) return 'STATE'
  if (t.includes('compat') || t.includes('api')) return 'COMPAT'
  return 'BOUNDARY'
}
