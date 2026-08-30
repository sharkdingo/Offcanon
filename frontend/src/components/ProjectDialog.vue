<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ArrowRight, ArrowUp, Check, Folder, FolderGit2, FolderOpen, LoaderCircle, ShieldCheck, X } from 'lucide-vue-next'
import { api, type DirectoryBrowse, type DirectoryLocation, type Project } from '../api'
import { useLocale } from '../i18n'
import BaseDialog from './BaseDialog.vue'

const props = defineProps<{ busy: boolean; project?: Project | null }>()
const emit = defineEmits<{
  close: []
  submit: [value: { name: string; canonicalPath: string; verificationCommands: string[] }]
}>()

const name = ref('')
const canonicalPath = ref('')
const verificationCommandsText = ref('')
const browserOpen = ref(false)
const browserBusy = ref(false)
const browser = ref<DirectoryBrowse | null>(null)
const browserError = ref<string | null>(null)
let browserRequest = 0
const commands = computed(() => verificationCommandsText.value
  .split('\n')
  .map((line) => line.trim())
  .filter(Boolean))
const validationError = ref<string | null>(null)
const { text } = useLocale()

watch(() => props.project, (project) => {
  name.value = project?.name ?? ''
  canonicalPath.value = project?.canonicalPath ?? ''
  verificationCommandsText.value = project?.verificationCommands.join('\n') ?? ''
  validationError.value = null
  browserOpen.value = false
}, { immediate: true })

