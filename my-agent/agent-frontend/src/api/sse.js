/**
 * SSE 流式对话客户端（fetch + ReadableStream）。
 *
 * 为什么不用 axios：axios 基于 XHR，拿不到增量流；SSE 必须用 fetch 的流式 reader
 * 手动按行切分。后端协议（AgentController + SSEPrinter）：
 *   - 每条事件形如  data:{"messageType":"...","finish":bool,...}
 *   - messageType: result(最终/增量结果) | tool_thought(思考/工具过程) | error(错误)
 *   - 心跳是 SSE 注释行（":"开头），直接忽略
 *   - finish=true 表示本次请求结束
 */

export const AGENT_TYPES = [
  { value: 4, label: '智能路由', desc: '由 Router 自动选择最合适的执行方式' },
  { value: 1, label: 'ReAct 模式', desc: '思考→行动→观察 循环，适合工具型任务' },
  { value: 2, label: '任务规划', desc: '先拆解计划再逐步执行，适合复杂任务' }
]

/**
 * 发起流式对话。
 * @param {object} payload { requestId, sessionId, query, agentType, outputStyle }
 * @param {(event: object) => void} onEvent 每解析出一条事件回调
 * @param {AbortSignal} signal 取消信号
 * @param {string} [lastEventId] 断线重连时传客户端最后收到的事件 seq，服务端只补缺失事件
 */
export async function streamChat(payload, onEvent, signal, lastEventId) {
  const headers = { 'Content-Type': 'application/json' }
  const token = localStorage.getItem('agent_token')
  if (token) headers.Authorization = `Bearer ${token}`
  if (lastEventId) headers['Last-Event-ID'] = String(lastEventId)

  const resp = await fetch('/api/ai/autoAgent', {
    method: 'POST',
    headers,
    body: JSON.stringify({ ...payload, isStream: true }),
    signal
  })
  if (resp.status === 401) {
    localStorage.removeItem('agent_token')
    localStorage.removeItem('agent_user')
    throw new Error('登录已过期，请重新登录')
  }
  if (resp.status === 429) {
    throw new Error('请求过于频繁，请稍后再试')
  }
  if (!resp.ok || !resp.body) {
    let message = `HTTP ${resp.status}`
    try {
      const body = await resp.json()
      message = body.message || message
    } catch { /* 非 JSON 错误体则用状态码 */ }
    throw new Error(message)
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    // 按行切分；最后一行可能不完整，留到下一轮
    const lines = buffer.split('\n')
    buffer = lines.pop() || ''
    for (const line of lines) {
      const trimmed = line.trim()
      if (!trimmed || trimmed.startsWith(':')) continue // 空行 / 心跳注释
      if (!trimmed.startsWith('data:')) continue
      const json = trimmed.slice(5).trim()
      if (!json) continue
      try {
        onEvent(JSON.parse(json))
      } catch (e) {
        console.warn('[SSE] 事件解析失败，已跳过:', json.slice(0, 200))
      }
    }
  }
}
