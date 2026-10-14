<script setup lang="ts">
// 附件面板（正文图片一览与处理）：把正文里所有图片引用铺成缩略图清单，一眼看出有哪些图、哪些还没进本系统；
// 逐张可「下载到本地」（本地化）/「上传替换」/「删除」。核心诉求：外链图转存失败时人工能就地补救
// ——能自己下载的传上来替换，觉得不重要的直接删（提交进标准库时的自动本地化只是兜底）。
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowDown, ArrowRight, Delete, MagicStick, Refresh, Upload } from '@element-plus/icons-vue'
import { localizeImages, uploadImage } from '@/api/assets'
import {
  imageDisplayUrl,
  parseDocImages,
  removeImageRaw,
  replaceImageRaw,
  rewriteImageSrc,
  type DocImage
} from '@/utils/docImages'

const props = withDefaults(defineProps<{
  /** 正文（v-model 双向：处理动作直接改正文，父组件即时看到） */
  modelValue: string
  /** 紧凑模式：缩略图更小、网格更矮（向导等固定高度画布内使用） */
  compact?: boolean
}>(), { compact: false })
const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

/** 解析当前正文里的图片引用（随正文变化实时刷新） */
const images = computed(() => parseDocImages(props.modelValue))

/**
 * 展开/收起：无图片时默认收起（不干扰正文浏览）；一旦有图，首次自动展开提示可处理，
 * 之后交给人工控制（用户手动收起后不再自动弹开，避免与正文编辑抢空间）。
 */
const expanded = ref(false)
let userToggled = false

watch(
  () => images.value.length,
  (count) => {
    if (count > 0 && !userToggled) expanded.value = true
  },
  { immediate: true }
)

function toggle() {
  userToggled = true
  expanded.value = !expanded.value
}

const externalCount = computed(() => images.value.filter((img) => img.external).length)
const localCount = computed(() => images.value.filter((img) => img.local).length)

const localizing = ref(false)
/** 缩略图加载失败（外链挂掉）的引用下标集合，用于显示"加载失败"占位 */
const brokenSet = ref<Set<string>>(new Set())

function content(): string {
  return props.modelValue ?? ''
}

function apply(next: string) {
  emit('update:modelValue', next)
}

function isBroken(img: DocImage): boolean {
  return brokenSet.value.has(img.raw)
}

function markBroken(img: DocImage) {
  const next = new Set(brokenSet.value)
  next.add(img.raw)
  brokenSet.value = next
}

/** 一键本地化：把正文里能下到的外链图收进本系统并替换地址（不落库，结果回编辑器） */
async function localizeAll() {
  if (!externalCount.value) {
    ElMessage.info('正文里没有外链图片')
    return
  }
  localizing.value = true
  try {
    const report = await localizeImages(content())
    apply(report.contentMd)
    // 更新色标：成功项现在是本地地址，失败项仍在；重新判定坏图
    brokenSet.value = new Set()
    if (report.failed === 0) {
      ElMessage.success(`已本地化 ${report.success} 张图片`)
    } else {
      ElMessage.warning(`已本地化 ${report.success} 张；${report.failed} 张下载失败，保留原链接，可上传替换或删除`)
    }
  } catch (error) {
    ElMessage.error((error as { message?: string }).message || '本地化失败')
  } finally {
    localizing.value = false
  }
}

/** 上传替换：人工选一张图 → 进本系统 → 替换该处引用 */
const replaceInput = ref<HTMLInputElement | null>(null)
let replaceTarget: DocImage | null = null

function pickReplace(img: DocImage) {
  replaceTarget = img
  replaceInput.value?.click()
}

async function onReplaceFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  const target = replaceTarget
  input.value = ''
  replaceTarget = null
  if (!file || !target) return
  try {
    const resp = await uploadImage(file)
    // 用最新解析结果里同 src 的引用替换（正文可能已被其它动作改动，重新定位更稳）
    const current = parseDocImages(content()).find((img) => img.src === target.src) ?? target
    apply(replaceImageRaw(content(), current.raw, rewriteImageSrc(current, resp.url)))
    ElMessage.success('已替换为系统图片')
  } catch (error) {
    ElMessage.error((error as { message?: string }).message || '上传失败')
  }
}

function removeImage(img: DocImage) {
  apply(removeImageRaw(content(), img.raw))
}

