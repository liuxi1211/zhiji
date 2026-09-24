<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createStaffAccount, listStaffAccounts, resetStaffPassword, updateStaffStatus } from '@/api/account'
import { useConstantsStore } from '@/stores/constants'
import type { StaffAccountView, StaffRole } from '@/api/types'

const constants = useConstantsStore()
const accounts = ref<StaffAccountView[]>([])
const loading = ref(false)
const createVisible = ref(false)
const createForm = reactive({ username: '', password: '', role: 'editor' as StaffRole })

async function load() {
  loading.value = true
  try {
    accounts.value = await listStaffAccounts()
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '加载失败')
  } finally {
    loading.value = false
  }
}
onMounted(() => {
  void constants.ensure()
  void load()
})

async function submitCreate() {
  try {
    await createStaffAccount({ ...createForm })
    ElMessage.success('已创建，初始口令请线下交付')
    createVisible.value = false
    createForm.username = ''
    createForm.password = ''
    await load()
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '创建失败')
  }
}

async function toggleStatus(row: StaffAccountView) {
  const target = row.status === 'active' ? 'disabled' : 'active'
  try {
    await updateStaffStatus(row.id, target)
    ElMessage.success('已更新')
    await load()
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '操作失败')
  }
}

async function resetPassword(row: StaffAccountView) {
  try {
    const { value } = await ElMessageBox.prompt(`为 ${row.username} 设置新口令（6~64 位）`, '重置口令', {
      inputPattern: /^.{6,64}$/,
      inputErrorMessage: '口令长度 6~64'
    })
    await resetStaffPassword(row.id, value)
    ElMessage.success('口令已重置')
  } catch {
    // 用户取消输入
  }
}
</script>

<template>
  <section class="accounts">
    <header class="zj-page-head">
      <h1>账号管理</h1>
      <p>后台人员账号的创建、角色与状态维护；客户账号不在后台管理</p>
    </header>

    <el-card shadow="never">
      <template #header>
        <div class="toolbar">
          <span>共 <b class="zj-num">{{ accounts.length }}</b> 个后台账号</span>
          <el-button type="primary" size="small" @click="createVisible = true">新增账号</el-button>
        </div>
      </template>
      <el-table v-loading="loading" :data="accounts" size="small">
      <el-table-column prop="username" label="用户名" min-width="140" />
      <el-table-column label="角色" width="120">
        <template #default="{ row }">{{ constants.name('staff_role', row.role) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'active' ? 'success' : 'danger'" size="small">
            {{ constants.name('account_status', row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="170" />
      <el-table-column prop="lastLoginAt" label="最近登录" width="170" />
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button size="small" text type="primary" @click="resetPassword(row)">重置口令</el-button>
          <el-button
            size="small"
            text
            :type="row.status === 'active' ? 'danger' : 'success'"
            @click="toggleStatus(row)"
          >
            {{ row.status === 'active' ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="createVisible" title="新增后台账号" width="420px">
      <el-form label-position="top">
        <el-form-item label="用户名">
          <el-input v-model="createForm.username" placeholder="3~32 个字符" />
        </el-form-item>
        <el-form-item label="初始口令（请线下交付给同事）">
          <el-input v-model="createForm.password" type="password" show-password placeholder="6~64 个字符" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="createForm.role">
            <el-option label="内容维护者（editor）" value="editor" />
            <el-option label="管理员（admin）" value="admin" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.toolbar b {
  color: var(--zj-primary);
  font-weight: 700;
}
</style>
