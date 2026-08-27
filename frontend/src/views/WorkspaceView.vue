<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { CircleDot, RefreshCw, ShieldCheck, X } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import type { RunEvent } from '../api'
import ExperimentDialog from '../components/ExperimentDialog.vue'
import ExperimentList from '../components/ExperimentList.vue'
import ExperimentReview from '../components/ExperimentReview.vue'
import ProjectDialog from '../components/ProjectDialog.vue'
import ProjectRail from '../components/ProjectRail.vue'
import PromotionDialog from '../components/PromotionDialog.vue'
import { useWorkspaceStore } from '../stores/workspace'

type StreamState = 'idle' | 'connecting' | 'live' | 'reconnecting' | 'offline'

const route = useRoute()
const router = useRouter()
const store = useWorkspaceStore()
const showProjectDialog = ref(false)
const showExperimentDialog = ref(false)
const showPromotionDialog = ref(false)
const submitting = ref(false)
const actionBusy = ref(false)
const activity = ref<RunEvent[]>([])
const streamState = ref<StreamState>('idle')
const eventWarning = ref<string | null>(null)
const initialized = ref(false)
const seenSequences = new Set<number>()
let eventSource: EventSource | null = null
let refreshTimer: number | null = null
let routeSyncVersion = 0

const projectParam = computed(() => typeof route.params.projectId === 'string' ? route.params.projectId : null)
const experimentParam = computed(() => typeof route.params.experimentId === 'string' ? route.params.experimentId : null)
const viewClass = computed(() => experimentParam.value ? 'view-review' : projectParam.value ? 'view-runs' : 'view-projects')
const selectedSessionTitle = computed(() => store.selectedSessionId
  ? store.sessions.find((session) => session.id === store.selectedSessionId)?.title ?? null
  : null)
const connectionLabel = computed(() => store.selectedExperimentId ? streamState.value : 'no stream')

const refreshEvents = new Set([
  'AGENT_COMPLETED',
  'RESULT_SNAPSHOT_SEALED',
  'VERIFICATION_STARTED',
  'VERIFICATION_FINISHED',
  'PROMOTION_PREPARING',
  'PROMOTION_VERIFICATION_STARTED',
  'PROMOTION_BLOCKED',
  'EXPERIMENT_FAILED',
  'PROMOTED',
])

function validRunEvent(value: unknown): value is RunEvent {
  if (!value || typeof value !== 'object') return false
  const event = value as Partial<RunEvent>
  return typeof event.sequence === 'number'
    && Number.isFinite(event.sequence)
    && typeof event.type === 'string'
    && typeof event.timestamp === 'string'
    && !!event.payload
    && typeof event.payload === 'object'
}

function scheduleProjectRefresh(experimentId: string) {
  if (refreshTimer !== null) window.clearTimeout(refreshTimer)
  refreshTimer = window.setTimeout(async () => {
    refreshTimer = null
    if (store.selectedExperimentId !== experimentId) return
    try {
      await store.reloadSelectedProject()
    } catch (cause) {
      store.error = cause instanceof Error ? cause.message : 'Unable to refresh experiment state'
    }
  }, 350)
}

function connectEvents(experimentId: string | null) {
  eventSource?.close()
  eventSource = null
  if (refreshTimer !== null) window.clearTimeout(refreshTimer)
  refreshTimer = null
  activity.value = []
  seenSequences.clear()
  eventWarning.value = null
  streamState.value = experimentId ? 'connecting' : 'idle'
  if (!experimentId) return

  const source = new EventSource(`/api/experiments/${experimentId}/events`)
  eventSource = source
  source.onopen = () => {
    if (eventSource === source) streamState.value = 'live'
  }
  source.onerror = () => {
    if (eventSource !== source) return
    streamState.value = source.readyState === EventSource.CLOSED ? 'offline' : 'reconnecting'
  }
  source.onmessage = (message) => {
    if (eventSource !== source) return
    let parsed: unknown
    try {
      parsed = JSON.parse(message.data)
    } catch {
      eventWarning.value = 'An event payload could not be parsed; the stream remains connected.'
      return
    }
    if (!validRunEvent(parsed)) {
      eventWarning.value = 'An event with an invalid shape was ignored.'
      return
    }
    if (seenSequences.has(parsed.sequence)) return
    seenSequences.add(parsed.sequence)
    activity.value.push(parsed)
    if (activity.value.length > 200) {
      const removed = activity.value.shift()
      if (removed) seenSequences.delete(removed.sequence)
    }
    if (refreshEvents.has(parsed.type)) scheduleProjectRefresh(experimentId)
  }
}

