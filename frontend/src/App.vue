<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Archive, Ban, CheckCircle2, CircleDot, FolderGit2, GitBranch, LoaderCircle, Plus, RefreshCw, ShieldCheck, TerminalSquare, X } from 'lucide-vue-next'
import { useWorkspaceStore } from './stores/workspace'

const store = useWorkspaceStore()
const showProjectForm = ref(false)
const showExperimentForm = ref(false)
const submitting = ref(false)
const projectForm = ref({ name: '', canonicalPath: '', verificationCommands: '' })
const experimentForm = ref({ sessionTitle: 'Default session', task: '' })

const statusLabel = (status: string) => status.replaceAll('_', ' ')
const statusTone = (status: string) => {
  if (status === 'VERIFIED' || status === 'PROMOTED') return 'success'
  if (status === 'FAILED' || status === 'REJECTED' || status === 'STALE') return 'danger'
  if (status === 'RUNNING' || status === 'VERIFYING' || status === 'PROMOTING') return 'active'
  return 'neutral'
}
const selectedStatus = computed(() => store.selectedExperiment?.status ?? 'CANONICAL')

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
    experimentForm.value = { sessionTitle: 'Default session', task: '' }
    showExperimentForm.value = false
  } catch (cause) {
    store.error = cause instanceof Error ? cause.message : 'Unable to create experiment'
  } finally {
    submitting.value = false
  }
}

async function refresh() {
  if (store.selectedProjectId) await store.selectProject(store.selectedProjectId)
  else await store.loadProjects()
}

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
          <button class="button primary" :disabled="!store.selectedProject" @click="showExperimentForm = true"><Plus :size="16" /> New experiment</button>
        </div>

        <div v-if="store.error" class="alert error"><Ban :size="16" /><span>{{ store.error }}</span><button class="icon-button small" title="Dismiss" @click="store.error = null"><X :size="14" /></button></div>

        <section v-if="store.experiments.length" class="timeline-section">
          <div class="section-heading"><span>RUN HISTORY</span><span class="muted">{{ store.experiments.length }} experiments</span></div>
          <div class="timeline">
            <button
              v-for="experiment in store.experiments"
              :key="experiment.id"
              class="timeline-row"
              :class="{ selected: experiment.id === store.selectedExperimentId }"
              @click="store.selectedExperimentId = experiment.id"
            >
              <div class="timeline-marker" :class="statusTone(experiment.status)"><CheckCircle2 v-if="experiment.status === 'VERIFIED' || experiment.status === 'PROMOTED'" :size="16" /><LoaderCircle v-else-if="experiment.status === 'RUNNING' || experiment.status === 'VERIFYING'" class="spin" :size="16" /><CircleDot v-else :size="16" /></div>
              <div class="timeline-content">
                <div class="timeline-topline"><span class="experiment-id">EXP-{{ experiment.id.slice(0, 8).toUpperCase() }}</span><span class="status" :class="statusTone(experiment.status)">{{ statusLabel(experiment.status) }}</span></div>
                <div class="task-line">{{ experiment.task }}</div>
                <div class="timeline-meta"><span>{{ new Date(experiment.createdAt).toLocaleString() }}</span><span>base {{ experiment.baseSnapshotId?.slice(0, 8) ?? 'pending' }}</span></div>
              </div>
            </button>
          </div>
        </section>
        <section v-else class="empty-state">
          <Archive :size="24" />
          <h2>No experiments yet</h2>
          <p>Register a project, then create an isolated experiment.</p>
          <button class="button secondary" :disabled="!store.selectedProject" @click="showExperimentForm = true"><Plus :size="16" /> Create experiment</button>
        </section>
      </main>

      <aside class="detail-column">
        <div class="detail-header"><span class="eyebrow">INSPECTOR</span><span class="workspace-chip" :class="statusTone(selectedStatus)">{{ store.selectedExperiment ? statusLabel(selectedStatus) : 'CANONICAL' }}</span></div>
        <template v-if="store.selectedExperiment">
          <div class="detail-title"><span class="experiment-id">EXP-{{ store.selectedExperiment.id.slice(0, 8).toUpperCase() }}</span><h2>{{ store.selectedExperiment.task }}</h2></div>
          <div class="detail-block"><div class="detail-label">EXECUTION WORLD</div><div class="world-row"><div class="world-icon experiment"><GitBranch :size="15" /></div><div><strong>Experiment workspace</strong><span>{{ store.selectedExperiment.workspacePath ?? 'not materialized' }}</span></div></div><div class="world-row"><div class="world-icon canonical"><ShieldCheck :size="15" /></div><div><strong>Canonical</strong><span>unchanged until promotion</span></div></div></div>
          <div class="detail-block"><div class="detail-label">BASE SNAPSHOT</div><code>{{ store.selectedExperiment.baseSnapshotId ?? 'pending' }}</code></div>
          <div class="detail-block"><div class="detail-label">SESSION</div><code>{{ store.selectedExperiment.sessionId }}</code></div>
          <div v-if="store.selectedExperiment.failureReason" class="detail-block failure"><div class="detail-label">FAILURE</div><p>{{ store.selectedExperiment.failureReason }}</p></div>
          <button v-if="!['CANCELLED', 'FAILED', 'REJECTED', 'PROMOTED'].includes(store.selectedExperiment.status)" class="button danger-outline" @click="store.cancelExperiment(store.selectedExperiment.id)"><Ban :size="16" /> Cancel experiment</button>
        </template>
        <div v-else class="detail-empty"><TerminalSquare :size="24" /><span>Select an experiment to inspect its execution state.</span></div>
      </aside>
    </div>

    <div v-if="showProjectForm || showExperimentForm" class="modal-backdrop" @click.self="showProjectForm = false; showExperimentForm = false">
      <form v-if="showProjectForm" class="modal" @submit.prevent="submitProject"><div class="modal-heading"><div><div class="eyebrow">NEW PROJECT</div><h2>Register canonical workspace</h2></div><button type="button" class="icon-button" title="Close" @click="showProjectForm = false"><X :size="17" /></button></div><label>Project name<input v-model="projectForm.name" required placeholder="Example service" /></label><label>Canonical path<input v-model="projectForm.canonicalPath" required placeholder="D:\\code\\example" /></label><label>Verification commands <span class="muted">one per line</span><textarea v-model="projectForm.verificationCommands" rows="3" placeholder="mvn test"></textarea></label><div class="modal-actions"><button type="button" class="button secondary" @click="showProjectForm = false">Cancel</button><button class="button primary" :disabled="submitting"><LoaderCircle v-if="submitting" class="spin" :size="16" /> Register project</button></div></form>
      <form v-else class="modal" @submit.prevent="submitExperiment"><div class="modal-heading"><div><div class="eyebrow">NEW EXPERIMENT</div><h2>Start from current canonical</h2></div><button type="button" class="icon-button" title="Close" @click="showExperimentForm = false"><X :size="17" /></button></div><label>Session title<input v-model="experimentForm.sessionTitle" required /></label><label>Task<textarea v-model="experimentForm.task" required rows="4" placeholder="Describe the programming task"></textarea></label><div class="modal-actions"><button type="button" class="button secondary" @click="showExperimentForm = false">Cancel</button><button class="button primary" :disabled="submitting"><LoaderCircle v-if="submitting" class="spin" :size="16" /> Create experiment</button></div></form>
    </div>
  </div>
</template>
