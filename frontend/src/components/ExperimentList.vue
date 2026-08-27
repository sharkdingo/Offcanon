<script setup lang="ts">
import { ArrowLeft, CheckCircle2, CircleDot, FlaskConical, LoaderCircle, Plus } from 'lucide-vue-next'
import type { Experiment, Project, Session } from '../api'
import { useLocale } from '../i18n'
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
const { text } = useLocale()

function selectFilter(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  emit('selectSession', value || null)
}
</script>

<template>
  <section class="run-list" :aria-label="text('实验', 'Experiments')">
    <header class="run-list-header">
      <button class="icon-button mobile-only" :aria-label="text('返回项目', 'Back to projects')" :title="text('项目', 'Projects')" @click="emit('projects')"><ArrowLeft :size="18" /></button>
      <div class="run-heading">
        <p class="eyebrow">{{ project ? text('项目', 'PROJECT') : text('工作区', 'WORKSPACE') }}</p>
        <h1>{{ project?.name ?? text('选择项目', 'Select a project') }}</h1>
      </div>
      <button class="button primary compact" :disabled="!project" @click="emit('create')"><Plus :size="16" /> {{ text('新建', 'New') }}</button>
    </header>

    <div v-if="project" class="run-toolbar">
      <div><span>{{ text('实验', 'Experiments') }}</span><small>{{ experiments.length }}</small></div>
      <label class="session-select">
        <span class="sr-only">{{ text('按会话筛选', 'Filter by session') }}</span>
        <select :value="selectedSessionId ?? ''" @change="selectFilter">
          <option value="">{{ text('全部会话', 'All sessions') }}</option>
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
      <strong>{{ project ? text('当前视图暂无实验', 'No experiments in this view') : text('未选择项目', 'No project selected') }}</strong>
      <span>{{ project ? text('从主线创建一个隔离候选。', 'Create an isolated candidate from canonical.') : text('从项目导航中选择一个项目。', 'Choose a project from the navigation rail.') }}</span>
      <button v-if="project" class="button secondary" @click="emit('create')"><Plus :size="16" /> {{ text('新建实验', 'New experiment') }}</button>
    </div>
  </section>
</template>