/** 单张重新本地化（只处理这一张外链），失败时提示可上传替换 */
const retryingIndex = ref<number | null>(null)
async function retryOne(index: number) {
  const img = images.value[index]
  if (!img) return
  retryingIndex.value = index
  try {
    // 用单张引用的 Markdown 片段走同一套本地化，后端返回该片段的结果
    const fragment = img.kind === 'md' ? img.raw : `<img src="${img.src}">`
    const report = await localizeImages(fragment)
    const localized = parseDocImages(report.contentMd)[0]
    if (localized && localized.local) {
      const current = parseDocImages(content()).find((i) => i.src === img.src) ?? img
      apply(replaceImageRaw(content(), current.raw, rewriteImageSrc(current, localized.src)))
      ElMessage.success('已本地化')
    } else {
      ElMessage.warning('这张图下载失败，可上传替换或删除')
    }
  } catch (error) {
    ElMessage.error((error as { message?: string }).message || '本地化失败')
  } finally {
    retryingIndex.value = null
  }
}
</script>

<template>
  <div
    class="attach-panel"
    :class="{ 'attach-panel--compact': props.compact, 'attach-panel--open': expanded }"
  >
    <div class="attach-head">
      <!-- 标题区整体可点：点标题即收起/展开，无需精准点箭头 -->
      <button type="button" class="attach-toggle" :aria-expanded="expanded" @click="toggle">
        <el-icon class="attach-caret">
          <ArrowDown v-if="expanded" />
          <ArrowRight v-else />
        </el-icon>
        <span class="attach-title">正文图片</span>
        <span class="attach-count zj-num">
          共 {{ images.length }} 张
          <template v-if="images.length">
            · <span class="ok-num">{{ localCount }}</span> 已入库
            <template v-if="externalCount"> · <span class="warn-num">{{ externalCount }}</span> 外链</template>
          </template>
        </span>
      </button>
    </div>

    <!-- 收起态只留一行标题（无图时默认收起，避免图片区影响全文浏览） -->
    <template v-if="expanded">
      <p v-if="!images.length" class="attach-empty">
        正文里暂无图片。图片会按正文中的引用自动汇总到这里，便于统一本地化或替换。
      </p>

      <div v-else class="attach-grid">
      <div
        v-for="(img, index) in images"
        :key="img.raw + index"
        class="attach-card"
        :class="{ broken: isBroken(img), external: img.external && !isBroken(img) }"
      >
        <div class="thumb-wrap">
          <img
            v-if="!isBroken(img)"
            :src="imageDisplayUrl(img)"
            alt=""
            class="thumb"
            loading="lazy"
            referrerpolicy="no-referrer"
            @error="markBroken(img)"
          />
          <div v-else class="thumb thumb-broken">
            <span>加载失败</span>
          </div>
          <!-- 状态角标：外链=待本地化（黄），已入库=已在本系统（绿） -->
          <span v-if="img.local" class="badge badge-ok">已入库</span>
          <span v-else-if="img.external" class="badge badge-warn">外链</span>
        </div>

        <div class="attach-src" :title="img.src">{{ img.src }}</div>

        <div class="attach-actions">
          <!-- 卡片窄，动作改纯图标 + tooltip：三枚图标一行放得下，不再顶出卡片边界 -->
          <el-tooltip v-if="img.external" content="本地化到本系统" placement="top">
            <el-button
              size="small"
              text
              :loading="retryingIndex === index"
              @click="retryOne(index)"
            >
              <el-icon><Refresh /></el-icon>
            </el-button>
          </el-tooltip>
          <el-tooltip content="上传替换" placement="top">
            <el-button size="small" text @click="pickReplace(img)">
              <el-icon><Upload /></el-icon>
            </el-button>
          </el-tooltip>
          <el-tooltip content="删除该图片引用" placement="top">
            <el-button size="small" text type="danger" @click="removeImage(img)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </el-tooltip>
        </div>
      </div>
      </div>

      <!-- 本地化按钮放在图片网格下方：头部只留标题与统计一行，按钮不再与文字抢宽度 -->
      <el-button
        v-if="images.length"
        class="btn-ai attach-localize"
        size="small"
        :icon="MagicStick"
        :loading="localizing"
        :disabled="!externalCount"
        @click="localizeAll"
      >
        {{ localizing ? '本地化中…' : '一键本地化外链图' }}
      </el-button>
    </template>

    <input ref="replaceInput" type="file" accept="image/*" style="display: none" @change="onReplaceFileChange" />
  </div>
