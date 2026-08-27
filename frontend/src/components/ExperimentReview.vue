<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  AlertTriangle,
  ArrowLeft,
  Ban,
  CheckCircle2,
  CircleDot,
  FileCheck2,
  FileDiff,
  FlaskConical,
  GitBranch,
  GitCommitHorizontal,
  LoaderCircle,
  RotateCcw,
  ShieldAlert,
  ShieldCheck,
  TerminalSquare,
} from 'lucide-vue-next'
import type {
  DiffEntry,
  Evidence,
  Experiment,
  Project,
  PromotionOutcome,
  PromotionPreview,
  PromotionReconcile,
  RunEvent,
} from '../api'
import { formatDate, formatDuration, shortFingerprint, shortId, statusLabel, statusTone } from '../ui'

type ReviewTab = 'summary' | 'changes' | 'evidence' | 'activity'
type StreamState = 'idle' | 'connecting' | 'live' | 'reconnecting' | 'offline'

const props = defineProps<{
  project: Project | null
  experiment: Experiment | null
  diff: DiffEntry[]
  evidence: Evidence[]
  promotionPreview: PromotionPreview | null
  promotionOutcome: PromotionOutcome | null
  promotionReconcile: PromotionReconcile | null
  activity: RunEvent[]
  streamState: StreamState
  eventWarning: string | null
  actionBusy: boolean
  detailLoading: boolean
}>()
const emit = defineEmits<{
  back: []
  start: []
  cancel: []
  promote: []
  reconcile: []
}>()

const activeTab = ref<ReviewTab>('summary')
const tabs: Array<{ id: ReviewTab; label: string }> = [
  { id: 'summary', label: 'Summary' },
  { id: 'changes', label: 'Changes' },
  { id: 'evidence', label: 'Evidence' },
  { id: 'activity', label: 'Activity' },
]

const trustedEvidence = computed(() => props.evidence.filter((item) => item.trusted))
const invalidatedEvidence = computed(() => props.evidence.filter((item) => !item.trusted && item.kind !== 'AGENT_COMMAND'))
const observations = computed(() => props.evidence.filter((item) => item.kind === 'AGENT_COMMAND'))
const passedTrustedEvidence = computed(() => trustedEvidence.value.filter(evidencePassed))
const totalAdditions = computed(() => props.diff.reduce((sum, item) => sum + item.additions, 0))
const totalDeletions = computed(() => props.diff.reduce((sum, item) => sum + item.deletions, 0))
const receiptFingerprint = computed(() => props.promotionOutcome?.fingerprint
  ?? props.promotionPreview?.currentFingerprint
  ?? null)
const receiptFiles = computed(() => props.promotionOutcome?.changedFiles.length ?? props.diff.length)
const canCancel = computed(() => props.experiment
  ? ['READY_TO_RUN', 'RUNNING', 'AGENT_COMPLETED', 'VERIFYING'].includes(props.experiment.status)
  : false)

function evidencePassed(item: Evidence) {
  return item.exitCode === 0 && !item.timedOut && !item.cancelled
}

function eventPayload(event: RunEvent) {
  return JSON.stringify(event.payload, null, 2)
}

function activateWithKeyboard(event: KeyboardEvent, index: number) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const next = event.key === 'Home' ? 0
    : event.key === 'End' ? tabs.length - 1
      : (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length
  activeTab.value = tabs[next].id
  document.getElementById(`review-tab-${tabs[next].id}`)?.focus()
}
</script>

