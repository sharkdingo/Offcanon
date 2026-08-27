<script setup lang="ts">
import { FolderGit2, Layers3, Plus, Users } from 'lucide-vue-next'
import type { Experiment, Project, Session } from '../api'
import { useLocale } from '../i18n'

const props = defineProps<{
  projects: Project[]
  sessions: Session[]
  experiments: Experiment[]
  selectedProjectId: string | null
  selectedSessionId: string | null
  loading: boolean
}>()
const emit = defineEmits<{
  register: []
  selectProject: [projectId: string]
  selectSession: [sessionId: string | null]
}>()
const { text } = useLocale()

function experimentCount(sessionId: string) {
  return props.experiments.filter((experiment) => experiment.sessionId === sessionId).length
}
</script>

<template>
  <aside class="project-rail" :aria-label="text('项目导航', 'Project navigation')">
    <div class="rail-section-heading">
      <span>{{ text('项目', 'Projects') }}</span>
      <button class="icon-button small" :aria-label="text('登记项目', 'Register project')" :title="text('登记项目', 'Register project')" @click="emit('register')"><Plus :size="16" /></button>
    </div>
    <div v-if="projects.length === 0 && !loading" class="rail-empty">
      <FolderGit2 :size="21" />
      <strong>{{ text('暂无项目', 'No projects') }}</strong>
      <span>{{ text('登记一个主线工作区以开始。', 'Register a canonical workspace to begin.') }}</span>
    </div>
    <nav class="rail-items" :aria-label="text('项目', 'Projects')">
      <button
        v-for="project in projects"
        :key="project.id"
        class="rail-row project-row"
        :class="{ selected: project.id === selectedProjectId }"
        :aria-current="project.id === selectedProjectId ? 'page' : undefined"
        @click="emit('selectProject', project.id)"
      >
        <FolderGit2 :size="16" />
        <span>{{ project.name }}</span>
        <small>{{ project.id === selectedProjectId ? experiments.length : '' }}</small>
      </button>
    </nav>

    <template v-if="selectedProjectId">
      <div class="rail-section-heading session-heading">
        <span>{{ text('会话', 'Sessions') }}</span><small>{{ sessions.length }}</small>
      </div>
      <nav class="rail-items" :aria-label="text('会话筛选', 'Session filter')">
        <button class="rail-row" :class="{ selected: selectedSessionId === null }" @click="emit('selectSession', null)">
          <Layers3 :size="15" /><span>{{ text('全部会话', 'All sessions') }}</span><small>{{ experiments.length }}</small>
        </button>
        <button
          v-for="session in sessions"
          :key="session.id"
          class="rail-row"
          :class="{ selected: session.id === selectedSessionId }"
          @click="emit('selectSession', session.id)"
        >
          <Users :size="15" /><span>{{ session.title }}</span><small>{{ experimentCount(session.id) }}</small>
        </button>
      </nav>
    </template>

    <footer class="rail-footer">
      <div><span>{{ text('执行', 'Execution') }}</span><strong>{{ text('已隔离', 'ISOLATED') }}</strong></div>
      <div><span>{{ text('提升', 'Promotion') }}</span><strong>{{ text('有保护', 'GUARDED') }}</strong></div>
    </footer>
  </aside>
</template>
