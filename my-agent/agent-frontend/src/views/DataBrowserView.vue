<template>
  <div class="browser-page">
    <div class="browser-header">
      <h3>数据浏览</h3>
      <span class="header-hint">MySQL 数仓 / Elasticsearch / Milvus 只读体检窗口，看看底座里到底存了什么</span>
    </div>

    <el-tabs v-model="activeTab" class="browser-tabs">
      <!-- ==================== MySQL ==================== -->
      <el-tab-pane label="MySQL 数仓" name="mysql">
        <div class="pane-layout">
          <div class="side-list">
            <div class="side-title">
              表（{{ mysqlTables.length }}）
              <el-button link size="small" :icon="Refresh" @click="loadMysqlTables" />
            </div>
            <el-input v-model="mysqlFilter" placeholder="过滤表名" size="small" clearable class="side-filter" />
            <div class="side-items">
              <div v-for="t in filteredMysqlTables" :key="t.name" class="side-item"
                   :class="{ active: t.name === mysqlTable }" @click="selectMysqlTable(t.name)">
                <el-icon><Coin /></el-icon>
                <div class="item-main">
                  <div class="item-name">{{ t.name }}</div>
                  <div class="item-meta">{{ formatCount(t.rows) }} 行 · {{ t.sizeMb }} MB</div>
                </div>
              </div>
            </div>
          </div>
          <div class="main-area">
            <template v-if="mysqlTable">
              <el-table :data="mysqlRows" v-loading="mysqlLoading" size="small" border stripe
                        class="data-table" :key="mysqlTable + '-' + mysqlPage">
                <el-table-column v-for="col in mysqlColumns" :key="col" :prop="col" :label="col"
                                 min-width="140" show-overflow-tooltip />
              </el-table>
              <el-pagination v-model:current-page="mysqlPage" :page-size="mysqlSize"
                             :total="mysqlTotal" layout="total, prev, pager, next, sizes"
                             :page-sizes="[20, 50, 100]" class="pager"
                             @current-change="loadMysqlRows" @size-change="onMysqlSizeChange" />
            </template>
            <el-empty v-else description="从左侧选择一张表查看数据" />
          </div>
        </div>
      </el-tab-pane>

      <!-- ==================== Elasticsearch ==================== -->
      <el-tab-pane label="Elasticsearch" name="es">
        <div class="pane-layout">
          <div class="side-list">
            <div class="side-title">
              索引（{{ esIndices.length }}）
              <el-button link size="small" :icon="Refresh" @click="loadEsIndices" />
            </div>
            <div class="side-items">
              <div v-for="idx in esIndices" :key="idx.name" class="side-item"
                   :class="{ active: idx.name === esIndex }" @click="selectEsIndex(idx.name)">
                <el-icon><Search /></el-icon>
                <div class="item-main">
                  <div class="item-name">
                    {{ idx.name }}
                    <el-tag v-if="idx.isSchemaIndex" size="small" type="success" effect="plain">schema</el-tag>
                  </div>
                  <div class="item-meta">{{ formatCount(idx.docs) }} docs · {{ formatBytes(idx.sizeBytes) }}</div>
                </div>
              </div>
            </div>
          </div>
          <div class="main-area">
            <template v-if="esIndex">
              <div class="toolbar-row">
                <el-input v-model="esQuery" placeholder="检索文档（query_string，留空浏览全部）" clearable
                          @keyup.enter="esSearch" class="search-input">
                  <template #append><el-button :icon="Search" @click="esSearch" /></template>
                </el-input>
              </div>
              <el-table :data="esDocs" v-loading="esLoading" size="small" border stripe
                        class="data-table" :key="esIndex + '-' + esPage + '-' + esQueryKey">
                <el-table-column label="_id" width="180" show-overflow-tooltip>
                  <template #default="{ row }">{{ row._id }}</template>
                </el-table-column>
                <el-table-column label="_score" width="90">
                  <template #default="{ row }">{{ row._score?.toFixed(2) ?? '-' }}</template>
                </el-table-column>
                <el-table-column label="_source" min-width="400">
                  <template #default="{ row }">
                    <pre class="source-pre">{{ JSON.stringify(row._source, null, 1) }}</pre>
                  </template>
                </el-table-column>
              </el-table>
              <el-pagination v-model:current-page="esPage" :page-size="esSize" :total="esTotal"
                             layout="total, prev, pager, next" class="pager"
                             @current-change="loadEsDocs" />
            </template>
            <el-empty v-else description="从左侧选择一个索引查看文档" />
          </div>
        </div>
      </el-tab-pane>

      <!-- ==================== Milvus ==================== -->
      <el-tab-pane label="Milvus 向量库" name="milvus">
        <div class="pane-layout">
          <div class="side-list">
            <div class="side-title">
              集合（{{ milvusCollections.length }}）
              <el-button link size="small" :icon="Refresh" @click="loadMilvusCollections" />
            </div>
            <div class="side-items">
              <div v-for="c in milvusCollections" :key="c.name" class="side-item"
                   :class="{ active: c.name === milvusCollection }" @click="selectMilvusCollection(c.name)">
                <el-icon><Cpu /></el-icon>
                <div class="item-main">
                  <div class="item-name">{{ c.name }}</div>
                  <div class="item-meta">{{ formatCount(c.entities) }} vectors</div>
                </div>
              </div>
            </div>
          </div>
          <div class="main-area">
            <template v-if="milvusCollection">
              <div class="schema-chips">
                <el-tag v-for="f in milvusFields" :key="f.name" size="small"
                        :type="f.primaryKey ? 'warning' : 'info'" effect="plain">
                  {{ f.name }} · {{ f.type }}{{ f.primaryKey ? ' (PK)' : '' }}
                </el-tag>
              </div>
              <el-table :data="milvusRows" v-loading="milvusLoading" size="small" border stripe
                        class="data-table" :key="milvusCollection">
                <el-table-column v-for="(col, i) in milvusColumns" :key="col" :label="col" min-width="160">
                  <template #default="{ row }">
                    <span class="cell-text">{{ formatCell(row[i]) }}</span>
                  </template>
                </el-table-column>
              </el-table>
            </template>
            <el-empty v-else description="从左侧选择一个集合查看向量实体" />
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Coin, Search, Cpu } from '@element-plus/icons-vue'
import {
  listMysqlTables, getMysqlRows, listEsIndices, getEsDocs,
  listMilvusCollections, getMilvusRows
} from '@/api'

