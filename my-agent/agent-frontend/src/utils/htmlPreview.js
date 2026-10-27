/**
 * 助手正文中的 HTML 内容识别与切段。
 *
 * 为什么要切段而不是整篇丢给 iframe：
 *  模型常常「先给一段说明，再给一整篇网页」，如果整段丢进 iframe，
 *  说明文字会跟着网页样式走、排版失控。切成 md / html 段后，
 *  说明继续走 markdown 气泡样式，网页部分单独用卡片预览。
 *
 * 识别范围（保守策略，避免把普通文本误判成网页）：
 *   1. ```html / ```htm 围栏代码块 —— 一定当网页；
 *   2. 裸的完整 HTML 文档（含 <!DOCTYPE html> 或 <html ...>...</html>）—— 当网页；
 *   3. 其他围栏代码块（sql/json/...）与普通文本 —— 一律当 markdown。
 */

const FENCE_RE = /```(?:html|htm)\s*\r?\n([\s\S]*?)```/gi
const DOC_RE = /(?:<!DOCTYPE\s+html[^>]*>\s*)?<html[\s\S]*?<\/html>/i

/** 从 HTML 里提取 <title>，作为卡片标题；没有则给个通用标题 */
function pickTitle(html) {
  const m = html.match(/<title[^>]*>([\s\S]*?)<\/title>/i)
  const t = m ? m[1].trim() : ''
  return t || '网页预览'
}

/**
 * 把正文切成 [{ type: 'md' | 'html', content, title }]。
 * 顺序保持不变，md 段可能为空串（此时前端跳过渲染）。
 */
export function splitHtmlSegments(text) {
  if (!text) return []
  const segments = []

  // 第一步：按 ```html 围栏切分
  let cursor = 0
  FENCE_RE.lastIndex = 0
  let m
  while ((m = FENCE_RE.exec(text)) !== null) {
    const before = text.slice(cursor, m.index)
    if (before.trim()) segments.push({ type: 'md', content: before })
    const html = m[1].trim()
    if (html) segments.push({ type: 'html', content: html, title: pickTitle(html) })
    cursor = m.index + m[0].length
  }
  const rest = text.slice(cursor)

  // 第二步：剩余文本里找裸的完整 HTML 文档（可能前后还有说明文字）
  let remaining = rest
  let docMatch
  while ((docMatch = remaining.match(DOC_RE)) !== null) {
    const before = remaining.slice(0, docMatch.index)
    if (before.trim()) segments.push({ type: 'md', content: before })
    const html = docMatch[0].trim()
    segments.push({ type: 'html', content: html, title: pickTitle(html) })
    remaining = remaining.slice(docMatch.index + docMatch[0].length)
  }
  if (remaining.trim()) segments.push({ type: 'md', content: remaining })

  return segments.filter((s) => s.content && s.content.trim())
}

/** 内容里是否含可预览的网页（流式结束后用于决定是否切段渲染） */
export function hasHtml(text) {
  if (!text) return false
  FENCE_RE.lastIndex = 0
  return FENCE_RE.test(text) || DOC_RE.test(text)
}
