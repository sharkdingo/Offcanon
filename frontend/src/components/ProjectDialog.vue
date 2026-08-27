<script setup lang="ts">
import { computed, ref } from 'vue'
import { FolderGit2, LoaderCircle, ShieldCheck, X } from 'lucide-vue-next'
import { useLocale } from '../i18n'
import BaseDialog from './BaseDialog.vue'

defineProps<{ busy: boolean }>()
const emit = defineEmits<{
  close: []
  submit: [value: { name: string; canonicalPath: string; verificationCommands: string[] }]
}>()

const name = ref('')
const canonicalPath = ref('')
const verificationCommandsText = ref('')
const commands = computed(() => verificationCommandsText.value
  .split('\n')
  .map((line) => line.trim())
  .filter(Boolean))
const validationError = ref<string | null>(null)
const { text } = useLocale()

function submit() {
  validationError.value = null
  if (!name.value.trim() || !canonicalPath.value.trim()) return
  if (commands.value.length === 0) {
    validationError.value = text('至少添加一条可信验证命令。', 'Add at least one trusted verification command.')
    return
  }
  emit('submit', {
    name: name.value.trim(),
    canonicalPath: canonicalPath.value.trim(),
    verificationCommands: commands.value,
  })
}
</script>

<template>
  <BaseDialog labelled-by="project-dialog-title" described-by="project-dialog-description" @close="emit('close')">
    <form class="dialog-form" @submit.prevent="submit">
      <header class="dialog-header">
        <div class="dialog-heading">
          <span class="dialog-icon canonical"><FolderGit2 :size="17" /></span>
          <div>
            <p class="eyebrow">{{ text('主线来源', 'CANONICAL SOURCE') }}</p>
            <h2 id="project-dialog-title">{{ text('登记项目', 'Register project') }}</h2>
          </div>
        </div>
        <button type="button" class="icon-button" :aria-label="text('关闭对话框', 'Close dialog')" :title="text('关闭', 'Close')" @click="emit('close')"><X :size="17" /></button>
      </header>
      <p id="project-dialog-description" class="dialog-description">{{ text('实验从这个工作区分支。只有通过这些命令的候选才能提升回主线。', 'Experiments branch from this workspace. Only candidates that pass these commands can be promoted back.') }}</p>
      <label for="project-name">{{ text('项目名称', 'Project name') }}</label>
      <input id="project-name" v-model="name" autofocus required autocomplete="off" :placeholder="text('例如：service', 'Example service')" />
      <label for="canonical-path">{{ text('主线路径', 'Canonical path') }}</label>
      <input id="canonical-path" v-model="canonicalPath" required autocomplete="off" placeholder="D:\code\example" />
      <label for="verification-commands">{{ text('可信验证命令', 'Trusted verification commands') }} <span>{{ text('每行一条', 'one per line') }}</span></label>
      <textarea id="verification-commands" v-model="verificationCommandsText" required rows="4" placeholder="mvn test"></textarea>
      <div class="policy-note"><ShieldCheck :size="15" /><span>{{ text('代理停止编辑后，会在干净的验证工作区中执行。', 'Runs in a clean verification workspace after the agent stops editing.') }}</span></div>
      <p v-if="validationError" class="field-error" role="alert">{{ validationError }}</p>
      <footer class="dialog-actions">
        <button type="button" class="button secondary" @click="emit('close')">{{ text('取消', 'Cancel') }}</button>
        <button class="button primary" :disabled="busy">
          <LoaderCircle v-if="busy" class="spin" :size="16" /> {{ text('登记项目', 'Register project') }}
        </button>
      </footer>
    </form>
  </BaseDialog>
</template>
