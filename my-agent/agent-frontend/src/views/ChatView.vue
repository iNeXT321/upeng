<template>
  <div class="chat-page">
    <!-- 顶部工具条 -->
    <div class="chat-toolbar">
      <el-select v-model="chat.agentType" style="width: 150px" :disabled="chat.streaming">
        <el-option
          v-for="t in agentTypes" :key="t.value"
          :label="t.label" :value="t.value">
          <span>{{ t.label }}</span>
          <span class="agent-desc">{{ t.desc }}</span>
        </el-option>
      </el-select>
      <el-tag effect="plain" type="info" size="small">
        会话 {{ chat.sessionId.slice(0, 8) }}…
      </el-tag>
      <div class="toolbar-spacer" />
      <el-button :icon="RefreshLeft" @click="newSession" :disabled="chat.streaming">
        新会话
      </el-button>
    </div>

    <!-- 消息区 -->
    <div class="chat-messages" ref="messagesEl">
      <el-empty v-if="!chat.messages.length" description="开始与 Agent 对话吧">
        <template #image><el-icon :size="60" color="#c0c4cc"><ChatLineRound /></el-icon></template>
      </el-empty>

      <div v-for="msg in chat.messages" :key="msg.id"
           class="msg-row" :class="msg.role">
        <div class="avatar">
          <el-icon v-if="msg.role === 'user'" :size="20"><User /></el-icon>
          <el-icon v-else :size="20" color="#409eff"><MagicStick /></el-icon>
        </div>
        <div class="bubble" :class="msg.role">
          <!-- 用户消息：显示附件标签 -->
          <div v-if="msg.attachments?.length" class="msg-attachments">
            <el-tag v-for="name in msg.attachments" :key="name" size="small" type="info">
              <el-icon><Document /></el-icon> {{ name }}
            </el-tag>
          </div>
          <!-- 过程步骤（思考/工具调用），完成后可折叠 -->
          <el-collapse v-if="msg.steps.length" class="msg-steps"
                       :model-value="msg.status === 'streaming' ? ['steps'] : []">
            <el-collapse-item :title="stepsTitle(msg)" name="steps">
              <template v-for="(s, i) in msg.steps" :key="i">
                <!-- 工具步骤：工具名 + 状态 + 耗时 + 可展开的参数/结果 -->
                <div v-if="s.kind === 'tool'" class="step-item tool-step">
                  <el-icon class="step-icon" :class="{ spin: stepRunning(msg, s, i) }">
                    <Loading v-if="stepRunning(msg, s, i)" />
                    <CircleCheck v-else />
                  </el-icon>
                  <div class="step-body">
                    <div class="tool-head">
                      <el-tag size="small" effect="plain" class="tool-tag">
                        <el-icon :size="12" style="vertical-align: -2px"><SetUp /></el-icon>
                        {{ s.toolName }}
                      </el-tag>
                      <span class="tool-status" :class="{ running: stepRunning(msg, s, i) }">
                        {{ toolStatusText(msg, s, i) }}
                      </span>
                    </div>
                    <details v-if="s.toolParam && Object.keys(s.toolParam).length" class="tool-detail">
                      <summary>调用参数</summary>
                      <pre class="tool-code">{{ formatParam(s.toolParam) }}</pre>
                    </details>
                    <details v-if="s.toolResult" class="tool-detail">
                      <summary>执行结果 · {{ s.toolResult.length }} 字符 · {{ preview(s.toolResult) }}</summary>
                      <div class="tool-md markdown" v-html="renderMd(s.toolResult)" />
                    </details>
                  </div>
                </div>
                <!-- 思考/规划步骤：markdown 渲染 -->
                <div v-else class="step-item">
                  <el-icon class="step-icon" :class="{ spin: stepRunning(msg, s, i) }">
                    <Loading v-if="stepRunning(msg, s, i)" />
                    <CircleCheck v-else />
                  </el-icon>
                  <div class="step-text markdown" v-html="renderMd(s.text)" />
                </div>
              </template>
            </el-collapse-item>
          </el-collapse>
          <!-- 正文（Markdown 渲染）+ 流式打字光标 -->
          <div v-if="msg.content" class="msg-content markdown" v-html="renderMd(msg.content)" />
          <span v-if="msg.status === 'streaming' && msg.content" class="type-cursor" />
          <div v-if="msg.status === 'streaming' && !msg.content && !msg.error" class="typing">
            <span class="dot" /><span class="dot" /><span class="dot" />
          </div>
          <el-alert v-if="msg.error" :title="msg.error" type="error" :closable="false" show-icon />
        </div>
      </div>
    </div>

    <!-- 输入区 -->
    <div class="chat-input">
      <div v-if="pendingFiles.length" class="pending-files">
        <el-tag v-for="(f, i) in pendingFiles" :key="i" closable @close="pendingFiles.splice(i, 1)">
          <el-icon><Document /></el-icon> {{ f.name }}（{{ f.content.length }} 字符）
        </el-tag>
      </div>
      <div class="input-row">
        <el-upload
          :auto-upload="false" :show-file-list="false"
          :on-change="onFilePicked" accept=".txt,.md,.csv,.json,.log,.xml,.yml,.yaml">
          <el-tooltip content="附加文本文件（内容将随消息发送）" placement="top">
            <el-button circle :icon="Paperclip" :disabled="chat.streaming" />
          </el-tooltip>
        </el-upload>
        <el-input
          v-model="input" type="textarea" :autosize="{ minRows: 1, maxRows: 6 }"
          placeholder="输入问题，Enter 发送，Shift+Enter 换行"
          @keydown.enter.exact.prevent="doSend" resize="none" />
        <el-button v-if="!chat.streaming" type="primary" :icon="Promotion"
                   @click="doSend" :disabled="!input.trim()" circle />
        <el-button v-else type="danger" :icon="VideoPause" @click="chat.stop()" circle>
        </el-button>
      </div>
      <div class="input-hint">
        {{ currentAgentLabel }} · 文本类附件会作为上下文随消息发送
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { RefreshLeft, Paperclip, Promotion, VideoPause } from '@element-plus/icons-vue'
import { useChatStore } from '@/stores/chat'
import { AGENT_TYPES } from '@/api/sse'
import { renderMd } from '@/utils/markdown'

