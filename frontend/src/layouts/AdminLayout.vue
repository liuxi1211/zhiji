<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

interface AdminMenuItem {
  path: string
  label: string
  roles?: string[]
}

interface AdminMenuGroup {
  label?: string
  roles?: string[]
  items: AdminMenuItem[]
}

// B 端菜单注册表：新增页面先在 docs/prd/B端管理台PRD.md 页面总览表登记，再在此加一行；
// roles 为空表示 admin/editor 均可见，仅 admin 可见的组按登录角色过滤（D18）。
const menuGroups: AdminMenuGroup[] = [
  {
    items: [{ path: '/admin/dashboard', label: '概览' }]
  },
  {
    label: '内容资产',
    items: [
      { path: '/admin/skills', label: '技能库' },
      { path: '/admin/kp', label: '知识点管理' }
    ]
  },
  {
    label: '内容生产',
    items: [
      { path: '/admin/docs', label: '素材库' },
      { path: '/admin/candidates', label: '候选池' },
      { path: '/admin/knowledge-map', label: '知识地图' },
      { path: '/admin/review-flow', label: '校对流转' }
    ]
  },
  {
    label: '系统',
    roles: ['admin'],
    items: [
      { path: '/admin/accounts', label: '账号管理' },
      { path: '/admin/settings', label: '系统配置' }
    ]
  }
]

const auth = useAuthStore()
const router = useRouter()

const visibleGroups = computed(() =>
  menuGroups.filter((group) => !group.roles || (auth.staffRole ? group.roles.includes(auth.staffRole) : false))
)

const openGroupIndexes = computed(() =>
  visibleGroups.value
    .map((group, index) => (group.label ? String(index) : ''))
    .filter((index) => index !== '')
)

function logout() {
  auth.clearStaff()
  ElMessage.success('已退出')
  void router.push('/admin/login')
}
</script>

<template>
  <el-container class="admin-layout">
    <el-aside width="200px" class="admin-aside">
      <div class="admin-brand">知己 · 管理台</div>
      <el-menu
        :default-active="$route.path"
        :default-openeds="openGroupIndexes"
        router
        class="admin-menu"
      >
        <template v-for="(group, groupIndex) in visibleGroups" :key="groupIndex">
          <el-menu-item v-if="!group.label" :index="group.items[0].path">
            {{ group.items[0].label }}
          </el-menu-item>
          <el-sub-menu v-else :index="String(groupIndex)">
            <template #title>{{ group.label }}</template>
            <el-menu-item
              v-for="item in group.items"
              :key="item.path"
              :index="item.path"
            >
              {{ item.label }}
            </el-menu-item>
          </el-sub-menu>
        </template>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="admin-header">
        <span>{{ $route.meta.title }}</span>
        <span class="header-right">
          <span class="current-user">{{ auth.staffUsername }}</span>
          <el-link class="logout" @click="logout">退出</el-link>
          <RouterLink to="/map" class="to-learn">返回学习端</RouterLink>
        </span>
      </el-header>
      <el-main>
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.admin-layout {
  min-height: 100vh;
}

.admin-aside {
  background: #fff;
  border-right: 1px solid var(--zj-border);
}

.admin-brand {
  padding: 16px;
  font-weight: 600;
  border-bottom: 1px solid var(--zj-border);
}

.admin-menu {
  border-right: none;
}

.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid var(--zj-border);
  font-weight: 600;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
  font-weight: 400;
}

.current-user {
  font-size: 13px;
  color: var(--zj-text-secondary, #909399);
}

.logout {
  font-size: 13px;
}

.to-learn {
  font-size: 13px;
  font-weight: 400;
  color: #2b6cf6;
  text-decoration: none;
}
</style>
