import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHashHistory } from 'vue-router'
import { configurePreviewTransport, type Role } from '../lib/api'
import { useAuth } from '../stores/auth'
import { useWorkspace } from '../stores/workspace'
import { names, previewRole, previewData } from './data'
import PreviewShell from './PreviewShell.vue'
import '../styles.css'
import '../workflow.css'

configurePreviewTransport(previewData)
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/student/planning' },
    { path: '/student/planning', component: () => import('../views/StudentPlanningView.vue') },
    { path: '/student/catalog', component: () => import('../views/CatalogView.vue') },
    { path: '/student/grades', component: () => import('../views/StudentGradesView.vue') },
    {
      path: '/:role(student|teacher|admin)/reviews',
      component: () => import('../views/GradeReviewsView.vue'),
    },
    { path: '/evidence', component: () => import('./EvidenceView.vue') },
    { path: '/admin/indicators', component: () => import('../views/AnalyticsDrillView.vue') },
    { path: '/admin/enrollments', component: () => import('../views/AdminEnrollmentsView.vue') },
    { path: '/admin/planning', component: () => import('./RunTrace.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})
const app = createApp(PreviewShell),
  pinia = createPinia()
app.use(pinia)
const auth = useAuth(),
  ws = useWorkspace()
router.beforeEach((to) => {
  const role = to.path.split('/')[1]?.toUpperCase() as Role
  if (role in names) previewRole.value = role
  auth.user = {
    id: role,
    username: 'demo',
    displayName: names[previewRole.value],
    role: previewRole.value,
    studentId: role === 'STUDENT' ? 'S01' : null,
    teacherId: role === 'TEACHER' ? 'T01' : null,
  }
  auth.initialized = true
})
await ws.loadTerms()
app.use(router).mount('#app')
