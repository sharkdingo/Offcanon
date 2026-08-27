<script setup lang="ts">
import { FolderGit2, Layers3, Plus, Users } from 'lucide-vue-next'
import type { Experiment, Project, Session } from '../api'

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

function experimentCount(sessionId: string) {
  return props.experiments.filter((experiment) => experiment.sessionId === sessionId).length
}
</script>

<template>
  <aside class="project-rail" aria-label="Project navigation">
    <div class="rail-section-heading">
      <span>Projects</span>
      <button class="icon-button small" aria-label="Register project" title="Register project" @click="emit('register')"><Plus :size="16" /></button>
    </div>
    <div v-if="projects.length === 0 && !loading" class="rail-empty">
      <FolderGit2 :size="21" />
      <strong>No projects</strong>
      <span>Register a canonical workspace to begin.</span>
    </div>
    <nav class="rail-items" aria-label="Projects">
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
        <span>Sessions</span><small>{{ sessions.length }}</small>
      </div>
      <nav class="rail-items" aria-label="Session filter">
        <button class="rail-row" :class="{ selected: selectedSessionId === null }" @click="emit('selectSession', null)">
          <Layers3 :size="15" /><span>All sessions</span><small>{{ experiments.length }}</small>
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
      <div><span>Execution</span><strong>ISOLATED</strong></div>
      <div><span>Promotion</span><strong>GUARDED</strong></div>
    </footer>
  </aside>
</template>
