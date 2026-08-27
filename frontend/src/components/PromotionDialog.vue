<script setup lang="ts">
import { ArrowRight, GitCommitHorizontal, LoaderCircle, ShieldCheck, X } from 'lucide-vue-next'
import type { Experiment, PromotionPreview } from '../api'
import { useLocale } from '../i18n'
import { shortFingerprint, shortId } from '../ui'
import BaseDialog from './BaseDialog.vue'

defineProps<{
  experiment: Experiment
  preview: PromotionPreview
  changedFileCount: number
  busy: boolean
}>()
const emit = defineEmits<{ close: []; confirm: [] }>()
const { text } = useLocale()
</script>

<template>
  <BaseDialog labelled-by="promotion-dialog-title" described-by="promotion-dialog-description" @close="emit('close')">
    <form class="dialog-form promotion-confirm" @submit.prevent="emit('confirm')">
      <header class="dialog-header">
        <div class="dialog-heading">
          <span class="dialog-icon verified"><ShieldCheck :size="17" /></span>
          <div>
            <p class="eyebrow">{{ text('不可逆的工作区变更', 'IRREVERSIBLE WORKSPACE CHANGE') }}</p>
            <h2 id="promotion-dialog-title">{{ text('提升已验证的候选？', 'Promote verified candidate?') }}</h2>
          </div>
        </div>
        <button type="button" class="icon-button" :aria-label="text('关闭对话框', 'Close dialog')" :title="text('关闭', 'Close')" @click="emit('close')"><X :size="17" /></button>
      </header>
      <p id="promotion-dialog-description" class="dialog-description">{{ text('最后一次指纹检查通过后，才会把封存结果应用到主线。', 'This applies the sealed result to canonical after one final fingerprint check.') }}</p>
      <div class="promotion-route" :aria-label="text('提升路径', 'Promotion route')">
        <div><span>{{ text('实验', 'Experiment') }}</span><strong>EXP-{{ shortId(experiment.id) }}</strong><code>{{ shortFingerprint(preview.finalCandidateFingerprint) }}</code></div>
        <ArrowRight :size="18" />
        <div><span>{{ text('主线', 'Canonical') }}</span><strong>{{ changedFileCount }} {{ text('个文件变更', 'changed files') }}</strong><code>{{ shortFingerprint(preview.currentFingerprint) }}</code></div>
      </div>
      <div class="policy-note verified"><GitCommitHorizontal :size="15" /><span>{{ text('该封存候选已通过可信验证。', 'Trusted verification passed for this sealed candidate.') }}</span></div>
      <footer class="dialog-actions">
        <button type="button" class="button secondary" @click="emit('close')">{{ text('保持隔离', 'Keep isolated') }}</button>
        <button class="button success" :disabled="busy || !preview.promotable">
          <LoaderCircle v-if="busy" class="spin" :size="16" /> {{ text('提升到主线', 'Promote to canonical') }}
        </button>
      </footer>
    </form>
  </BaseDialog>
</template>
