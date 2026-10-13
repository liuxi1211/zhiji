<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { IntakeClaim } from '@/api/types'
import {
  acceptClaims,
  clearIntakeChat,
  getChatHistory,
  reviseIntakeChat,
  startIntakeChat
} from '@/api/profile'

const props = defineProps<{
  profileId: number
  profileStatus: string
  /** 基础信息必填项（年限 + 技术栈）是否已填写：未填写时不允许开始对话 */
  intakeReady: boolean
}>()

const emit = defineEmits<{
  (e: 'accepted'): void
}>()

const MAX_ROUNDS = 5

/** 内容渲染：占位【…】高亮（先转义再替换，防注入） */
function renderContent(content: string): string {
  const escaped = (content ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
  return escaped.replace(/【([^【】]*)】/g, '<mark class="content-ph">【$1】</mark>')
}

const messages = ref<
  { id: number; role: 'user' | 'assistant'; text: string; claims: IntakeClaim[] | null; gaps?: string[] }[]
>([])
const round = ref(0)
const loadingHistory = ref(true)
const generating = ref(false)
const revising = ref(false)
const accepting = ref(false)
const draftText = ref('')
const chatEl = ref<HTMLElement | null>(null)

const lastClaimTurn = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const msg = messages.value[i]
    if (msg.role === 'assistant' && msg.claims && msg.claims.length > 0) {
      return { index: i, claims: msg.claims }
    }
  }
  return null
})

const roundText = computed(() => `第 ${round.value}/${MAX_ROUNDS} 轮`)

onMounted(async () => {
  try {
    const history = await getChatHistory(props.profileId)
    messages.value = history.messages.map((m) => ({
      id: m.id,
      role: m.role,
      text: m.text,
      claims: m.claims
    }))
    round.value = history.round
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '加载对话失败')
  } finally {
    loadingHistory.value = false
    scrollToBottom()
  }
})

function scrollToBottom() {
  void nextTick(() => {
    chatEl.value?.scrollTo({ top: chatEl.value.scrollHeight, behavior: 'smooth' })
  })
}

// 面板被父级 v-show 隐藏时滚动无效，切回对话步骤时由父组件调用
defineExpose({ scrollToBottom })

async function startFirst() {
  if (!props.intakeReady) return
  generating.value = true
  try {
    const turn = await startIntakeChat(props.profileId)
    messages.value.push({
      id: Date.now(),
      role: 'assistant',
      text: turn.reply,
      claims: turn.claims,
      gaps: turn.coverageGaps ?? []
    })
    round.value = turn.round
    scrollToBottom()
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '首轮生成失败，稍后再试')
  } finally {
    generating.value = false
  }
}

async function sendRevise() {
  const text = draftText.value.trim()
  if (!text || revising.value) return
  if (round.value >= MAX_ROUNDS) {
    ElMessage.warning('对话轮次已用完，请采纳当前版本或重新开始对话')
    return
  }
  draftText.value = ''
  await doRevise(text)
}

/** 一键补写覆盖缺口：技术栈组件没被草案覆盖时，用固定话术发起修订 */
async function fixGaps(gaps: string[]) {
  if (!gaps.length || revising.value) return
  if (round.value >= MAX_ROUNDS) {
    ElMessage.warning('对话轮次已用完，请采纳当前版本或重新开始对话')
    return
  }
  await doRevise(
    `我的技术栈里还有这些没在草案中出现：${gaps.join('、')}。请把它们补进最匹配的技能条（只写我真实有的，缺事实用【】占位，并保持小标题互不重复）。`
  )
}

async function doRevise(text: string) {
  messages.value.push({ id: Date.now(), role: 'user', text, claims: null })
  revising.value = true
  scrollToBottom()
  try {
    const turn = await reviseIntakeChat(props.profileId, text)
    messages.value.push({
      id: Date.now() + 1,
      role: 'assistant',
      text: turn.reply,
      claims: turn.claims,
      gaps: turn.coverageGaps ?? []
    })
    round.value = turn.round
    scrollToBottom()
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '修订失败，稍后再试')
  } finally {
    revising.value = false
    scrollToBottom()
  }
}

async function acceptCurrent() {
  const turn = lastClaimTurn.value
  if (!turn || accepting.value) return
  accepting.value = true
  try {
    await acceptClaims(props.profileId, turn.claims)
    ElMessage.success('已采纳本版技能条，可在下方微调')
    emit('accepted')
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '采纳失败')
  } finally {
    accepting.value = false
  }
}

