<script setup lang="ts">
import { ArrowRight, FileCheck2, FolderGit2, ShieldCheck } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { computed } from 'vue'

const router = useRouter()
const auth = useAuthStore()
const isZh = computed(() => auth.locale === 'zh-CN')

async function enterWorkspace() {
  auth.completeOnboarding()
  await router.replace({ name: 'home' })
}

async function skip() {
  await enterWorkspace()
}
</script>

<template>
  <main class="onboarding-screen">
    <section class="onboarding-panel" aria-labelledby="onboarding-title">
      <header class="onboarding-header">
        <div class="brand-mark onboarding-mark" aria-hidden="true">O</div>
        <div>
          <p class="auth-kicker">OFFCANON / {{ isZh ? '首次使用' : 'FIRST RUN' }}</p>
          <h1 id="onboarding-title">{{ isZh ? '欢迎，' : 'Welcome, ' }}{{ auth.session?.displayName }}</h1>
        </div>
      </header>
      <p class="onboarding-lede">{{ isZh ? '三个检查点，让每次改动都可复核。' : 'Three checkpoints keep every change reviewable.' }}</p>

      <ol class="onboarding-steps">
        <li>
          <span class="onboarding-icon canonical"><FolderGit2 :size="18" /></span>
          <div><strong>{{ isZh ? '登记主线' : 'Register canonical' }}</strong><span>{{ isZh ? '指定你想保护的代码仓库。' : 'Point Offcanon at the repository you want to protect.' }}</span></div>
          <small>01</small>
        </li>
        <li>
          <span class="onboarding-icon experiment"><ShieldCheck :size="18" /></span>
          <div><strong>{{ isZh ? '运行隔离改动' : 'Run an isolated change' }}</strong><span>{{ isZh ? '代理在源代码树之外工作。' : 'The agent works away from the source tree.' }}</span></div>
          <small>02</small>
        </li>
        <li>
          <span class="onboarding-icon verified"><FileCheck2 :size="18" /></span>
          <div><strong>{{ isZh ? '审阅证据' : 'Review the proof' }}</strong><span>{{ isZh ? '提升前，差异、检查和活动记录始终在一起。' : 'Diff, checks, and activity stay together before promotion.' }}</span></div>
          <small>03</small>
        </li>
      </ol>

      <footer class="onboarding-actions">
        <button class="button secondary" @click="skip">{{ isZh ? '暂时跳过' : 'Skip for now' }}</button>
        <button class="button primary" @click="enterWorkspace">{{ isZh ? '打开工作区' : 'Open workspace' }} <ArrowRight :size="15" /></button>
      </footer>
    </section>
  </main>
</template>
