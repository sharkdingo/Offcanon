<script setup lang="ts">
import { ref } from 'vue'
import { FlaskConical, LoaderCircle, X } from 'lucide-vue-next'
import { useLocale } from '../i18n'
import BaseDialog from './BaseDialog.vue'

const props = defineProps<{ busy: boolean; hasSelectedSession: boolean; selectedSessionTitle: string | null }>()
const { text } = useLocale()
const emit = defineEmits<{
  close: []
  submit: [value: { sessionTitle: string; task: string; newSession: boolean }]
}>()

const newSession = ref(!props.hasSelectedSession)
const sessionTitle = ref(text('新会话', 'New session'))
const task = ref('')

function submit() {
  if (!task.value.trim()) return
  emit('submit', {
    sessionTitle: sessionTitle.value.trim() || text('新会话', 'New session'),
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
            <p class="eyebrow">{{ text('隔离候选', 'ISOLATED CANDIDATE') }}</p>
            <h2 id="experiment-dialog-title">{{ text('新建实验', 'New experiment') }}</h2>
          </div>
        </div>
        <button type="button" class="icon-button" :aria-label="text('关闭对话框', 'Close dialog')" :title="text('关闭', 'Close')" @click="emit('close')"><X :size="17" /></button>
      </header>
      <p id="experiment-dialog-description" class="dialog-description">{{ text('先捕获当前主线状态，再让代理在独立工作区中运行。', 'Capture the current canonical state, then let the agent work in a separate workspace.') }}</p>
      <label class="toggle-row" for="new-session">
        <input id="new-session" v-model="newSession" type="checkbox" :disabled="!hasSelectedSession" />
        <span>{{ text('创建新会话', 'Create a new session') }}</span>
      </label>
      <label v-if="newSession" for="session-title">{{ text('会话标题', 'Session title') }}</label>
      <input v-if="newSession" id="session-title" v-model="sessionTitle" required autocomplete="off" />
      <div v-else class="selected-context">
        <span>{{ text('会话', 'Session') }}</span><strong>{{ selectedSessionTitle ?? text('全部会话', 'All sessions') }}</strong>
      </div>
      <label for="experiment-task">{{ text('任务', 'Task') }}</label>
      <textarea id="experiment-task" v-model="task" autofocus required rows="6" :placeholder="text('描述编程任务', 'Describe the programming task')"></textarea>
      <footer class="dialog-actions">
        <button type="button" class="button secondary" @click="emit('close')">{{ text('取消', 'Cancel') }}</button>
        <button class="button primary" :disabled="busy">
          <LoaderCircle v-if="busy" class="spin" :size="16" /> {{ text('创建实验', 'Create experiment') }}
        </button>
      </footer>
    </form>
  </BaseDialog>
</template>
