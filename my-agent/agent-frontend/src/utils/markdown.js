import MarkdownIt from 'markdown-it'

const md = new MarkdownIt({
  html: false,      // 禁止内嵌 HTML，防 XSS
  linkify: true,
  breaks: true
})

// 链接统一新窗口打开
const defaultLinkOpen = md.renderer.rules.link_open ||
  ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options))
md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  tokens[idx].attrSet('target', '_blank')
  tokens[idx].attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen(tokens, idx, options, env, self)
}

/** 渲染 Markdown（已关闭 html 标签透传，输出可安全 v-html） */
export function renderMd(text) {
  if (!text) return ''
  return md.render(String(text))
}
