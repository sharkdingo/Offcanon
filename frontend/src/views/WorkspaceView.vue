<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { AlertTriangle, CircleDot, Edit3, RefreshCw, Settings, ShieldCheck, X } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, type Project, type RunEvent } from '../api'
import AgentThread from '../components/AgentThread.vue'
import ProjectDialog from '../components/ProjectDialog.vue'
import PromotionDialog from '../components/PromotionDialog.vue'
import TaskSidebar from '../components/TaskSidebar.vue'
import ExperimentReview from '../components/ExperimentReview.vue'
import { useLocale } from '../i18n'
import { useWorkspaceStore } from '../stores/workspace'

type StreamState = 'idle' | 'connecting' | 'live' | 'reconnecting' | 'offline'

const route = useRoute()
const router = useRouter()
const { auth, text } = useLocale()
const store = useWorkspaceStore()
const threadRef = ref<InstanceType<typeof AgentThread> | null>(null)
const showProjectDialog = ref(false)
const editingProject = ref<Project | null>(null)
const showReview = ref(false)
const showPromotionDialog = ref(false)
const mobileNavOpen = ref(false)
const submitting = ref(false)
const actionBusy = ref(false)
const forceNewTask = ref(false)
const activity = ref<RunEvent[]>([])
const streamState = ref<StreamState>('idle')
const eventWarning = ref<string | null>(null)
const initialized = ref(false)
const seenSequences = new Set<number>()
let eventSource: EventSource | null = null
let eventExperimentId: string | null = null
let refreshTimer: number | null = null
let routeSyncVersion = 0

function projectSaveError(cause: unknown) {
  if (cause instanceof ApiError && cause.code === 'PROJECT_ALREADY_REGISTERED') {
    return text('这个 Git 仓库已由其他账户打开。请切换到账户，或选择另一个仓库。', 'This Git repository is already registered by another account. Switch accounts or choose another repository.')
  }
  if (cause instanceof ApiError && cause.code === 'VERIFICATION_POLICY_MISSING') {
    return text('新项目至少需要一条项目验收命令。', 'A new project needs at least one project acceptance command.')
  }
  return cause instanceof Error ? cause.message : text('无法保存项目。', 'Unable to save project')
}

const projectParam = computed(() => typeof route.params.projectId === 'string' ? route.params.projectId : null)
const experimentParam = computed(() => typeof route.params.experimentId === 'string' ? route.params.experimentId : null)
const accountInitials = computed(() => auth.session?.displayName
  .split(/\s+/)
  .map((part) => part[0])
  .join('')
  .slice(0, 2)
  .toUpperCase() ?? 'O')

const connectionLabel = computed(() => {
  const labels: Record<StreamState, [string, string]> = {
    idle: ['空闲', 'idle'], connecting: ['连接中', 'connecting'], live: ['实时', 'live'],
    reconnecting: ['重连中', 'reconnecting'], offline: ['离线', 'offline'],
  }
  const pair = labels[streamState.value]
  return text(pair[0], pair[1])
})

const activeExperiments = computed(() => store.activeExperiments)
const latestExperiment = computed(() => activeExperiments.value.at(-1) ?? null)

const refreshEvents = new Set([
  'EXPERIMENT_STARTED', 'AGENT_COMPLETED', 'RESULT_SNAPSHOT_SEALED', 'VERIFICATION_STARTED', 'VERIFICATION_FINISHED',
  'PROMOTION_PREPARING', 'PROMOTION_VERIFICATION_STARTED', 'PROMOTION_BLOCKED', 'EXPERIMENT_FAILED',
  'PROMOTION_RECOVERY_REQUIRED', 'PROMOTION_RECOVERY_DEFERRED', 'PROMOTION_RECOVERED',
  'PROMOTION_MANUALLY_RECONCILED', 'PROMOTED', 'EXPERIMENT_RECOVERED', 'EXPERIMENT_CANCELLED',
])

function validRunEvent(value: unknown): value is RunEvent {
  if (!value || typeof value !== 'object') return false
  const event = value as Partial<RunEvent>
  return typeof event.eventId === 'string'
    && typeof event.experimentId === 'string'
    && typeof event.sequence === 'number' && Number.isFinite(event.sequence)
    && typeof event.type === 'string' && typeof event.timestamp === 'string'
    && !!event.payload && typeof event.payload === 'object'
}

function scheduleProjectRefresh(experimentId: string) {
  if (refreshTimer !== null) window.clearTimeout(refreshTimer)
  refreshTimer = window.setTimeout(async () => {
    refreshTimer = null
    if (store.selectedExperimentId !== experimentId) return
    try {
      await store.reloadSelectedProject()
    } catch (cause) {
      store.error = cause instanceof Error ? cause.message : text('无法刷新任务状态。', 'Unable to refresh task state')
    }
  }, 300)
}

