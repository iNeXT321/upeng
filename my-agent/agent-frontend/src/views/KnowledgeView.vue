<template>
  <div class="page">
    <div class="page-header">
      <h3>知识库</h3>
      <div class="header-meta">
        <el-tag effect="plain">{{ info.backend }}</el-tag>
        <el-tag effect="plain" type="success">{{ info.chunks }} 个片段</el-tag>
      </div>
    </div>

    <el-row :gutter="20">
      <!-- 左：文档管理 -->
      <el-col :span="14">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>已索引文档</span>
              <el-upload :auto-upload="false" :show-file-list="false"
                         :on-change="onFilePicked" accept=".txt,.md,.csv,.json">
                <el-button type="primary" size="small" :icon="Upload" :loading="uploading">
                  上传文档入库
                </el-button>
              </el-upload>
            </div>
          </template>
          <el-table :data="docs" v-loading="loading" stripe size="small">
            <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
            <el-table-column prop="chars" label="字符数" width="90" />
            <el-table-column label="标签" min-width="120">
              <template #default="{ row }">
                <el-tag v-for="t in row.tags" :key="t" size="small" style="margin-right:4px">{{ t }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!docs.length && !loading" description="暂无文档，右上角上传或手动录入" :image-size="60" />
          <el-form label-position="top" style="margin-top:16px">
            <el-row :gutter="10">
              <el-col :span="10">
                <el-input v-model="manual.title" placeholder="标题" />
              </el-col>
              <el-col :span="14">
                <el-input v-model="manual.tags" placeholder="标签（逗号分隔，可选）" />
              </el-col>
            </el-row>
            <el-input v-model="manual.content" type="textarea" :rows="3"
                      placeholder="粘贴文本内容直接入库…" style="margin-top:10px" />
            <el-button type="primary" style="margin-top:10px" :disabled="!manual.content?.trim()"
                       :loading="uploading" @click="ingestManual">录入知识库</el-button>
          </el-form>
        </el-card>
      </el-col>

      <!-- 右：召回测试 -->
      <el-col :span="10">
        <el-card shadow="never">
          <template #header><span>召回测试</span></template>
          <div class="search-row">
            <el-input v-model="query" placeholder="输入测试问题，看召回哪些片段"
                      @keydown.enter="doSearch" clearable />
            <el-button type="primary" :loading="searching" @click="doSearch">检索</el-button>
          </div>
          <div v-for="(hit, i) in hits" :key="i" class="hit-card">
            <div class="hit-head">
              <span class="hit-title">{{ hit.title || '（无标题）' }}</span>
              <el-tag size="small" type="warning">score {{ hit.score.toFixed(3) }}</el-tag>
            </div>
            <div class="hit-content">{{ hit.content }}</div>
          </div>
          <el-empty v-if="!hits.length && searched && !searching" description="没有召回结果" :image-size="60" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Upload } from '@element-plus/icons-vue'
import { getKnowledge, ingestKnowledge, searchKnowledge } from '@/api'

const info = ref({ backend: '-', chunks: 0 })
const docs = ref([])
const loading = ref(false)
const uploading = ref(false)
const manual = ref({ title: '', content: '', tags: '' })
const query = ref('')
const hits = ref([])
const searching = ref(false)
const searched = ref(false)

onMounted(refresh)

async function refresh() {
  loading.value = true
  try {
    const data = await getKnowledge()
    info.value = data
    docs.value = data.docs || []
  } catch (e) {
    ElMessage.error(e.message)
  }
  loading.value = false
}

/**
 * 文件入库：读取文本 → POST /ai/knowledge。
 * 知识库内容会通过 search_knowledge 工具进入 Agent 的可检索范围。
 */
async function ingest(title, content, source, tags) {
  uploading.value = true
  try {
    const res = await ingestKnowledge({ title, content, source, tags })
    ElMessage.success(`入库成功，当前 ${res.chunks} 个片段`)
    await refresh()
  } catch (e) {
    ElMessage.error(`入库失败: ${e.message}`)
  }
  uploading.value = false
}

async function onFilePicked(uploadFile) {
  const raw = uploadFile.raw
  if (!raw) return
  try {
    const content = await raw.text()
    await ingest(raw.name, content, `upload:${raw.name}`, ['upload'])
  } catch {
    ElMessage.warning(`${raw.name} 不是可读文本文件`)
  }
}

function ingestManual() {
  const tags = manual.value.tags.split(/[,，]/).map(t => t.trim()).filter(Boolean)
  ingest(manual.value.title || '未命名文档', manual.value.content, 'manual', tags)
  manual.value = { title: '', content: '', tags: '' }
}

async function doSearch() {
  if (!query.value.trim()) return
  searching.value = true
  searched.value = true
  try {
    const res = await searchKnowledge(query.value, 4)
    hits.value = res.hits || []
  } catch (e) {
    ElMessage.error(e.message)
  }
  searching.value = false
}
</script>

<style scoped>
.page { padding: 20px; height: 100%; overflow-y: auto; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-header h3 { margin: 0; }
.header-meta { display: flex; gap: 8px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.search-row { display: flex; gap: 8px; margin-bottom: 14px; }
.hit-card {
  border: 1px solid #e4e7ed; border-radius: 8px; padding: 10px 12px; margin-bottom: 10px;
}
.hit-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.hit-title { font-weight: 600; font-size: 13px; }
.hit-content {
  font-size: 12px; color: #606266; max-height: 120px; overflow-y: auto;
  white-space: pre-wrap; word-break: break-word;
}
</style>