const activeTab = ref('mysql')

/* ---------------- MySQL ---------------- */
const mysqlTables = ref([])
const mysqlFilter = ref('')
const mysqlTable = ref('')
const mysqlColumns = ref([])
const mysqlRows = ref([])
const mysqlPage = ref(1)
const mysqlSize = ref(20)
const mysqlTotal = ref(0)
const mysqlLoading = ref(false)

const filteredMysqlTables = computed(() =>
  mysqlTables.value.filter(t => !mysqlFilter.value || t.name.includes(mysqlFilter.value)))

async function loadMysqlTables() {
  try {
    const res = await listMysqlTables()
    mysqlTables.value = res.tables || []
  } catch (e) { ElMessage.error(e.message) }
}

function selectMysqlTable(name) {
  mysqlTable.value = name
  mysqlPage.value = 1
  loadMysqlRows()
}

async function loadMysqlRows() {
  mysqlLoading.value = true
  try {
    const res = await getMysqlRows(mysqlTable.value, mysqlPage.value, mysqlSize.value)
    mysqlColumns.value = res.columns || []
    // 后端返回行数组（与列顺序对齐），转成 el-table 需要的对象数组
    mysqlRows.value = (res.rows || []).map(r => {
      const obj = {}
      mysqlColumns.value.forEach((c, i) => { obj[c] = r[i] })
      return obj
    })
    mysqlTotal.value = res.total || 0
  } catch (e) { ElMessage.error(e.message) }
  mysqlLoading.value = false
}

function onMysqlSizeChange(size) {
  mysqlSize.value = size
  mysqlPage.value = 1
  loadMysqlRows()
}

/* ---------------- Elasticsearch ---------------- */
const esIndices = ref([])
const esIndex = ref('')
const esQuery = ref('')
const esQueryKey = ref('')
const esDocs = ref([])
const esPage = ref(1)
const esSize = ref(20)
const esTotal = ref(0)
const esLoading = ref(false)

async function loadEsIndices() {
  try {
    const res = await listEsIndices()
    esIndices.value = res.indices || []
  } catch (e) { ElMessage.error(e.message) }
}

