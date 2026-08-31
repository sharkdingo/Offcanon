<script setup lang="ts">
import { AlertTriangle, ArrowLeft, ArrowRight, GitCommitHorizontal, LoaderCircle, ShieldCheck, X } from 'lucide-vue-next'
import type { Experiment, PromotionPreview } from '../api'
import { useLocale } from '../i18n'
import { shortFingerprint, shortId } from '../ui'
import BaseDialog from './BaseDialog.vue'

defineProps<{
  experiment: Experiment
  preview: PromotionPreview
  changedFileCount: number
  deletedFileCount: number
  busy: boolean
}>()
const emit = defineEmits<{ close: []; confirm: [] }>()
const { text } = useLocale()
const canConfirm = (preview: PromotionPreview) => !preview.recoveryRequired && (preview.promotable || preview.conflict)
</script>

<template>
  <BaseDialog labelled-by="promotion-dialog-title" described-by="promotion-dialog-description" :close-disabled="busy" @close="emit('close')">
    <form class="dialog-form promotion-confirm" @submit.prevent="canConfirm(preview) && emit('confirm')">
      <header class="dialog-header">
        <div class="dialog-heading">
          <span class="dialog-icon" :class="preview.recoveryRequired || preview.conflict ? 'warning' : 'verified'">
            <AlertTriangle v-if="preview.recoveryRequired || preview.conflict" :size="17" />
            <ShieldCheck v-else :size="17" />
          </span>
          <div>
            <p class="eyebrow">{{ preview.recoveryRequired ? text('需要恢复', 'RECOVERY REQUIRED') : preview.conflict ? text('主线状态', 'CANONICAL STATE') : text('更新主线', 'UPDATE CANONICAL') }}</p>
            <h2 id="promotion-dialog-title">{{ preview.recoveryRequired ? text('请先恢复项目状态', 'Recover project state first') : preview.conflict ? text('项目主线已变化', 'The project changed') : text('将改动应用到项目？', 'Apply changes to your project?') }}</h2>
          </div>
        </div>
        <button type="button" class="icon-button" :aria-label="text('关闭对话框', 'Close dialog')" :title="text('关闭', 'Close')" :disabled="busy" @click="emit('close')"><X :size="17" /></button>
      </header>
      <p id="promotion-dialog-description" class="dialog-description">
        {{ preview.recoveryRequired
          ? text('上一笔应用操作尚未完成。请关闭此窗口，在审阅面板中完成恢复。', 'An earlier application is unfinished. Close this dialog and reconcile it in the review panel.')
          : preview.conflict
          ? text('主线已经偏离这个实验的基线。确认后只会保留主线并将实验标记为已过期。', 'Canonical no longer matches this experiment base. Confirming will keep canonical unchanged and mark the experiment out of date.')
          : text('Offcanon 会先确认主线没有变化，再把这个已验证结果写回当前项目工作区。不会自动创建 Git 提交。', 'Offcanon will confirm canonical has not changed, then write this verified result to the current project workspace. No Git commit is created automatically.') }}
      </p>
      <div
        class="promotion-route"
        :aria-label="preview.recoveryRequired
          ? text('需要先恢复应用状态', 'Promotion recovery is required first')
          : preview.conflict
          ? text('主线变化使实验过期', 'Canonical change marks the experiment out of date')
          : text('将实验改动应用到主线', 'Apply experiment changes to canonical')"
      >
        <div><span>{{ text('实验', 'Experiment') }}</span><strong>EXP-{{ shortId(experiment.id) }}</strong><code>{{ shortFingerprint(preview.finalCandidateFingerprint) }}</code></div>
        <ArrowLeft v-if="preview.conflict && !preview.recoveryRequired" :size="18" />
        <ArrowRight v-else :size="18" />
        <div>
          <span>{{ text('主线', 'Canonical') }}</span>
          <strong>{{ preview.recoveryRequired ? text('暂不修改', 'no change yet') : preview.conflict ? text('保留当前状态', 'kept unchanged') : `${changedFileCount} ${text('个文件变更', 'changed files')}${deletedFileCount ? `（含 ${deletedFileCount} 个删除）` : ''}` }}</strong>
          <code>{{ shortFingerprint(preview.currentFingerprint) }}</code>
        </div>
      </div>
      <div class="policy-note" :class="preview.recoveryRequired || preview.conflict ? 'warning' : 'verified'">
        <AlertTriangle v-if="preview.recoveryRequired || preview.conflict" :size="15" />
        <GitCommitHorizontal v-else :size="15" />
        <span>{{ preview.recoveryRequired
          ? text('请先在审阅面板恢复项目状态。', 'Reconcile the project state in the review panel first.')
          : preview.conflict
          ? text('不会覆盖主线中的新改动。', 'New canonical changes will not be overwritten.')
          : text('该实验结果已通过验收证据检查；写回后请自行检查并提交 Git。', 'Acceptance evidence passed; inspect the written files and create a Git commit yourself.') }}</span>
      </div>
      <footer class="dialog-actions">
        <button type="button" class="button secondary" :disabled="busy" @click="emit('close')">{{ text('保持隔离', 'Keep isolated') }}</button>
        <button class="button" :class="preview.conflict || preview.recoveryRequired ? 'warning' : 'success'" :disabled="busy || !canConfirm(preview)">
          <LoaderCircle v-if="busy" class="spin" :size="16" />
          {{ preview.recoveryRequired ? text('需要恢复', 'Recovery required') : preview.conflict ? text('标记为已过期', 'Mark out of date') : text('应用到主线', 'Apply to canonical') }}
        </button>
      </footer>
    </form>
  </BaseDialog>
</template>
