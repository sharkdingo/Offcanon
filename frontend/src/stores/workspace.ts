import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { api, type DiffEntry, type Evidence, type Experiment, type Project } from '../api'

export const useWorkspaceStore = defineStore('workspace', () => {
  const projects = ref<Project[]>([])
  const experiments = ref<Experiment[]>([])
  const selectedProjectId = ref<string | null>(null)
  const selectedExperimentId = ref<string | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const evidence = ref<Evidence[]>([])
  const diff = ref<DiffEntry[]>([])

  const selectedProject = computed(() => projects.value.find((project) => project.id === selectedProjectId.value) ?? null)
  const selectedExperiment = computed(() => experiments.value.find((experiment) => experiment.id === selectedExperimentId.value) ?? null)

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
    selectedProjectId.value = projectId
    selectedExperimentId.value = null
    evidence.value = []
    diff.value = []
    try {
      experiments.value = await api.experiments(projectId)
      selectedExperimentId.value = experiments.value.at(-1)?.id ?? null
      if (selectedExperimentId.value) {
        await loadExperimentDetails(selectedExperimentId.value)
      }
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : 'Unable to load experiments'
    }
  }

  async function selectExperiment(experimentId: string) {
    selectedExperimentId.value = experimentId
    evidence.value = []
    diff.value = []
    try {
      await loadExperimentDetails(experimentId)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : 'Unable to load experiment details'
    }
  }

  async function loadExperimentDetails(experimentId: string) {
    const [loadedEvidence, loadedDiff] = await Promise.all([
      api.evidence(experimentId),
      api.diff(experimentId),
    ])
    evidence.value = loadedEvidence
    diff.value = loadedDiff
  }

  async function createProject(body: { name: string; canonicalPath: string; verificationCommands: string[] }) {
    const project = await api.createProject(body)
    projects.value.push(project)
    await selectProject(project.id)
  }

  async function createExperiment(body: { sessionTitle: string; task: string }) {
    if (!selectedProjectId.value) return
    const experiment = await api.createExperiment(selectedProjectId.value, body)
    experiments.value.push(experiment)
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
    await api.promoteExperiment(experimentId)
    await reloadSelectedProject()
  }

  async function reloadSelectedProject() {
    if (!selectedProjectId.value) return
    experiments.value = await api.experiments(selectedProjectId.value)
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
    selectedProjectId,
    selectedExperimentId,
    selectedProject,
    selectedExperiment,
    loading,
    error,
    evidence,
    diff,
    loadProjects,
    selectProject,
    selectExperiment,
    createProject,
    createExperiment,
    startExperiment,
    cancelExperiment,
    promoteExperiment,
    reloadSelectedProject,
  }
})
