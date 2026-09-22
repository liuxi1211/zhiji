<script setup lang="ts">
import { RouterView } from 'vue-router'

const menus = [
  { path: '/admin/dashboard', label: '概览' },
  { path: '/admin/kp', label: '考点管理' },
  { path: '/admin/candidates', label: '候选池' },
  { path: '/admin/docs', label: '素材解析' },
  { path: '/admin/review-flow', label: '校对流转' }
]
</script>

<template>
  <el-container class="admin-layout">
    <el-aside width="200px" class="admin-aside">
      <div class="admin-brand">知己 · 管理台</div>
      <el-menu :default-active="$route.path" router class="admin-menu">
        <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
          {{ m.label }}
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="admin-header">
        <span>{{ $route.meta.title }}</span>
        <RouterLink to="/today" class="to-learn">返回学习端</RouterLink>
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

.to-learn {
  font-size: 13px;
  font-weight: 400;
  color: #2b6cf6;
  text-decoration: none;
}
</style>