function selectEsIndex(name) {
  esIndex.value = name
  esQuery.value = ''
  esQueryKey.value = ''
  esPage.value = 1
  loadEsDocs()
}

function esSearch() {
  esQueryKey.value = esQuery.value.trim()
  esPage.value = 1
  loadEsDocs()
}

async function loadEsDocs() {
  esLoading.value = true
  try {
    const res = await getEsDocs(esIndex.value, esPage.value, esSize.value, esQueryKey.value)
    esDocs.value = res.docs || []
    esTotal.value = res.total || 0
  } catch (e) { ElMessage.error(e.message) }
  esLoading.value = false
}

/* ---------------- Milvus ---------------- */
const milvusCollections = ref([])
const milvusCollection = ref('')
const milvusFields = ref([])
const milvusColumns = ref([])
const milvusRows = ref([])
const milvusLoading = ref(false)

async function loadMilvusCollections() {
  try {
    const res = await listMilvusCollections()
    milvusCollections.value = res.collections || []
  } catch (e) { ElMessage.error(e.message) }
}

function selectMilvusCollection(name) {
  milvusCollection.value = name
  loadMilvusRows()
}

async function loadMilvusRows() {
  milvusLoading.value = true
  try {
    const res = await getMilvusRows(milvusCollection.value, 50)
    milvusColumns.value = res.columns || []
    milvusFields.value = res.fields || []
    milvusRows.value = res.rows || []
  } catch (e) { ElMessage.error(e.message) }
  milvusLoading.value = false
}

/* ---------------- 工具 ---------------- */
function formatCount(n) {
  if (n == null || n < 0) return '-'
  return n.toLocaleString()
}

function formatBytes(bytes) {
  if (bytes == null) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

function formatCell(v) {
  if (v == null) return '-'
  if (Array.isArray(v)) return JSON.stringify(v).slice(0, 60)
  if (typeof v === 'object') return JSON.stringify(v).slice(0, 120)
  const s = String(v)
  return s.length > 80 ? s.slice(0, 80) + '…' : s
}

onMounted(() => {
  loadMysqlTables()
  loadEsIndices()
  loadMilvusCollections()
})
</script>

<style scoped>
.browser-page { display: flex; flex-direction: column; height: 100%; padding: 0 20px; }
.browser-header { display: flex; align-items: baseline; gap: 12px; padding: 16px 0 4px; }
.browser-header h3 { margin: 0; }
.header-hint { font-size: 12px; color: #909399; }
.browser-tabs { flex: 1; overflow: hidden; }
.browser-tabs :deep(.el-tabs__content) { height: calc(100% - 55px); }
.browser-tabs :deep(.el-tab-pane) { height: 100%; }

.pane-layout { display: flex; gap: 16px; height: 100%; }
.side-list {
  width: 260px; flex-shrink: 0; display: flex; flex-direction: column;
  background: #fff; border: 1px solid #e4e7ed; border-radius: 8px; padding: 10px;
}
.side-title { display: flex; align-items: center; justify-content: space-between; font-size: 13px; font-weight: 600; padding-bottom: 8px; }
.side-filter { margin-bottom: 8px; }
.side-items { flex: 1; overflow-y: auto; }
.side-item {
  display: flex; align-items: center; gap: 8px; padding: 8px 10px;
  border-radius: 6px; cursor: pointer; font-size: 13px;
}
.side-item:hover { background: #f5f7fa; }
.side-item.active { background: #ecf5ff; }
.side-item .el-icon { color: #909399; }
.side-item.active .el-icon { color: #409eff; }
.item-main { min-width: 0; }
.item-name { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.item-meta { font-size: 11px; color: #909399; }
.main-area { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 10px; overflow: hidden; }
.data-table { flex: 1; overflow: auto; }
.pager { justify-content: flex-end; }
.toolbar-row { display: flex; gap: 10px; }
.search-input { max-width: 480px; }
.source-pre {
  margin: 0; font-size: 11px; line-height: 1.5;
  white-space: pre-wrap; word-break: break-all;
  font-family: 'SF Mono', Menlo, Consolas, monospace;
}
.schema-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.cell-text { font-size: 12px; word-break: break-all; }
</style>