function submit() {
  validationError.value = null
  if (!name.value.trim() || !canonicalPath.value.trim()) return
  if (!isAbsolutePath(canonicalPath.value.trim())) {
    validationError.value = text('请输入运行 Offcanon 的这台机器上的绝对路径。', 'Use an absolute path on the machine running Offcanon.')
    return
  }
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

async function toggleBrowser() {
  if (browserOpen.value) {
    browserOpen.value = false
    return
  }
  browserOpen.value = true
  if (!browser.value || canonicalPath.value.trim()) {
    await browse(canonicalPath.value.trim() || undefined)
  }
}

async function browse(path?: string) {
  const requestId = ++browserRequest
  browserBusy.value = true
  browserError.value = null
  try {
    const listing = await api.browseDirectories(path)
    if (requestId === browserRequest) browser.value = listing
  } catch (cause) {
    if (requestId === browserRequest) {
      browser.value = null
      browserError.value = cause instanceof Error
        ? cause.message
        : text('无法读取这个目录。', 'Unable to read this directory.')
    }
  } finally {
    if (requestId === browserRequest) browserBusy.value = false
  }
}

function selectDetectedRoot() {
  const selection = browser.value
  const root = selection?.gitRoot
  if (!root || !selection) return
  canonicalPath.value = root
  if (!name.value.trim()) name.value = selection.suggestedName || directoryName(root)
  if (!verificationCommandsText.value.trim() && selection.suggestedVerificationCommands.length) {
    verificationCommandsText.value = selection.suggestedVerificationCommands.join('\n')
  }
  browserOpen.value = false
}

function directoryName(path: string) {
  const normalized = path.replace(/[\\/]+$/, '')
  return normalized.split(/[\\/]/).pop() || normalized
}

function locationLabel(location: DirectoryLocation) {
  if (location.kind === 'HOME') return text('用户目录', 'Home')
  if (location.kind === 'WORKING_DIRECTORY') return text('运行目录', 'Working directory')
  return location.path
}

function isAbsolutePath(value: string) {
  return value.startsWith('/') || value.startsWith('\\\\') || /^[A-Za-z]:[\\/]/.test(value)
}
</script>

<template>
  <BaseDialog labelled-by="project-dialog-title" described-by="project-dialog-description" @close="emit('close')">
    <form class="dialog-form" @submit.prevent="submit">
      <header class="dialog-header">
        <div class="dialog-heading">
          <span class="dialog-icon canonical"><FolderGit2 :size="17" /></span>
          <div>
            <p class="eyebrow">{{ text('本机项目', 'LOCAL PROJECT') }}</p>
            <h2 id="project-dialog-title">{{ props.project ? text('编辑项目', 'Edit project') : text('打开项目', 'Open project') }}</h2>
          </div>
        </div>
        <button type="button" class="icon-button" :aria-label="text('关闭对话框', 'Close dialog')" :title="text('关闭', 'Close')" @click="emit('close')"><X :size="17" /></button>
      </header>
      <p id="project-dialog-description" class="dialog-description">{{ props.project ? text('更新项目名称和项目验收命令；项目根路径保持不变。', 'Update the project name and project acceptance commands; the project root stays fixed.') : text('选择要交给 Offcanon 的本地 Git 项目。', 'Choose the local Git project to open in Offcanon.') }}</p>
      <label for="canonical-path">{{ text('项目目录', 'Project directory') }}</label>
      <div class="path-input-row">
        <input id="canonical-path" v-model="canonicalPath" :readonly="!!props.project" :aria-readonly="props.project ? 'true' : 'false'" :autofocus="!props.project" required autocomplete="off" spellcheck="false" aria-describedby="canonical-path-help" placeholder="D:\code\example" />
        <button v-if="!props.project" type="button" class="button secondary compact" :disabled="browserBusy" @click="toggleBrowser">
          <LoaderCircle v-if="browserBusy" class="spin" :size="15" />
          <FolderOpen v-else :size="15" />
          {{ text('浏览目录', 'Browse') }}
        </button>
      </div>
      <p id="canonical-path-help" class="field-help">{{ props.project ? text('已注册项目的根路径不可修改；如需更换仓库，请打开一个新项目。', 'A registered project root cannot be changed; open a new project to use another repository.') : text('选择目录后，Offcanon 会识别 Git 仓库根目录。也可以直接粘贴绝对路径。', 'Offcanon detects the Git repository root after you choose a directory. You can also paste an absolute path.') }}</p>
      <section v-if="browserOpen" class="directory-picker" :aria-label="text('选择项目目录', 'Choose project directory')">
        <header class="directory-picker-header">
          <div>
            <p class="eyebrow">{{ text('本机目录', 'LOCAL DIRECTORIES') }}</p>
            <code :title="browser?.path">{{ browser?.path ?? text('读取中', 'Loading') }}</code>
          </div>
          <button type="button" class="icon-button small" :aria-label="text('关闭目录浏览器', 'Close directory browser')" :title="text('关闭', 'Close')" @click="browserOpen = false"><X :size="15" /></button>
        </header>
        <p v-if="browserError" class="field-error" role="alert">{{ browserError }}</p>
        <template v-if="browser">
          <div v-if="browser.locations.length" class="directory-locations" :aria-label="text('常用位置', 'Locations')">
            <button v-for="location in browser.locations" :key="`${location.kind}:${location.path}`" type="button" class="directory-location" :title="location.path" :disabled="browserBusy" @click="browse(location.path)">
              <FolderOpen :size="14" />
              <span>{{ locationLabel(location) }}</span>
            </button>
          </div>
          <div class="directory-toolbar">
            <button type="button" class="icon-button small" :disabled="!browser.parent || browserBusy" :aria-label="text('返回上一级', 'Go to parent directory')" :title="text('返回上一级', 'Go to parent directory')" @click="browser.parent && browse(browser.parent)"><ArrowUp :size="15" /></button>
            <span>{{ text('选择 Git 仓库目录', 'Choose a Git repository directory') }}</span>
          </div>
          <div v-if="browser.gitRoot" class="directory-git-status">
            <ShieldCheck :size="15" />
            <span v-if="browser.gitRoot === browser.path">{{ text('当前目录是 Git 仓库根目录。', 'This directory is a Git repository root.') }}</span>
            <span v-else>{{ text('检测到所属 Git 仓库根目录。', 'A containing Git repository root was detected.') }}</span>
            <button type="button" class="button success compact" :disabled="browserBusy" @click="selectDetectedRoot"><Check :size="14" />{{ text('选择此仓库', 'Use this repository') }}</button>
          </div>
          <div class="directory-entries">
            <button v-for="entry in browser.entries" :key="entry.path" type="button" class="directory-entry" :title="entry.path" :disabled="browserBusy" @click="browse(entry.path)">
              <Folder :size="15" /><span>{{ entry.name }}</span><ArrowRight :size="14" />
            </button>
            <p v-if="browser.entries.length === 0" class="directory-empty">{{ text('这里没有可浏览的子目录。', 'No child directories to browse here.') }}</p>
            <p v-else-if="browser.truncated" class="directory-empty">{{ text('只显示前 500 个目录，请直接输入路径以继续。', 'Showing the first 500 directories; enter a path to continue.') }}</p>
          </div>
        </template>
      </section>
      <label for="project-name">{{ text('项目名称', 'Project name') }}</label>
      <input id="project-name" v-model="name" :autofocus="!!props.project" required autocomplete="off" :placeholder="text('选择目录后自动填写', 'Filled after choosing a directory')" />
      <label for="verification-commands">{{ text('项目验收命令', 'Project acceptance commands') }} <span>{{ text('每行一条', 'one per line') }}</span></label>
      <textarea id="verification-commands" v-model="verificationCommandsText" required rows="4" placeholder="mvn test"></textarea>
      <div class="policy-note"><ShieldCheck :size="15" /><span>{{ text('代理停止编辑后，会在一次性工作区执行；通过结果才可作为应用依据。命令由你负责确认其安全性。', 'Runs in a disposable workspace after the agent stops editing; only passing results can support an application. You are responsible for confirming command safety.') }}</span></div>
      <p v-if="validationError" class="field-error" role="alert">{{ validationError }}</p>
      <footer class="dialog-actions">
        <button type="button" class="button secondary" @click="emit('close')">{{ text('取消', 'Cancel') }}</button>
        <button class="button primary" :disabled="busy">
          <LoaderCircle v-if="busy" class="spin" :size="16" /> {{ props.project ? text('保存项目', 'Save project') : text('打开项目', 'Open project') }}
        </button>
      </footer>
    </form>
  </BaseDialog>
</template>
