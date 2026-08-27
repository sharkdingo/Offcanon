import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import WorkspaceView from './views/WorkspaceView.vue'
import './styles.css'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: WorkspaceView },
    { path: '/projects/:projectId', name: 'project', component: WorkspaceView },
    { path: '/projects/:projectId/experiments/:experimentId', name: 'experiment', component: WorkspaceView },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

createApp(App).use(createPinia()).use(router).mount('#app')
