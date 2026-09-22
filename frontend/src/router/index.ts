import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

/**
 * 路由按"端的边界"分两棵树，各自独立布局：
 *   /        C 端（学习端，考生用）—— 对应后端 I-01 ~ I-15
 *   /admin   B 端（管理端，维护题库）—— 对应后端 I-16 ~ I-20
 * 两棵树不共用导航；公共页（404）单独放。
 */
const learnRoutes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('@/layouts/LearnLayout.vue'),
    children: [
      { path: '', redirect: '/today' },
      {
        path: 'today',
        name: 'today',
        component: () => import('@/views/learn/TodayView.vue'),
        meta: { title: '今日卡' }
      },
      {
        path: 'path',
        name: 'path',
        component: () => import('@/views/learn/PathView.vue'),
        meta: { title: '路径地图' }
      },
      {
        path: 'practice/:kpId',
        name: 'practice',
        component: () => import('@/views/learn/PracticeView.vue'),
        meta: { title: '合卷练' }
      },
      {
        path: 'exam',
        name: 'exam',
        component: () => import('@/views/learn/ExamView.vue'),
        meta: { title: '模拟考' }
      },
      {
        path: 'review',
        name: 'review',
        component: () => import('@/views/learn/ReviewView.vue'),
        meta: { title: '复盘' }
      },
      {
        path: 'profile',
        name: 'profile',
        component: () => import('@/views/learn/ProfileView.vue'),
        meta: { title: '我的画像' }
      }
    ]
  }
]

const adminRoutes: RouteRecordRaw[] = [
  {
    path: '/admin',
    component: () => import('@/layouts/AdminLayout.vue'),
    children: [
      { path: '', redirect: '/admin/dashboard' },
      {
        path: 'dashboard',
        name: 'admin-dashboard',
        component: () => import('@/views/admin/DashboardView.vue'),
        meta: { title: '概览' }
      },
      {
        path: 'kp',
        name: 'admin-kp',
        component: () => import('@/views/admin/KpManageView.vue'),
        meta: { title: '考点管理' }
      },
      {
        path: 'candidates',
        name: 'admin-candidates',
        component: () => import('@/views/admin/CandidateView.vue'),
        meta: { title: '候选池' }
      },
      {
        path: 'docs',
        name: 'admin-docs',
        component: () => import('@/views/admin/DocView.vue'),
        meta: { title: '素材解析' }
      },
      {
        path: 'review-flow',
        name: 'admin-review-flow',
        component: () => import('@/views/admin/ReviewFlowView.vue'),
        meta: { title: '校对流转' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    ...learnRoutes,
    ...adminRoutes,
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue')
    }
  ]
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · 知己` : '知己 · 面试陪练'
})

export default router
