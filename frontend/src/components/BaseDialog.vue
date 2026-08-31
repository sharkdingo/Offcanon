<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

const props = withDefaults(defineProps<{ labelledBy: string; describedBy?: string; closeDisabled?: boolean }>(), {
  closeDisabled: false,
})
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement | null>(null)
const returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
let closing = false
let focusRestored = false

function restoreFocus() {
  if (focusRestored) return
  focusRestored = true
  window.setTimeout(() => {
    if (returnFocus
      && document.contains(returnFocus)
      && !returnFocus.hasAttribute('disabled')
      && returnFocus.offsetParent !== null
      && !returnFocus.closest('[inert], [aria-hidden="true"]')) {
      returnFocus.focus()
      return
    }
    // Actions such as clearing a credential can remove or disable the
    // original trigger. Keep keyboard focus in the visible application.
    const fallback = Array.from(document.querySelectorAll<HTMLElement>(
      'button, a[href], input, textarea, select, summary, [contenteditable="true"], [tabindex]:not([tabindex="-1"])',
    )).find((item) => !item.hasAttribute('disabled')
      && item.offsetParent !== null
      && !item.closest('[inert], [aria-hidden="true"]'))
    fallback?.focus()
  }, 0)
}

function close() {
  if (props.closeDisabled || !dialog.value?.open) return
  closing = true
  dialog.value.close()
}

function handleClose() {
  emit('close')
  restoreFocus()
}

function handleBackdrop(event: MouseEvent) {
  const target = event.target
  if (!(target instanceof HTMLDialogElement)) return
  const bounds = target.getBoundingClientRect()
  const outside = event.clientX < bounds.left || event.clientX > bounds.right
    || event.clientY < bounds.top || event.clientY > bounds.bottom
  if (outside) close()
}

function trapFocus(event: KeyboardEvent) {
  if (event.key !== 'Tab' || !dialog.value) return
  const focusable = Array.from(dialog.value.querySelectorAll<HTMLElement>('button, a[href], input, textarea, select, summary, [contenteditable="true"], [tabindex]:not([tabindex="-1"])'))
    .filter((item) => !item.hasAttribute('disabled') && item.offsetParent !== null)
  if (!focusable.length) return
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (!dialog.value.contains(document.activeElement)) { event.preventDefault(); first.focus() }
  else if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}

onMounted(() => {
  dialog.value?.showModal()
  window.setTimeout(() => {
    const first = dialog.value?.querySelector<HTMLElement>('[autofocus]')
      ?? dialog.value?.querySelector<HTMLElement>('input, textarea, button')
    first?.focus()
  }, 0)
})

onBeforeUnmount(() => {
  if (dialog.value?.open && !closing) dialog.value.close()
  restoreFocus()
})

defineExpose({ close })
</script>

<template>
  <dialog
    ref="dialog"
    class="dialog"
    aria-modal="true"
    :aria-labelledby="props.labelledBy"
    :aria-describedby="props.describedBy"
    @cancel.prevent="close"
    @keydown.esc.prevent.stop="close"
    @close="handleClose"
    @click="handleBackdrop"
    @keydown="trapFocus"
  >
    <slot :close="close" />
  </dialog>
</template>
