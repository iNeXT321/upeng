import { defineStore } from 'pinia'
import { streamChat } from '@/api/sse'
import { stopAgent, listSessions, getSessionDetail, clearSession } from '@/api'

function uuid() {
  return crypto.randomUUID ? crypto.randomUUID() : Date.now() + '-' + Math.random().toString(16).slice(2)
}

/**
 * 对话中心 store：多轮会话、流式渲染、停止、历史回放。
 *
 * 与后端的契约：
 *  - 同一会话内 sessionId 不变，后端据此回放历史（多轮连续对话的核心）；
 *  - requestId 每次请求新生成，是"停止生成"的定位键；
 *  - assistant 消息在流式过程中逐段拼接：tool_thought 进 steps（过程折叠展示），
 *    result 追加为正文。
 */
export const useChatStore = defineStore('chat', {
  state: () => ({
    sessionId: uuid(),
    messages: [],            // { id, role: 'user'|'assistant', content, steps: [], status, error }
    streaming: false,        // 是否有请求正在进行
    currentRequestId: null,
    agentType: 4,            // 默认智能路由
    abortController: null,
    sessionCache: []         // 服务端会话列表
  }),

  getters: {
    canSend: (s) => !s.streaming
  },

  actions: {
    newSession() {
      this.abort()
      this.sessionId = uuid()
      this.messages = []
    },

    /** 切换到服务端已有会话：拉取服务端回放的完整历史 */
    async switchSession(sessionId) {
      this.abort()
      this.sessionId = sessionId
      try {
        const detail = await getSessionDetail(sessionId)
        this.messages = (detail.messages || []).map((t) => ({
          id: uuid(),
          role: t.role === 'user' ? 'user' : 'assistant',
          content: t.content || '',
          steps: [],
          status: 'done'
        })).filter((m) => m.content)
      } catch (e) {
        this.messages = []
        throw e
      }
    },

    async refreshSessions() {
      try {
        this.sessionCache = await listSessions(undefined, 50) || []
      } catch {
        this.sessionCache = []
      }
    },

    async removeSession(sessionId) {
      await clearSession(sessionId)
      if (sessionId === this.sessionId) this.newSession()
      await this.refreshSessions()
    },

    /** 停止生成：前端断流 + 通知后端取消令牌 */
    async stop() {
      const requestId = this.currentRequestId
      this.abort()
      if (requestId) {
        try { await stopAgent(requestId) } catch { /* 后端可能已结束，忽略 */ }
      }
    },

    abort() {
      if (this.abortController) {
        this.abortController.abort()
        this.abortController = null
      }
      this.currentRequestId = null
      this.stopTypewriter()
      const last = this.messages[this.messages.length - 1]
      if (last && last.role === 'assistant' && last.status !== 'done') {
        // 停止时把缓冲区内容一次性落出来，不丢字
        last.content += last.pending || ''
        last.pending = ''
        last.status = last.error ? 'error' : 'done'
        if (!last.content && !last.error) last.content = '（已停止）'
        this.streaming = false
      }
    },

    /**
     * 打字机：SSE 到达的文本先进 pending 缓冲区，
     * 定时器按自适应速度逐字搬运到 content（每 tick 消费 1/25 的积压，
     * 积压越多敲得越快，保证不落后于模型吐字速度）。
     */
    startTypewriter(aiMsg) {
      if (this.typeTimer) return
      this.typeTimer = setInterval(() => {
        if (aiMsg.pending) {
          const take = Math.max(1, Math.ceil(aiMsg.pending.length / 25))
          aiMsg.content += aiMsg.pending.slice(0, take)
          aiMsg.pending = aiMsg.pending.slice(take)
        } else if (aiMsg._finished) {
          this.stopTypewriter()
          this.finalize(aiMsg)
        }
      }, 24)
    },

    stopTypewriter() {
      if (this.typeTimer) {
        clearInterval(this.typeTimer)
        this.typeTimer = null
      }
    },

    /** 缓冲区清空后真正的收尾（由打字机调用） */
    finalize(aiMsg) {
      aiMsg.status = aiMsg.error ? 'error' : 'done'
      if (!aiMsg.content && !aiMsg.steps.length && !aiMsg.error) {
        aiMsg.content = '（本次没有返回内容）'
      }
      this.streaming = false
      this.refreshSessions()
    },

    /**
     * 发送一条消息（含可选的文件内容注入）。
     * @param {string} text 用户输入
     * @param {Array<{name:string, content:string}>} files 已在客户端读取为文本的附件
     */
    async send(text, files = []) {
      const query = text.trim()
      if (!query || this.streaming) return

      let fullQuery = query
      if (files.length) {
        const appendix = files.map(f => `\n\n--- 附件：${f.name} ---\n${f.content}`).join('')
        fullQuery = query + appendix
      }

      const userMsg = {
        id: uuid(), role: 'user', content: query,
        attachments: files.map(f => f.name), steps: [], status: 'done'
      }
      const aiMsg = {
        id: uuid(), role: 'assistant', content: '', pending: '', steps: [],
        status: 'streaming', error: null, requestId: null, _finished: false,
        lastSeq: 0 // 已应用的最新事件序号：重连时传给服务端做增量续传，本地做去重
      }
      this.messages.push(userMsg, aiMsg)
      this.streaming = true
      this.startTypewriter(aiMsg)

      const requestId = uuid()
      this.currentRequestId = requestId
      aiMsg.requestId = requestId
      const controller = new AbortController()
      this.abortController = controller

      const MAX_RETRY = 3
      let attempt = 0
      try {
        while (true) {
          try {
            await streamChat(
              { requestId, sessionId: this.sessionId, query: fullQuery, agentType: this.agentType },
              (event) => this.handleEvent(aiMsg, event),
              controller.signal,
              aiMsg.lastSeq > 0 ? String(aiMsg.lastSeq) : undefined
            )
            break
          } catch (e) {
            if (e.name === 'AbortError') throw e
            // 断线自动重连：指数退避；401/429 或服务端明确报错不重试
            const retriable = !aiMsg.error
              && e.message !== '登录已过期，请重新登录'
              && !String(e.message).includes('频繁')
              && attempt < MAX_RETRY - 1
            if (!retriable) throw e
            attempt++
            const backoffMs = 1000 * Math.pow(2, attempt - 1)
            aiMsg.steps.push({
              kind: 'system', text: `连接中断，${backoffMs / 1000}s 后自动重连（第 ${attempt} 次，续传点 seq=${aiMsg.lastSeq}）`,
              time: Date.now()
            })
            await new Promise((r) => setTimeout(r, backoffMs))
            if (controller.signal.aborted) {
              const abortErr = new Error('aborted')
              abortErr.name = 'AbortError'
              throw abortErr
            }
          }
        }
        this.finish(aiMsg)
      } catch (e) {
        if (e.name === 'AbortError') {
          this.finish(aiMsg)
        } else {
          aiMsg.error = e.message || '连接中断'
          aiMsg._finished = true
          this.abortController = null
          this.currentRequestId = null
          // 让打字机把已到达的内容敲完再收尾，状态由 finalize 置为 error
        }
      }
    },

    handleEvent(aiMsg, event) {
      const type = event.messageType
      // 事件序号去重：重连补发的事件可能与本地已应用的重复
      if (typeof event.seq === 'number') {
        if (event.seq <= aiMsg.lastSeq) return
        aiMsg.lastSeq = event.seq
      }
      if (event.requestId) aiMsg.requestId = event.requestId

      if (type === 'error') {
        aiMsg.error = event.result || event.resultMap?.taskSummary || '服务异常'
        return
      }
      if (type === 'tool_thought') {
        const thought = event.toolThought || event.resultMap?.taskSummary || ''
        if (thought) aiMsg.steps.push({ kind: 'thought', text: thought, time: Date.now() })
        return
      }
      // result：正文优先取 result，降级取 taskSummary（仅当 result 为空时）
      const result = event.result ?? ''
      const summary = event.resultMap?.taskSummary ?? ''
      const text = result || summary
      if (text) aiMsg.content += text
    },

    finish(aiMsg) {
      aiMsg.status = 'done'
      if (!aiMsg.content && !aiMsg.steps.length && !aiMsg.error) {
        aiMsg.content = '（本次没有返回内容）'
      }
      this.streaming = false
      this.currentRequestId = null
      this.abortController = null
      this.refreshSessions()
    }
  }
})
