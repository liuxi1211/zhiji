<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { staffLogin } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

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
    void router.push('/admin/dashboard')
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
    <el-card class="login-card" shadow="never">
      <template #header>
        <span>知己 · 管理台登录</span>
      </template>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="后台人员账号" />
        </el-form-item>
        <el-form-item label="口令">
          <el-input v-model="form.password" type="password" show-password />
        </el-form-item>
        <el-button type="primary" :loading="loading" class="submit-btn" @click="submit">登录</el-button>
      </el-form>
      <div class="hint">后台账号由管理员创建；忘记口令请联系管理员重置</div>
    </el-card>
  </div>
</template>

<style scoped>
.staff-login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--zj-bg, #f5f7fa);
}

.login-card {
  width: 360px;
}

.submit-btn {
  width: 100%;
}

.hint {
  margin-top: 12px;
  font-size: 12px;
  color: var(--zj-text-secondary, #909399);
  text-align: center;
}
</style>
