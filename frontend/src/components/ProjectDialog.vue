<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { AlertTriangle, ArrowRight, ArrowUp, Check, Folder, FolderGit2, FolderOpen, FolderPlus, LoaderCircle, ShieldCheck, X } from 'lucide-vue-next'
import { api, type DirectoryBrowse, type DirectoryLocation, type Project } from '../api'
import { useLocale } from '../i18n'
import { formatError } from '../ui'
import BaseDialog from './BaseDialog.vue'

const props = defineProps<{
  busy: boolean
  project?: Project | null
  error?: string | null
  /** Number of passing results that are waiting for review in this project. */
  verifiedResultCount?: number
}>()
const emit = defineEmits<{
  close: []
  submit: [value: { name: string; canonicalPath: string; verificationCommands: string[]; createNew: boolean }]
}>()

type ProjectMode = 'open' | 'new'

const mode = ref<ProjectMode>('open')
const name = ref('')
const nameAutoFilled = ref(false)
const canonicalPath = ref('')
const creationParent = ref<string | null>(null)
const verificationCommandsText = ref('')
let verificationCommandsTouched = false
const browserOpen = ref(false)
const browserBusy = ref(false)
const browser = ref<DirectoryBrowse | null>(null)
const browserError = ref<string | null>(null)
let browserRequest = 0
const draftBaseline = ref<{ id: string | null; name: string; canonicalPath: string; commands: string } | null>(null)
const commands = computed(() => verificationCommandsText.value
  .split('\n')
  .map((line) => line.trim())
  .filter(Boolean))
const validationError = ref<string | null>(null)
const policyChangeConfirmed = ref(false)
const { text } = useLocale()
const isNewMode = computed(() => !props.project && mode.value === 'new')

const policyChanged = computed(() => {
  const baseline = draftBaseline.value
  return !!props.project && !!baseline && commands.value.join('\n') !== baseline.commands
})

const requiresPolicyChangeConfirmation = computed(() => policyChanged.value
  && (props.verifiedResultCount ?? 0) > 0)

const draftDirty = computed(() => {
  const baseline = draftBaseline.value
  if (!baseline) return false
  return name.value !== baseline.name
    || canonicalPath.value !== baseline.canonicalPath
    || commands.value.join('\n') !== baseline.commands
})

function applyProject(project: Project | null | undefined) {
  mode.value = 'open'
  name.value = project?.name ?? ''
  nameAutoFilled.value = false
  canonicalPath.value = project?.canonicalPath ?? ''
  creationParent.value = null
  verificationCommandsText.value = project?.verificationCommands.join('\n') ?? ''
  verificationCommandsTouched = false
  draftBaseline.value = {
    id: project?.id ?? null,
    name: project?.name ?? '',
    canonicalPath: project?.canonicalPath ?? '',
    commands: project?.verificationCommands.join('\n') ?? '',
  }
  validationError.value = null
  policyChangeConfirmed.value = false
  browserOpen.value = false
}

watch(() => props.project, (project) => {
  // Keep an in-progress edit intact when a background refresh replaces the
  // same project object. A subsequent save will surface any version conflict.
  if (project?.id && draftBaseline.value?.id === project.id && draftDirty.value) return
  applyProject(project)
}, { immediate: true })

function setAutoName(value: string | null | undefined) {
  const next = value?.trim() ?? ''
  if (!next) return
  name.value = next
  nameAutoFilled.value = true
}

function handleNameInput() {
  // A user edit takes ownership of the display name. Subsequent path edits
  // should not overwrite a name they intentionally chose.
  nameAutoFilled.value = false
  if (isNewMode.value && creationParent.value && name.value.trim()
    && !/[\\/]/.test(name.value.trim())) {
    // When a parent was selected in the browser, use the edited name as a
    // convenient default for the final directory segment. A manually pasted
    // target path remains authoritative, while the name stays display-only.
    canonicalPath.value = joinPath(creationParent.value, name.value.trim())
  }
}

function handleCommandsInput() {
  verificationCommandsTouched = true
  policyChangeConfirmed.value = false
}

watch(canonicalPath, (path) => {
  if (props.project) return
  if (isNewMode.value && creationParent.value && name.value.trim()
    && normalizePathForComparison(path) !== normalizePathForComparison(joinPath(creationParent.value, name.value.trim()))) {
    // A manually pasted target path takes precedence over the browser's
    // parent-directory selection.
    creationParent.value = null
  }
  const suggestion = directoryName(path.trim())
  if (!suggestion || suggestion === '.' || /^[A-Za-z]:$/.test(suggestion)) return
  if (!name.value.trim() || nameAutoFilled.value) setAutoName(suggestion)
})

