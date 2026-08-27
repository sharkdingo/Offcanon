export type StatusTone = 'neutral' | 'experiment' | 'active' | 'success' | 'warning' | 'danger'

export function statusLabel(status: string) {
  return status.replaceAll('_', ' ')
}

export function statusTone(status: string): StatusTone {
  if (status === 'VERIFIED' || status === 'PROMOTED') return 'success'
  if (status === 'RECOVERY_REQUIRED' || status === 'STALE') return 'warning'
  if (status === 'FAILED' || status === 'REJECTED' || status === 'CANCELLED') return 'danger'
  if (['RUNNING', 'VERIFYING', 'PREPARING_PROMOTION', 'PROMOTING'].includes(status)) return 'active'
  if (['READY_TO_RUN', 'AGENT_COMPLETED'].includes(status)) return 'experiment'
  return 'neutral'
}

export function shortId(value: string | null | undefined, length = 8) {
  return value ? value.slice(0, length).toUpperCase() : 'PENDING'
}

export function shortFingerprint(value: string | null | undefined) {
  return value ? value.slice(0, 12) : 'not available'
}

export function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

export function formatDuration(milliseconds: number) {
  if (milliseconds < 1000) return `${milliseconds} ms`
  return `${(milliseconds / 1000).toFixed(milliseconds < 10_000 ? 1 : 0)} s`
}
