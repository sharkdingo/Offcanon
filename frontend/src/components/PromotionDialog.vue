<script setup lang="ts">
import { ArrowRight, GitCommitHorizontal, LoaderCircle, ShieldCheck, X } from 'lucide-vue-next'
import type { Experiment, PromotionPreview } from '../api'
import { shortFingerprint, shortId } from '../ui'
import BaseDialog from './BaseDialog.vue'

defineProps<{
  experiment: Experiment
  preview: PromotionPreview
  changedFileCount: number
  busy: boolean
}>()
const emit = defineEmits<{ close: []; confirm: [] }>()
</script>

<template>
  <BaseDialog labelled-by="promotion-dialog-title" described-by="promotion-dialog-description" @close="emit('close')">
    <form class="dialog-form promotion-confirm" @submit.prevent="emit('confirm')">
      <header class="dialog-header">
        <div class="dialog-heading">
          <span class="dialog-icon verified"><ShieldCheck :size="17" /></span>
          <div>
            <p class="eyebrow">IRREVERSIBLE WORKSPACE CHANGE</p>
            <h2 id="promotion-dialog-title">Promote verified candidate?</h2>
          </div>
        </div>
        <button type="button" class="icon-button" aria-label="Close dialog" title="Close" @click="emit('close')"><X :size="17" /></button>
      </header>
      <p id="promotion-dialog-description" class="dialog-description">This applies the sealed result to canonical after one final fingerprint check.</p>
      <div class="promotion-route" aria-label="Promotion route">
        <div><span>Experiment</span><strong>EXP-{{ shortId(experiment.id) }}</strong><code>{{ shortFingerprint(preview.finalCandidateFingerprint) }}</code></div>
        <ArrowRight :size="18" />
        <div><span>Canonical</span><strong>{{ changedFileCount }} changed files</strong><code>{{ shortFingerprint(preview.currentFingerprint) }}</code></div>
      </div>
      <div class="policy-note verified"><GitCommitHorizontal :size="15" /><span>Trusted verification passed for this sealed candidate.</span></div>
      <footer class="dialog-actions">
        <button type="button" class="button secondary" @click="emit('close')">Keep isolated</button>
        <button class="button success" :disabled="busy || !preview.promotable">
          <LoaderCircle v-if="busy" class="spin" :size="16" /> Promote to canonical
        </button>
      </footer>
    </form>
  </BaseDialog>
</template>
