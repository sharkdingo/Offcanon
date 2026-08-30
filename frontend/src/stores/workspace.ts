import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  api,
  type DiffEntry,
  type Evidence,
  type Experiment,
  type Project,
  type PromotionOutcome,
  type PromotionPreview,
  type PromotionReconcile,
  type Session,
} from '../api'
import { localizedText } from '../i18n'

export const useWorkspaceStore = defineStore('workspace', () => {
  const projects = ref<Project[]>([])
  const experiments = ref<Experiment[]>([])
  const sessions = ref<Session[]>([])
  const selectedProjectId = ref<string | null>(null)
  const selectedSessionId = ref<string | null>(null)
  const selectedExperimentId = ref<string | null>(null)
  const loading = ref(false)
  const detailLoading = ref(false)
  const detailError = ref<string | null>(null)
  // Detail endpoints are independent. Keep their errors separate so an
  // unavailable diff does not hide evidence or promotion state.
  const evidenceError = ref<string | null>(null)
  const diffError = ref<string | null>(null)
  const promotionPreviewError = ref<string | null>(null)
  const error = ref<string | null>(null)
  const evidence = ref<Evidence[]>([])
  const diff = ref<DiffEntry[]>([])
  const promotionOutcome = ref<PromotionOutcome | null>(null)
  const promotionReconcile = ref<PromotionReconcile | null>(null)
  const promotionPreview = ref<PromotionPreview | null>(null)
  let detailRequest = 0
  let detailExperimentId: string | null = null
  let projectRequest = 0
  let projectsRequest = 0
  let stateGeneration = 0

  function isCurrent(generation: number) {
    return generation === stateGeneration
  }

  function causeMessage(cause: unknown, fallback: string) {
    return cause instanceof Error ? cause.message : fallback
  }

  function resetDetailState(clearData = true) {
    detailError.value = null
    evidenceError.value = null
    diffError.value = null
    promotionPreviewError.value = null
    if (clearData) {
      evidence.value = []
      diff.value = []
      promotionPreview.value = null
    }
  }

  const selectedProject = computed(() => projects.value.find((project) => project.id === selectedProjectId.value) ?? null)
  const selectedExperiment = computed(() => experiments.value.find((experiment) => experiment.id === selectedExperimentId.value) ?? null)
  const activeSession = computed(() => sessions.value.find((session) => session.id === selectedSessionId.value) ?? null)
  const activeExperiments = computed(() => selectedSessionId.value
    ? experiments.value.filter((experiment) => experiment.sessionId === selectedSessionId.value)
    : [])
  const visibleExperiments = computed(() => selectedSessionId.value
    ? experiments.value.filter((experiment) => experiment.sessionId === selectedSessionId.value)
    : experiments.value)
  const sessionTitle = (sessionId: string) => sessions.value.find((session) => session.id === sessionId)?.title ?? sessionId.slice(0, 8)

  async function loadProjects() {
    const generation = stateGeneration
    const requestId = ++projectsRequest
    loading.value = true
    error.value = null
    try {
      const loadedProjects = await api.projects()
      if (!isCurrent(generation) || requestId !== projectsRequest) return
      projects.value = loadedProjects
    } catch (cause) {
      if (!isCurrent(generation) || requestId !== projectsRequest) return
      error.value = cause instanceof Error ? cause.message : localizedText('无法加载项目。', 'Unable to load projects')
    } finally {
      if (isCurrent(generation) && requestId === projectsRequest) loading.value = false
    }
  }

  async function selectProject(projectId: string, experimentId: string | null = null) {
    const generation = stateGeneration
    const requestId = ++projectRequest
    error.value = null
    const projectChanged = selectedProjectId.value !== projectId
    selectedProjectId.value = projectId
    selectedSessionId.value = null
    selectedExperimentId.value = null
    detailExperimentId = null
    detailLoading.value = false
    resetDetailState()
    promotionOutcome.value = null
    promotionReconcile.value = null
    promotionPreview.value = null
    if (projectChanged) {
      sessions.value = []
      experiments.value = []
    }
    try {
      const [loadedSessions, loadedExperiments] = await Promise.all([
        api.sessions(projectId),
        api.experiments(projectId),
      ])
      if (!isCurrent(generation) || requestId !== projectRequest || selectedProjectId.value !== projectId) return
      sessions.value = loadedSessions
      experiments.value = loadedExperiments
      const requested = loadedExperiments.find((item) => item.id === experimentId)
      const fallback = requested ?? loadedExperiments.at(-1) ?? null
      selectedExperimentId.value = fallback?.id ?? null
      selectedSessionId.value = fallback?.sessionId ?? loadedSessions.at(-1)?.id ?? null
      if (selectedExperimentId.value) {
        // Loading the review panels is independent from selecting a project.
        // Do not make a successful project load depend on a diff endpoint.
        void loadExperimentDetails(selectedExperimentId.value)
      }
    } catch (cause) {
      if (!isCurrent(generation) || requestId !== projectRequest || selectedProjectId.value !== projectId) return
      error.value = cause instanceof Error ? cause.message : localizedText('无法加载实验。', 'Unable to load experiments')
    }
  }

  async function selectExperiment(experimentId: string) {
    const selected = experiments.value.find((experiment) => experiment.id === experimentId)
    if (!selected) {
      throw new Error('Experiment does not belong to the selected project')
    }
    // A deep link can point at a turn from another session. Keep the thread,
    // sidebar highlight, and review drawer on the same session boundary.
    error.value = null
    selectedSessionId.value = selected.sessionId
    selectedExperimentId.value = experimentId
    promotionOutcome.value = null
    promotionReconcile.value = null
    // Review data is best-effort and reports errors per panel.
    void loadExperimentDetails(experimentId)
  }

  async function selectSession(sessionId: string | null) {
    const generation = stateGeneration
    error.value = null
    selectedSessionId.value = sessionId
    promotionOutcome.value = null
    promotionReconcile.value = null
    promotionPreview.value = null
    if (!sessionId) {
      clearExperimentSelection()
      return
    }
    const selectedIsVisible = visibleExperiments.value.some((experiment) => experiment.id === selectedExperimentId.value)
    const next = visibleExperiments.value.at(-1) ?? null
    if (!selectedIsVisible) {
      if (next) {
        selectedExperimentId.value = next.id
        void loadExperimentDetails(next.id)
      } else {
        clearExperimentSelection()
      }
    }
  }

  async function loadExperimentDetails(experimentId: string) {
    const generation = stateGeneration
    const requestId = ++detailRequest
    detailLoading.value = true
    const experimentChanged = detailExperimentId !== experimentId
    detailExperimentId = experimentId
    // Preserve already-rendered data when refreshing the same experiment. A
    // transient failure should replace only that endpoint's value/error.
    resetDetailState(experimentChanged)
    const [evidenceResult, diffResult, previewResult] = await Promise.allSettled([
      Promise.resolve().then(() => api.evidence(experimentId)),
      Promise.resolve().then(() => api.diff(experimentId)),
      Promise.resolve().then(() => api.promotionPreview(experimentId)),
    ])
    if (!isCurrent(generation) || requestId !== detailRequest || selectedExperimentId.value !== experimentId) return

    const detailFailures: string[] = []
    if (evidenceResult.status === 'fulfilled') {
      evidence.value = evidenceResult.value
    } else {
      evidenceError.value = causeMessage(evidenceResult.reason,
        localizedText('无法加载验证记录。', 'Unable to load verification records'))
      detailFailures.push(evidenceError.value)
    }
    if (diffResult.status === 'fulfilled') {
      diff.value = diffResult.value
    } else {
      diffError.value = causeMessage(diffResult.reason,
        localizedText('无法加载实验差异。', 'Unable to load experiment diff'))
      detailFailures.push(diffError.value)
    }
    if (previewResult.status === 'fulfilled') {
      promotionPreview.value = previewResult.value
    } else {
      promotionPreviewError.value = causeMessage(previewResult.reason,
        localizedText('无法加载应用条件。', 'Unable to load application conditions'))
      detailFailures.push(promotionPreviewError.value)
    }
    // Kept for callers that still want a compact aggregate message. Panels
    // should prefer their dedicated error ref above.
    detailError.value = detailFailures.length ? detailFailures.join(' · ') : null
    detailLoading.value = false
  }

  async function createProject(body: { name: string; canonicalPath: string; verificationCommands: string[] }) {
    const generation = stateGeneration
    let project: Project
    try {
      project = await api.createProject(body)
    } catch (cause) {
      if (!isCurrent(generation)) return undefined
      throw cause
    }
    if (!isCurrent(generation)) return undefined
    const existingIndex = projects.value.findIndex((existing) => existing.id === project.id)
    if (existingIndex >= 0) projects.value[existingIndex] = project
    else projects.value.push(project)
    // The project row is already usable. Session/experiment hydration happens
    // asynchronously and should not turn a successful create into an error.
    void selectProject(project.id)
    if (!isCurrent(generation)) return undefined
    return project
  }

  async function updateProject(body: { name: string; canonicalPath: string; verificationCommands: string[] }) {
    const generation = stateGeneration
    const projectId = selectedProjectId.value
    if (!projectId) return undefined
    const updated = await api.updateProject(projectId, body)
    if (!isCurrent(generation) || selectedProjectId.value !== projectId) return undefined
    const index = projects.value.findIndex((project) => project.id === updated.id)
    if (index >= 0) projects.value[index] = updated
    else projects.value.push(updated)
    return updated
  }

  async function createExperiment(body: { sessionTitle: string; task: string; sessionId?: string | null; newSession?: boolean }) {
    const generation = stateGeneration
    const projectId = selectedProjectId.value
    if (!projectId) return
    let experiment: Experiment
    try {
      experiment = await api.createExperiment(projectId, {
        sessionId: body.newSession ? null : (body.sessionId ?? selectedSessionId.value),
        sessionTitle: body.sessionTitle,
        task: body.task,
      })
    } catch (cause) {
      if (!isCurrent(generation)) return undefined
      throw cause
    }
    if (!isCurrent(generation) || selectedProjectId.value !== projectId) return undefined
    upsertExperiment(experiment)
    if (!sessions.value.some((session) => session.id === experiment.sessionId)) {
      // Optimistically expose the new session while the authoritative list is
      // refreshed in the background.
      sessions.value.push({
        id: experiment.sessionId,
        projectId,
        title: body.sessionTitle,
        createdAt: experiment.createdAt,
      })
      void refreshSessionsInBackground(projectId, generation)
    }
    selectedSessionId.value = experiment.sessionId
    selectedExperimentId.value = experiment.id
    void loadExperimentDetails(experiment.id)
    if (!isCurrent(generation) || selectedProjectId.value !== projectId) return undefined
    return experiment
  }

  async function continueExperiment(experimentId: string, task: string) {
    const generation = stateGeneration
    const projectId = selectedProjectId.value
    let successor: Experiment
    try {
      successor = await api.continueExperiment(experimentId, task)
    } catch (cause) {
      if (!isCurrent(generation)) return undefined
      throw cause
    }
    if (!isCurrent(generation) || selectedProjectId.value !== projectId) return undefined
    upsertExperiment(successor)
    selectedSessionId.value = successor.sessionId
    selectedExperimentId.value = successor.id
    promotionOutcome.value = null
    promotionReconcile.value = null
    void loadExperimentDetails(successor.id)
    if (!isCurrent(generation) || selectedProjectId.value !== projectId) return undefined
    return successor
  }

  async function startExperiment(experimentId: string) {
    const generation = stateGeneration
    const projectId = selectedProjectId.value
    let started: Experiment
    try {
      started = await api.startExperiment(experimentId)
    } catch (cause) {
      if (!isCurrent(generation)) return
      throw cause
    }
    if (!isCurrent(generation) || selectedProjectId.value !== projectId) return
    replaceExperiment(started)
  }

  async function cancelExperiment(experimentId: string) {
    const generation = stateGeneration
    const projectId = selectedProjectId.value
    let updated: Experiment
    try {
      updated = await api.cancelExperiment(experimentId)
    } catch (cause) {
      if (!isCurrent(generation)) return
      throw cause
    }
    if (!isCurrent(generation) || selectedProjectId.value !== projectId) return
    replaceExperiment(updated)
  }

  async function promoteExperiment(experimentId: string) {
    const generation = stateGeneration
    const projectId = selectedProjectId.value
    let outcome: PromotionOutcome
    try {
      outcome = await api.promoteExperiment(experimentId)
    } catch (cause) {
      if (!isCurrent(generation)) return
      throw cause
    }
    if (!isCurrent(generation) || selectedProjectId.value !== projectId) return
    if (selectedProjectId.value === projectId && selectedExperimentId.value === experimentId) {
      promotionOutcome.value = outcome
    }
    if (projectId && selectedProjectId.value === projectId) await reloadSelectedProject()
  }

  async function confirmExperimentStale(experimentId: string) {
    const generation = stateGeneration
    const projectId = selectedProjectId.value
    let outcome: Awaited<ReturnType<typeof api.confirmExperimentStale>>
    try {
      outcome = await api.confirmExperimentStale(experimentId)
    } catch (cause) {
      if (!isCurrent(generation)) return
      throw cause
    }
    if (!isCurrent(generation) || selectedProjectId.value !== projectId) return
    if (selectedProjectId.value === projectId && selectedExperimentId.value === experimentId) {
      promotionOutcome.value = outcome.markedStale ? {
        promoted: false,
        status: outcome.status,
        detail: outcome.detail,
        changedFiles: [],
        fingerprint: outcome.currentFingerprint,
      } : null
    }
    if (projectId && selectedProjectId.value === projectId) await reloadSelectedProject()
  }

  async function reconcilePromotion(experimentId: string) {
    const generation = stateGeneration
    const projectId = selectedProjectId.value
    let outcome: PromotionReconcile
    try {
      // A project-level unresolved journal is authoritative even when the
      // selected experiment row still says VERIFIED. Reconcile that journal
      // first so the UI cannot strand recovery behind the wrong experiment.
      outcome = projectId && promotionPreview.value?.recoveryRequired
        ? await api.reconcileProjectPromotion(projectId)
        : await api.reconcilePromotion(experimentId)
    } catch (cause) {
      if (!isCurrent(generation)) return
      throw cause
    }
    if (!isCurrent(generation) || selectedProjectId.value !== projectId) return
    if (selectedProjectId.value === projectId) {
      promotionReconcile.value = outcome
    }
    if (projectId && selectedProjectId.value === projectId) await reloadSelectedProject()
  }

  async function reloadSelectedProject() {
    if (!selectedProjectId.value) return
    const generation = stateGeneration
    const projectId = selectedProjectId.value
    const requestId = ++projectRequest
    let loadedSessions: Session[]
    let loadedExperiments: Experiment[]
    try {
      [loadedSessions, loadedExperiments] = await Promise.all([
        api.sessions(projectId),
        api.experiments(projectId),
      ])
    } catch (cause) {
      if (!isCurrent(generation)) return
      throw cause
    }
    if (!isCurrent(generation) || requestId !== projectRequest || selectedProjectId.value !== projectId) return
    sessions.value = loadedSessions
    experiments.value = loadedExperiments
    if (selectedSessionId.value && !loadedSessions.some((session) => session.id === selectedSessionId.value)) {
      selectedSessionId.value = null
    }
    if (selectedExperimentId.value && loadedExperiments.some((item) => item.id === selectedExperimentId.value)) {
      void loadExperimentDetails(selectedExperimentId.value)
    } else {
      selectedExperimentId.value = null
      detailExperimentId = null
      resetDetailState()
      detailLoading.value = false
    }
  }

  function clearSelection() {
    ++stateGeneration
    ++projectRequest
    ++detailRequest
    ++projectsRequest
    detailExperimentId = null
    selectedProjectId.value = null
    selectedSessionId.value = null
    selectedExperimentId.value = null
    sessions.value = []
    experiments.value = []
    resetDetailState()
    promotionOutcome.value = null
    promotionReconcile.value = null
    detailLoading.value = false
    loading.value = false
  }

  function clearExperimentSelection() {
    ++detailRequest
    detailExperimentId = null
    selectedExperimentId.value = null
    resetDetailState()
    promotionOutcome.value = null
    promotionReconcile.value = null
    promotionPreview.value = null
    detailLoading.value = false
  }

  function upsertExperiment(updated: Experiment) {
    const index = experiments.value.findIndex((experiment) => experiment.id === updated.id)
    if (index >= 0) experiments.value[index] = updated
    else experiments.value.push(updated)
  }

  function replaceExperiment(updated: Experiment) {
    const index = experiments.value.findIndex((experiment) => experiment.id === updated.id)
    if (index >= 0) experiments.value[index] = updated
  }

  async function refreshSessionsInBackground(projectId: string, generation: number) {
    try {
      const loadedSessions = await api.sessions(projectId)
      if (!isCurrent(generation) || selectedProjectId.value !== projectId) return
      sessions.value = loadedSessions
    } catch (cause) {
      // Keep the created experiment visible; this is a non-blocking hydration
      // failure and will be retried by the next workspace refresh.
      if (isCurrent(generation) && selectedProjectId.value === projectId && !error.value) {
        error.value = causeMessage(cause, localizedText('无法刷新任务列表。', 'Unable to refresh task list'))
      }
    }
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
    activeSession,
    activeExperiments,
    sessionTitle,
    loading,
    detailLoading,
    detailError,
    evidenceError,
    diffError,
    promotionPreviewError,
    error,
    evidence,
    diff,
    promotionOutcome,
    promotionReconcile,
    promotionPreview,
    loadProjects,
    selectProject,
    selectExperiment,
    createProject,
    updateProject,
    createExperiment,
    continueExperiment,
    startExperiment,
    cancelExperiment,
    promoteExperiment,
    confirmExperimentStale,
    reconcilePromotion,
    selectSession,
    reloadSelectedProject,
    clearSelection,
    clearExperimentSelection,
  }
})
