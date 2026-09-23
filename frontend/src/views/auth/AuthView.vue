<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { customerCaptcha, customerLogin, customerRegister } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const mode = ref<'login' | 'register'>('login')
const form = reactive({ username: '', password: '', captchaCode: '' })
const captchaId = ref('')
const captchaSvg = ref('')
const loading = ref(false)

async function refreshCaptcha() {
  const captcha = await customerCaptcha()
  captchaId.value = captcha.captchaId
  captchaSvg.value = captcha.svg
  form.captchaCode = ''
}
onMounted(() => {
  void refreshCaptcha()
})

async function submit() {
  loading.value = true
  try {
    const result = mode.value === 'register'
      ? await customerRegister({ ...form, captchaId: captchaId.value })
      : await customerLogin({ ...form, captchaId: captchaId.value })
    auth.setCustomer(result.token, result.username)
    ElMessage.success(mode.value === 'register' ? '注册成功' : '登录成功')
    // 默认简历档案随建档模块（T10）创建；现阶段先进我的页看档案占位
    void router.push('/profile')
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '操作失败')
    await refreshCaptcha()
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <el-card class="auth-card" shadow="never">
      <template #header>
        <span>{{ mode === 'register' ? '注册账号' : '登录' }}</span>
      </template>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="3~32 个字符" />
        </el-form-item>
        <el-form-item label="口令">
          <el-input v-model="form.password" type="password" show-password placeholder="6~64 个字符" />
        </el-form-item>
        <el-form-item label="验证码">
          <div class="captcha-row">
            <el-input v-model="form.captchaCode" placeholder="输入右侧字符" />
            <!-- 验证码 SVG 由本服务实时生成，点击刷新 -->
            <span class="captcha-img" title="点击刷新" @click="refreshCaptcha" v-html="captchaSvg"></span>
          </div>
        </el-form-item>
        <el-button type="primary" :loading="loading" class="submit-btn" @click="submit">
          {{ mode === 'register' ? '注册' : '登录' }}
        </el-button>
      </el-form>
      <div class="switch-row">
        <span v-if="mode === 'login'">还没有账号？</span>
        <span v-else>已有账号？</span>
        <el-link type="primary" @click="mode = mode === 'login' ? 'register' : 'login'">
          {{ mode === 'login' ? '去注册' : '去登录' }}
        </el-link>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--zj-bg, #f5f7fa);
}

.auth-card {
  width: 360px;
}

.captcha-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.captcha-img {
  cursor: pointer;
  display: inline-flex;
  border: 1px solid var(--zj-border, #dcdfe6);
  border-radius: 4px;
  overflow: hidden;
}

.submit-btn {
  width: 100%;
}

.switch-row {
  margin-top: 12px;
  display: flex;
  gap: 6px;
  justify-content: center;
  font-size: 13px;
  color: var(--zj-text-secondary, #909399);
}
</style>
