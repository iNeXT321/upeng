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
const OPEN_FENCE_RE = /```(?:html|htm)\s*\r?\n/gi
const DOC_RE = /(?:<!DOCTYPE\s+html[^>]*>\s*)?<html[^>]*>[\s\S]*?<\/html>/gi
// 完整文档的内容特征：真正的页面必然含这些结构标签之一，
// 用于排除"HTML 以 <html> 开头以 </html> 结束"这类讲标签的普通文本
const PAGE_MARK_RE = /<(head|body|div|style|meta|title|script|table|p|h[1-6]|section|header|footer)[\s>]/
// 截断兜底：流式被停止或输出被截断时，文档可能没有 </html>，但 <!DOCTYPE html
// 开头几乎只可能是"想给网页"，误判率极低
const TRUNCATED_DOC_RE = /<!DOCTYPE\s+html/i
const OPEN_HTML_RE = /<html[\s>]/i

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

  // 第一步：按 ```html 围栏切分（只吃闭合的围栏）
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
  let remaining = text.slice(cursor)

  // 第二步：未闭合的 ```html 围栏（流式被停止 / 输出截断）——围栏之后全部当网页
  OPEN_FENCE_RE.lastIndex = 0
  let open = null
  let openMatch
  while ((openMatch = OPEN_FENCE_RE.exec(remaining)) !== null) {
    open = openMatch
  }
  if (open !== null) {
    const before = remaining.slice(0, open.index)
    if (before.trim()) segments.push({ type: 'md', content: before })
    const html = remaining.slice(open.index + open[0].length).trim()
    if (html) segments.push({ type: 'html', content: html, title: pickTitle(html) })
    remaining = ''
  }

  // 第三步：剩余文本里找裸的完整 HTML 文档（可能前后还有说明文字）。
  // 懒惰匹配找到的候选要过 PAGE_MARK_RE 校验，防止"以 <html> 开头"这类文本被误切；
  // 校验失败的候选跳过并继续向后找，避免死循环
  let searchFrom = 0
  let docMatch
  while (searchFrom < remaining.length) {
    DOC_RE.lastIndex = searchFrom
    docMatch = DOC_RE.exec(remaining)
    if (docMatch === null) break
    if (!PAGE_MARK_RE.test(docMatch[0])) {
      searchFrom = docMatch.index + 1
      continue
    }
    const before = remaining.slice(0, docMatch.index)
    if (before.trim()) segments.push({ type: 'md', content: before })
    const html = docMatch[0].trim()
    segments.push({ type: 'html', content: html, title: pickTitle(html) })
    remaining = remaining.slice(docMatch.index + docMatch[0].length)
    searchFrom = 0
  }

  // 第四步：截断的裸文档（有 <!DOCTYPE html 但没有 </html>）——从 DOCTYPE 到结尾当网页
  const trunc = remaining.match(TRUNCATED_DOC_RE)
  if (trunc !== null) {
    const before = remaining.slice(0, trunc.index)
    if (before.trim()) segments.push({ type: 'md', content: before })
    const html = remaining.slice(trunc.index).trim()
    if (html) segments.push({ type: 'html', content: html, title: pickTitle(html) })
    remaining = ''
  } else {
    // <html 开头但没有 </html>：要求后面确实像页面（有 head/body/div/style）才当网页，
    // 防止把"讲 <html 标签"的普通文本误判成网页
    const openHtml = remaining.search(OPEN_HTML_RE)
    if (openHtml >= 0) {
      const tail = remaining.slice(openHtml)
      if (/<(head|body|div|style)[\s>]/i.test(tail)) {
        const before = remaining.slice(0, openHtml)
        if (before.trim()) segments.push({ type: 'md', content: before })
        segments.push({ type: 'html', content: tail.trim(), title: pickTitle(tail) })
        remaining = ''
      }
    }
  }

  if (remaining.trim()) segments.push({ type: 'md', content: remaining })

  return segments.filter((s) => s.content && s.content.trim())
}

/** 内容里是否含可预览的网页（流式结束后用于决定是否切段渲染） */
export function hasHtml(text) {
  if (!text) return false
  FENCE_RE.lastIndex = 0
  if (FENCE_RE.test(text)) return true
  OPEN_FENCE_RE.lastIndex = 0
  if (OPEN_FENCE_RE.test(text)) return true
  if (TRUNCATED_DOC_RE.test(text)) return true
  DOC_RE.lastIndex = 0
  const doc = DOC_RE.exec(text)
  if (doc !== null && PAGE_MARK_RE.test(doc[0])) return true
  return OPEN_HTML_RE.test(text) && /<(head|body|div|style)[\s>]/i.test(text)
}
