<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { customerCaptcha, customerLogin, customerRegister } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import BrandMark from '@/components/BrandMark.vue'
import ThemeToggle from '@/components/ThemeToggle.vue'

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
    <div class="auth-shell">
      <aside class="auth-story">
        <BrandMark size="lg" />
        <h1 class="story-title">把面试里<br />答不上的，练成答得上的</h1>
        <p class="story-note">
          知己用一条追问链照出你的真实水平：问到哪一层崩，第二天就从哪一层接着练。
        </p>
        <ul class="story-list">
          <li>AI 面试官按追问链逐层往深问</li>
          <li>命中的要点必须附你的原话，引不出算未命中</li>
          <li>分数由公式算，模型只做对齐判断</li>
        </ul>
        <div class="story-foot">
          <span class="zj-eyebrow">知己 · 面试陪练</span>
        </div>
      </aside>

      <main class="auth-panel">
        <div class="panel-top">
          <ThemeToggle />
        </div>
        <div class="panel-body">
          <h2 class="panel-title">{{ mode === 'register' ? '注册账号' : '登录' }}</h2>
          <p class="panel-sub">
            {{ mode === 'register' ? '创建账号后即可开始建档与练习' : '继续你上次的练习进度' }}
          </p>

          <el-form label-position="top" @submit.prevent="submit">
            <el-form-item label="用户名">
              <el-input v-model="form.username" placeholder="3~32 个字符" size="large" />
            </el-form-item>
            <el-form-item label="口令">
              <el-input
                v-model="form.password"
                type="password"
                show-password
                placeholder="6~64 个字符"
                size="large"
              />
            </el-form-item>
            <el-form-item label="验证码">
              <div class="captcha-row">
                <el-input v-model="form.captchaCode" placeholder="输入右侧字符" size="large" />
                <!-- 验证码 SVG 由本服务实时生成，点击刷新 -->
                <span
                  class="captcha-img"
                  role="button"
                  tabindex="0"
                  title="点击刷新"
                  aria-label="验证码，点击刷新"
                  @click="refreshCaptcha"
                  @keydown.enter.prevent="refreshCaptcha"
                  @keydown.space.prevent="refreshCaptcha"
                  v-html="captchaSvg"
                ></span>
              </div>
              <p class="captcha-hint">看不清？点击图片换一张</p>
            </el-form-item>
            <el-button type="primary" size="large" :loading="loading" class="submit-btn" @click="submit">
              {{ mode === 'register' ? '注册' : '登录' }}
            </el-button>
          </el-form>

          <div class="switch-row">
            <span>{{ mode === 'login' ? '还没有账号？' : '已有账号？' }}</span>
            <el-link type="primary" @click="mode = mode === 'login' ? 'register' : 'login'">
              {{ mode === 'login' ? '去注册' : '去登录' }}
            </el-link>
          </div>
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--zj-paper);
}

.auth-shell {
  display: grid;
  grid-template-columns: 1.05fr 1fr;
  width: min(920px, 100%);
  min-height: 540px;
  background: var(--zj-surface);
  border: 1px solid var(--zj-line-soft);
  border-radius: var(--zj-radius-lg);
  box-shadow: var(--zj-shadow);
  overflow: hidden;
}

/* 左栏深色叙事面：进站第一眼就要说明产品在做什么 */
.auth-story {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 36px 34px;
  background: var(--zj-sidebar);
  color: #fff;
}

.story-title {
  margin: 8px 0 0;
  font-size: var(--zj-fs-xl);
  font-weight: 650;
  line-height: 1.35;
  letter-spacing: -0.01em;
}

.story-note {
  margin: 0;
  font-size: var(--zj-fs-sm);
  line-height: 1.75;
  color: #9fb0ae;
}

.story-list {
  margin: 4px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.story-list li {
  position: relative;
  padding-left: 18px;
  font-size: var(--zj-fs-sm);
  line-height: 1.6;
  color: #bfccca;
}

/* 每条的刻度短横，与品牌字标同源 */
.story-list li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0.65em;
  width: 9px;
  height: 2px;
  border-radius: 1px;
  background: #63c6be;
}

.story-foot {
  margin-top: auto;
  padding-top: 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}

.story-foot .zj-eyebrow {
  color: #8b9795;
}

.story-foot .zj-eyebrow::before {
  background: #8b9795;
}

.auth-panel {
  display: flex;
  flex-direction: column;
  padding: 20px 34px 34px;
}

.panel-top {
  display: flex;
  justify-content: flex-end;
}

.panel-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding-bottom: 20px;
}

.panel-title {
  margin: 0 0 4px;
  font-size: var(--zj-fs-lg);
  font-weight: 650;
  letter-spacing: -0.01em;
  color: var(--zj-ink-900);
}

.panel-sub {
  margin: 0 0 22px;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-500);
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
  flex-shrink: 0;
  border: 1px solid var(--zj-line);
  border-radius: var(--zj-radius-sm);
  overflow: hidden;
  transition: border-color var(--zj-dur-fast) var(--zj-ease-out);
}

.captcha-img:hover,
.captcha-img:focus-visible {
  border-color: var(--zj-primary);
}

.captcha-hint {
  margin: 4px 0 0;
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

.submit-btn {
  width: 100%;
  margin-top: 4px;
}

.switch-row {
  margin-top: 16px;
  display: flex;
  gap: 6px;
  justify-content: center;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-500);
}

@media (max-width: 760px) {
  .auth-shell {
    grid-template-columns: 1fr;
    min-height: auto;
  }

  .auth-story {
    padding: 26px 24px;
  }

  .story-title {
    font-size: var(--zj-fs-lg);
  }

  .story-list {
    display: none;
  }

  .auth-panel {
    padding: 20px 24px 28px;
  }
}
</style>
