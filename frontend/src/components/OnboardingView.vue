<script setup lang="ts">
import { ArrowRight, Bot, FileCheck2, FolderGit2 } from 'lucide-vue-next'
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
      <p class="onboarding-lede">{{ isZh ? '从本机项目开始，Agent 的改动只在你确认后进入主线。' : 'Start with a local project. Agent changes reach canonical only after you accept them.' }}</p>

      <ol class="onboarding-steps">
        <li>
          <span class="onboarding-icon canonical"><FolderGit2 :size="18" /></span>
          <div><strong>{{ isZh ? '打开本机项目' : 'Open a local project' }}</strong><span>{{ isZh ? '选择这台机器上的 Git 仓库。' : 'Choose a Git repository on this machine.' }}</span></div>
          <small>01</small>
        </li>
        <li>
          <span class="onboarding-icon experiment"><Bot :size="18" /></span>
          <div><strong>{{ isZh ? '描述编程任务' : 'Describe a coding task' }}</strong><span>{{ isZh ? 'Agent 在独立实验中探索、修改并运行代码。' : 'The agent explores, edits, and runs code in a separate experiment.' }}</span></div>
          <small>02</small>
        </li>
        <li>
          <span class="onboarding-icon verified"><FileCheck2 :size="18" /></span>
          <div><strong>{{ isZh ? '审阅并应用' : 'Review and apply' }}</strong><span>{{ isZh ? '查看代码改动和验证结果，再决定是否更新项目。' : 'Review the changes and checks, then decide whether to update your project.' }}</span></div>
          <small>03</small>
        </li>
      </ol>

      <footer class="onboarding-actions">
        <button class="button primary" @click="enterWorkspace">{{ isZh ? '打开项目列表' : 'Open project list' }} <ArrowRight :size="15" /></button>
      </footer>
    </section>
  </main>
</template>
