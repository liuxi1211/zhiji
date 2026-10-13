<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { SwitchButton } from '@element-plus/icons-vue'
import BrandMark from '@/components/BrandMark.vue'
import ThemeToggle from '@/components/ThemeToggle.vue'
import { useAuthStore } from '@/stores/auth'

interface AdminMenuItem {
  path: string
  label: string
  icon?: string
  roles?: string[]
}

interface AdminMenuGroup {
  label?: string
  roles?: string[]
  items: AdminMenuItem[]
}

/* 菜单线性图标：静态内置字符串（非用户输入），v-html 安全；风格对齐 LearnLayout 的手绘线性图标 */
const MENU_ICONS: Record<string, string> = {
  catalog: '<circle cx="5.5" cy="5.5" r="2"/><circle cx="18.5" cy="5.5" r="2"/><circle cx="12" cy="18.5" r="2"/><path d="M7 6.5 10.8 16.8M17 6.5 13.2 16.8M7.5 5.5h9"/>',
  docs: '<path d="M4 6.5A1.5 1.5 0 0 1 5.5 5H10l2 2.5h6.5A1.5 1.5 0 0 1 20 9v9a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 18V6.5Z"/>',
  production: '<path d="M4 5.5h16M4 12h16M4 18.5h16"/><rect x="6.5" y="3.8" width="3" height="3.4" rx="0.6"/><rect x="12" y="10.3" width="3" height="3.4" rx="0.6"/><rect x="9" y="16.8" width="3" height="3.4" rx="0.6"/>',
  judge: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5v4.5l3 2"/>',
  accounts: '<circle cx="9" cy="8" r="3.2"/><path d="M3.5 20a5.5 5.5 0 0 1 11 0"/><path d="M15.5 5.5a3.2 3.2 0 0 1 0 5"/><path d="M17 14.8a5.5 5.5 0 0 1 3.5 5.2"/>',
  settings: '<path d="M4 7h16M4 12h16M4 17h16"/><circle cx="9" cy="7" r="1.8"/><circle cx="15" cy="12" r="1.8"/><circle cx="8" cy="17" r="1.8"/>'
}