function submit() {
  validationError.value = null
  const trimmedName = name.value.trim()
  const trimmedPath = canonicalPath.value.trim()
  if (!trimmedName) {
    validationError.value = text('请输入项目名称。', 'Enter a project name.')
    return
  }
  if (!trimmedPath) {
    validationError.value = text('请输入项目目录。', 'Enter a project directory.')
    return
  }
  if (trimmedName.length > 200) {
    validationError.value = text('项目名称不能超过 200 个字符。', 'Project names must be 200 characters or fewer.')
    return
  }
  if (trimmedPath.length > 4_096) {
    validationError.value = text('项目目录不能超过 4096 个字符。', 'Project paths must be 4,096 characters or fewer.')
    return
  }
  if (!isAbsolutePath(trimmedPath)) {
    validationError.value = text('请输入运行 Offcanon 的这台机器上的绝对路径。', 'Use an absolute path on the machine running Offcanon.')
    return
  }
  if (commands.value.length > 20) {
    validationError.value = text('最多添加 20 条项目验收命令。', 'Add no more than 20 project acceptance commands.')
    return
  }
  if (commands.value.some((command) => command.length > 1_000)) {
    validationError.value = text('每条验收命令不能超过 1000 个字符。', 'Each acceptance command must be 1,000 characters or fewer.')
    return
  }
  if (requiresPolicyChangeConfirmation.value && !policyChangeConfirmed.value) {
    // Changing a policy invalidates any already-passed result that is still
    // waiting to be applied. Require an explicit second click so the user can
    // see that those results return to the sealed, waiting-for-verification
    // state and must be checked under the new policy.
    policyChangeConfirmed.value = true
    return
  }
  emit('submit', {
    name: trimmedName,
    canonicalPath: trimmedPath,
    verificationCommands: commands.value,
    createNew: isNewMode.value,
  })
}

function switchMode(next: ProjectMode) {
  if (props.project || mode.value === next) return
  mode.value = next
  validationError.value = null
  browserOpen.value = false
  browser.value = null
  browserError.value = null
  creationParent.value = null
}

async function toggleBrowser() {
  if (browserOpen.value) {
    browserOpen.value = false
    return
  }
  browserOpen.value = true
  await browse(browserStartPath())
}

function browserStartPath() {
  const current = canonicalPath.value.trim()
  if (!current || !isNewMode.value) return current || undefined
  return parentPath(current) || current
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
      browserError.value = formatError(cause, '无法读取这个目录。', 'Unable to read this directory.')
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
  if (!name.value.trim() || nameAutoFilled.value) setAutoName(selection.suggestedName || directoryName(root))
  if (!verificationCommandsTouched && !verificationCommandsText.value.trim() && selection.suggestedVerificationCommands.length) {
    verificationCommandsText.value = selection.suggestedVerificationCommands.join('\n')
  }
  browserOpen.value = false
}

function selectCreationParent() {
  const selection = browser.value
  if (!selection) return
  const selectedName = name.value.trim() || 'new-project'
  creationParent.value = selection.path
  if (!name.value.trim() || nameAutoFilled.value) setAutoName(selectedName)
  canonicalPath.value = joinPath(selection.path, selectedName)
  validationError.value = null
  browserOpen.value = false
}

function selectOpenDirectory() {
  const selection = browser.value
  if (!selection) return
  canonicalPath.value = selection.path
  if (!name.value.trim() || nameAutoFilled.value) setAutoName(selection.suggestedName || directoryName(selection.path))
  if (!verificationCommandsTouched && !verificationCommandsText.value.trim() && selection.suggestedVerificationCommands.length) {
    verificationCommandsText.value = selection.suggestedVerificationCommands.join('\n')
  }
  browserOpen.value = false
}

function parentPath(path: string) {
  const normalized = path.replace(/[\\/]+$/, '')
  const index = Math.max(normalized.lastIndexOf('\\'), normalized.lastIndexOf('/'))
  if (index < 0) return null
  if (/^[A-Za-z]:$/.test(normalized)) return `${normalized}\\`
  if (index === 0) return '/'
  if (index === 2 && /^[A-Za-z]:/.test(normalized)) return normalized.slice(0, 3)
  return normalized.slice(0, index)
}

function joinPath(parent: string, child: string) {
  const trimmed = parent.replace(/[\\/]+$/, '')
  if (!trimmed) return `/${child}`
  if (/^[A-Za-z]:$/.test(trimmed)) return `${trimmed}\\${child}`
  const separator = parent.includes('\\') ? '\\' : '/'
  return `${trimmed}${separator}${child}`
}

