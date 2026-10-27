<template>
  <div class="html-card">
    <!-- 工具栏：标题 + 视图切换 + 外部操作 -->
    <div class="html-head">
      <el-icon class="html-icon" :size="14"><Monitor /></el-icon>
      <span class="html-title" :title="title">{{ title }}</span>
      <span class="html-meta">{{ sizeText }}</span>
      <div class="html-actions">
        <el-radio-group v-model="mode" size="small">
          <el-radio-button value="preview">预览</el-radio-button>
          <el-radio-button value="source">源码</el-radio-button>
        </el-radio-group>
        <el-tooltip content="刷新预览" placement="top">
          <el-button size="small" :icon="RefreshRight" circle @click="reload" />
        </el-tooltip>
        <el-tooltip content="全屏查看" placement="top">
          <el-button size="small" :icon="FullScreen" circle @click="fullscreen = true" />
        </el-tooltip>
        <el-tooltip content="在新窗口打开" placement="top">
          <el-button size="small" :icon="TopRight" circle @click="openExternal" />
        </el-tooltip>
        <el-tooltip content="复制 HTML" placement="top">
          <el-button size="small" :icon="CopyDocument" circle @click="copyHtml" />
        </el-tooltip>
      </div>
    </div>

    <!-- 预览：沙箱 iframe，高度由内部页面上报 -->
    <div v-show="mode === 'preview'" class="html-body" :style="{ height: framedHeight + 'px' }">
      <iframe
        ref="frameEl"
        class="html-frame"
        :sandbox="SANDBOX"
        referrerpolicy="no-referrer"
        title="网页预览" />
    </div>

    <!-- 源码：等宽只读，双向可滚动 -->
    <pre v-if="mode === 'source'" class="html-source">{{ html }}</pre>
  </div>

  <!-- 全屏：复用同一份 sandbox 策略，只放大尺寸；v-if 控制挂载，关闭即销毁，
       避免隐藏状态下重复执行页面脚本 -->
  <el-dialog v-model="fullscreen" fullscreen :show-close="true" :title="title"
             class="html-dialog" destroy-on-close>
    <iframe v-if="fullscreen" class="html-frame html-frame-full" :sandbox="SANDBOX"
            referrerpolicy="no-referrer" :srcdoc="doc" />
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Monitor, RefreshRight, FullScreen, TopRight, CopyDocument
} from '@element-plus/icons-vue'

const props = defineProps({
  html: { type: String, required: true },
  title: { type: String, default: '网页预览' }
})

/**
 * 安全基线：不给 allow-same-origin。
 * 模型生成的页面可以跑脚本（图表、交互组件需要），但拿不到父页面的
 * localStorage / Cookie（token 存在 localStorage 里，这是关键隔离点）。
 */
const SANDBOX = 'allow-scripts allow-popups allow-forms allow-modals'

const mode = ref('preview')
const fullscreen = ref(false)
const framedHeight = ref(360)
const frameEl = ref(null)
const sizeText = computed(() => {
  const kb = props.html.length / 1024
  return kb >= 1 ? `${kb.toFixed(1)} KB` : `${props.html.length} 字`
})

/** 高度上报脚本：iframe 内测完 scrollHeight 通过 postMessage 回传 */
const REPORT_SCRIPT = `<script>
(function () {
  function report() {
    try {
      parent.postMessage({ __htmlPreviewHeight: Math.ceil(document.documentElement.scrollHeight) }, '*')
    } catch (e) {}
  }
  window.addEventListener('load', report)
  window.addEventListener('resize', report)
  setTimeout(report, 120)
  setTimeout(report, 600)
  if (window.ResizeObserver && document.body) {
    new ResizeObserver(report).observe(document.body)
  }
})();
<\/script>`

/** 兜底样式：模型没带 CSS 时保证可读；带了就不干预它的设计 */
const BASE_CSS = `<style>
html, body { margin: 0; padding: 12px; }
body {
  font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei', sans-serif;
  font-size: 14px; line-height: 1.65; color: #303133; background: #fff;
}
img { max-width: 100%; }
table { border-collapse: collapse; }
th, td { border: 1px solid #dcdfe6; padding: 6px 10px; }
</style>`

