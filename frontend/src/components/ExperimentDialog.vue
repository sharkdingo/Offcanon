<script setup lang="ts">
import { ref } from 'vue'
import { FlaskConical, LoaderCircle, X } from 'lucide-vue-next'
import BaseDialog from './BaseDialog.vue'

const props = defineProps<{ busy: boolean; hasSelectedSession: boolean; selectedSessionTitle: string | null }>()
const emit = defineEmits<{
  close: []
  submit: [value: { sessionTitle: string; task: string; newSession: boolean }]
}>()

const newSession = ref(!props.hasSelectedSession)
const sessionTitle = ref('New session')
const task = ref('')

function submit() {
  if (!task.value.trim()) return
  emit('submit', {
    sessionTitle: sessionTitle.value.trim() || 'New session',
    task: task.value.trim(),
    newSession: newSession.value,
  })
}
</script>

<template>
  <BaseDialog labelled-by="experiment-dialog-title" described-by="experiment-dialog-description" @close="emit('close')">
    <form class="dialog-form" @submit.prevent="submit">
      <header class="dialog-header">
        <div class="dialog-heading">
          <span class="dialog-icon experiment"><FlaskConical :size="17" /></span>
          <div>
            <p class="eyebrow">ISOLATED CANDIDATE</p>
            <h2 id="experiment-dialog-title">New experiment</h2>
          </div>
        </div>
        <button type="button" class="icon-button" aria-label="Close dialog" title="Close" @click="emit('close')"><X :size="17" /></button>
      </header>
      <p id="experiment-dialog-description" class="dialog-description">Capture the current canonical state, then let the agent work in a separate workspace.</p>
      <label class="toggle-row" for="new-session">
        <input id="new-session" v-model="newSession" type="checkbox" :disabled="!hasSelectedSession" />
        <span>Create a new session</span>
      </label>
      <label v-if="newSession" for="session-title">Session title</label>
      <input v-if="newSession" id="session-title" v-model="sessionTitle" required autocomplete="off" />
      <div v-else class="selected-context">
        <span>Session</span><strong>{{ selectedSessionTitle ?? 'All sessions' }}</strong>
      </div>
      <label for="experiment-task">Task</label>
      <textarea id="experiment-task" v-model="task" autofocus required rows="6" placeholder="Describe the programming task"></textarea>
      <footer class="dialog-actions">
        <button type="button" class="button secondary" @click="emit('close')">Cancel</button>
        <button class="button primary" :disabled="busy">
          <LoaderCircle v-if="busy" class="spin" :size="16" /> Create experiment
        </button>
      </footer>
    </form>
  </BaseDialog>
</template>
