export type StatusTone = 'neutral' | 'experiment' | 'active' | 'success' | 'warning' | 'danger'

const ZH_STATUS_LABELS: Record<string, string> = {
  READY_TO_RUN: '待运行',
  RUNNING: '运行中',
  AGENT_COMPLETED: '代理完成',
  VERIFYING: '验证中',
  VERIFIED: '已验证',
  PREPARING_PROMOTION: '准备提升',
  PROMOTION_VERIFICATION_STARTED: '提升验证中',
  PROMOTING: '提升中',
  PROMOTED: '已提升',
  FAILED: '失败',
  REJECTED: '已拒绝',
  CANCELLED: '已取消',
  STALE: '已过期',
  RECOVERY_REQUIRED: '需要恢复',
}

export function statusLabel(status: string, locale?: string) {
  const resolvedLocale = locale ?? (typeof document === 'undefined' ? 'en-US' : document.documentElement.lang)
  return resolvedLocale === 'zh-CN' ? ZH_STATUS_LABELS[status] ?? status.replaceAll('_', ' ') : status.replaceAll('_', ' ')
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
