export type Project = {
  id: string
  name: string
  canonicalPath: string
  verificationCommands: string[]
  createdAt: string
}

export type Experiment = {
  id: string
  projectId: string
  sessionId: string
  task: string
  status: string
  baseSnapshotId: string | null
  resultSnapshotId: string | null
  workspacePath: string | null
  agentSummary: string | null
  failureReason: string | null
  createdAt: string
  version: number
}

export type Session = {
  id: string
  projectId: string
  title: string
  createdAt: string
}

export type Evidence = {
  id: string
  experimentId: string
  snapshotId: string
  kind: string
  command: string
  cwd: string
  exitCode: number
  stdout: string
  stderr: string
  startedAt: string
  completedAt: string
  durationMillis: number
  timedOut: boolean
  trusted: boolean
  environmentProfile: string
  cancelled: boolean
}

export type DiffEntry = {
  path: string
  change: 'ADDED' | 'MODIFIED' | 'DELETED'
  beforeBytes: number
  afterBytes: number
  binary: boolean
  additions: number
  deletions: number
  patch: string
}

export type PromotionOutcome = {
  promoted: boolean
  status: string
  detail: string
  changedFiles: string[]
  fingerprint: string | null
}

export type PromotionPreview = {
  baseFingerprint: string | null
  currentFingerprint: string | null
  finalCandidateFingerprint: string | null
  verificationStatus: 'NOT_RUN' | 'PASSED' | 'FAILED'
  trustedVerification: boolean
  conflict: boolean
  blockingReason: string | null
  promotable: boolean
}

export type PromotionReconcile = {
  promotionId: string
  experimentStatus: string
  journalPhase: string
  fingerprint: string | null
  detail: string
}

export type RunEvent = {
  eventId: string
  experimentId: string
  sequence: number
  type: string
  timestamp: string
  payload: Record<string, unknown>
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly code: string,
    readonly traceId: string | null,
    readonly details: Record<string, unknown>,
    readonly status: number,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
    ...init,
  })
  if (!response.ok) {
    const detail = await response.json().catch(() => ({ detail: response.statusText })) as {
      detail?: string
      message?: string
      code?: string
      traceId?: string
      details?: Record<string, unknown>
    }
    throw new ApiError(
      detail.detail ?? detail.message ?? response.statusText,
      detail.code ?? 'HTTP_ERROR',
      detail.traceId ?? null,
      detail.details ?? {},
      response.status,
    )
  }
  return response.json() as Promise<T>
}

export const api = {
  projects: () => request<Project[]>('/api/projects'),
  createProject: (body: { name: string; canonicalPath: string; verificationCommands: string[] }) =>
    request<Project>('/api/projects', { method: 'POST', body: JSON.stringify(body) }),
  experiments: (projectId: string) => request<Experiment[]>(`/api/projects/${projectId}/experiments`),
  sessions: (projectId: string) => request<Session[]>(`/api/projects/${projectId}/sessions`),
  createSession: (projectId: string, title: string) =>
    request<Session>(`/api/projects/${projectId}/sessions`, { method: 'POST', body: JSON.stringify({ title }) }),
  createExperiment: (projectId: string, body: { sessionId?: string | null; sessionTitle?: string; task: string }) =>
    request<Experiment>(`/api/projects/${projectId}/experiments`, { method: 'POST', body: JSON.stringify(body) }),
  startExperiment: (experimentId: string) =>
    request<Experiment>(`/api/experiments/${experimentId}/start`, { method: 'POST' }),
  cancelExperiment: (experimentId: string) =>
    request<Experiment>(`/api/experiments/${experimentId}/cancel`, { method: 'POST' }),
  promoteExperiment: (experimentId: string) =>
    request<PromotionOutcome>(`/api/experiments/${experimentId}/promote`, { method: 'POST' }),
  promotionPreview: (experimentId: string) =>
    request<PromotionPreview>(`/api/experiments/${experimentId}/promotion-preview`),
  reconcilePromotion: (experimentId: string) =>
    request<PromotionReconcile>(`/api/experiments/${experimentId}/promotion-reconcile`, { method: 'POST' }),
  evidence: (experimentId: string) => request<Evidence[]>(`/api/experiments/${experimentId}/evidence`),
  diff: (experimentId: string) => request<DiffEntry[]>(`/api/experiments/${experimentId}/diff`),
}