async function syncRoute() {
  if (!initialized.value) return
  const syncId = ++routeSyncVersion
  const desiredProject = projectParam.value
  const desiredExperiment = experimentParam.value

  if (!desiredProject) {
    store.clearSelection()
    return
  }
  if (store.projects.length === 0 && store.error) return
  if (!store.projects.some((project) => project.id === desiredProject)) {
    store.error = 'The linked project is not available in this workspace.'
    await router.replace({ name: 'home' })
    return
  }

  if (store.selectedProjectId !== desiredProject) {
    await store.selectProject(desiredProject, desiredExperiment)
  } else if (desiredExperiment && store.selectedExperimentId !== desiredExperiment) {
    if (store.experiments.some((experiment) => experiment.id === desiredExperiment)) {
      await store.selectExperiment(desiredExperiment)
    } else {
      store.error = 'The linked experiment does not belong to this project.'
      await router.replace({ name: 'project', params: { projectId: desiredProject } })
      return
    }
  } else if (!desiredExperiment && store.selectedExperimentId) {
    store.clearExperimentSelection()
  }

  if (syncId !== routeSyncVersion) return
  if (desiredExperiment && !store.experiments.some((experiment) => experiment.id === desiredExperiment)) {
    store.error = 'The linked experiment is no longer available.'
    await router.replace({ name: 'project', params: { projectId: desiredProject } })
  }
}

async function openProject(projectId: string) {
  await router.push({ name: 'project', params: { projectId } })
}

async function openExperiment(experimentId: string) {
  if (!store.selectedProjectId) return
  await router.push({ name: 'experiment', params: { projectId: store.selectedProjectId, experimentId } })
}

async function selectSession(sessionId: string | null) {
  await store.selectSession(sessionId)
  if (experimentParam.value && !store.selectedExperimentId && store.selectedProjectId) {
    await router.push({ name: 'project', params: { projectId: store.selectedProjectId } })
  }
}

async function submitProject(value: { name: string; canonicalPath: string; verificationCommands: string[] }) {
  submitting.value = true
  store.error = null
  try {
    const project = await store.createProject(value)
    showProjectDialog.value = false
    await router.push({ name: 'project', params: { projectId: project.id } })
  } catch (cause) {
    store.error = cause instanceof Error ? cause.message : 'Unable to register project'
  } finally {
    submitting.value = false
  }
}

async function submitExperiment(value: { sessionTitle: string; task: string; newSession: boolean }) {
  submitting.value = true
  store.error = null
  try {
    const experiment = await store.createExperiment(value)
    if (!experiment) return
    showExperimentDialog.value = false
    await openExperiment(experiment.id)
  } catch (cause) {
    store.error = cause instanceof Error ? cause.message : 'Unable to create experiment'
  } finally {
    submitting.value = false
  }
}

async function runAction(action: () => Promise<void>, closePromotion = false) {
  if (actionBusy.value) return
  actionBusy.value = true
  store.error = null
  try {
    await action()
    if (closePromotion) showPromotionDialog.value = false
  } catch (cause) {
    store.error = cause instanceof Error ? cause.message : 'Experiment action failed'
  } finally {
    actionBusy.value = false
  }
}

async function refresh() {
  const projectBeforeRefresh = store.selectedProjectId
  store.error = null
  await store.loadProjects()
  await syncRoute()
  if (store.selectedProjectId && store.selectedProjectId === projectBeforeRefresh) {
    try {
      await store.reloadSelectedProject()
    } catch (cause) {
      store.error = cause instanceof Error ? cause.message : 'Unable to refresh workspace'
    }
  }
}