function connectEvents(experimentId: string | null, force = false) {
  // Route changes can run more than once while the project payload is loading.
  // Keep a healthy stream alive, but allow a closed stream to be recreated.
  if (!force && eventSource && eventExperimentId === experimentId && eventSource.readyState !== EventSource.CLOSED) return
  eventSource?.close()
  eventSource = null
  eventExperimentId = experimentId
  if (refreshTimer !== null) window.clearTimeout(refreshTimer)
  refreshTimer = null
  activity.value = []
  seenSequences.clear()
  eventWarning.value = null
  streamState.value = experimentId ? 'connecting' : 'idle'
  if (!experimentId) return

  // EventSource does not expose arbitrary headers. Explicit credentials keep
  // the HttpOnly session cookie attached when the frontend is served through a
  // development/reverse-proxy origin.
  const source = new EventSource(`/api/experiments/${experimentId}/events`, { withCredentials: true })
  eventSource = source
  source.onopen = () => { if (eventSource === source) streamState.value = 'live' }
  source.onerror = () => {
    if (eventSource !== source) return
    streamState.value = source.readyState === EventSource.CLOSED ? 'offline' : 'reconnecting'
  }
  source.onmessage = (message) => {
    if (eventSource !== source) return
    let parsed: unknown
    try { parsed = JSON.parse(message.data) } catch {
      eventWarning.value = text('事件内容无法解析。', 'An event payload could not be parsed.')
      return
    }
    if (!validRunEvent(parsed)) {
      eventWarning.value = text('已忽略一个格式无效的事件。', 'An event with an invalid shape was ignored.')
      return
    }
    if (parsed.experimentId !== experimentId) {
      eventWarning.value = text('已忽略了属于其他任务的事件。', 'An event for another task was ignored.')
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
    showReview.value = false
    mobileNavOpen.value = true
    forceNewTask.value = false
    connectEvents(null)
    return
  }
  if (store.projects.length === 0 && store.error) {
    connectEvents(null)
    return
  }
  if (!store.projects.some((project) => project.id === desiredProject)) {
    store.error = text('链接中的项目不可用，请从项目列表重新选择。', 'The linked project is unavailable. Choose it again from the project list.')
    connectEvents(null)
    await router.replace({ name: 'home' })
    return
  }
  if (store.selectedProjectId !== desiredProject) {
    await store.selectProject(desiredProject, desiredExperiment)
    if (syncId !== routeSyncVersion) return
    if (desiredExperiment && store.selectedExperimentId !== desiredExperiment) {
      store.error = text('链接的任务不可用。', 'The linked task is unavailable.')
      connectEvents(null)
      await router.replace({ name: 'project', params: { projectId: desiredProject } })
      return
    }
  } else if (desiredExperiment && store.selectedExperimentId !== desiredExperiment) {
    if (store.experiments.some((experiment) => experiment.id === desiredExperiment)) {
      await store.selectExperiment(desiredExperiment)
    } else {
      store.error = text('链接的任务不可用。', 'The linked task is unavailable.')
      connectEvents(null)
      await router.replace({ name: 'project', params: { projectId: desiredProject } })
      return
    }
  }
  showReview.value = !!desiredExperiment
  if (syncId !== routeSyncVersion) return
  if (!eventSource || eventExperimentId !== store.selectedExperimentId || eventSource.readyState === EventSource.CLOSED) {
    connectEvents(store.selectedExperimentId)
  }
}

async function openProject(projectId: string) {
  mobileNavOpen.value = false
  forceNewTask.value = false
  await router.push({ name: 'project', params: { projectId } })
}

async function selectSession(sessionId: string) {
  mobileNavOpen.value = false
  forceNewTask.value = false
  await store.selectSession(sessionId)
  if (!store.selectedProjectId) return
  const latest = store.activeExperiments.at(-1)
  if (latest) await router.push({ name: 'project', params: { projectId: store.selectedProjectId } })
  else if (experimentParam.value) await router.push({ name: 'project', params: { projectId: store.selectedProjectId } })
}

async function openReview(experimentId: string) {
  mobileNavOpen.value = false
  await store.selectExperiment(experimentId)
  showReview.value = true
  if (store.selectedProjectId) {
    await router.push({ name: 'experiment', params: { projectId: store.selectedProjectId, experimentId } })
  }
}

async function selectExperiment(experimentId: string) {
  await store.selectExperiment(experimentId)
  if (store.selectedProjectId) {
    // Keep browser history/deep links aligned with selections made in the
    // conversation list, including historical turns.
    await router.push({ name: 'experiment', params: { projectId: store.selectedProjectId, experimentId } })
  }
}

async function closeReview() {
  showReview.value = false
  if (store.selectedProjectId) await router.push({ name: 'project', params: { projectId: store.selectedProjectId } })
}

async function continueFromReview() {
  await closeReview()
  await threadRef.value?.focusComposer()
}

async function submitProject(value: { name: string; canonicalPath: string; verificationCommands: string[] }) {
  submitting.value = true
  store.error = null
  try {
    const project = editingProject.value
      ? await store.updateProject(value)
      : await store.createProject(value)
    if (!project) return
    const reopened = 'project' in project && project.reopened
    const selected = 'project' in project ? project.project : project
    editingProject.value = null
    showProjectDialog.value = false
    await router.push({ name: 'project', params: { projectId: selected.id } })
    if (reopened) {
      store.error = text('已打开这个账户中的现有项目；名称和验收命令沿用原设置，可通过顶部编辑按钮修改。', 'Opened the existing project in this account. Its saved name and acceptance commands were kept; use the edit button in the header to change them.')
    }
  } catch (cause) {
    store.error = projectSaveError(cause)
  } finally { submitting.value = false }
}

function openProjectDialog() {
  store.error = null
  editingProject.value = null
  showProjectDialog.value = true
}

function editSelectedProject() {
  if (!store.selectedProject) return
  store.error = null
  editingProject.value = store.selectedProject
  showProjectDialog.value = true
}

function defaultSessionTitle(task: string) {
  const compact = task.replace(/\s+/g, ' ').trim()
  return compact.length > 52 ? `${compact.slice(0, 52)}...` : compact
}

async function submitTask(task: string, newTask = false) {
  if (!store.selectedProjectId || submitting.value || actionBusy.value) return
  submitting.value = true
  store.error = null
  try {
    let experiment
    const createFresh = newTask || forceNewTask.value
    if (!createFresh && latestExperiment.value && store.selectedSessionId) {
      experiment = await store.continueExperiment(latestExperiment.value.id, task)
    } else {
      experiment = await store.createExperiment({
        sessionTitle: defaultSessionTitle(task),
        task,
        newSession: true,
      })
      if (experiment && experiment.status === 'READY_TO_RUN') await store.startExperiment(experiment.id)
    }
    if (experiment) {
      forceNewTask.value = false
      showReview.value = false
      await router.push({ name: 'project', params: { projectId: experiment.projectId } })
    }
  } catch (cause) {
    store.error = cause instanceof Error ? cause.message : text('无法开始任务。', 'Unable to start the task')
  } finally { submitting.value = false }
}

async function retryExperiment(experimentId: string) {
  if (submitting.value || actionBusy.value) return
  const source = store.experiments.find((item) => item.id === experimentId)
  if (!source || !store.selectedProjectId) return
  submitting.value = true
  store.error = null
  try {
    const successor = await store.continueExperiment(experimentId, source.task)
    if (successor?.status === 'READY_TO_RUN') await store.startExperiment(successor.id)
    if (successor) {
      showReview.value = false
      await router.push({ name: 'project', params: { projectId: successor.projectId } })
    }
  } catch (cause) {
    store.error = cause instanceof Error ? cause.message : text('无法重试任务。', 'Unable to retry the task')
  } finally {
    submitting.value = false
  }
}

function openSettings() {
  showReview.value = false
  showPromotionDialog.value = false
  const query: Record<string, string> = {}
  if (store.selectedProjectId) query.projectId = store.selectedProjectId
  if (store.selectedExperimentId) query.experimentId = store.selectedExperimentId
  void router.push({ name: 'settings', query })
}

async function startNewTask() {
  forceNewTask.value = true
  mobileNavOpen.value = false
  await store.selectSession(null)
  store.clearExperimentSelection()
  if (store.selectedProjectId) {
    await router.push({ name: 'project', params: { projectId: store.selectedProjectId } })
  }
  await threadRef.value?.focusComposer()
}

async function startExperiment(experimentId: string) {
  await runAction(() => store.startExperiment(experimentId))
}

async function cancelExperiment(experimentId: string) {
  await runAction(() => store.cancelExperiment(experimentId))
}

async function reconcileSelected() {
  const experiment = store.selectedExperiment
  if (!experiment) return
  await runAction(() => store.reconcilePromotion(experiment.id))
}

async function reconcileProject() {
  await runAction(() => store.reconcileProjectRecovery())
}

async function runAction(action: () => Promise<void>, closePromotion = false) {
  if (actionBusy.value) return
  actionBusy.value = true
  store.error = null
  try {
    await action()
    if (closePromotion) showPromotionDialog.value = false
  } catch (cause) {
    store.error = cause instanceof Error ? cause.message : text('任务操作失败。', 'Task action failed')
  } finally { actionBusy.value = false }
}

async function confirmPromotionDecision() {
  const experiment = store.selectedExperiment
  const preview = store.promotionPreview
  if (!experiment || !preview) return
  await runAction(() => preview.conflict
    ? store.confirmExperimentStale(experiment.id)
    : store.promoteExperiment(experiment.id), true)
}

async function refresh() {
  const projectBeforeRefresh = store.selectedProjectId
  store.error = null
  await store.loadProjects()
  await syncRoute()
  if (projectBeforeRefresh && store.selectedProjectId === projectBeforeRefresh) {
    try { await store.reloadSelectedProject() } catch (cause) {
      store.error = cause instanceof Error ? cause.message : text('无法刷新。', 'Unable to refresh')
    }
  }
}

function handleGlobalKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape') return
  if (showPromotionDialog.value) showPromotionDialog.value = false
  else if (showReview.value) void closeReview()
  else mobileNavOpen.value = false
}

