<script setup lang="ts">
import { computed } from 'vue'
import DOMPurify from 'dompurify'
import { marked } from 'marked'

const props = withDefaults(defineProps<{
  source?: string | null
}>(), {
  source: '',
})

const MAX_RENDER_CHARS = 120_000
const ALLOWED_TAGS = [
  'p', 'br', 'hr', 'strong', 'em', 'del', 's', 'u',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'ul', 'ol', 'li', 'blockquote',
  'a', 'code', 'pre',
  'table', 'thead', 'tbody', 'tfoot', 'tr', 'th', 'td',
]
const ALLOWED_ATTR = ['href', 'title', 'colspan', 'rowspan', 'scope']

// Keep Markdown rendering in one place. The resulting HTML is always sanitized
// before it reaches v-html; callers never need to opt into raw HTML themselves.
const rendered = computed(() => renderMarkdown(props.source ?? ''))

function renderMarkdown(source: string) {
  if (!source.trim()) return ''

  const boundedSource = source.length > MAX_RENDER_CHARS
    ? source.slice(0, MAX_RENDER_CHARS) + '\n\n...[content truncated]...'
    : source

  try {
    const parsed = marked.parse(boundedSource, {
      gfm: true,
      breaks: true,
    })

    if (typeof parsed !== 'string') return ''

    return DOMPurify.sanitize(parsed, {
      // Markdown responses are content, not an extension point for the app.
      // An explicit allowlist also prevents raw HTML from importing media or
      // borrowing application CSS classes to cover the surrounding UI.
      ALLOWED_TAGS,
      ALLOWED_ATTR,
      ALLOW_DATA_ATTR: false,
      SANITIZE_NAMED_PROPS: true,
      // Keep navigable links, but reject protocol-relative URLs and active
      // schemes. Every alternative is anchored so prefix tricks cannot pass.
      ALLOWED_URI_REGEXP: /^(?:(?:https?:\/\/|mailto:|tel:)[^\s\\]+|\/(?![\/\\])[^\s\\]*|\.\.?\/[^\s\\]*|#[^\s\\]*|[^\\/:?#\s][^:\\s]*)$/i,
    })
  } catch {
    // A malformed model response should still be visible, but never as HTML.
    return escapeHtml(boundedSource).replace(/\n/g, '<br>')
  }
}

function escapeHtml(value: string) {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}
</script>

<template>
  <div v-if="rendered" class="markdown-content" v-html="rendered" />
</template>
