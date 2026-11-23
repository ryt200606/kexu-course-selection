import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from './stores/auth'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('./views/LoginView.vue'), meta: { public: true, title: '欢迎回来' } },
    { path: '/', component: () => import('./layouts/AppLayout.vue'), children: [
      { path: '', name: 'overview', component: () => import('./views/OverviewView.vue'), meta: { title: '教学总览' } },
      { path: 'student/catalog', component: () => import('./views/CatalogView.vue'), meta: { role: 'STUDENT', title: '选课中心' } },
      { path: 'student/timetable', component: () => import('./views/TimetableView.vue'), meta: { role: 'STUDENT', title: '我的课表' } },
      { path: 'student/courses', component: () => import('./views/StudentCoursesView.vue'), meta: { role: 'STUDENT', title: '我的课程' } },
      { path: 'student/grades', component: () => import('./views/StudentGradesView.vue'), meta: { role: 'STUDENT', title: '成绩与学分' } },
      { path: 'teacher/sections', component: () => import('./views/TeacherView.vue'), meta: { role: 'TEACHER', title: '授课与成绩' } },
      { path: 'teacher/sections/:id', component: () => import('./views/TeacherView.vue'), meta: { role: 'TEACHER', title: '班级成绩册' } },
      { path: 'admin/manage/:resource?', component: () => import('./views/AdminManageView.vue'), meta: { role: 'ADMIN', title: '教学管理' } },
      { path: 'admin/enrollments', component: () => import('./views/AdminEnrollmentsView.vue'), meta: { role: 'ADMIN', title: '选课与递补' } },
      { path: 'admin/analytics', component: () => import('./views/AnalyticsView.vue'), meta: { role: 'ADMIN', title: '教学分析' } },
      { path: 'admin/audit', component: () => import('./views/AuditView.vue'), meta: { role: 'ADMIN', title: '操作记录' } },
      { path: 'admin/system', component: () => import('./views/SystemView.vue'), meta: { role: 'ADMIN', title: '运行与一致性' } },
      { path: 'notifications', component: () => import('./views/NotificationsView.vue'), meta: { title: '消息中心' } },
      { path: 'profile', component: () => import('./views/ProfileView.vue'), meta: { title: '个人信息' } },
    ] },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
router.beforeEach(async to => {
  const auth = useAuth()
  try { await auth.initialize() } catch { if (to.name !== 'login') return { name: 'login', query: { unavailable: '1' } } }
  if (!to.meta.public && !auth.user) return { name: 'login', query: { redirect: to.fullPath } }
  if (to.meta.role && auth.user?.role !== to.meta.role) return { name: 'overview' }
  if (to.name === 'login' && auth.user) return { name: 'overview' }
  document.title = `${String(to.meta.title || '教学工作台')} · 课序`
})
window.addEventListener('course:session-expired', () => { const auth = useAuth(); auth.expire(); if (router.currentRoute.value.name !== 'login') void router.replace({ name: 'login', query: { expired: '1' } }) })
