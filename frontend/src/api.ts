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
  workspacePath: string | null
  agentSummary: string | null
  failureReason: string | null
  createdAt: string
  version: number
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
    ...init,
  })
  if (!response.ok) {
    const detail = await response.json().catch(() => ({ detail: response.statusText }))
    throw new Error(detail.detail ?? detail.message ?? response.statusText)
  }
  return response.json() as Promise<T>
}

export const api = {
  projects: () => request<Project[]>('/api/projects'),
  createProject: (body: { name: string; canonicalPath: string; verificationCommands: string[] }) =>
    request<Project>('/api/projects', { method: 'POST', body: JSON.stringify(body) }),
  experiments: (projectId: string) => request<Experiment[]>(`/api/projects/${projectId}/experiments`),
  createExperiment: (projectId: string, body: { sessionTitle: string; task: string }) =>
    request<Experiment>(`/api/projects/${projectId}/experiments`, { method: 'POST', body: JSON.stringify(body) }),
  cancelExperiment: (experimentId: string) =>
    request<Experiment>(`/api/experiments/${experimentId}/cancel`, { method: 'POST' }),
}
