<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { AlertTriangle, Archive, Ban, CheckCircle2, CircleDot, FileDiff, FolderGit2, GitBranch, LoaderCircle, Plus, RefreshCw, ShieldAlert, ShieldCheck, TerminalSquare, Users, X } from 'lucide-vue-next'
import { useWorkspaceStore } from './stores/workspace'

const store = useWorkspaceStore()
const showProjectForm = ref(false)
const showExperimentForm = ref(false)
const submitting = ref(false)
const actionBusy = ref(false)
const projectForm = ref({ name: '', canonicalPath: '', verificationCommands: '' })
const experimentForm = ref({ sessionTitle: 'New session', task: '', newSession: false })
const activity = ref<Array<{ sequence: number; type: string; timestamp: string; payload: Record<string, unknown> }>>([])
let eventSource: EventSource | null = null

const statusLabel = (status: string) => status.replaceAll('_', ' ')
const statusTone = (status: string) => {
  if (status === 'VERIFIED' || status === 'PROMOTED') return 'success'
  if (status === 'FAILED' || status === 'REJECTED' || status === 'STALE' || status === 'RECOVERY_REQUIRED') return 'danger'
  if (status === 'RUNNING' || status === 'VERIFYING' || status === 'PREPARING_PROMOTION' || status === 'PROMOTING') return 'active'
  return 'neutral'
}
const selectedStatus = computed(() => store.selectedExperiment?.status ?? 'CANONICAL')
const trustedEvidence = computed(() => store.evidence.filter((item) => item.trusted))
const invalidatedVerification = computed(() => store.evidence.filter((item) => !item.trusted && item.kind !== 'AGENT_COMMAND'))
const agentObservations = computed(() => store.evidence.filter((item) => item.kind === 'AGENT_COMMAND'))
const evidencePassed = (item: { exitCode: number; timedOut: boolean; cancelled: boolean }) =>
  item.exitCode === 0 && !item.timedOut && !item.cancelled
const shortFingerprint = (value: string | null) => value ? value.slice(0, 12) : 'not available'

async function submitProject() {
  if (!projectForm.value.name || !projectForm.value.canonicalPath) return
  submitting.value = true
  try {
    await store.createProject({
      name: projectForm.value.name,
      canonicalPath: projectForm.value.canonicalPath,
      verificationCommands: projectForm.value.verificationCommands.split('\n').map((line) => line.trim()).filter(Boolean),
    })
    projectForm.value = { name: '', canonicalPath: '', verificationCommands: '' }
    showProjectForm.value = false
  } catch (cause) {
    store.error = cause instanceof Error ? cause.message : 'Unable to create project'
  } finally {
    submitting.value = false
  }
}

async function submitExperiment() {
  if (!experimentForm.value.task) return
  submitting.value = true
  try {
    await store.createExperiment(experimentForm.value)
    experimentForm.value = { sessionTitle: 'New session', task: '', newSession: false }
    showExperimentForm.value = false
  } catch (cause) {
    store.error = cause instanceof Error ? cause.message : 'Unable to create experiment'
  } finally {
    submitting.value = false
  }
}

function openExperimentForm() {
  experimentForm.value.newSession = !store.selectedSessionId
  showExperimentForm.value = true
}

async function runExperimentAction(action: () => Promise<void>) {
  if (actionBusy.value) return
  actionBusy.value = true
  store.error = null
  try {
    await action()
  } catch (cause) {
    store.error = cause instanceof Error ? cause.message : 'Experiment action failed'
  } finally {
    actionBusy.value = false
  }
}

async function refresh() {
  if (store.selectedProjectId) await store.selectProject(store.selectedProjectId)
  else await store.loadProjects()
}

function connectEvents(experimentId: string | null) {
  eventSource?.close()
  eventSource = null
  activity.value = []
  if (!experimentId) return
  eventSource = new EventSource(`/api/experiments/${experimentId}/events`)
  eventSource.onmessage = (message) => {
    const event = JSON.parse(message.data) as { sequence: number; type: string; timestamp: string; payload: Record<string, unknown> }
    if (!activity.value.some((item) => item.sequence === event.sequence)) activity.value.push(event)
    if (['AGENT_COMPLETED', 'RESULT_SNAPSHOT_SEALED', 'VERIFICATION_STARTED', 'VERIFICATION_FINISHED',
      'PROMOTION_PREPARING', 'PROMOTION_VERIFICATION_STARTED', 'PROMOTION_BLOCKED', 'EXPERIMENT_FAILED', 'PROMOTED'].includes(event.type)) {
      window.setTimeout(() => store.reloadSelectedProject(), 120)
    }
  }
}

