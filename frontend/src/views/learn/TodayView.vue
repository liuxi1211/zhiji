<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getCacheStat, listDemoKp } from '@/api/demo'
import type { CacheStat, DemoKp } from '@/api/demo'
import type { ApiError } from '@/api/types'

/**
 * 今日卡（I-07）。
 * 骨架阶段先放一条链路探针：前端 → Vite proxy → Spring Boot → SQLite，
 * 通了就说明前后端骨架接上了，业务实现随后往里填。
 */
const loading = ref(false)
const error = ref<ApiError | null>(null)
const kps = ref<DemoKp[]>([])
const stat = ref<CacheStat | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    kps.value = await listDemoKp()
    stat.value = await getCacheStat()
  } catch (e) {
    error.value = e as ApiError
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="today">
    <el-card shadow="never" class="probe">
      <template #header>
        <div class="probe-header">
          <span>链路自检（前端 → Spring Boot → SQLite）</span>
          <el-button size="small" :loading="loading" @click="load">重试</el-button>
        </div>
      </template>

      <el-alert
        v-if="error"
        type="error"
        :closable="false"
        show-icon
        :title="`${error.code}: ${error.message}`"
      >
        <div class="hint">请确认后端已在 8080 端口启动。</div>
      </el-alert>

      <template v-else>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="接口可达">是</el-descriptions-item>
          <el-descriptions-item label="考点条数">{{ kps.length }}</el-descriptions-item>
          <el-descriptions-item label="落库次数">
            {{ stat?.dbHitCount ?? '-' }}
            <span class="hint">（连读多次仍为 1，说明 Caffeine 缓存命中）</span>
          </el-descriptions-item>
        </el-descriptions>

        <el-table v-if="kps.length" :data="kps" size="small" class="kp-table">
          <el-table-column prop="bizId" label="bizId" />
          <el-table-column prop="title" label="标题" />
          <el-table-column prop="subType" label="题型" width="120" />
          <el-table-column prop="difficulty" label="难度" width="80" />
        </el-table>
        <el-empty v-else description="暂无数据，可先调 POST /demo/kp 写入" />
      </template>
    </el-card>

    <el-card shadow="never" class="placeholder">
      <p class="muted">今日卡（3 新学 + 2 复测）待后端 I-07 就绪后接入。</p>
    </el-card>
  </div>
</template>

<style scoped>
.today {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.probe-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.kp-table {
  margin-top: 12px;
}

.hint {
  margin-left: 6px;
  font-size: 12px;
  color: var(--zj-text-weak);
}

.muted {
  margin: 0;
  color: var(--zj-text-weak);
  font-size: 13px;
}
</style>