// B 端菜单注册表：严格对齐新版《核心模块定义》的内容维护者工作台——
// 知识库管理（素材库/内容生产台/知识库管理，新增向导为入口弹窗不占菜单）+ 账号与体系配置。
// 旧口径占位页（概览/技能库/知识点管理/候选池/知识地图/校对流转）全部退役：
// 「概览」的待办数由内容生产台承担，「技能域字典」由 5.3 固化领域清单取代。
const menuGroups: AdminMenuGroup[] = [
  {
    label: '内容资产',
    items: [
      { path: '/admin/catalog', label: '知识库管理', icon: 'catalog' }
    ]
  },
  {
    label: '内容生产',
    items: [
      { path: '/admin/docs', label: '素材库', icon: 'docs' },
      { path: '/admin/production', label: '内容生产台', icon: 'production' },
      { path: '/admin/judge', label: '试判台', icon: 'judge' }
    ]
  },
  {
    label: '系统',
    roles: ['admin'],
    items: [
      { path: '/admin/accounts', label: '账号管理', icon: 'accounts' },
      { path: '/admin/settings', label: '系统配置', icon: 'settings' }
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

const roleLabel = computed(() => (auth.staffRole === 'admin' ? '管理员' : '内容维护者'))

/**
 * 顶栏显示"所属分组"而非页面标题 —— 页面标题已在内容区 h1 呈现，
 * 顶栏重复同一行字是纯冗余；改为分组口径后顶栏变成面包屑，两处各司其职。
 */
const currentGroupLabel = computed(() => {
  const path = router.currentRoute.value.path
  const group = visibleGroups.value.find((g) => g.items.some((item) => item.path === path))
  return group?.label ?? '管理台'
})

function logout() {
  auth.clearStaff()
  ElMessage.success('已退出')
  void router.push('/admin/login')
}
</script>

<template>
  <el-container class="admin-layout">
    <el-aside width="224px" class="admin-aside">
      <div class="admin-brand">
        <BrandMark size="md" tone="onDark" />
        <span class="admin-brand-tag">管理台</span>
      </div>

      <el-menu
        :default-active="$route.path"
        :default-openeds="openGroupIndexes"
        router
        class="admin-menu"
      >
        <template v-for="(group, groupIndex) in visibleGroups" :key="groupIndex">
          <el-menu-item v-if="!group.label" :index="group.items[0].path">
            <svg v-if="group.items[0].icon" class="menu-icon" viewBox="0 0 24 24" aria-hidden="true" v-html="MENU_ICONS[group.items[0].icon]"></svg>
            {{ group.items[0].label }}
          </el-menu-item>
          <el-sub-menu v-else :index="String(groupIndex)">
            <template #title>{{ group.label }}</template>
            <el-menu-item
              v-for="item in group.items"
              :key="item.path"
              :index="item.path"
            >
              <svg v-if="item.icon" class="menu-icon" viewBox="0 0 24 24" aria-hidden="true" v-html="MENU_ICONS[item.icon]"></svg>
              {{ item.label }}
            </el-menu-item>
          </el-sub-menu>
        </template>
      </el-menu>

      <div class="admin-aside-foot">
        <span class="zj-eyebrow">v0.1 · 内部</span>
      </div>
    </el-aside>

    <el-container>
      <el-header class="admin-header">
        <div class="header-left">
          <span class="header-eyebrow">{{ currentGroupLabel }}</span>
        </div>
        <div class="header-right">
          <ThemeToggle />
          <span class="header-divider" aria-hidden="true"></span>
          <span class="current-user">
            <span class="current-user-name">{{ auth.staffUsername }}</span>
            <span class="current-user-role">{{ roleLabel }}</span>
          </span>
          <el-button class="logout" text size="small" :icon="SwitchButton" @click="logout">退出</el-button>
          <RouterLink to="/map" class="to-learn">返回学习端</RouterLink>
        </div>
      </el-header>
      <el-main class="admin-main">
        <RouterView v-slot="{ Component }">
          <Transition name="route-fade" mode="out-in">
            <component :is="Component" />
          </Transition>
        </RouterView>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
/* 应用壳固定在视口内：侧栏与顶栏常驻，只有内容区滚动（el-main 自带 overflow:auto）。
 * 之前的 min-height 让长页面把品牌区和顶栏一起滚出视野，是"文档感"的主要来源。
 */
.admin-layout {
  height: 100vh;
}

/* 内层 el-container 是 flex 子项，min-height 归零防内容把它顶破视口 */
.admin-layout > .el-container {
  min-height: 0;
}

.admin-aside {
  display: flex;
  flex-direction: column;
  background: var(--zj-sidebar);
  border-right: none;
  overflow: hidden;
}

.admin-brand {
  display: flex;
  align-items: center;
  gap: var(--zj-space-2);
  padding: var(--zj-space-4) var(--zj-space-4) var(--zj-space-3);
}

.admin-brand-tag {
  padding: 2px 7px;
  align-self: center;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  border-radius: 5px;
  color: #c2cdcb;
  background: rgba(255, 255, 255, 0.08);
}

.admin-menu {
  flex: 1;
  padding: 4px 10px;
  border-right: none;
  overflow-y: auto;
}

/* 当前项用左侧主色轨道条指示，替代 Element 默认的整行底色 */
.admin-menu :deep(.el-menu-item),
.admin-menu :deep(.el-sub-menu__title) {
  height: 40px;
  line-height: 40px;
  margin-bottom: 2px;
  border-radius: 8px;
  position: relative;
}

.admin-menu :deep(.el-menu-item.is-active) {
  background: rgba(255, 255, 255, 0.09);
  color: #fff;
  font-weight: 600;
}

.admin-menu :deep(.el-menu-item.is-active::before) {
  content: '';
  position: absolute;
  left: 0;
  top: 11px;
  bottom: 11px;
  width: 3px;
  border-radius: 2px;
  background: var(--zj-primary);
}

/* 停用 Element 横向菜单项自带的 2px 下边框 */
.admin-menu :deep(.el-menu-item) {
  border-bottom: none;
}

/* v-html 注入的 path/rect 在 SVG 命名空间内；fill/stroke 系为可继承属性，设在外层 svg 即可传导 */
.menu-icon {
  flex: none;
  width: 16px;
  height: 16px;
  margin-right: 8px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.admin-menu :deep(.el-sub-menu__title) {
  font-size: 12px;
  letter-spacing: 0.06em;
  color: #7c8785;
  height: 36px;
  line-height: 36px;
}

.admin-menu :deep(.el-sub-menu .el-menu) {
  background: transparent;
}

/* 缩进由 Element 用 --el-menu-base-level-padding + level×level-padding 计算，勿用 !important 压掉 */

.admin-aside-foot {
  padding: 14px 18px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

/* 侧栏是深底，--zj-ink-* 在此不可用（那是"纸面"用的色）；需用深底专用的浅灰 */
.admin-aside-foot .zj-eyebrow {
  color: #8b9795;
}

.admin-aside-foot .zj-eyebrow::before {
  background: #8b9795;
}

.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: auto;
  min-height: 64px;
  padding: 12px 24px;
  background: var(--zj-surface);
  border-bottom: 1px solid var(--zj-line-soft);
}

.header-left {
  display: flex;
  align-items: center;
}

/* 顶栏只作面包屑（分组名），页面标题归内容区 h1，避免同一行字出现两次 */
.header-eyebrow {
  font-size: var(--zj-fs-xs);
  font-weight: 600;
  letter-spacing: var(--zj-tracking-eyebrow);
  color: var(--zj-ink-600);
  text-transform: uppercase;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-divider {
  width: 1px;
  height: 20px;
  background: var(--zj-line);
}

.current-user {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  line-height: 1.3;
}

.current-user-name {
  font-size: var(--zj-fs-sm);
  font-weight: 600;
  color: var(--zj-ink-700);
}

.current-user-role {
  font-size: 11px;
  color: var(--zj-ink-600);
}

.logout {
  font-size: var(--zj-fs-sm);
}

.to-learn {
  padding: 5px 12px;
  font-size: var(--zj-fs-sm);
  color: var(--zj-primary);
  border: 1px solid var(--zj-line);
  border-radius: 8px;
  transition: border-color var(--zj-dur-fast) var(--zj-ease-out),
    background var(--zj-dur-fast) var(--zj-ease-out);
}

.to-learn:hover {
  border-color: var(--zj-primary);
  background: var(--zj-primary-soft);
}

.admin-main {
  background: var(--zj-paper);
  padding: var(--zj-space-5);
}

/* 宽屏适配：内容区随视口流式拉伸，但封顶 1760px 防行宽失控。
 * 1280 固定上限在 2K（可用宽约 2280px）下两侧各空出 500px+，是"内容缩在中间"的根源；
 * min(1760px, 100%) 保证 1920 及以下视口行为不变，2K/4K 下表格、栅格自动吃满增量。
 * 各页内部均为流式栅格（1fr 网格 / auto-fill / el-table），无需逐页改动。
 */
.admin-main > * {
  max-width: min(1760px, 100%);
  margin-inline: auto;
}

/* —— 响应式 —— */
/* 菜单项只有文字、没有图标，所以不收成"图标条"（那会变空列）；
 * 窄屏改为压缩侧栏宽度 + 允许表格横向滚动，保住可读性。
 */
@media (max-width: 1080px) {
  .admin-aside {
    width: 176px !important;
  }

  .admin-brand-tag,
  .admin-aside-foot {
    display: none;
  }

  .admin-main {
    padding: var(--zj-space-4);
  }

  .admin-main :deep(.el-table) {
    overflow-x: auto;
  }
}

@media (max-width: 768px) {
  .admin-aside {
    width: 148px !important;
  }

  .admin-header {
    padding-inline: var(--zj-space-4);
  }

  .header-eyebrow,
  .current-user-role {
    display: none;
  }

  .admin-main {
    padding: var(--zj-space-3);
  }
}
</style>