async function restartChat() {
  try {
    await ElMessageBox.confirm(
      '清空后当前对话与各轮草案都会删除（已采纳的技能条不受影响），重新从第一版开始。',
      '重新开始对话',
      { type: 'warning', confirmButtonText: '清空重聊', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await clearIntakeChat(props.profileId)
    messages.value = []
    round.value = 0
    ElMessage.success('对话已清空')
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '操作失败')
  }
}
</script>

<template>
  <div class="chat-panel">
    <div v-if="loadingHistory" class="chat-empty">加载对话中…</div>

    <template v-else>
      <div class="sess-meta">
        <span>参考池已注入 · AI 只写你的技术栈，编造的事实以【】占位</span>
        <span class="zj-num">{{ roundText }}</span>
      </div>

      <div ref="chatEl" class="chat-stream">
        <div v-if="messages.length === 0" class="chat-empty">
          <template v-if="!intakeReady">
            <p class="chat-empty-title">先完成第 1 步的基础信息</p>
            <p class="chat-empty-hint">
              工作年限决定措辞强度，技术栈决定 AI 能写什么——这两项填完，教练才有起草依据。
            </p>
            <el-button class="btn-ai" disabled>生成第一版草案</el-button>
          </template>
          <template v-else>
            <p class="chat-empty-title">还没有对话</p>
            <p class="chat-empty-hint">
              基础信息已就绪，让教练根据你的年限、技术栈与经历直接起草第一版技能条，
              不满意直接回复它，来回磨到满意。
            </p>
            <el-button class="btn-ai" type="primary" :loading="generating" @click="startFirst">
              生成第一版草案
            </el-button>
            <p class="chat-wait">通常需要 30~90 秒</p>
          </template>
        </div>

        <template v-for="(msg, index) in messages" :key="msg.id">
          <div v-if="msg.role === 'user'" class="msg msg-user">{{ msg.text }}</div>
          <div v-else class="msg msg-ai">
            <p v-if="msg.text" class="msg-reply">{{ msg.text }}</p>
            <div v-if="msg.claims && msg.claims.length > 0" class="claim-version">
              <div v-for="(claim, ci) in msg.claims" :key="ci" class="version-card">
                <h5 class="version-title">{{ claim.title }}</h5>
                <!-- eslint-disable-next-line vue/no-v-html —— 内容已转义，仅注入占位高亮标记 -->
                <p class="version-content" v-html="renderContent(claim.content)"></p>
              </div>
              <div
                v-if="index === messages.length - 1 && lastClaimTurn && lastClaimTurn.index === index"
                class="version-actions"
              >
                <el-button type="primary" size="small" :loading="accepting" @click="acceptCurrent">
                  就用这版
                </el-button>
                <span class="version-hint">采纳后可在下方技能条里逐条微调，占位符在定稿前处理</span>
              </div>
              <div
                v-if="msg.gaps && msg.gaps.length > 0 && index === messages.length - 1 && !generating && !revising"
                class="gap-hint"
              >
                <span class="gap-text">技术栈未覆盖：{{ msg.gaps.join('、') }}</span>
                <el-button size="small" link type="primary" @click="fixGaps(msg.gaps ?? [])">让教练补上</el-button>
              </div>
            </div>
          </div>
        </template>

        <div v-if="generating || revising" class="msg msg-ai">
          <div class="ai-body-typing" aria-live="polite">
            <span class="typing-dot" aria-hidden="true"></span>
            <span class="typing-dot" aria-hidden="true"></span>
            <span class="typing-dot" aria-hidden="true"></span>
            <span class="typing-text">{{ generating ? '教练正在通读你的信息并起草…' : '教练正在按你的反馈修订…' }}</span>
          </div>
        </div>
      </div>

      <div v-if="messages.length > 0" class="composer">
        <el-input
          v-model="draftText"
          type="textarea"
          :rows="2"
          maxlength="2000"
          resize="none"
          :placeholder="
            round >= MAX_ROUNDS
              ? '轮次已用完，请采纳当前版本'
              : '直接说人话反馈：哪里不满意、补充经历、调整方向…'
          "
          @keydown.enter.exact.prevent="sendRevise"
        />
        <el-button
          type="primary"
          :loading="revising"
          :disabled="!draftText.trim() || round >= MAX_ROUNDS"
          @click="sendRevise"
        >
          发送
        </el-button>
      </div>
      <div v-if="messages.length > 0" class="chat-tools">
        <el-link type="danger" @click="restartChat">清空重聊</el-link>
      </div>
    </template>
  </div>
</template>

<style scoped>
.chat-panel {
  display: flex;
  flex-direction: column;
  gap: var(--zj-space-3);
}

.sess-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--zj-space-3);
  flex-wrap: wrap;
  padding: var(--zj-space-2) var(--zj-space-3);
  border: 1px solid var(--zj-line-soft);
  border-radius: var(--zj-radius);
  background: var(--zj-surface-sunken);
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

