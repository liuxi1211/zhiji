import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/**
 * 路由按"端的边界"分两棵树，各自独立布局：
 *   /        C 端（学习端，考生用）—— 对应核心模块定义第二节的六功能
 *   /admin   B 端（管理端，维护知识库）—— 对应核心模块定义第三节
 * 接口契约枚举见 api/types.ts 与核心模块定义附录 A；接口清单随各后端模块落地时定义。
 * 两棵树不共用导航；公共页（404、知识卡分享公开页）单独放。
 */
const learnRoutes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('@/layouts/LearnLayout.vue'),
    children: [
      { path: '', redirect: '/map' },
      {
        path: 'map',
        name: 'map',
        component: () => import('@/views/learn/MapView.vue'),
        meta: { title: '学习地图' }
      },
      {
        path: 'gacha',
        name: 'gacha',
        component: () => import('@/views/learn/GachaView.vue'),
        meta: { title: '抽卡' }
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
        meta: { title: '我的' }
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
        path: 'skills',
        name: 'admin-skills',
        component: () => import('@/views/admin/SkillsView.vue'),
        meta: { title: '技能库' }
      },
      {
        path: 'kp',
        name: 'admin-kp',
        component: () => import('@/views/admin/KpManageView.vue'),
        meta: { title: '知识点管理' }
      },
      {
        path: 'candidates',
        name: 'admin-candidates',
        component: () => import('@/views/admin/CandidateView.vue'),
        meta: { title: '候选池' }
      },
      {
        path: 'knowledge-map',
        name: 'admin-knowledge-map',
        component: () => import('@/views/admin/KnowledgeMapView.vue'),
        meta: { title: '知识地图' }
      },
      {
        path: 'docs',
        name: 'admin-docs',
        component: () => import('@/views/admin/DocView.vue'),
        meta: { title: '素材库' }
      },
      {
        path: 'review-flow',
        name: 'admin-review-flow',
        component: () => import('@/views/admin/ReviewFlowView.vue'),
        meta: { title: '校对流转' }
      },
      {
        path: 'accounts',
        name: 'admin-accounts',
        component: () => import('@/views/admin/AccountsView.vue'),
        meta: { title: '账号管理' }
      },
      {
        path: 'settings',
        name: 'admin-settings',
        component: () => import('@/views/admin/SettingsView.vue'),
        meta: { title: '系统配置' }
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
      path: '/share/:kpId',
      name: 'share-kp',
      component: () => import('@/views/share/ShareView.vue'),
      meta: { title: '知识卡分享' }
    },
    {
      // 登录 / 注册共用一页；未登录可达页仅这两个地址、/share/:kpId 与 404（核心模块定义 2.7）
      path: '/login',
      name: 'login',
      component: () => import('@/views/auth/AuthView.vue'),
      meta: { title: '登录' }
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/auth/AuthView.vue'),
      meta: { title: '注册' }
    },
    {
      // 后台登录：员工（sys_staff）专用入口，与 C 端会话互不通用（核心模块定义 2.7，决策清单 D18）
      path: '/admin/login',
      name: 'admin-login',
      component: () => import('@/views/auth/StaffLoginView.vue'),
      meta: { title: '后台登录' }
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue')
    }
  ]
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  // 后台树：员工会话守卫（/admin/login 除外）
  if (to.path.startsWith('/admin')) {
    if (to.path === '/admin/login') {
      return auth.isStaffLoggedIn ? { path: '/admin/dashboard' } : true
    }
    return auth.isStaffLoggedIn ? true : { path: '/admin/login' }
  }
  // 学习树：客户会话守卫；公开页（登录/注册/分享/404）放行
  if (to.name === 'share-kp' || to.name === 'not-found' || to.name === 'login' || to.name === 'register') {
    return true
  }
  return auth.isCustomerLoggedIn ? true : { path: '/login' }
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · 知己` : '知己 · 面试陪练'
})

export default router
