<template>
  <div class="page">
    <div class="page-header">
      <h3>历史会话</h3>
      <el-button type="primary" :icon="Plus" @click="newChat">新建会话</el-button>
    </div>

    <el-table :data="chat.sessionCache" v-loading="loading" stripe>
      <el-table-column prop="sessionId" label="会话 ID" width="320">
        <template #default="{ row }">
          <span class="mono">{{ row.sessionId }}</span>
          <el-tag v-if="row.sessionId === chat.sessionId" size="small" type="success" style="margin-left:8px">当前</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="userId" label="用户" width="140" />
      <el-table-column prop="turns" label="消息轮数" width="110" />
      <el-table-column prop="updatedAt" label="最近活跃">
        <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="220" align="right">
        <template #default="{ row }">
          <el-button size="small" @click="viewDetail(row)">查看</el-button>
          <el-button size="small" type="primary" plain @click="resume(row)">继续对话</el-button>
          <el-popconfirm title="确定清空该会话？" @confirm="removeSession(row.sessionId)">
            <template #reference>
              <el-button size="small" type="danger" plain>删除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <!-- 会话回放抽屉：排查"模型到底看到了什么" -->
    <el-drawer v-model="drawerVisible" :title="`会话回放 · ${detailSessionId?.slice(0, 8)}…`" size="55%">
      <div v-loading="detailLoading">
        <el-alert v-if="detailRendered" type="info" :closable="false" style="margin-bottom:12px">
          <pre class="rendered-pre">{{ detailRendered }}</pre>
        </el-alert>
        <div v-for="(m, i) in detailMessages" :key="i" class="replay-msg" :class="m.role">
          <div class="replay-role">{{ m.role }}</div>
          <div class="replay-content">
            <div v-if="m.toolCallId" class="mono">toolCallId: {{ m.toolCallId }} · tool: {{ m.toolName }}</div>
            <div>{{ m.content }}</div>
          </div>
        </div>
        <el-empty v-if="!detailMessages.length && !detailLoading" description="该会话暂无消息" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { useChatStore } from '@/stores/chat'
import { getSessionDetail } from '@/api'

const chat = useChatStore()
const router = useRouter()
const loading = ref(false)
const drawerVisible = ref(false)
const detailLoading = ref(false)
const detailSessionId = ref(null)
const detailMessages = ref([])
const detailRendered = ref('')

onMounted(refresh)

async function refresh() {
  loading.value = true
  await chat.refreshSessions()
  loading.value = false
}

function formatTime(ts) {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN')
}

function newChat() {
  chat.newSession()
  router.push('/chat')
}

async function resume(row) {
  try {
    await chat.switchSession(row.sessionId)
    router.push('/chat')
  } catch (e) {
    ElMessage.error(`加载会话失败: ${e.message}`)
  }
}

async function viewDetail(row) {
  detailSessionId.value = row.sessionId
  drawerVisible.value = true
  detailLoading.value = true
  try {
    const detail = await getSessionDetail(row.sessionId)
    detailMessages.value = detail.messages || []
    detailRendered.value = detail.rendered || ''
  } catch (e) {
    ElMessage.error(e.message)
  }
  detailLoading.value = false
}

async function removeSession(sessionId) {
  try {
    await chat.removeSession(sessionId)
    ElMessage.success('会话已删除')
    refresh()
  } catch (e) {
    ElMessage.error(e.message)
  }
}
</script>

<style scoped>
.page { padding: 20px; height: 100%; overflow-y: auto; }
.page-header {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;
}
.page-header h3 { margin: 0; }
.mono { font-family: Menlo, Consolas, monospace; font-size: 12px; }
.rendered-pre { margin: 0; white-space: pre-wrap; font-size: 12px; }
.replay-msg { margin-bottom: 12px; }
.replay-role {
  font-size: 12px; color: #909399; text-transform: uppercase; margin-bottom: 4px;
}
.replay-content {
  padding: 10px 12px; border-radius: 8px; background: #f5f7fa;
  font-size: 13px; white-space: pre-wrap; word-break: break-word;
}
.replay-msg.user .replay-content { background: #ecf5ff; }
</style>