watch(() => route.fullPath, () => void syncRoute())
watch(() => store.selectedExperimentId, (next) => connectEvents(next))
watch(() => store.selectedExperimentId, () => {
  // A promotion confirmation belongs to one experiment. Never leave it open
  // while the user navigates to another historical turn.
  showPromotionDialog.value = false
})

onMounted(async () => {
  // Pinia stores outlive this view. Clear the previous account's project and
  // experiment selection before loading the newly authenticated user's data.
  store.clearSelection()
  await store.loadProjects()
  initialized.value = true
  await syncRoute()
  // The Pinia store survives route changes, while this view's EventSource does
  // not. Reconnect explicitly when returning from Settings or another route.
  connectEvents(store.selectedExperimentId)
  window.addEventListener('keydown', handleGlobalKeydown)
})

onUnmounted(() => {
  eventSource?.close()
  eventSource = null
  eventExperimentId = null
  if (refreshTimer !== null) window.clearTimeout(refreshTimer)
  window.removeEventListener('keydown', handleGlobalKeydown)
  store.clearSelection()
})
</script>

<template>
  <div class="agent-shell">
    <header class="agent-topbar">
      <button class="brand-lockup" :aria-label="text('打开项目列表', 'Open project list')" :title="text('打开项目列表', 'Open project list')" @click="router.push({ name: 'home' })">
        <span class="brand-mark">O</span>
        <span><strong>Offcanon</strong><small>{{ text('Coding Agent', 'Coding Agent') }}</small></span>
      </button>
       <div class="topbar-project" v-if="store.selectedProject"><span class="topbar-project-dot" />{{ store.selectedProject.name }}<button class="icon-button small" :aria-label="text('编辑项目', 'Edit project')" :title="text('编辑项目', 'Edit project')" @click="editSelectedProject"><Edit3 :size="13" /></button></div>
      <div class="topbar-context">
        <span v-if="store.selectedExperimentId" class="connection-state" :class="streamState"><span class="stream-dot" :class="streamState" />{{ connectionLabel }}</span>
        <button class="icon-button" :aria-label="text('刷新', 'Refresh')" :title="text('刷新', 'Refresh')" :disabled="store.loading" @click="refresh"><RefreshCw :class="{ spin: store.loading }" :size="16" /></button>
        <button class="account-button" :aria-label="text('打开设置', 'Open settings')" :title="text('设置', 'Settings')" @click="openSettings"><span>{{ accountInitials }}</span><Settings :size="14" /></button>
      </div>
    </header>

    <div v-if="store.error" class="global-alert" role="alert">
      <CircleDot :size="16" /><span>{{ store.error }}</span><button class="icon-button small" :aria-label="text('关闭错误', 'Dismiss error')" :title="text('关闭', 'Dismiss')" @click="store.error = null"><X :size="15" /></button>
    </div>

    <div v-if="store.selectedProject && store.promotionRecovery?.recoveryRequired" class="project-recovery-banner" role="alert">
      <AlertTriangle :size="17" />
      <div>
        <strong>{{ text('项目需要恢复', 'Project recovery required') }}</strong>
        <span>{{ text('上一笔应用操作尚未完成，新的应用会被阻止。请先确认当前项目状态。', 'An earlier project update is unresolved, so new applications are blocked. Reconcile the current project state first.') }}</span>
      </div>
      <button class="button warning compact" :disabled="actionBusy" @click="reconcileProject"><RefreshCw :class="{ spin: actionBusy }" :size="14" />{{ text('恢复项目', 'Reconcile project') }}</button>
    </div>
    <div v-else-if="store.selectedProject && store.promotionRecoveryError" class="project-recovery-banner error" role="alert">
      <AlertTriangle :size="17" />
      <div>
        <strong>{{ text('无法确认项目写回状态', 'Project write-back state is unknown') }}</strong>
        <span>{{ store.promotionRecoveryError }}</span>
      </div>
      <button class="button secondary compact" :disabled="store.loading" @click="refresh"><RefreshCw :class="{ spin: store.loading }" :size="14" />{{ text('重新检查', 'Check again') }}</button>
    </div>

    <div class="agent-frame" :class="{ 'nav-open': mobileNavOpen, 'no-project': !store.selectedProject }">
      <button v-if="mobileNavOpen && store.selectedProject" class="agent-nav-scrim" :aria-label="text('关闭任务导航', 'Close task navigation')" @click="mobileNavOpen = false" />
      <TaskSidebar
        :projects="store.projects"
        :sessions="store.sessions"
        :experiments="store.experiments"
        :selected-project-id="store.selectedProjectId"
        :selected-session-id="store.selectedSessionId"
        :loading="store.loading"
         @add-project="openProjectDialog"
        @select-project="openProject"
        @select-session="selectSession"
        @new-task="startNewTask"
        @close="mobileNavOpen = false"
      />

      <AgentThread
        ref="threadRef"
        :project="store.selectedProject"
        :session="store.activeSession"
        :experiments="activeExperiments"
        :selected-experiment-id="store.selectedExperimentId"
        :activity="activity"
        :stream-state="streamState"
        :action-busy="actionBusy || submitting"
        :detail-loading="store.detailLoading"
        :promotion-preview="store.promotionPreview"
        @open-navigation="mobileNavOpen = true"
        @submit="submitTask"
        @new-task="startNewTask"
        @select="selectExperiment"
        @review="openReview"
        @open-settings="openSettings"
        @retry="retryExperiment"
        @reconnect="store.selectedExperimentId && connectEvents(store.selectedExperimentId, true)"
        @start="startExperiment"
        @cancel="cancelExperiment"
      />
    </div>

    <Transition name="drawer">
      <div v-if="showReview && store.selectedExperiment" class="review-overlay">
        <button class="review-overlay-scrim" :aria-label="text('关闭审阅', 'Close review')" @click="closeReview" />
        <aside class="review-drawer" role="dialog" aria-modal="true" :aria-label="text('任务审阅', 'Task review')">
          <header class="review-drawer-header">
            <div><span>{{ text('任务审阅', 'TASK REVIEW') }}</span><strong>EXP-{{ store.selectedExperiment.id.slice(0, 8).toUpperCase() }}</strong></div>
            <button class="icon-button" :aria-label="text('关闭审阅', 'Close review')" :title="text('关闭', 'Close')" @click="closeReview"><X :size="17" /></button>
          </header>
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
            :detail-error="store.detailError"
            :evidence-error="store.evidenceError"
            :diff-error="store.diffError"
            :promotion-preview-error="store.promotionPreviewError"
            :promotion-recovery-error="store.promotionRecoveryError"
            @back="closeReview"
            @start="store.selectedExperiment && startExperiment(store.selectedExperiment.id)"
            @cancel="store.selectedExperiment && cancelExperiment(store.selectedExperiment.id)"
            @promote="showPromotionDialog = true"
            @reconcile="reconcileSelected"
            @open-settings="openSettings"
            @retry="store.selectedExperiment && retryExperiment(store.selectedExperiment.id)"
            @reconnect="store.selectedExperimentId && connectEvents(store.selectedExperimentId, true)"
            @continue-task="continueFromReview"
          />
        </aside>
      </div>
    </Transition>

    <ProjectDialog v-if="showProjectDialog" :project="editingProject" :busy="submitting" :error="store.error" @close="showProjectDialog = false; editingProject = null" @submit="submitProject" />
    <PromotionDialog
      v-if="showPromotionDialog && store.selectedExperiment && store.promotionPreview"
      :experiment="store.selectedExperiment"
      :preview="store.promotionPreview"
      :changed-file-count="store.diff.length"
      :deleted-file-count="store.diff.filter((item) => item.change === 'DELETED').length"
      :busy="actionBusy"
      @close="showPromotionDialog = false"
      @confirm="confirmPromotionDecision"
    />
  </div>
</template>
