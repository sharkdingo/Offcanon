<script setup lang="ts">
import { ArrowRight, Bot, FileCheck2, FolderGit2, KeyRound } from 'lucide-vue-next'
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
          <span class="onboarding-icon settings"><KeyRound :size="18" /></span>
          <div><strong>{{ isZh ? '先配置模型连接' : 'Configure the model connection' }}</strong><span>{{ isZh ? '在设置中填写 Endpoint、模型名和 API key；保存后即可开始任务。' : 'Enter the endpoint, model name, and API key in Settings; save them before starting a task.' }}</span></div>
          <small>01</small>
        </li>
        <li>
          <span class="onboarding-icon canonical"><FolderGit2 :size="18" /></span>
          <div><strong>{{ isZh ? '打开本机项目' : 'Open a local project' }}</strong><span>{{ isZh ? '选择这台机器上的 Git 仓库，并设置项目验收命令。' : 'Choose a Git repository on this machine and set its acceptance commands.' }}</span></div>
          <small>02</small>
        </li>
        <li>
          <span class="onboarding-icon experiment"><Bot :size="18" /></span>
          <div><strong>{{ isZh ? '描述编程任务' : 'Describe a coding task' }}</strong><span>{{ isZh ? 'Agent 在独立实验中探索、修改并运行代码。' : 'The agent explores, edits, and runs code in a separate experiment.' }}</span></div>
          <small>03</small>
        </li>
        <li>
          <span class="onboarding-icon verified"><FileCheck2 :size="18" /></span>
          <div><strong>{{ isZh ? '审阅并应用' : 'Review and apply' }}</strong><span>{{ isZh ? '查看代码改动和验收证据，再决定是否更新项目。' : 'Review the changes and acceptance evidence, then decide whether to update your project.' }}</span></div>
          <small>04</small>
        </li>
      </ol>

      <div class="onboarding-boundary">
        <strong>{{ isZh ? '这是受信本机工作台' : 'This is a trusted local workbench' }}</strong>
        <span>{{ isZh ? '项目路径和验收命令在运行 Offcanon 的这台机器上执行；验收证据不是操作系统安全沙箱。' : 'Project paths and acceptance commands run on the machine hosting Offcanon; acceptance evidence is not an operating-system sandbox.' }}</span>
        <span>{{ isZh ? '账户、设置、项目、任务、实验与证据会持久保存在本机；重启 Offcanon 不会清空它们。' : 'Accounts, settings, projects, tasks, experiments, and evidence are stored durably on this machine and survive Offcanon restarts.' }}</span>
      </div>

      <footer class="onboarding-actions">
        <button class="button primary" @click="enterWorkspace">{{ isZh ? '打开项目列表' : 'Open project list' }} <ArrowRight :size="15" /></button>
      </footer>
    </section>
  </main>
</template>
