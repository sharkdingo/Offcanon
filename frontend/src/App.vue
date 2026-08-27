<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterView } from 'vue-router'
import AuthView from './components/AuthView.vue'
import OnboardingView from './components/OnboardingView.vue'
import { useAuthStore } from './stores/auth'

const auth = useAuthStore()
onMounted(() => auth.initialize())
</script>

<template>
  <div v-if="!auth.ready" class="auth-loading" role="status">Loading Offcanon</div>
  <AuthView v-else-if="!auth.isAuthenticated" />
  <OnboardingView v-else-if="auth.needsOnboarding" />
  <RouterView v-else />
</template>
