<script setup lang="ts">
import { ArrowRight, Languages, LockKeyhole, LoaderCircle } from 'lucide-vue-next'
import { computed, ref } from 'vue'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const username = ref('')
const password = ref('')
const register = ref(false)
const submitting = ref(false)
const error = ref<string | null>(null)
const isZh = computed(() => auth.locale === 'zh-CN')

function toggleLocale() {
  auth.applyLocale(isZh.value ? 'en-US' : 'zh-CN')
}

async function submit() {
  error.value = null
  if (!username.value.trim() || password.value.length < 8) {
    error.value = isZh.value ? '请输入用户名和至少 8 位密码。' : 'Enter a username and a password of at least 8 characters.'
    return
  }
  submitting.value = true
  try {
    await auth.signIn(username.value, password.value, register.value)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : (isZh.value ? '无法登录。' : 'Unable to sign in.')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="auth-screen">
    <section class="auth-panel" aria-labelledby="auth-title">
      <div class="auth-panel-tools">
        <button class="icon-button" type="button" :aria-label="isZh ? '切换语言' : 'Switch language'" :title="isZh ? 'English' : '中文'" @click="toggleLocale"><Languages :size="16" /></button>
      </div>
      <div class="auth-mark" aria-hidden="true">O</div>
      <p class="auth-kicker">OFFCANON</p>
      <h1 id="auth-title">{{ isZh ? '让改动清晰可见。' : 'Make the change legible.' }}</h1>
      <p class="auth-lede">{{ isZh ? '隔离改动、可信验证，再清晰地回到主线。' : 'A quiet workspace for isolated changes, trusted checks, and a clear return to canonical.' }}</p>

      <div class="auth-tabs" role="tablist" :aria-label="isZh ? '账户操作' : 'Account action'">
        <button type="button" role="tab" :aria-selected="!register" @click="register = false">{{ isZh ? '登录' : 'Sign in' }}</button>
        <button type="button" role="tab" :aria-selected="register" @click="register = true">{{ isZh ? '创建账户' : 'Create account' }}</button>
      </div>

      <form class="auth-form" @submit.prevent="submit">
        <label for="auth-username">{{ isZh ? '用户名' : 'Username' }}</label>
        <input id="auth-username" v-model="username" autofocus autocomplete="username" :placeholder="isZh ? '例如：alex' : 'e.g. alex'" minlength="3" maxlength="64" required />
        <label for="auth-password">{{ isZh ? '密码' : 'Password' }}</label>
        <input id="auth-password" v-model="password" type="password" autocomplete="current-password" :placeholder="isZh ? '至少 8 位' : 'At least 8 characters'" minlength="8" maxlength="256" required />
        <p v-if="error" class="field-error" role="alert">{{ error }}</p>
        <button class="button primary auth-submit" :disabled="submitting">
          <LoaderCircle v-if="submitting" class="spin" :size="16" />
          <LockKeyhole v-else :size="16" />
          {{ register ? (isZh ? '创建并进入' : 'Create and enter') : (isZh ? '进入工作区' : 'Enter workspace') }}
          <ArrowRight v-if="!submitting" :size="15" />
        </button>
      </form>

      <p class="auth-footnote"><span class="stream-dot live" />{{ isZh ? '账户数据按用户隔离' : 'Your projects stay scoped to your account' }}</p>
    </section>
    <aside class="auth-aside" :aria-label="isZh ? 'Offcanon 工作流' : 'Offcanon workflow'">
      <div class="auth-aside-line"><span>01</span><strong>{{ isZh ? '隔离' : 'Isolate' }}</strong><small>{{ isZh ? '在主线之外工作' : 'work outside canonical' }}</small></div>
      <div class="auth-aside-line"><span>02</span><strong>{{ isZh ? '验证' : 'Verify' }}</strong><small>{{ isZh ? '让证据跟随结果' : 'keep evidence with the result' }}</small></div>
      <div class="auth-aside-line"><span>03</span><strong>{{ isZh ? '决策' : 'Decide' }}</strong><small>{{ isZh ? '证据成立后再提升' : 'promote only when the proof holds' }}</small></div>
    </aside>
  </main>
</template>