/**
 * 组装最终文档：
 *  - 注入 <base target="_blank">：页面内链接在新窗口打开，避免在受限 iframe 里被拦；
 *  - 注入高度上报脚本；
 *  - 只有页面自身没有 <style> 时才补兜底样式，尊重生成页面的设计。
 */
function buildDoc(html) {
  const hasStyle = /<style[\s>]/i.test(html)
  const inject = `${BASE_CSS && !hasStyle ? BASE_CSS : ''}<base target="_blank">${REPORT_SCRIPT}`

  if (/<head[^>]*>/i.test(html)) {
    return html.replace(/<head([^>]*)>/i, `<head$1><meta charset="utf-8">${inject}`)
  }
  if (/<html[^>]*>/i.test(html)) {
    return html.replace(/<html([^>]*)>/i, `<html$1><head><meta charset="utf-8">${inject}</head>`)
  }
  if (/<\/body>/i.test(html)) {
    return html.replace(/<\/body>/i, `${inject}</body>`)
  }
  if (/<\/html>/i.test(html)) {
    return html.replace(/<\/html>/i, `${inject}</html>`)
  }
  // 片段：包成完整文档
  return `<!DOCTYPE html><html><head><meta charset="utf-8">${inject}</head><body>${html}</body></html>`
}

const doc = computed(() => buildDoc(props.html))

/** 只认自家 iframe 的高度上报，其他来源的消息一律忽略 */
function onMessage(e) {
  const frame = frameEl.value
  if (!frame || e.source !== frame.contentWindow) return
  const h = e.data && e.data.__htmlPreviewHeight
  if (typeof h === 'number' && h > 0) {
    // 下限 200（空页面不塌陷），上限 720（再高就内部滚动，避免一条消息撑满整屏）
    framedHeight.value = Math.min(720, Math.max(200, h + 2))
  }
}

function mountFrame() {
  nextTick(() => {
    if (frameEl.value) frameEl.value.srcdoc = doc.value
  })
}

function reload() {
  framedHeight.value = 360
  mountFrame()
  ElMessage.success('已重新加载预览')
}

function openExternal() {
  const url = URL.createObjectURL(new Blob([doc.value], { type: 'text/html;charset=utf-8' }))
  window.open(url, '_blank', 'noopener')
  // 浏览器在新窗口加载完之前不能 revoke，给足缓冲
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
}

async function copyHtml() {
  try {
    await navigator.clipboard.writeText(props.html)
    ElMessage.success('HTML 已复制')
  } catch {
    ElMessage.warning('当前环境不支持剪贴板，请切换到源码视图手动复制')
  }
}

onMounted(() => {
  window.addEventListener('message', onMessage)
  mountFrame()
})
onBeforeUnmount(() => window.removeEventListener('message', onMessage))
// 内容变化（例如重新生成）时重新装载
watch(() => props.html, mountFrame)
</script>

<style scoped>
.html-card {
  margin: 10px 0; border: 1px solid #e4e7ed; border-radius: 8px;
  overflow: hidden; background: #fff;
}
.html-head {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 10px; background: #f5f7fa; border-bottom: 1px solid #e4e7ed;
}
.html-icon { color: #409eff; }
.html-title {
  font-size: 12px; font-weight: 600; color: #303133;
  max-width: 42%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.html-meta { font-size: 12px; color: #909399; }
.html-actions { margin-left: auto; display: flex; align-items: center; gap: 6px; }
.html-body { width: 100%; background: #fff; transition: height .2s ease; }
.html-frame { width: 100%; height: 100%; border: 0; display: block; background: #fff; }
.html-frame-full { height: calc(100vh - 90px); }
.html-source {
  margin: 0; padding: 10px 12px; max-height: 420px; overflow: auto;
  background: #f6f8fa; font-family: 'SF Mono', Menlo, Consolas, monospace;
  font-size: 12px; line-height: 1.6; white-space: pre; color: #303133;
}
</style>
