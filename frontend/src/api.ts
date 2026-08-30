import { getAuthToken, notifyUnauthorized } from './authToken'

export type Project = {
  id: string
  name: string
  canonicalPath: string
  verificationCommands: string[]
  createdAt: string
}

export type ProjectRegistration = Project & {
  reopened: boolean
}

export type DirectoryEntry = {
  name: string
  path: string
}

export type DirectoryLocation = {
  kind: 'HOME' | 'WORKING_DIRECTORY' | 'FILESYSTEM_ROOT'
  path: string
}

export type DirectoryBrowse = {
  path: string
  parent: string | null
  entries: DirectoryEntry[]
  truncated: boolean
  gitRoot: string | null
  suggestedName: string | null
  suggestedVerificationCommands: string[]
  locations: DirectoryLocation[]
}

export type Experiment = {
  id: string
  projectId: string
  sessionId: string
  continuedFromExperimentId: string | null
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

export type PromotionStaleConfirmation = {
  markedStale: boolean
  status: string
  detail: string
  currentFingerprint: string | null
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
  recoveryRequired: boolean
  recoveryJournalPhase: string | null
  recoveryPromotionId: string | null
}

export type PromotionReconcile = {
  promotionId: string
  experimentStatus: string
  journalPhase: string
  fingerprint: string | null
  detail: string
}

export type ProjectPromotionRecovery = {
  projectId: string
  recoveryRequired: boolean
  promotionId: string | null
  experimentId: string | null
  journalPhase: string | null
  failureReason: string | null
  leaseUntil: string | null
  unresolvedCount: number
}

export type RunEvent = {
  eventId: string
  experimentId: string
  sequence: number
  type: string
  timestamp: string
  payload: Record<string, unknown>
}

export type AuthUser = {
  id: string
  username: string
  createdAt: string
}

export type AuthResponse = {
  token: string
  expiresAt: string
  user: AuthUser
}

export type UserSettings = {
  userId: string
  theme: 'system' | 'light' | 'dark'
  locale: 'zh-CN' | 'en-US'
  modelEndpoint: string
  modelName: string
  modelApiKeyConfigured: boolean
  agentMaxSteps: number
  agentRunTimeoutSeconds: number
  contextLimitChars: number
  updatedAt: string
  version: number
}

export type ModelConfigurationStatus = {
  apiKeyConfigured: boolean
  endpointConfigured: boolean
  modelConfigured: boolean
  endpointValid: boolean
  endpoint: string | null
  model: string | null
}

export type ModelTestResponse = {
  reachable: boolean
  code: string
  detail: string
}

export type RuntimeSettingsPolicy = {
  defaultMaxSteps: number
  defaultRunTimeoutSeconds: number
  defaultContextLimitChars: number
  maxStepsCeiling: number
  runTimeoutSecondsCeiling: number
  contextLimitCharsCeiling: number
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
  const token = getAuthToken()
  const headers = new Headers(init?.headers)
  if (!headers.has('Content-Type') && init?.body) headers.set('Content-Type', 'application/json')
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const response = await fetch(url, { ...init, headers })
  if (!response.ok) {
    if (response.status === 401) notifyUnauthorized(token)
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
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export const api = {
  register: (body: { username: string; password: string }) =>
    request<AuthResponse>('/api/auth/register', { method: 'POST', body: JSON.stringify(body) }),
  login: (body: { username: string; password: string }) =>
    request<AuthResponse>('/api/auth/login', { method: 'POST', body: JSON.stringify(body) }),
  me: () => request<AuthUser>('/api/auth/me'),
  logout: () => request<void>('/api/auth/logout', { method: 'POST' }),
  settings: () => request<UserSettings>('/api/settings'),
  modelStatus: () => request<ModelConfigurationStatus>('/api/settings/model-status'),
  runtimePolicy: () => request<RuntimeSettingsPolicy>('/api/settings/runtime-policy'),
  updateSettings: (body: Omit<UserSettings, 'userId' | 'updatedAt' | 'version' | 'modelApiKeyConfigured'> & { modelApiKey?: string }) =>
    request<UserSettings>('/api/settings', { method: 'PUT', body: JSON.stringify(body) }),
  clearModelCredential: () =>
    request<UserSettings>('/api/settings/model-credential', { method: 'DELETE' }),
  testModel: (body: { modelEndpoint: string; modelName: string; apiKey?: string }) =>
    request<ModelTestResponse>('/api/settings/model-test', { method: 'POST', body: JSON.stringify(body) }),
  projects: () => request<Project[]>('/api/projects'),
  browseDirectories: (path?: string) => {
    const query = path ? `?path=${encodeURIComponent(path)}` : ''
    return request<DirectoryBrowse>(`/api/local-directories${query}`)
  },
  createProject: (body: { name: string; canonicalPath: string; verificationCommands: string[] }) =>
    request<ProjectRegistration>('/api/projects', { method: 'POST', body: JSON.stringify(body) }),
  updateProject: (projectId: string, body: { name: string; canonicalPath: string; verificationCommands: string[] }) =>
    request<Project>(`/api/projects/${projectId}`, { method: 'PUT', body: JSON.stringify(body) }),
  experiments: (projectId: string) => request<Experiment[]>(`/api/projects/${projectId}/experiments`),
  sessions: (projectId: string) => request<Session[]>(`/api/projects/${projectId}/sessions`),
  createSession: (projectId: string, title: string) =>
    request<Session>(`/api/projects/${projectId}/sessions`, { method: 'POST', body: JSON.stringify({ title }) }),
  createExperiment: (projectId: string, body: { sessionId?: string | null; sessionTitle?: string; task: string }) =>
    request<Experiment>(`/api/projects/${projectId}/experiments`, { method: 'POST', body: JSON.stringify(body) }),
  continueExperiment: (experimentId: string, task: string) =>
    request<Experiment>(`/api/experiments/${experimentId}/continue`, {
      method: 'POST',
      body: JSON.stringify({ task }),
    }),
  startExperiment: (experimentId: string) =>
    request<Experiment>(`/api/experiments/${experimentId}/start`, { method: 'POST' }),
  cancelExperiment: (experimentId: string) =>
    request<Experiment>(`/api/experiments/${experimentId}/cancel`, { method: 'POST' }),
  promoteExperiment: (experimentId: string) =>
    request<PromotionOutcome>(`/api/experiments/${experimentId}/promote`, { method: 'POST' }),
  confirmExperimentStale: (experimentId: string) =>
    request<PromotionStaleConfirmation>(`/api/experiments/${experimentId}/stale-confirmation`, { method: 'POST' }),
  promotionPreview: (experimentId: string) =>
    request<PromotionPreview>(`/api/experiments/${experimentId}/promotion-preview`),
  reconcilePromotion: (experimentId: string) =>
    request<PromotionReconcile>(`/api/experiments/${experimentId}/promotion-reconcile`, { method: 'POST' }),
  promotionRecovery: (projectId: string) =>
    request<ProjectPromotionRecovery>(`/api/projects/${projectId}/promotion-recovery`),
  reconcileProjectPromotion: (projectId: string) =>
    request<PromotionReconcile>(`/api/projects/${projectId}/promotion-reconcile`, { method: 'POST' }),
  evidence: (experimentId: string) => request<Evidence[]>(`/api/experiments/${experimentId}/evidence`),
  diff: (experimentId: string) => request<DiffEntry[]>(`/api/experiments/${experimentId}/diff`),
}
