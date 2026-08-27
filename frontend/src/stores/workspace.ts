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
  const error = ref<string | null>(null)
  const evidence = ref<Evidence[]>([])
  const diff = ref<DiffEntry[]>([])
  const promotionOutcome = ref<PromotionOutcome | null>(null)
  const promotionReconcile = ref<PromotionReconcile | null>(null)
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
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : localizedText('无法加载项目。', 'Unable to load projects')
    } finally {
      loading.value = false
    }
  }

  async function selectProject(projectId: string, experimentId: string | null = null) {
    const requestId = ++projectRequest
    const projectChanged = selectedProjectId.value !== projectId
    selectedProjectId.value = projectId
    selectedSessionId.value = null
    selectedExperimentId.value = null
    detailLoading.value = false
    evidence.value = []
    diff.value = []
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
      if (requestId !== projectRequest || selectedProjectId.value !== projectId) return
      sessions.value = loadedSessions
      experiments.value = loadedExperiments
      selectedExperimentId.value = loadedExperiments.some((item) => item.id === experimentId) ? experimentId : null
      if (selectedExperimentId.value) {
        await loadExperimentDetails(selectedExperimentId.value)
      }
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : localizedText('无法加载实验。', 'Unable to load experiments')
    }
  }

  async function selectExperiment(experimentId: string) {
    if (!experiments.value.some((experiment) => experiment.id === experimentId)) {
      throw new Error('Experiment does not belong to the selected project')
    }
    selectedExperimentId.value = experimentId
    evidence.value = []
    diff.value = []
    promotionOutcome.value = null
    promotionReconcile.value = null
    promotionPreview.value = null
    try {
      await loadExperimentDetails(experimentId)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : localizedText('无法加载实验详情。', 'Unable to load experiment details')
    }
  }

  async function selectSession(sessionId: string | null) {
    selectedSessionId.value = sessionId
    promotionOutcome.value = null
    promotionReconcile.value = null
    promotionPreview.value = null
    const selectedIsVisible = visibleExperiments.value.some((experiment) => experiment.id === selectedExperimentId.value)
    if (!selectedIsVisible) {
      clearExperimentSelection()
    }
  }

  async function loadExperimentDetails(experimentId: string) {
    const requestId = ++detailRequest
    detailLoading.value = true
    promotionPreview.value = null
    const [evidenceResult, diffResult, previewResult] = await Promise.allSettled([
      api.evidence(experimentId),
      api.diff(experimentId),
      api.promotionPreview(experimentId),
    ])
    if (requestId !== detailRequest || selectedExperimentId.value !== experimentId) return
    evidence.value = evidenceResult.status === 'fulfilled' ? evidenceResult.value : []
    diff.value = diffResult.status === 'fulfilled' ? diffResult.value : []
    promotionPreview.value = previewResult.status === 'fulfilled' ? previewResult.value : null
    const failure = evidenceResult.status === 'rejected' ? evidenceResult.reason
        : diffResult.status === 'rejected' ? diffResult.reason
        : previewResult.status === 'rejected' ? previewResult.reason : null
    detailLoading.value = false
    if (failure) throw (failure instanceof Error ? failure : new Error(localizedText('无法加载实验详情。', 'Unable to load experiment details')))
  }

  async function createProject(body: { name: string; canonicalPath: string; verificationCommands: string[] }) {
    const project = await api.createProject(body)
    projects.value.push(project)
    await selectProject(project.id)
    return project
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
    return experiment
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

  async function reconcilePromotion(experimentId: string) {
    const projectId = selectedProjectId.value
    const outcome = await api.reconcilePromotion(experimentId)
    if (selectedProjectId.value === projectId && selectedExperimentId.value === experimentId) {
      promotionReconcile.value = outcome
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
    if (selectedSessionId.value && !loadedSessions.some((session) => session.id === selectedSessionId.value)) {
      selectedSessionId.value = null
    }
    if (selectedExperimentId.value && loadedExperiments.some((item) => item.id === selectedExperimentId.value)) {
      await loadExperimentDetails(selectedExperimentId.value)
    } else {
      selectedExperimentId.value = null
      evidence.value = []
      diff.value = []
      promotionPreview.value = null
      detailLoading.value = false
    }
  }

  function clearSelection() {
    ++projectRequest
    ++detailRequest
    selectedProjectId.value = null
    selectedSessionId.value = null
    selectedExperimentId.value = null
    sessions.value = []
    experiments.value = []
    evidence.value = []
    diff.value = []
    promotionOutcome.value = null
    promotionReconcile.value = null
    promotionPreview.value = null
    detailLoading.value = false
  }

  function clearExperimentSelection() {
    ++detailRequest
    selectedExperimentId.value = null
    evidence.value = []
    diff.value = []
    promotionOutcome.value = null
    promotionReconcile.value = null
    promotionPreview.value = null
    detailLoading.value = false
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
    detailLoading,
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
    createExperiment,
    startExperiment,
    cancelExperiment,
    promoteExperiment,
    reconcilePromotion,
    selectSession,
    reloadSelectedProject,
    clearSelection,
    clearExperimentSelection,
  }
})