watch(() => route.fullPath, () => void syncRoute())
watch(() => store.selectedExperimentId, connectEvents)

onMounted(async () => {
  await store.loadProjects()
  initialized.value = true
  await syncRoute()
})

onUnmounted(() => {
  eventSource?.close()
  if (refreshTimer !== null) window.clearTimeout(refreshTimer)
})
</script>

<template>
  <div class="app-shell" :class="viewClass">
    <header class="topbar">
      <button class="brand-lockup" aria-label="Open projects" @click="router.push({ name: 'home' })">
        <span class="brand-mark">P</span>
        <span><strong>PICO</strong><small>experiment console</small></span>
      </button>
      <div class="topbar-context">
        <span class="canonical-indicator"><ShieldCheck :size="14" /> canonical guarded</span>
        <span class="connection-state" :class="streamState"><span class="stream-dot" :class="streamState" />{{ connectionLabel }}</span>
        <button class="icon-button" aria-label="Refresh workspace" title="Refresh" :disabled="store.loading" @click="refresh"><RefreshCw :class="{ spin: store.loading }" :size="17" /></button>
      </div>
    </header>

    <div v-if="store.error" class="global-alert" role="alert">
      <CircleDot :size="16" /><span>{{ store.error }}</span><button class="icon-button small" aria-label="Dismiss error" title="Dismiss" @click="store.error = null"><X :size="15" /></button>
    </div>

    <div class="workspace-frame">
      <ProjectRail
        :projects="store.projects"
        :sessions="store.sessions"
        :experiments="store.experiments"
        :selected-project-id="store.selectedProjectId"
        :selected-session-id="store.selectedSessionId"
        :loading="store.loading"
        @register="showProjectDialog = true"
        @select-project="openProject"
        @select-session="selectSession"
      />
      <ExperimentList
        :project="store.selectedProject"
        :experiments="store.visibleExperiments"
        :sessions="store.sessions"
        :selected-experiment-id="store.selectedExperimentId"
        :selected-session-id="store.selectedSessionId"
        @select="openExperiment"
        @create="showExperimentDialog = true"
        @projects="router.push({ name: 'home' })"
        @select-session="selectSession"
      />
      <ExperimentReview
        :project="store.selectedProject"
        :experiment="store.selectedExperiment"
        :diff="store.diff"
        :evidence="store.evidence"
        :promotion-preview="store.promotionPreview"
        :promotion-outcome="store.promotionOutcome"
        :promotion-reconcile="store.promotionReconcile"
        :activity="activity"
        :stream-state="streamState"
        :event-warning="eventWarning"
        :action-busy="actionBusy"
        :detail-loading="store.detailLoading"
        @back="store.selectedProjectId && router.push({ name: 'project', params: { projectId: store.selectedProjectId } })"
        @start="store.selectedExperiment && runAction(() => store.startExperiment(store.selectedExperiment!.id))"
        @cancel="store.selectedExperiment && runAction(() => store.cancelExperiment(store.selectedExperiment!.id))"
        @promote="showPromotionDialog = true"
        @reconcile="store.selectedExperiment && runAction(() => store.reconcilePromotion(store.selectedExperiment!.id))"
      />
    </div>

    <ProjectDialog v-if="showProjectDialog" :busy="submitting" @close="showProjectDialog = false" @submit="submitProject" />
    <ExperimentDialog
      v-if="showExperimentDialog"
      :busy="submitting"
      :has-selected-session="!!store.selectedSessionId"
      :selected-session-title="selectedSessionTitle"
      @close="showExperimentDialog = false"
      @submit="submitExperiment"
    />
    <PromotionDialog
      v-if="showPromotionDialog && store.selectedExperiment && store.promotionPreview"
      :experiment="store.selectedExperiment"
      :preview="store.promotionPreview"
      :changed-file-count="store.diff.length"
      :busy="actionBusy"
      @close="showPromotionDialog = false"
      @confirm="runAction(() => store.promoteExperiment(store.selectedExperiment!.id), true)"
    />
  </div>
</template>