.chat-stream {
  display: flex;
  flex-direction: column;
  gap: var(--zj-space-3);
  max-height: 640px;
  overflow-y: auto;
  padding: var(--zj-space-1) 2px;
}

.chat-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--zj-space-2);
  padding: var(--zj-space-6) var(--zj-space-4);
  text-align: center;
}

.chat-empty-title {
  font-size: var(--zj-fs-md);
  font-weight: 650;
  color: var(--zj-ink-900);
}

.chat-empty-hint {
  max-width: 460px;
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
  line-height: 1.7;
}

.chat-wait {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-400);
}

.msg {
  max-width: 92%;
  font-size: var(--zj-fs-sm);
  line-height: 1.7;
}

.msg-user {
  align-self: flex-end;
  padding: 9px 14px;
  border-radius: 12px 12px 3px 12px;
  background: var(--zj-primary-soft);
  border: 1px solid var(--zj-primary-soft);
  color: var(--zj-ink-900);
  white-space: pre-wrap;
}

.msg-ai {
  align-self: flex-start;
  width: 100%;
  max-width: 100%;
}

.msg-reply {
  margin: 0 0 var(--zj-space-2);
  color: var(--zj-ink-700);
}

.claim-version {
  display: flex;
  flex-direction: column;
  gap: var(--zj-space-2);
}

.version-card {
  background: var(--zj-surface);
  border: 1px solid var(--zj-line-soft);
  border-left: 2px solid var(--zj-primary);
  border-radius: var(--zj-radius);
  padding: var(--zj-space-2) var(--zj-space-3);
}

.version-title {
  margin: 0 0 2px;
  font-size: var(--zj-fs-xs);
  font-weight: 650;
  color: var(--zj-ink-900);
}

.version-content {
  margin: 0;
  font-size: var(--zj-fs-xs);
  line-height: 1.75;
  color: var(--zj-ink-700);
  white-space: pre-wrap;
}

:deep(.content-ph) {
  background: var(--zj-copper-soft);
  color: var(--zj-copper);
  border-radius: 3px;
  padding: 0 2px;
  font-weight: 600;
}

.version-actions {
  display: flex;
  align-items: center;
  gap: var(--zj-space-3);
  flex-wrap: wrap;
}

.version-hint {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-400);
}

.gap-hint {
  display: flex;
  align-items: center;
  gap: var(--zj-space-3);
  flex-wrap: wrap;
  padding: var(--zj-space-2) var(--zj-space-3);
  border: 1px dashed var(--zj-line-soft);
  border-radius: var(--zj-radius);
  background: var(--zj-surface-sunken);
}

.gap-text {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

.ai-body-typing {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: var(--zj-space-2) var(--zj-space-3);
  background: var(--zj-surface);
  border: 1px solid var(--zj-line-soft);
  border-radius: var(--zj-radius);
}

.typing-dot {
  width: 5px;
  height: 5px;
  border-radius: 999px;
  background: var(--zj-ink-400);
  animation: ph-blink 1.2s infinite;
}

.typing-dot:nth-child(2) {
  animation-delay: 0.2s;
}

.typing-dot:nth-child(3) {
  animation-delay: 0.4s;
}

.typing-text {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

@keyframes ph-blink {
  0%,
  80%,
  100% {
    opacity: 0.25;
  }
  40% {
    opacity: 1;
  }
}

.composer {
  display: flex;
  align-items: flex-end;
  gap: var(--zj-space-2);
}

.chat-tools {
  display: flex;
  justify-content: flex-end;
}

@media (prefers-reduced-motion: reduce) {
  .typing-dot {
    animation: none;
    opacity: 0.5;
  }
}
</style>
