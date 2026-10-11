<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { staffLogin } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import BrandMark from '@/components/BrandMark.vue'
import ThemeToggle from '@/components/ThemeToggle.vue'

const router = useRouter()
const auth = useAuthStore()
const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function submit() {
  loading.value = true
  try {
    const result = await staffLogin({ ...form })
    auth.setStaff(result.token, result.username, result.role)
    ElMessage.success('登录成功')
    void router.push('/admin/production')
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="staff-login-page">
    <div class="staff-card">
      <header class="staff-head">
        <BrandMark size="md" />
        <span class="staff-tag">管理台</span>
        <ThemeToggle />
      </header>

      <div class="staff-body">
        <h1 class="staff-title">后台登录</h1>
        <p class="staff-sub">内部人员入口，与学习端账号互不通用</p>

        <el-form label-position="top" @submit.prevent="submit">
          <el-form-item label="用户名">
            <el-input v-model="form.username" placeholder="后台人员账号" size="large" />
          </el-form-item>
          <el-form-item label="口令">
            <el-input v-model="form.password" type="password" show-password size="large" />
          </el-form-item>
          <el-button type="primary" size="large" :loading="loading" class="submit-btn" @click="submit">
            登录
          </el-button>
        </el-form>
      </div>

      <footer class="staff-foot">后台账号由管理员创建；忘记口令请联系管理员重置</footer>
    </div>
  </div>
</template>

<style scoped>
.staff-login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--zj-paper);
}

.staff-card {
  width: min(400px, 100%);
  background: var(--zj-surface);
  border: 1px solid var(--zj-line-soft);
  border-radius: var(--zj-radius-lg);
  box-shadow: var(--zj-shadow);
  overflow: hidden;
}

.staff-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px 22px;
  background: var(--zj-sidebar);
}

.staff-tag {
  padding: 2px 7px;
  font-size: 11px;
  font-weight: 600;
  border-radius: 5px;
  color: #c2cdcb;
  background: rgba(255, 255, 255, 0.08);
}

.staff-head :deep(.theme-toggle) {
  margin-left: auto;
}

.staff-body {
  padding: 26px 22px 22px;
}

.staff-title {
  margin: 0 0 4px;
  font-size: var(--zj-fs-md);
  font-weight: 650;
  color: var(--zj-ink-900);
}

.staff-sub {
  margin: 0 0 20px;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-500);
}

.submit-btn {
  width: 100%;
}

.staff-foot {
  padding: 13px 22px;
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-400);
  background: var(--zj-surface-sunken);
  border-top: 1px solid var(--zj-line-soft);
}
</style>