function normalizePathForComparison(path: string) {
  return path.replace(/[\\/]+/g, '\\').replace(/\\$/, '').toLowerCase()
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
  <BaseDialog labelled-by="project-dialog-title" described-by="project-dialog-description" :close-disabled="busy" @close="emit('close')">
    <form class="dialog-form" @submit.prevent="submit">
      <header class="dialog-header">
        <div class="dialog-heading">
          <span class="dialog-icon canonical"><FolderPlus v-if="isNewMode" :size="17" /><FolderGit2 v-else :size="17" /></span>
          <div>
            <p class="eyebrow">{{ text('本机项目', 'LOCAL PROJECT') }}</p>
            <h2 id="project-dialog-title">{{ props.project ? text('项目设置', 'Project settings') : isNewMode ? text('新建项目', 'New project') : text('打开项目', 'Open project') }}</h2>
          </div>
        </div>
        <button type="button" class="icon-button" :aria-label="text('关闭对话框', 'Close dialog')" :title="text('关闭', 'Close')" :disabled="busy" @click="emit('close')"><X :size="17" /></button>
      </header>
      <p id="project-dialog-description" class="dialog-description">{{ props.project ? text('更新项目名称和项目验收命令；项目根路径保持不变。', 'Update the project name and project acceptance commands; the project root stays fixed.') : isNewMode ? text('创建一个新的本机目录并初始化 Git，然后将它登记为项目。验收命令可以稍后补充。', 'Create a new local directory, initialize Git, and register it as a project. Acceptance commands can be added later.') : text('选择要交给 Offcanon 的本地 Git 项目。项目验收命令可以稍后在项目设置中补充。', 'Choose the local Git project to open in Offcanon. Acceptance commands can be added later in project settings.') }}</p>
      <div v-if="!props.project" class="segmented-control project-mode-control" role="group" :aria-label="text('项目操作', 'Project action')">
        <button type="button" :aria-pressed="mode === 'open'" @click="switchMode('open')"><FolderGit2 :size="15" />{{ text('打开已有项目', 'Open existing') }}</button>
        <button type="button" :aria-pressed="mode === 'new'" @click="switchMode('new')"><FolderPlus :size="15" />{{ text('新建项目', 'Create new') }}</button>
      </div>
      <label for="canonical-path">{{ isNewMode ? text('新项目目录', 'New project directory') : text('项目目录', 'Project directory') }}</label>
      <div class="path-input-row">
        <input id="canonical-path" v-model="canonicalPath" :readonly="!!props.project" :aria-readonly="props.project ? 'true' : 'false'" :autofocus="!props.project" required autocomplete="off" spellcheck="false" maxlength="4096" aria-describedby="canonical-path-help" :placeholder="isNewMode ? 'D:\\code\\new-project' : 'D:\\code\\example'" />
        <button v-if="!props.project" type="button" class="button secondary compact" :disabled="browserBusy" @click="toggleBrowser">
          <LoaderCircle v-if="browserBusy" class="spin" :size="15" />
          <FolderOpen v-else :size="15" />
          {{ isNewMode ? text('选择父目录', 'Choose parent') : text('浏览目录', 'Browse') }}
        </button>
      </div>
      <p id="canonical-path-help" class="field-help">{{ props.project ? text('已注册项目的根路径不可修改；如需更换仓库，请打开一个新项目。路径指向运行 Offcanon 服务的这台机器。', 'A registered project root cannot be changed; open a new project to use another repository. The path is on the machine running the Offcanon service.') : isNewMode ? text('只创建最后一级目录；父目录必须已存在，目标目录必须为空。项目名称是可编辑的显示名称；点击“选择此位置”后会用它作为默认目录名，直接粘贴路径则以路径为准。路径指向运行 Offcanon 服务的这台机器。', 'Only the final directory is created. The parent must already exist and the target must be empty. The project name is editable; clicking “Use this location” supplies the default directory name, while a pasted path remains authoritative. The path is on the machine running Offcanon.') : text('在目录浏览器中点击“选择此仓库”或“选择此目录”后，Offcanon 会确认 Git 根目录并建议项目名称；直接粘贴路径不会自动建议验收命令，请自行填写。路径必须位于运行 Offcanon 服务的这台机器。', 'In the directory browser, click “Use this repository” or “Use this directory” to confirm the Git root and suggest a project name. Pasting a path does not suggest acceptance commands; enter them yourself. The path must be on the machine running Offcanon.') }}</p>
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
            <span>{{ isNewMode ? text('选择新项目的父目录', 'Choose a parent directory for the new project') : text('选择 Git 仓库目录', 'Choose a Git repository directory') }}</span>
          </div>
          <div v-if="browser.gitRoot && !isNewMode" class="directory-git-status">
            <ShieldCheck :size="15" />
            <span v-if="browser.gitRoot === browser.path">{{ text('当前目录是 Git 仓库根目录。', 'This directory is a Git repository root.') }}</span>
            <span v-else>{{ text('检测到所属 Git 仓库根目录。', 'A containing Git repository root was detected.') }}</span>
            <button type="button" class="button success compact" :disabled="browserBusy" @click="selectDetectedRoot"><Check :size="14" />{{ text('选择此仓库', 'Use this repository') }}</button>
          </div>
          <div v-else-if="!isNewMode" class="directory-open-status">
            <FolderGit2 :size="15" />
            <span>{{ text('当前目录尚未检测到 Git 根目录；选择后会由后端再次校验。', 'No Git root was detected here; the backend will validate it after selection.') }}</span>
            <button type="button" class="button secondary compact" :disabled="browserBusy" @click="selectOpenDirectory"><Check :size="14" />{{ text('选择此目录', 'Use this directory') }}</button>
          </div>
          <div v-if="isNewMode" class="directory-create-status">
            <FolderPlus :size="15" />
            <span>{{ text('将在当前目录下创建新的 Git 项目。', 'A new Git project will be created inside this directory.') }}</span>
            <button type="button" class="button primary compact" :disabled="browserBusy" @click="selectCreationParent"><Check :size="14" />{{ text('选择此位置', 'Use this location') }}</button>
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
      <input id="project-name" v-model="name" @input="handleNameInput" :autofocus="!!props.project" required autocomplete="off" maxlength="200" :placeholder="isNewMode ? text('选择位置后自动建议，可修改', 'Suggested after choosing a location; editable') : text('确认选择目录后自动建议，可修改', 'Suggested after confirming a directory; editable')" />
      <label for="verification-commands">{{ text('项目验收命令', 'Project acceptance commands') }} <span>{{ text('每行一条', 'one per line') }}</span></label>
      <textarea id="verification-commands" v-model="verificationCommandsText" @input="handleCommandsInput" maxlength="20019" rows="4" :placeholder="text('可暂不填写，例如 npm test', 'Optional, for example npm test')"></textarea>
       <p class="field-help">{{ props.project
         ? text('可留空；任务准备、运行、验收、应用或恢复期间会暂时锁定命令。若有已通过验收但尚未应用的结果，修改命令前会要求确认，并将这些结果退回待验收状态；历史记录仍保留。已封存且等待验收的结果可以直接补充、修改或清空。未配置时，验收和应用会被阻止。', 'Optional. Commands are temporarily locked while a task is being prepared, run, verified, applied, or reconciled. If verified results are waiting to be applied, changing the commands requires confirmation and returns them to the sealed waiting state; history is kept. Sealed results waiting for verification can be added, changed, or cleared directly. Verification and apply are blocked until one is configured.')
        : text('创建或打开项目时可以暂不填写。通过目录浏览器选择并检测到项目配置时会建议命令，但仍可修改或清空；直接粘贴路径请自行填写。进入项目后点击顶部的「项目设置」即可设置或修改。未配置时，验收和应用会被阻止。', 'You can leave this blank when creating or opening a project. The directory browser may suggest commands after it detects project configuration; they remain editable or removable. Enter commands yourself when pasting a path. Use Project settings in the header to configure or change them later. Verification and apply are blocked until one is configured.') }}</p>
      <div v-if="requiresPolicyChangeConfirmation && !policyChangeConfirmed" class="policy-change-warning" role="alert">
        <AlertTriangle :size="15" />
        <span>{{ text(`当前有 ${props.verifiedResultCount} 个已通过验收但尚未应用的结果。确认修改后，这些结果会回到待验收状态；历史记录保留，之后需按新命令重新验收。`, `${props.verifiedResultCount} verified result${props.verifiedResultCount === 1 ? '' : 's'} are waiting to be applied. Confirming this change will return them to the sealed waiting state; history is kept and the new policy must be verified again.`) }}</span>
      </div>
      <div class="policy-note"><ShieldCheck :size="15" /><span>{{ text('代理停止编辑后，会在一次性工作区执行；通过结果才可作为应用依据。这里不是操作系统安全沙箱，命令由你负责确认其安全性。', 'Runs in a disposable workspace after the agent stops editing; only passing results can support an application. This is not an operating-system security sandbox; you are responsible for confirming command safety.') }}</span></div>
      <p v-if="validationError" class="field-error" role="alert">{{ validationError }}</p>
      <p v-else-if="props.error" class="field-error" role="alert">{{ props.error }}</p>
      <footer class="dialog-actions">
        <button type="button" class="button secondary" :disabled="busy" @click="emit('close')">{{ text('取消', 'Cancel') }}</button>
        <button class="button primary" :disabled="busy">
          <LoaderCircle v-if="busy" class="spin" :size="16" /> {{ props.project && requiresPolicyChangeConfirmation && policyChangeConfirmed ? text('确认并保存', 'Confirm and save') : props.project ? text('保存项目', 'Save project') : isNewMode ? text('创建并打开', 'Create and open') : text('打开项目', 'Open project') }}
        </button>
      </footer>
    </form>
  </BaseDialog>
</template>