const chat = useChatStore()
const agentTypes = AGENT_TYPES
const input = ref('')
const pendingFiles = ref([])
const messagesEl = ref(null)

const currentAgentLabel = computed(
  () => agentTypes.find(t => t.value === chat.agentType)?.label || '')

/** 步骤是否仍在执行：工具步骤看自身状态，思考步骤看"是否为流式中的最后一步" */
function stepRunning(msg, s, i) {
  if (s.kind === 'tool') return s.status === 'running'
  return msg.status === 'streaming' && i === msg.steps.length - 1
}

function toolStatusText(msg, s, i) {
  if (stepRunning(msg, s, i)) return '执行中…'
  if (!s.toolResult) return '完成（无结果返回）'
  return s.durationMs != null ? `完成 · ${fmtDur(s.durationMs)}` : '完成'
}

function fmtDur(ms) {
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`
}

function stepsTitle(msg) {
  const running = msg.steps.some((s, idx) => stepRunning(msg, s, idx))
  return running ? `执行过程（${msg.steps.length}）· 进行中…` : `执行过程（${msg.steps.length}）`
}

/** 参数对象格式化为可读 JSON（含 _raw 降级字段时还原原始串） */
function formatParam(param) {
  try {
    return JSON.stringify(param, null, 2)
  } catch {
    return String(param)
  }
}

/** 结果摘要：压成单行、截断到 80 字符，放在折叠条 summary 里当预览 */
function preview(text) {
  const oneLine = String(text).replace(/\s+/g, ' ').trim()
  return oneLine.length > 80 ? oneLine.slice(0, 80) + '…' : oneLine
}

watch(() => chat.messages.length, scrollToBottom)
watch(() => chat.messages[chat.messages.length - 1]?.content, scrollToBottom)

function scrollToBottom() {
  nextTick(() => {
    if (messagesEl.value) messagesEl.value.scrollTop = messagesEl.value.scrollHeight
  })
}

function newSession() {
  chat.newSession()
  ElMessage.success('已开启新会话')
}

async function doSend() {
  const text = input.value.trim()
  if (!text) return
  const files = pendingFiles.value.splice(0)
  input.value = ''
  await chat.send(text, files)
}

/**
 * 文件上传策略：AgentRequest 尚无二进制附件协议，
 * 文本类文件在客户端读取内容随消息注入（对模型等价于"看到文件"）。
 */
const MAX_FILE_CHARS = 20000
async function onFilePicked(uploadFile) {
  const raw = uploadFile.raw
  if (!raw) return
  try {
    const content = await raw.text()
    if (!content) {
      ElMessage.warning(`文件 ${raw.name} 内容为空`)
      return
    }
    pendingFiles.value.push({
      name: raw.name,
      content: content.length > MAX_FILE_CHARS
        ? content.slice(0, MAX_FILE_CHARS) + '\n…（内容过长已截断）'
        : content
    })
  } catch {
    ElMessage.warning(`${raw.name} 不是可读文本文件，暂不支持`)
  }
}
</script>

<style scoped>
.chat-page { display: flex; flex-direction: column; height: 100%; }
.chat-toolbar {
  display: flex; align-items: center; gap: 12px;
  padding: 12px 20px; background: #fff; border-bottom: 1px solid #e4e7ed;
}
.toolbar-spacer { flex: 1; }
.agent-desc { float: right; color: #909399; font-size: 12px; margin-left: 16px; }
.chat-messages { flex: 1; overflow-y: auto; padding: 24px 10%; }
.msg-row { display: flex; gap: 12px; margin-bottom: 20px; }
.msg-row.user { flex-direction: row-reverse; }
.avatar {
  width: 36px; height: 36px; border-radius: 8px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  background: #ecf5ff;
}
.msg-row.user .avatar { background: #f0f2f5; }
.bubble {
  max-width: 78%; padding: 12px 16px; border-radius: 10px;
  background: #fff; border: 1px solid #e4e7ed; font-size: 14px; line-height: 1.7;
}
.msg-row.user .bubble { background: #d9ecff; border-color: #c6e2ff; }
.msg-attachments { margin-bottom: 8px; display: flex; gap: 6px; flex-wrap: wrap; }
.msg-steps { margin-bottom: 8px; border: none; }
.msg-steps :deep(.el-collapse-item__header) {
  height: 30px; font-size: 12px; color: #909399; background: #f5f7fa;
  border-radius: 6px; padding: 0 10px;
}
.msg-steps :deep(.el-collapse-item__wrap) { border: none; background: transparent; }
.step-item {
  display: flex; gap: 6px; align-items: flex-start;
  font-size: 12px; color: #606266; padding: 3px 0; word-break: break-all;
}
.step-icon { margin-top: 2px; color: #409eff; flex-shrink: 0; }
.step-icon.spin { animation: step-spin 1s linear infinite; }
@keyframes step-spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
.step-text { flex: 1; min-width: 0; }
/* 工具步骤 */
.tool-step { padding: 4px 0; }
.step-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.tool-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.tool-tag { font-family: 'SF Mono', Menlo, Consolas, monospace; }
.tool-status { color: #909399; }
.tool-status.running { color: #409eff; }
.tool-detail { font-size: 12px; }
.tool-detail summary {
  cursor: pointer; color: #909399; user-select: none; line-height: 1.6;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.tool-detail summary:hover { color: #409eff; }
.tool-code {
  margin: 4px 0 0; padding: 8px 10px; max-height: 260px; overflow: auto;
  background: #f6f8fa; border: 1px solid #e4e7ed; border-radius: 6px;
  font-family: 'SF Mono', Menlo, Consolas, monospace; font-size: 12px;
  line-height: 1.6; white-space: pre-wrap; word-break: break-all; color: #303133;
}
/* 执行结果 markdown 容器：限制高度可滚动，字号贴近步骤文本 */
.tool-md {
  margin: 4px 0 0; padding: 8px 10px; max-height: 320px; overflow: auto;
  background: #f6f8fa; border: 1px solid #e4e7ed; border-radius: 6px;
  font-size: 12px; line-height: 1.6; word-break: break-all;
}
.tool-md :deep(table) { font-size: 12px; white-space: normal; }
.typing { display: inline-flex; gap: 4px; padding: 4px 0; }
.type-cursor {
  display: inline-block; width: 2px; height: 1em;
  margin-left: 2px; vertical-align: -0.15em; background: #409eff;
  animation: cursor-blink 0.9s steps(1) infinite;
}
@keyframes cursor-blink { 0%, 60% { opacity: 1; } 61%, 100% { opacity: 0; } }
.dot {
  width: 6px; height: 6px; border-radius: 50%; background: #409eff;
  animation: blink 1.2s infinite ease-in-out;
}
.dot:nth-child(2) { animation-delay: .2s; }
.dot:nth-child(3) { animation-delay: .4s; }
@keyframes blink { 0%, 80%, 100% { opacity: .25; } 40% { opacity: 1; } }
.chat-input {
  padding: 12px 10% 16px; background: #fff; border-top: 1px solid #e4e7ed;
}
.pending-files { margin-bottom: 8px; display: flex; gap: 6px; flex-wrap: wrap; }
.input-row { display: flex; gap: 10px; align-items: flex-end; }
.input-hint { margin-top: 6px; font-size: 12px; color: #c0c4cc; }
</style>