<template>
  <main class="review-surface" aria-label="Experiment review">
    <template v-if="experiment">
      <header class="review-header">
        <button class="icon-button mobile-only" aria-label="Back to experiments" title="Experiments" @click="emit('back')"><ArrowLeft :size="18" /></button>
        <div class="review-title">
          <p class="eyebrow">{{ project?.name }} / EXP-{{ shortId(experiment.id) }}</p>
          <h2>{{ experiment.task }}</h2>
        </div>
        <span class="status-badge prominent" :class="statusTone(experiment.status)">{{ statusLabel(experiment.status) }}</span>
      </header>

      <div class="decision-bar" :class="statusTone(experiment.status)">
        <div class="decision-icon">
          <CheckCircle2 v-if="experiment.status === 'VERIFIED' || experiment.status === 'PROMOTED'" :size="20" />
          <AlertTriangle v-else-if="['RECOVERY_REQUIRED', 'STALE', 'FAILED', 'REJECTED'].includes(experiment.status)" :size="20" />
          <LoaderCircle v-else-if="['RUNNING', 'VERIFYING', 'PROMOTING'].includes(experiment.status)" class="spin" :size="20" />
          <FlaskConical v-else :size="20" />
        </div>
        <div class="decision-copy">
          <strong v-if="experiment.status === 'VERIFIED'">Candidate verified. Canonical is still unchanged.</strong>
          <strong v-else-if="experiment.status === 'PROMOTED'">Verified candidate is now canonical.</strong>
          <strong v-else-if="experiment.status === 'RECOVERY_REQUIRED'">Canonical state needs reconciliation.</strong>
          <strong v-else-if="experiment.status === 'READY_TO_RUN'">Isolated workspace is ready.</strong>
          <strong v-else>{{ statusLabel(experiment.status) }}</strong>
          <span v-if="experiment.status === 'VERIFIED' && detailLoading">Loading the sealed diff and trusted evidence.</span>
          <span v-else-if="experiment.status === 'VERIFIED'">Review {{ diff.length }} changed files and {{ trustedEvidence.length }} trusted checks before promotion.</span>
          <span v-else-if="experiment.status === 'PROMOTED'">Promotion receipt is available in this review.</span>
          <span v-else-if="experiment.status === 'RECOVERY_REQUIRED'">Inspect the recorded state, then run the guarded reconciliation.</span>
          <span v-else-if="experiment.failureReason">{{ experiment.failureReason }}</span>
          <span v-else>Canonical remains protected while this experiment advances.</span>
        </div>
        <div class="decision-actions">
          <button v-if="experiment.status === 'READY_TO_RUN'" class="button primary" :disabled="actionBusy" @click="emit('start')"><LoaderCircle :class="{ spin: actionBusy }" :size="16" /> Start agent</button>
          <button v-if="experiment.status === 'VERIFIED'" class="button success" :disabled="actionBusy || !promotionPreview?.promotable" @click="emit('promote')"><GitCommitHorizontal :size="16" /> Review promotion</button>
          <button v-if="experiment.status === 'RECOVERY_REQUIRED'" class="button warning" :disabled="actionBusy" @click="emit('reconcile')"><RotateCcw :class="{ spin: actionBusy }" :size="16" /> Reconcile state</button>
          <button v-if="canCancel" class="button danger-ghost" :disabled="actionBusy" @click="emit('cancel')"><Ban :size="16" /> Cancel</button>
        </div>
      </div>

      <nav class="review-tabs" role="tablist" aria-label="Experiment review sections">
        <button
          v-for="(tab, index) in tabs"
          :id="`review-tab-${tab.id}`"
          :key="tab.id"
          role="tab"
          :aria-selected="activeTab === tab.id"
          :aria-controls="`review-panel-${tab.id}`"
          :tabindex="activeTab === tab.id ? 0 : -1"
          @click="activeTab = tab.id"
          @keydown="activateWithKeyboard($event, index)"
        >
          {{ tab.label }}
          <small v-if="tab.id === 'changes'">{{ detailLoading ? '...' : diff.length }}</small>
          <small v-else-if="tab.id === 'evidence'">{{ detailLoading ? '...' : trustedEvidence.length }}</small>
          <span v-else-if="tab.id === 'activity'" class="stream-dot" :class="streamState" :title="`Event stream ${streamState}`" />
        </button>
      </nav>

      <section v-show="activeTab === 'summary'" id="review-panel-summary" class="review-panel" role="tabpanel" aria-labelledby="review-tab-summary" tabindex="0">
        <div v-if="detailLoading" class="review-loading" role="status">
          <LoaderCircle class="spin" :size="22" /><strong>Loading review evidence</strong><span>Reading the sealed diff, verification records, and promotion gate.</span>
        </div>
        <template v-else>
        <div v-if="experiment.status === 'PROMOTED'" class="receipt-band">
          <span class="receipt-icon"><ShieldCheck :size="22" /></span>
          <div><p class="eyebrow">PROMOTION RECEIPT</p><h3>Canonical updated from sealed result</h3><span>{{ receiptFiles }} {{ receiptFiles === 1 ? 'file' : 'files' }} applied after trusted verification.</span></div>
          <dl><div><dt>Result</dt><dd><code>{{ shortFingerprint(receiptFingerprint) }}</code></dd></div><div><dt>Snapshot</dt><dd><code>{{ shortId(experiment.resultSnapshotId, 12) }}</code></dd></div></dl>
        </div>

        <div v-if="promotionReconcile" class="outcome-band warning">
          <RotateCcw :size="18" /><div><strong>{{ statusLabel(promotionReconcile.journalPhase) }}</strong><span>{{ promotionReconcile.detail }}</span></div><code>{{ shortFingerprint(promotionReconcile.fingerprint) }}</code>
        </div>
        <div v-if="promotionOutcome && experiment.status !== 'PROMOTED'" class="outcome-band" :class="promotionOutcome.promoted ? 'success' : 'danger'">
          <ShieldCheck v-if="promotionOutcome.promoted" :size="18" /><ShieldAlert v-else :size="18" />
          <div><strong>{{ statusLabel(promotionOutcome.status) }}</strong><span>{{ promotionOutcome.detail }}</span></div>
        </div>
        <div v-if="experiment.failureReason && experiment.status !== 'RECOVERY_REQUIRED'" class="outcome-band danger">
          <ShieldAlert :size="18" /><div><strong>Experiment stopped</strong><span>{{ experiment.failureReason }}</span></div>
        </div>

        <div class="summary-grid">
          <section class="summary-section agent-summary">
            <p class="section-label">AGENT CONCLUSION</p>
            <p v-if="experiment.agentSummary" class="summary-text">{{ experiment.agentSummary }}</p>
            <p v-else class="empty-copy">The agent has not produced a final summary.</p>
          </section>

          <section class="summary-section decision-evidence">
            <p class="section-label">DECISION EVIDENCE</p>
            <dl class="metric-list">
              <div><dt>Changed files</dt><dd>{{ diff.length }} <span>+{{ totalAdditions }} -{{ totalDeletions }}</span></dd></div>
              <div><dt>Trusted checks</dt><dd :class="{ verified: trustedEvidence.length > 0 && passedTrustedEvidence.length === trustedEvidence.length }">{{ passedTrustedEvidence.length }} / {{ trustedEvidence.length }}</dd></div>
              <div><dt>Invalidated checks</dt><dd :class="{ danger: invalidatedEvidence.length > 0 }">{{ invalidatedEvidence.length }}</dd></div>
              <div><dt>Agent observations</dt><dd>{{ observations.length }}</dd></div>
            </dl>
          </section>

          <section class="summary-section execution-worlds">
            <p class="section-label">EXECUTION WORLDS</p>
            <div class="world-line"><span class="world-icon experiment"><FlaskConical :size="16" /></span><div><strong>Experiment</strong><code :title="experiment.workspacePath ?? ''">{{ experiment.workspacePath ?? 'not materialized' }}</code></div></div>
            <div class="world-divider"><span>promotion gate</span></div>
            <div class="world-line"><span class="world-icon canonical"><GitBranch :size="16" /></span><div><strong>Canonical</strong><code :title="project?.canonicalPath">{{ project?.canonicalPath }}</code></div></div>
          </section>

          <section v-if="promotionPreview && experiment.status !== 'PROMOTED'" class="summary-section fingerprint-section">
            <p class="section-label">PROMOTION GATE</p>
            <dl class="fingerprint-list">
              <div><dt>Base</dt><dd><code :title="promotionPreview.baseFingerprint ?? ''">{{ shortFingerprint(promotionPreview.baseFingerprint) }}</code></dd></div>
              <div><dt>Canonical now</dt><dd><code :title="promotionPreview.currentFingerprint ?? ''">{{ shortFingerprint(promotionPreview.currentFingerprint) }}</code></dd></div>
              <div><dt>Sealed result</dt><dd><code :title="promotionPreview.finalCandidateFingerprint ?? ''">{{ shortFingerprint(promotionPreview.finalCandidateFingerprint) }}</code></dd></div>
            </dl>
            <div class="gate-status" :class="promotionPreview.promotable ? 'success' : promotionPreview.conflict ? 'danger' : 'neutral'">
              <ShieldCheck v-if="promotionPreview.promotable" :size="16" />
              <ShieldAlert v-else-if="promotionPreview.conflict" :size="16" />
              <CircleDot v-else :size="16" />
              <span>{{ promotionPreview.promotable ? 'Ready for guarded promotion' : promotionPreview.blockingReason ?? 'Promotion unavailable' }}</span>
            </div>
          </section>
        </div>
        </template>
      </section>

      <section v-show="activeTab === 'changes'" id="review-panel-changes" class="review-panel" role="tabpanel" aria-labelledby="review-tab-changes" tabindex="0">
        <header class="panel-heading"><div><p class="section-label">SEALED RESULT DIFF</p><h3>{{ detailLoading ? 'Loading sealed diff' : `${diff.length} changed files` }}</h3></div><span v-if="!detailLoading" class="diff-totals"><strong>+{{ totalAdditions }}</strong><em>-{{ totalDeletions }}</em></span></header>
        <div v-if="detailLoading" class="review-loading compact" role="status"><LoaderCircle class="spin" :size="20" /><strong>Loading changes</strong></div>
        <div v-else-if="diff.length" class="diff-list">
          <details v-for="item in diff" :key="item.path" class="diff-entry">
            <summary>
              <span class="diff-kind" :class="item.change.toLowerCase()">{{ item.change === 'MODIFIED' ? 'M' : item.change === 'ADDED' ? '+' : '-' }}</span>
              <code>{{ item.path }}</code>
              <span>{{ item.binary ? 'binary' : `+${item.additions} -${item.deletions}` }}</span>
            </summary>
            <pre v-if="item.patch" class="code-output diff-patch">{{ item.patch }}</pre>
            <p v-else class="empty-copy">No textual patch available.</p>
          </details>
        </div>
        <div v-else class="panel-empty"><FileDiff :size="24" /><strong>No sealed changes</strong><span>The agent has not produced a candidate diff.</span></div>
      </section>

      <section v-show="activeTab === 'evidence'" id="review-panel-evidence" class="review-panel" role="tabpanel" aria-labelledby="review-tab-evidence" tabindex="0">
        <header class="panel-heading"><div><p class="section-label">TRUSTED VERIFICATION</p><h3>{{ detailLoading ? 'Loading verification records' : `${passedTrustedEvidence.length} of ${trustedEvidence.length} checks passed` }}</h3></div><ShieldCheck :class="trustedEvidence.length > 0 && passedTrustedEvidence.length === trustedEvidence.length ? 'text-success' : 'text-muted'" :size="22" /></header>
        <div v-if="detailLoading" class="review-loading compact" role="status"><LoaderCircle class="spin" :size="20" /><strong>Loading evidence</strong></div>
        <div v-else-if="trustedEvidence.length" class="evidence-list">
          <details v-for="item in trustedEvidence" :key="item.id" class="evidence-entry">
            <summary>
              <span class="evidence-result" :class="evidencePassed(item) ? 'success' : 'danger'"><CheckCircle2 v-if="evidencePassed(item)" :size="16" /><ShieldAlert v-else :size="16" /></span>
              <span><code>{{ item.command }}</code><small>{{ item.environmentProfile }} · exit {{ item.exitCode }} · {{ formatDuration(item.durationMillis) }} · snapshot {{ shortId(item.snapshotId) }}</small></span>
            </summary>
            <div class="evidence-detail"><dl><div><dt>Working directory</dt><dd><code>{{ item.cwd }}</code></dd></div><div><dt>Completed</dt><dd>{{ formatDate(item.completedAt) }}</dd></div></dl><pre v-if="item.stdout" class="code-output">{{ item.stdout }}</pre><pre v-if="item.stderr" class="code-output stderr">{{ item.stderr }}</pre></div>
          </details>
        </div>
        <div v-else class="panel-empty"><FileCheck2 :size="24" /><strong>No trusted evidence yet</strong><span>Verification runs after the candidate is sealed.</span></div>

        <template v-if="invalidatedEvidence.length">
          <div class="subsection-heading danger"><ShieldAlert :size="16" /><div><strong>Invalidated verification</strong><span>These records failed source-integrity checks and cannot authorize promotion.</span></div></div>
          <details v-for="item in invalidatedEvidence" :key="item.id" class="evidence-entry invalidated">
            <summary><span class="evidence-result danger"><ShieldAlert :size="16" /></span><span><code>{{ item.command }}</code><small>untrusted · exit {{ item.exitCode }} · snapshot {{ shortId(item.snapshotId) }}</small></span></summary>
            <div class="evidence-detail"><pre v-if="item.stdout" class="code-output">{{ item.stdout }}</pre><pre v-if="item.stderr" class="code-output stderr">{{ item.stderr }}</pre></div>
          </details>
        </template>

        <template v-if="observations.length">
          <div class="subsection-heading"><TerminalSquare :size="16" /><div><strong>Agent observations</strong><span>Useful context, never promotion authority.</span></div></div>
          <details v-for="item in observations" :key="item.id" class="evidence-entry observation">
            <summary><span class="evidence-result neutral"><TerminalSquare :size="16" /></span><span><code>{{ item.command }}</code><small>observation only · exit {{ item.exitCode }} · {{ formatDuration(item.durationMillis) }}</small></span></summary>
            <div class="evidence-detail"><pre v-if="item.stdout" class="code-output">{{ item.stdout }}</pre><pre v-if="item.stderr" class="code-output stderr">{{ item.stderr }}</pre></div>
          </details>
        </template>
      </section>

      <section v-show="activeTab === 'activity'" id="review-panel-activity" class="review-panel" role="tabpanel" aria-labelledby="review-tab-activity" tabindex="0">
        <header class="panel-heading"><div><p class="section-label">RUN ACTIVITY</p><h3>{{ activity.length }} retained events</h3></div><span class="connection-label" :class="streamState"><span class="stream-dot" :class="streamState" />{{ streamState }}</span></header>
        <p v-if="eventWarning" class="inline-warning" role="status"><AlertTriangle :size="15" />{{ eventWarning }}</p>
        <ol v-if="activity.length" class="activity-list">
          <li v-for="event in activity" :key="event.eventId || event.sequence">
            <span class="activity-sequence">{{ String(event.sequence).padStart(3, '0') }}</span>
            <span class="activity-marker" />
            <div><strong>{{ statusLabel(event.type) }}</strong><time :datetime="event.timestamp">{{ formatDate(event.timestamp) }}</time></div>
          </li>
        </ol>
        <div v-else class="panel-empty"><CircleDot :size="24" /><strong>No activity received</strong><span>The stream reconnects automatically while this experiment is open.</span></div>

        <details v-if="activity.length" class="debug-events">
          <summary>Debug event payloads</summary>
          <div v-for="event in activity" :key="`debug-${event.eventId || event.sequence}`"><span>#{{ event.sequence }} {{ event.type }}</span><pre class="code-output">{{ eventPayload(event) }}</pre></div>
        </details>
      </section>
    </template>

    <div v-else class="pane-empty review-empty">
      <ShieldCheck :size="27" />
      <strong>Select an experiment to review</strong>
      <span>Changes and trusted evidence appear here before canonical can be updated.</span>
    </div>
  </main>
</template>