</template>

<style scoped>
.attach-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--zj-line);
  border-radius: 8px;
  background: var(--zj-surface-sunken);
}

.attach-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

/* 标题区是可点按钮：去掉原生按钮外观，只保留一行内容 */
.attach-toggle {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
  flex: 1 1 auto;
  padding: 0;
  border: none;
  background: none;
  font: inherit;
  text-align: left;
  cursor: pointer;
  color: inherit;
}

.attach-localize {
  flex: none;
}

.attach-caret {
  align-self: center;
  font-size: 13px;
  color: var(--zj-ink-500);
}

@media (hover: hover) {
  .attach-toggle:hover .attach-title {
    color: var(--zj-primary);
  }
}

.attach-title {
  font-size: var(--zj-fs-sm);
  font-weight: 600;
  color: var(--zj-ink-800);
}

.attach-count {
  font-size: 12px;
  color: var(--zj-ink-500);
}

.ok-num {
  color: var(--zj-primary);
  font-weight: 600;
}

.warn-num {
  color: var(--zj-warning, #d48806);
  font-weight: 600;
}

.attach-empty {
  margin: 0;
  font-size: 12px;
  color: var(--zj-ink-500);
  line-height: 1.6;
}

/* 缩略图网格：一眼看全本文所有图。卡片收窄以提升密度——
 * 图多时自动排成更多列、每张更小，避免整个面板纵向被图片霸占（面板自身滚动，不动弹窗外框）。
 * align-content: start：行按内容高度排布，容器变高时空行不被拉伸（否则少图会被撑变形） */
.attach-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
  align-content: start;
  gap: 8px;
  max-height: 200px;
  overflow-y: auto;
  padding-right: 2px;
}

.attach-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 6px;
  min-width: 0;
  border: 1px solid var(--zj-line);
  border-radius: 8px;
  background: var(--zj-surface);
}

/* 外链卡：左侧黄色描边引导注意；坏图卡：红色描边最醒目 */
.attach-card.external {
  border-color: var(--zj-warning, #e6a23c);
  box-shadow: inset 3px 0 0 var(--zj-warning, #e6a23c);
}

.attach-card.broken {
  border-color: var(--zj-danger, #d03050);
  box-shadow: inset 3px 0 0 var(--zj-danger, #d03050);
}

.thumb-wrap {
  position: relative;
  height: 52px;
  border-radius: 6px;
  overflow: hidden;
  background: var(--zj-surface-sunken);
}

.thumb {
  width: 100%;
  height: 100%;
  object-fit: contain;
  display: block;
}

.thumb-broken {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--zj-danger, #d03050);
  border: 1px dashed var(--zj-danger, #d03050);
}

.badge {
  position: absolute;
  top: 4px;
  left: 4px;
  padding: 0 5px;
  font-size: 10px;
  line-height: 16px;
  border-radius: 4px;
  color: #fff;
}

.badge-ok {
  background: var(--zj-primary, #2f6f68);
}

.badge-warn {
  background: var(--zj-warning, #e6a23c);
}

.attach-src {
  font-size: 11px;
  color: var(--zj-ink-500);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 动作行：纯图标按钮横向铺满、均分宽度；图标尺寸固定，不会顶出卡片 */
.attach-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.attach-actions :deep(.el-button) {
  flex: 1 1 0;
  min-width: 0;
  margin: 0;
  padding: 2px 0;
  font-size: 12px;
}

.attach-actions :deep(.el-button + .el-button) {
  margin-left: 0; /* Element 按钮相邻自带 margin-left，会挤出卡片，这里清零 */
}

/* 紧凑模式：向导固定高度画布内使用，缩略图与网格都收小，纵向占位更少 */
.attach-panel--compact {
  padding: 8px 10px;
}

/* 紧凑模式常在窄护栏里：本地化按钮铺满一行，图标+长文案放得下 */
.attach-panel--compact .attach-localize {
  width: 100%;
}

.attach-panel--compact .attach-grid {
  grid-template-columns: repeat(auto-fill, minmax(88px, 1fr));
  gap: 8px;
  max-height: 132px;
}

.attach-panel--compact .thumb-wrap {
  height: 44px;
}
</style>