watch(() => store.selectedExperimentId, connectEvents)
onUnmounted(() => eventSource?.close())

onMounted(() => store.loadProjects())
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand-lockup">
        <div class="brand-mark">P</div>
        <div>
          <div class="brand-name">PICO</div>
          <div class="brand-subtitle">experiment workbench</div>
        </div>
      </div>
      <div class="topbar-meta">
        <span class="workspace-chip canonical"><CircleDot :size="13" /> CANONICAL</span>
        <span class="connection-state"><span class="live-dot" /> local runtime</span>
        <button class="icon-button" title="Refresh" @click="refresh"><RefreshCw :size="16" /></button>
      </div>
    </header>

    <div class="workspace-grid">
      <aside class="sidebar panel-divider-right">
        <div class="section-heading">
          <span>PROJECTS</span>
          <button class="icon-button small" title="Register project" @click="showProjectForm = true"><Plus :size="15" /></button>
        </div>
        <div v-if="store.projects.length === 0 && !store.loading" class="empty-sidebar">
          <FolderGit2 :size="20" />
          <span>No projects registered</span>
        </div>
        <button
          v-for="project in store.projects"
          :key="project.id"
          class="project-row"
          :class="{ selected: project.id === store.selectedProjectId }"
          @click="store.selectProject(project.id)"
        >
          <FolderGit2 :size="16" />
          <span class="project-name">{{ project.name }}</span>
          <span class="project-count">{{ project.id === store.selectedProjectId ? store.experiments.length : '·' }}</span>
        </button>
        <div v-if="store.sessions.length" class="session-list">
          <div class="section-heading"><span>SESSIONS</span><span class="muted">{{ store.sessions.length }}</span></div>
          <button class="session-row" :class="{ selected: !store.selectedSessionId }" @click="store.selectSession(null)">
            <Users :size="14" /><span>All sessions</span>
          </button>
          <button v-for="session in store.sessions" :key="session.id" class="session-row" :class="{ selected: session.id === store.selectedSessionId }" @click="store.selectSession(session.id)">
            <Users :size="14" /><span>{{ session.title }}</span><span class="project-count">{{ store.experiments.filter((item) => item.sessionId === session.id).length }}</span>
          </button>
        </div>
        <div class="sidebar-footer">
          <div class="mini-stat"><span>Isolation</span><strong>LOCAL</strong></div>
          <div class="mini-stat"><span>Model</span><strong>ADAPTER</strong></div>
        </div>
      </aside>

      <main class="main-column">
        <div class="main-header">
          <div>
            <div class="eyebrow">PROJECT / {{ store.selectedProject?.name ?? 'SELECT A PROJECT' }}</div>
            <h1>Experiments</h1>
          </div>
          <button class="button primary" :disabled="!store.selectedProject" @click="openExperimentForm"><Plus :size="16" /> New experiment</button>
        </div>

        <div v-if="store.error" class="alert error"><Ban :size="16" /><span>{{ store.error }}</span><button class="icon-button small" title="Dismiss" @click="store.error = null"><X :size="14" /></button></div>

        <section v-if="store.visibleExperiments.length" class="timeline-section">
          <div class="section-heading"><span>RUN HISTORY</span><span class="muted">{{ store.visibleExperiments.length }} experiments</span></div>
          <div class="timeline">
            <button
              v-for="experiment in store.visibleExperiments"
              :key="experiment.id"
              class="timeline-row"
              :class="{ selected: experiment.id === store.selectedExperimentId }"
              @click="store.selectExperiment(experiment.id)"
            >
              <div class="timeline-marker" :class="statusTone(experiment.status)"><CheckCircle2 v-if="experiment.status === 'VERIFIED' || experiment.status === 'PROMOTED'" :size="16" /><LoaderCircle v-else-if="experiment.status === 'RUNNING' || experiment.status === 'VERIFYING'" class="spin" :size="16" /><CircleDot v-else :size="16" /></div>
              <div class="timeline-content">
                <div class="timeline-topline"><span class="experiment-id">EXP-{{ experiment.id.slice(0, 8).toUpperCase() }}</span><span class="status" :class="statusTone(experiment.status)">{{ statusLabel(experiment.status) }}</span></div>
                <div class="task-line">{{ experiment.task }}</div>
                <div class="timeline-meta"><span>{{ new Date(experiment.createdAt).toLocaleString() }}</span><span>{{ store.sessionTitle(experiment.sessionId) }}</span><span>base {{ experiment.baseSnapshotId?.slice(0, 8) ?? 'pending' }}</span></div>
              </div>
            </button>
          </div>
        </section>
        <section v-else class="empty-state">
          <Archive :size="24" />
          <h2>No experiments yet</h2>
          <p>Register a project, then create an isolated experiment.</p>
          <button class="button secondary" :disabled="!store.selectedProject" @click="openExperimentForm"><Plus :size="16" /> Create experiment</button>
        </section>
      </main>

      <aside class="detail-column">
        <div class="detail-header"><span class="eyebrow">INSPECTOR</span><span class="workspace-chip" :class="statusTone(selectedStatus)">{{ store.selectedExperiment ? statusLabel(selectedStatus) : 'CANONICAL' }}</span></div>
        <template v-if="store.selectedExperiment">
          <div class="detail-title"><span class="experiment-id">EXP-{{ store.selectedExperiment.id.slice(0, 8).toUpperCase() }}</span><h2>{{ store.selectedExperiment.task }}</h2></div>
          <div class="detail-block"><div class="detail-label">EXECUTION WORLD</div><div class="world-row"><div class="world-icon experiment"><GitBranch :size="15" /></div><div><strong>Experiment workspace</strong><span>{{ store.selectedExperiment.workspacePath ?? 'not materialized' }}</span></div></div><div class="world-row"><div class="world-icon canonical"><ShieldCheck :size="15" /></div><div><strong>Canonical</strong><span>{{ store.selectedExperiment.status === 'PROMOTED' ? 'updated from the verified candidate' : 'unchanged until promotion' }}</span></div></div></div>
          <div v-if="store.promotionPreview" class="detail-block promotion-preview" :class="{ conflict: store.promotionPreview.conflict }">
            <div class="detail-label"><GitBranch :size="14" /> PROMOTION PREVIEW</div>
            <div class="fingerprint-list">
              <div class="fingerprint-row"><span>Base</span><code :title="store.promotionPreview.baseFingerprint ?? ''">{{ shortFingerprint(store.promotionPreview.baseFingerprint) }}</code></div>
              <div class="fingerprint-row"><span>Current</span><code :title="store.promotionPreview.currentFingerprint ?? ''">{{ shortFingerprint(store.promotionPreview.currentFingerprint) }}</code></div>
              <div class="fingerprint-row final"><span>Final candidate</span><code :title="store.promotionPreview.finalCandidateFingerprint ?? ''">{{ shortFingerprint(store.promotionPreview.finalCandidateFingerprint) }}</code></div>
            </div>
            <div class="verification-summary" :class="{ trusted: store.promotionPreview.trustedVerification, passed: store.promotionPreview.verificationStatus === 'PASSED' }">
              <ShieldCheck v-if="store.promotionPreview.trustedVerification && store.promotionPreview.verificationStatus === 'PASSED'" :size="16" />
              <ShieldAlert v-else-if="store.promotionPreview.trustedVerification" :size="16" />
              <CircleDot v-else :size="16" />
              <div><strong>{{ store.promotionPreview.trustedVerification ? 'Trusted verification' : 'Verification pending' }}</strong><span>{{ statusLabel(store.promotionPreview.verificationStatus) }}</span></div>
            </div>
            <div v-if="store.promotionPreview.blockingReason" class="preview-blocker" :class="{ conflict: store.promotionPreview.conflict }"><AlertTriangle :size="14" /><span>{{ store.promotionPreview.blockingReason }}</span></div>
          </div>
          <div class="detail-block"><div class="detail-label">SESSION</div><strong class="session-title">{{ store.sessionTitle(store.selectedExperiment.sessionId) }}</strong><code>{{ store.selectedExperiment.sessionId }}</code></div>
          <div v-if="store.selectedExperiment.failureReason" class="detail-block failure"><div class="detail-label">FAILURE</div><p>{{ store.selectedExperiment.failureReason }}</p></div>
          <div v-if="store.promotionOutcome" class="detail-block" :class="{ failure: !store.promotionOutcome.promoted }"><div class="detail-label">PROMOTION DECISION</div><p>{{ store.promotionOutcome.status.replaceAll('_', ' ') }}: {{ store.promotionOutcome.detail }}</p><div v-if="store.promotionOutcome.changedFiles.length" class="evidence-meta">{{ store.promotionOutcome.changedFiles.length }} files applied · {{ store.promotionOutcome.fingerprint }}</div></div>
          <div class="detail-actions">
            <button v-if="store.selectedExperiment.status === 'READY_TO_RUN'" class="button primary" :disabled="actionBusy" @click="runExperimentAction(() => store.startExperiment(store.selectedExperiment!.id))"><LoaderCircle :class="{ spin: actionBusy }" :size="16" /> Start agent</button>
            <button v-if="store.selectedExperiment.status === 'VERIFIED'" class="button primary" :disabled="actionBusy || !store.promotionPreview?.promotable" @click="runExperimentAction(() => store.promoteExperiment(store.selectedExperiment!.id))"><ShieldCheck :size="16" /> Promote to canonical</button>
            <button v-if="['READY_TO_RUN', 'RUNNING', 'AGENT_COMPLETED', 'VERIFYING'].includes(store.selectedExperiment.status)" class="button danger-outline" :disabled="actionBusy" @click="runExperimentAction(() => store.cancelExperiment(store.selectedExperiment!.id))"><Ban :size="16" /> Cancel experiment</button>
          </div>
          <div v-if="activity.length" class="detail-block activity-block"><div class="detail-label">LIVE ACTIVITY</div><div v-for="event in activity" :key="event.sequence" class="activity-row"><span class="activity-sequence">{{ String(event.sequence).padStart(2, '0') }}</span><span>{{ event.type.replaceAll('_', ' ') }}</span><span class="activity-time">{{ new Date(event.timestamp).toLocaleTimeString() }}</span></div></div>
          <div v-if="store.diff.length" class="detail-block"><div class="detail-label"><FileDiff :size="14" /> EXPERIMENT DIFF <span class="muted">{{ store.diff.length }} files</span></div><details v-for="item in store.diff" :key="item.path" class="diff-entry"><summary class="diff-row"><span class="diff-kind" :class="item.change.toLowerCase()">{{ item.change === 'MODIFIED' ? 'M' : item.change === 'ADDED' ? '+' : '-' }}</span><code>{{ item.path }}</code><span class="diff-size">{{ item.binary ? 'binary' : `+${item.additions} -${item.deletions}` }}</span></summary><pre class="diff-patch">{{ item.patch }}</pre></details></div>
          <div v-if="trustedEvidence.length" class="detail-block"><div class="detail-label trusted-label"><ShieldCheck :size="14" /> TRUSTED VERIFICATION <span class="muted">{{ trustedEvidence.length }}</span></div><details v-for="item in trustedEvidence" :key="item.id" class="evidence-entry trusted"><summary class="evidence-row"><div><span class="evidence-command">{{ item.command }}</span><span class="evidence-meta">{{ item.kind.replaceAll('_', ' ') }} · {{ item.environmentProfile }} · exit {{ item.exitCode }} · {{ item.durationMillis }}ms · snapshot {{ item.snapshotId.slice(0, 8) }}</span></div><ShieldCheck v-if="evidencePassed(item)" class="evidence-ok" :size="15" /><ShieldAlert v-else class="evidence-fail" :size="15" /></summary><div class="evidence-detail"><div><span class="evidence-meta">cwd</span><code>{{ item.cwd }}</code></div><div><span class="evidence-meta">{{ new Date(item.startedAt).toLocaleString() }} → {{ new Date(item.completedAt).toLocaleString() }}<span v-if="item.cancelled"> · cancelled</span><span v-if="item.timedOut"> · timed out</span></span></div><pre v-if="item.stdout" class="evidence-output">{{ item.stdout }}</pre><pre v-if="item.stderr" class="evidence-output stderr">{{ item.stderr }}</pre></div></details></div>
          <div v-if="invalidatedVerification.length" class="detail-block failure"><div class="detail-label"><ShieldAlert :size="14" /> INVALIDATED VERIFICATION</div><details v-for="item in invalidatedVerification" :key="item.id" class="evidence-entry"><summary class="evidence-row"><div><span class="evidence-command">{{ item.command }}</span><span class="evidence-meta">source integrity check failed · exit {{ item.exitCode }} · snapshot {{ item.snapshotId.slice(0, 8) }}</span></div><ShieldAlert class="evidence-fail" :size="15" /></summary><div class="evidence-detail"><code>{{ item.cwd }}</code><pre v-if="item.stdout" class="evidence-output">{{ item.stdout }}</pre><pre v-if="item.stderr" class="evidence-output stderr">{{ item.stderr }}</pre></div></details></div>
          <div v-if="agentObservations.length" class="detail-block"><div class="detail-label"><TerminalSquare :size="14" /> AGENT COMMAND OBSERVATIONS <span class="observation-badge">OBSERVATION ONLY</span></div><details v-for="item in agentObservations" :key="item.id" class="evidence-entry observation"><summary class="evidence-row"><div><span class="evidence-command">{{ item.command }}</span><span class="evidence-meta">{{ item.environmentProfile }} · exit {{ item.exitCode }} · {{ item.durationMillis }}ms · snapshot {{ item.snapshotId.slice(0, 8) }}</span></div><TerminalSquare v-if="evidencePassed(item)" class="observation-icon" :size="15" /><Ban v-else class="evidence-fail" :size="15" /></summary><div class="evidence-detail"><div><span class="evidence-meta">cwd</span><code>{{ item.cwd }}</code></div><div><span class="evidence-meta">{{ new Date(item.startedAt).toLocaleString() }} → {{ new Date(item.completedAt).toLocaleString() }}<span v-if="item.cancelled"> · cancelled</span><span v-if="item.timedOut"> · timed out</span></span></div><pre v-if="item.stdout" class="evidence-output">{{ item.stdout }}</pre><pre v-if="item.stderr" class="evidence-output stderr">{{ item.stderr }}</pre></div></details></div>
        </template>
        <div v-else class="detail-empty"><TerminalSquare :size="24" /><span>Select an experiment to inspect its execution state.</span></div>
      </aside>
    </div>

    <div v-if="showProjectForm || showExperimentForm" class="modal-backdrop" @click.self="showProjectForm = false; showExperimentForm = false">
      <form v-if="showProjectForm" class="modal" @submit.prevent="submitProject"><div class="modal-heading"><div><div class="eyebrow">NEW PROJECT</div><h2>Register canonical workspace</h2></div><button type="button" class="icon-button" title="Close" @click="showProjectForm = false"><X :size="17" /></button></div><label>Project name<input v-model="projectForm.name" required placeholder="Example service" /></label><label>Canonical path<input v-model="projectForm.canonicalPath" required placeholder="D:\\code\\example" /></label><label>Verification commands <span class="muted">one per line</span><textarea v-model="projectForm.verificationCommands" rows="3" placeholder="mvn test"></textarea></label><div class="modal-actions"><button type="button" class="button secondary" @click="showProjectForm = false">Cancel</button><button class="button primary" :disabled="submitting"><LoaderCircle v-if="submitting" class="spin" :size="16" /> Register project</button></div></form>
      <form v-else class="modal" @submit.prevent="submitExperiment"><div class="modal-heading"><div><div class="eyebrow">NEW EXPERIMENT</div><h2>Start from current canonical</h2></div><button type="button" class="icon-button" title="Close" @click="showExperimentForm = false"><X :size="17" /></button></div><label class="check-row"><input v-model="experimentForm.newSession" type="checkbox" /> Create a new session</label><label>Session title<input v-model="experimentForm.sessionTitle" required :disabled="!experimentForm.newSession" /></label><label>Task<textarea v-model="experimentForm.task" required rows="4" placeholder="Describe the programming task"></textarea></label><div class="modal-actions"><button type="button" class="button secondary" @click="showExperimentForm = false">Cancel</button><button class="button primary" :disabled="submitting"><LoaderCircle v-if="submitting" class="spin" :size="16" /> Create experiment</button></div></form>
    </div>
  </div>
</template>
