<script setup lang="ts">
import { FolderGit2, MessageSquare, PanelLeftClose, Plus, ChevronRight } from 'lucide-vue-next'
import type { Experiment, Project, Session } from '../api'
import { useLocale } from '../i18n'
import { experimentDisplayTone, experimentStatusLabel, formatDate } from '../ui'

const props = defineProps<{
  projects: Project[]
  sessions: Session[]
  experiments: Experiment[]
  selectedProjectId: string | null
  selectedSessionId: string | null
  loading: boolean
}>()

const emit = defineEmits<{
  addProject: []
  selectProject: [projectId: string]
  selectSession: [sessionId: string]
  newTask: []
  close: []
}>()

const { text } = useLocale()

function latestFor(sessionId: string) {
  return props.experiments.filter((item) => item.sessionId === sessionId).at(-1) ?? null
}

function taskTitle(session: Session) {
  const latest = latestFor(session.id)
  // A session is the user's conversation thread. Keep its sidebar title
  // stable while continuations add experiments; otherwise the same thread
  // appears to be renamed after every follow-up request.
  return session.title || latest?.task || text('未命名任务', 'Untitled task')
}

function taskStatus(experiment: Experiment) {
  const project = props.projects.find((item) => item.id === experiment.projectId)
  return experimentStatusLabel(experiment, Boolean(project?.verificationCommands.length))
}
</script>

<template>
  <aside class="task-sidebar" :aria-label="text('项目和任务', 'Projects and tasks')">
    <div class="sidebar-brand-row">
      <div class="sidebar-section-label">{{ text('项目', 'Projects') }}</div>
      <div class="sidebar-heading-actions">
        <button class="icon-button small" :aria-label="text('打开或新建项目', 'Open or create project')" :title="text('打开或新建项目', 'Open or create project')" @click="emit('addProject')"><Plus :size="15" /></button>
        <button v-if="selectedProjectId" class="icon-button small sidebar-close" :aria-label="text('关闭任务导航', 'Close task navigation')" :title="text('关闭', 'Close')" @click="emit('close')"><PanelLeftClose :size="15" /></button>
      </div>
    </div>

    <nav class="sidebar-projects" :aria-label="text('项目列表', 'Project list')">
      <button
        v-for="project in projects"
        :key="project.id"
        class="sidebar-project"
        :class="{ selected: project.id === selectedProjectId }"
        :aria-current="project.id === selectedProjectId ? 'page' : undefined"
        :title="project.canonicalPath"
        @click="emit('selectProject', project.id)"
      >
        <FolderGit2 :size="16" />
        <span>{{ project.name }}</span>
        <ChevronRight :size="14" />
      </button>
      <div v-if="!projects.length && !loading" class="sidebar-empty">
        <FolderGit2 :size="18" />
        <span>{{ text('还没有打开项目', 'No project is open') }}</span>
        <button class="button secondary compact" @click="emit('addProject')"><Plus :size="14" />{{ text('打开或新建项目', 'Open or create project') }}</button>
      </div>
    </nav>

    <div v-if="selectedProjectId" class="sidebar-task-heading">
      <div class="sidebar-section-label">{{ text('任务', 'Tasks') }}</div>
      <button class="icon-button small" :aria-label="text('新任务', 'New task')" :title="text('新任务', 'New task')" @click="emit('newTask')"><Plus :size="15" /></button>
    </div>

    <nav v-if="selectedProjectId" class="sidebar-tasks" :aria-label="text('任务列表', 'Task list')">
      <button
        v-for="session in sessions.slice().reverse()"
        :key="session.id"
        class="sidebar-task"
        :class="{ selected: session.id === selectedSessionId }"
        :aria-current="session.id === selectedSessionId ? 'page' : undefined"
        :title="taskTitle(session)"
        @click="emit('selectSession', session.id)"
      >
        <span class="task-icon"><MessageSquare :size="14" /></span>
        <span class="task-copy">
          <strong>{{ taskTitle(session) }}</strong>
          <small>{{ formatDate(session.createdAt) }}</small>
        </span>
        <span v-if="latestFor(session.id)" class="task-status" :class="experimentDisplayTone(latestFor(session.id)!)">{{ taskStatus(latestFor(session.id)!) }}</span>
      </button>
      <div v-if="!sessions.length" class="sidebar-empty task-empty">
        <MessageSquare :size="18" />
        <span>{{ text('从一句任务开始。', 'Start with a task.') }}</span>
        <button class="button primary compact" @click="emit('newTask')"><Plus :size="14" />{{ text('新任务', 'New task') }}</button>
      </div>
    </nav>

  </aside>
</template>
