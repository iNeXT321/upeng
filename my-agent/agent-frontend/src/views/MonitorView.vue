<template>
  <div class="page">
    <div class="page-header">
      <h3>运行监控</h3>
      <el-tag :type="healthType" effect="dark">{{ platform.health?.status || '未知' }}</el-tag>
    </div>

    <!-- 健康卡片 -->
    <el-row :gutter="16" v-if="platform.health">
      <el-col :span="8">
        <el-card shadow="never">
          <template #header><span>模型网关</span></template>
          <div class="kv" v-for="(v, k) in modelSummary" :key="k">
            <span class="k">{{ k }}</span>
            <el-tag size="small" :type="v.available ? 'success' : 'danger'">{{ v.resolved || v.route }}</el-tag>
          </div>
          <div class="kv"><span class="k">已发现模型</span>
            <span class="v">{{ (platform.health.llm?.discoveredModels || []).join(', ') }}</span></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <template #header><span>持久化 / 知识库</span></template>
          <div class="kv"><span class="k">存储后端</span><span class="v">{{ platform.health.persistence?.backend }}</span></div>
          <div class="kv"><span class="k">持久化</span>
            <el-tag size="small" :type="platform.health.persistence?.persistent ? 'success' : 'info'">
              {{ platform.health.persistence?.persistent ? '是' : '内存' }}</el-tag></div>
          <div class="kv"><span class="k">历史轮数上限</span><span class="v">{{ platform.health.persistence?.historyTurns }}</span></div>
          <div class="kv"><span class="k">知识库</span>
            <span class="v">{{ platform.health.knowledge?.backend }}（{{ platform.health.knowledge?.chunks }} 片段）</span></div>
          <div class="kv"><span class="k">护栏规则</span><span class="v">{{ (platform.health.guard?.rules || []).join(' → ') }}</span></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <template #header><span>运行时</span></template>
          <div class="kv"><span class="k">并发</span>
            <span class="v">{{ platform.stats?.runtime?.activeRequests ?? 0 }} / {{ platform.stats?.runtime?.maxConcurrentRequests ?? '-' }}</span></div>
          <div class="kv"><span class="k">请求超时</span><span class="v">{{ ms(platform.stats?.runtime?.requestTimeoutMs) }}</span></div>
          <div class="kv"><span class="k">可取消请求</span><span class="v">{{ platform.stats?.runtime?.cancellableRequests ?? 0 }}</span></div>
          <div class="kv"><span class="k">工具治理</span>
            <span class="v">单次 {{ ms(platform.health.toolPolicy?.callTimeoutMs) }} / 截断 {{ platform.health.toolPolicy?.maxResultChars }} 字符</span></div>
          <div class="kv"><span class="k">处理器</span><span class="v">{{ (platform.health.handlers || []).join(', ') }}</span></div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 指标 -->
    <el-row :gutter="16" style="margin-top:16px" v-if="metrics">
      <el-col :span="8">
        <el-card shadow="never">
          <template #header><span>请求指标</span></template>
          <div class="kv"><span class="k">总请求</span><span class="v num">{{ metrics.requests.total }}</span></div>
          <div class="kv"><span class="k">成功 / 失败</span>
            <span class="v num">{{ metrics.requests.success }} / {{ metrics.requests.failed }}</span></div>
          <div class="kv"><span class="k">被拦截 / 被取消</span>
            <span class="v num">{{ metrics.requests.blocked }} / {{ metrics.requests.cancelled }}</span></div>
          <div class="kv"><span class="k">平均耗时</span><span class="v num">{{ ms(metrics.requests.avgCostMs) }}</span></div>
          <div class="kv"><span class="k">最大耗时</span><span class="v num">{{ ms(metrics.requests.maxCostMs) }}</span></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <template #header><span>模型调用</span></template>
          <div class="kv"><span class="k">调用次数</span><span class="v num">{{ metrics.llm.calls }}</span></div>
          <div class="kv"><span class="k">失败次数</span><span class="v num">{{ metrics.llm.errors }}</span></div>
          <div class="kv"><span class="k">平均延迟</span><span class="v num">{{ ms(metrics.llm.avgLatencyMs) }}</span></div>
          <div class="kv"><span class="k">Token（入/出）</span>
            <span class="v num">{{ metrics.llm.promptTokens }} / {{ metrics.llm.completionTokens }}</span></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <template #header><span>工具 / 护栏</span></template>
          <div class="kv"><span class="k">工具调用</span><span class="v num">{{ metrics.tools.calls }}</span></div>
          <div class="kv"><span class="k">工具失败 / 截断</span>
            <span class="v num">{{ metrics.tools.errors }} / {{ metrics.tools.truncated }}</span></div>
          <div class="kv"><span class="k">平均工具延迟</span><span class="v num">{{ ms(metrics.tools.avgLatencyMs) }}</span></div>
          <div class="kv"><span class="k">护栏拦截 / 改写</span>
            <span class="v num">{{ metrics.guard.blocked }} / {{ metrics.guard.modified ?? 0 }}</span></div>
          <div class="kv"><span class="k">SSE 错误</span><span class="v num">{{ metrics.guard.sseErrors }}</span></div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 工具清单 -->
    <el-card shadow="never" style="margin-top:16px">
      <template #header>
        <div class="card-header">
          <span>可用工具（{{ platform.tools.count ?? platform.tools.length ?? 0 }}）</span>
          <el-button size="small" :icon="Refresh" :loading="platform.toolsLoading" @click="platform.refreshTools()" />
        </div>
      </template>
      <el-table :data="toolList" v-loading="platform.toolsLoading" stripe size="small">
        <el-table-column prop="name" label="名称" width="220">
          <template #default="{ row }"><span class="mono">{{ row.name }}</span></template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="300" show-overflow-tooltip />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { usePlatformStore } from '@/stores/platform'

const platform = usePlatformStore()

onMounted(() => {
  if (!platform.health) platform.startPolling(5000)
  platform.refreshTools()
})

const healthType = computed(() =>
  ({ UP: 'success', DEGRADED: 'warning', DOWN: 'danger' }[platform.health?.status] || 'info'))

const modelSummary = computed(() => platform.health?.llm?.roles || {})
const metrics = computed(() => platform.stats?.metrics)
const toolList = computed(() => platform.tools?.tools || platform.tools || [])

function ms(v) {
  if (v == null) return '-'
  return v >= 1000 ? (v / 1000).toFixed(1) + 's' : Math.round(v) + 'ms'
}
</script>

<style scoped>
.page { padding: 20px; height: 100%; overflow-y: auto; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-header h3 { margin: 0; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.kv { display: flex; justify-content: space-between; align-items: center; padding: 5px 0; font-size: 13px; }
.kv .k { color: #909399; }
.kv .v { color: #303133; text-align: right; max-width: 65%; word-break: break-all; }
.kv .num { font-family: Menlo, Consolas, monospace; font-weight: 600; }
.mono { font-family: Menlo, Consolas, monospace; }
</style>
