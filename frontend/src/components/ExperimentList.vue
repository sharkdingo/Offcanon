<script setup lang="ts">
import { ArrowLeft, CheckCircle2, CircleDot, FlaskConical, LoaderCircle, Plus } from 'lucide-vue-next'
import type { Experiment, Project, Session } from '../api'
import { formatDate, shortId, statusLabel, statusTone } from '../ui'

defineProps<{
  project: Project | null
  experiments: Experiment[]
  sessions: Session[]
  selectedExperimentId: string | null
  selectedSessionId: string | null
}>()
const emit = defineEmits<{
  select: [experimentId: string]
  create: []
  projects: []
  selectSession: [sessionId: string | null]
}>()

function selectFilter(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  emit('selectSession', value || null)
}
</script>

<template>
  <section class="run-list" aria-label="Experiments">
    <header class="run-list-header">
      <button class="icon-button mobile-only" aria-label="Back to projects" title="Projects" @click="emit('projects')"><ArrowLeft :size="18" /></button>
      <div class="run-heading">
        <p class="eyebrow">{{ project ? 'PROJECT' : 'WORKSPACE' }}</p>
        <h1>{{ project?.name ?? 'Select a project' }}</h1>
      </div>
      <button class="button primary compact" :disabled="!project" @click="emit('create')"><Plus :size="16" /> New</button>
    </header>

    <div v-if="project" class="run-toolbar">
      <div><span>Experiments</span><small>{{ experiments.length }}</small></div>
      <label class="session-select">
        <span class="sr-only">Filter by session</span>
        <select :value="selectedSessionId ?? ''" @change="selectFilter">
          <option value="">All sessions</option>
          <option v-for="session in sessions" :key="session.id" :value="session.id">{{ session.title }}</option>
        </select>
      </label>
    </div>

    <div v-if="experiments.length" class="run-rows">
      <button
        v-for="experiment in experiments"
        :key="experiment.id"
        class="run-row"
        :class="{ selected: experiment.id === selectedExperimentId }"
        :aria-current="experiment.id === selectedExperimentId ? 'page' : undefined"
        @click="emit('select', experiment.id)"
      >
        <span class="run-status-icon" :class="statusTone(experiment.status)">
          <CheckCircle2 v-if="experiment.status === 'VERIFIED' || experiment.status === 'PROMOTED'" :size="16" />
          <LoaderCircle v-else-if="['RUNNING', 'VERIFYING', 'PREPARING_PROMOTION', 'PROMOTING'].includes(experiment.status)" class="spin" :size="16" />
          <CircleDot v-else :size="16" />
        </span>
        <span class="run-copy">
          <span class="run-topline"><code>EXP-{{ shortId(experiment.id) }}</code><span class="status-badge" :class="statusTone(experiment.status)">{{ statusLabel(experiment.status) }}</span></span>
          <strong>{{ experiment.task }}</strong>
          <span class="run-meta"><span>{{ formatDate(experiment.createdAt) }}</span><span>base {{ shortId(experiment.baseSnapshotId) }}</span></span>
        </span>
      </button>
    </div>

    <div v-else class="pane-empty">
      <FlaskConical :size="25" />
      <strong>{{ project ? 'No experiments in this view' : 'No project selected' }}</strong>
      <span>{{ project ? 'Create an isolated candidate from canonical.' : 'Choose a project from the navigation rail.' }}</span>
      <button v-if="project" class="button secondary" @click="emit('create')"><Plus :size="16" /> New experiment</button>
    </div>
  </section>
</template>
