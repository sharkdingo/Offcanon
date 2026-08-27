import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { api, type DiffEntry, type Evidence, type Experiment, type Project, type PromotionOutcome, type PromotionPreview, type Session } from '../api'

export const useWorkspaceStore = defineStore('workspace', () => {
  const projects = ref<Project[]>([])
  const experiments = ref<Experiment[]>([])
  const sessions = ref<Session[]>([])
  const selectedProjectId = ref<string | null>(null)
  const selectedSessionId = ref<string | null>(null)
  const selectedExperimentId = ref<string | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const evidence = ref<Evidence[]>([])
  const diff = ref<DiffEntry[]>([])
  const promotionOutcome = ref<PromotionOutcome | null>(null)
  const promotionPreview = ref<PromotionPreview | null>(null)
  let detailRequest = 0
  let projectRequest = 0

  const selectedProject = computed(() => projects.value.find((project) => project.id === selectedProjectId.value) ?? null)
  const selectedExperiment = computed(() => experiments.value.find((experiment) => experiment.id === selectedExperimentId.value) ?? null)
  const visibleExperiments = computed(() => selectedSessionId.value
    ? experiments.value.filter((experiment) => experiment.sessionId === selectedSessionId.value)
    : experiments.value)
  const sessionTitle = (sessionId: string) => sessions.value.find((session) => session.id === sessionId)?.title ?? sessionId.slice(0, 8)

  async function loadProjects() {
    loading.value = true
    error.value = null
    try {
      projects.value = await api.projects()
      if (!selectedProjectId.value && projects.value.length > 0) {
        await selectProject(projects.value[0].id)
      }
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : 'Unable to load projects'
    } finally {
      loading.value = false
    }
  }

  async function selectProject(projectId: string) {
    const requestId = ++projectRequest
    selectedProjectId.value = projectId
    selectedSessionId.value = null
    selectedExperimentId.value = null
    evidence.value = []
    diff.value = []
    promotionOutcome.value = null
    promotionPreview.value = null
    try {
      const [loadedSessions, loadedExperiments] = await Promise.all([
        api.sessions(projectId),
        api.experiments(projectId),
      ])
      if (requestId !== projectRequest || selectedProjectId.value !== projectId) return
      sessions.value = loadedSessions
      experiments.value = loadedExperiments
      selectedSessionId.value = null
      selectedExperimentId.value = loadedExperiments.at(-1)?.id ?? null
      if (selectedExperimentId.value) {
        await loadExperimentDetails(selectedExperimentId.value)
      }
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : 'Unable to load experiments'
    }
  }

  async function selectExperiment(experimentId: string) {
    selectedExperimentId.value = experimentId
    selectedSessionId.value = experiments.value.find((experiment) => experiment.id === experimentId)?.sessionId ?? selectedSessionId.value
    evidence.value = []
    diff.value = []
    promotionOutcome.value = null
    promotionPreview.value = null
    try {
      await loadExperimentDetails(experimentId)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : 'Unable to load experiment details'
    }
  }

  async function selectSession(sessionId: string | null) {
    selectedSessionId.value = sessionId
    promotionOutcome.value = null
    promotionPreview.value = null
    const candidate = visibleExperiments.value.at(-1)
    selectedExperimentId.value = candidate?.id ?? null
    evidence.value = []
    diff.value = []
    if (candidate) {
      try {
        await loadExperimentDetails(candidate.id)
      } catch (cause) {
        error.value = cause instanceof Error ? cause.message : 'Unable to load experiment details'
      }
    }
  }

  async function loadExperimentDetails(experimentId: string) {
    const requestId = ++detailRequest
    promotionPreview.value = null
    const [evidenceResult, diffResult, previewResult] = await Promise.allSettled([
      api.evidence(experimentId),
      api.diff(experimentId),
      api.promotionPreview(experimentId),
    ])
    if (requestId !== detailRequest || selectedExperimentId.value !== experimentId) return
    if (evidenceResult.status === 'fulfilled') evidence.value = evidenceResult.value
    if (diffResult.status === 'fulfilled') diff.value = diffResult.value
    if (previewResult.status === 'fulfilled') promotionPreview.value = previewResult.value
    const failure = evidenceResult.status === 'rejected' ? evidenceResult.reason
      : diffResult.status === 'rejected' ? diffResult.reason
        : previewResult.status === 'rejected' ? previewResult.reason : null
    if (failure) throw (failure instanceof Error ? failure : new Error('Unable to load experiment details'))
  }

  async function createProject(body: { name: string; canonicalPath: string; verificationCommands: string[] }) {
    const project = await api.createProject(body)
    projects.value.push(project)
    await selectProject(project.id)
  }

  async function createExperiment(body: { sessionTitle: string; task: string; sessionId?: string | null; newSession?: boolean }) {
    if (!selectedProjectId.value) return
    const experiment = await api.createExperiment(selectedProjectId.value, {
      sessionId: body.newSession ? null : (body.sessionId ?? selectedSessionId.value),
      sessionTitle: body.sessionTitle,
      task: body.task,
    })
    experiments.value.push(experiment)
    if (!sessions.value.some((session) => session.id === experiment.sessionId)) {
      sessions.value = await api.sessions(selectedProjectId.value)
    }
    selectedSessionId.value = experiment.sessionId
    selectedExperimentId.value = experiment.id
    await loadExperimentDetails(experiment.id)
  }

  async function startExperiment(experimentId: string) {
    const started = await api.startExperiment(experimentId)
    replaceExperiment(started)
  }

  async function cancelExperiment(experimentId: string) {
    const updated = await api.cancelExperiment(experimentId)
    replaceExperiment(updated)
  }

  async function promoteExperiment(experimentId: string) {
    const projectId = selectedProjectId.value
    const outcome = await api.promoteExperiment(experimentId)
    if (selectedProjectId.value === projectId && selectedExperimentId.value === experimentId) {
      promotionOutcome.value = outcome
    }
    if (projectId && selectedProjectId.value === projectId) await reloadSelectedProject()
  }

  async function reloadSelectedProject() {
    if (!selectedProjectId.value) return
    const projectId = selectedProjectId.value
    const requestId = ++projectRequest
    const [loadedSessions, loadedExperiments] = await Promise.all([
      api.sessions(projectId),
      api.experiments(projectId),
    ])
    if (requestId !== projectRequest || selectedProjectId.value !== projectId) return
    sessions.value = loadedSessions
    experiments.value = loadedExperiments
    if (selectedExperimentId.value) {
      await loadExperimentDetails(selectedExperimentId.value)
    }
  }

  function replaceExperiment(updated: Experiment) {
    const index = experiments.value.findIndex((experiment) => experiment.id === updated.id)
    if (index >= 0) experiments.value[index] = updated
  }

  return {
    projects,
    experiments,
    visibleExperiments,
    sessions,
    selectedProjectId,
    selectedSessionId,
    selectedExperimentId,
    selectedProject,
    selectedExperiment,
    sessionTitle,
    loading,
    error,
    evidence,
    diff,
    promotionOutcome,
    promotionPreview,
    loadProjects,
    selectProject,
    selectExperiment,
    createProject,
    createExperiment,
    startExperiment,
    cancelExperiment,
    promoteExperiment,
    selectSession,
    reloadSelectedProject,
  }
})
