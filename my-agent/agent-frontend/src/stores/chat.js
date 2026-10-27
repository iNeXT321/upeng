import { defineStore } from 'pinia'
import { streamChat } from '@/api/sse'
import { stopAgent, listSessions, getSessionDetail, clearSession } from '@/api'
import { splitHtmlSegments } from '@/utils/htmlPreview'

function uuid() {
  return crypto.randomUUID ? crypto.randomUUID() : Date.now() + '-' + Math.random().toString(16).slice(2)
}

/**
 * 对话中心 store：多轮会话、流式渲染、停止、历史回放。
 *
 * 与后端的契约：
 *  - 同一会话内 sessionId 不变，后端据此回放历史（多轮连续对话的核心）；
 *  - requestId 每次请求新生成，是"停止生成"的定位键；
 *  - assistant 消息在流式过程中逐段拼接：tool_thought（思考）、tool_call（工具调用意图）、
 *    tool_result（工具结果回填）、task（规划步骤）进 steps（过程折叠展示），result 追加为正文。
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
        this.messages = (detail.messages || []).map((t) => {
          const content = t.content || ''
          return {
            id: uuid(),
            role: t.role === 'user' ? 'user' : 'assistant',
            content,
            // 回放时同样切段：历史里含网页的回答也能预览，而不是一坨源码
            segments: t.role === 'user' ? [] : splitHtmlSegments(content),
            steps: [],
            status: 'done'
          }
        }).filter((m) => m.content)
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
        // 中断的内容若已构成完整网页（含闭合标签）也允许预览，否则自然降级为 markdown
        last.segments = last.error ? [] : splitHtmlSegments(last.content)
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
          // 限速逐字搬运：基础 1 字/24ms（约 42 字/秒，接近真人打字观感）；
          // 积压越多稍加速但封顶 6 字/tick，保证大段文本也能匀速敲出而非瞬间闪现
          const take = Math.min(6, Math.max(1, Math.ceil(aiMsg.pending.length / 15)))
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
      aiMsg.content += aiMsg.pending
      aiMsg.pending = ''
      aiMsg.status = aiMsg.error ? 'error' : 'done'
      // 停止/出错时收尾还在 running 的工具步骤，避免步骤区永远显示"执行中"
      aiMsg.steps.forEach((s) => {
        if (s.kind === 'tool' && s.status === 'running') s.status = 'done'
      })
      // 流式结束后再切段：流式期间 iframe 会随每个 token 重建，网页预览只在完成后挂载
      aiMsg.segments = aiMsg.error ? [] : splitHtmlSegments(aiMsg.content)
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
        id: uuid(), role: 'assistant', content: '', pending: '', steps: [], segments: [],
        status: 'streaming', error: null, requestId: null, _finished: false,
        _streamed: false, // 是否已收到 agent_stream 增量正文（用于 final result 去重）
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
      // tool_call：模型决定调用某工具（尚未执行）。参数里有 SQL / 检索词等关键信息，
      // 前端先建一个 running 状态的步骤，等 tool_result 回填结果与耗时
      if (type === 'tool_call') {
        const call = event.toolCall || {}
        aiMsg.steps.push({
          kind: 'tool',
          toolName: call.toolName || '未知工具',
          toolParam: call.toolParam || null,
          toolResult: null,
          status: 'running',
          time: Date.now(),
          durationMs: null
        })
        return
      }
      // tool_result：工具执行完毕。优先回填到同名 running 步骤；
      // 断线重连补发时可能错过 tool_call，则直接新建一个已完成步骤
      if (type === 'tool_result') {
        const tr = event.toolResult || {}
        let step = [...aiMsg.steps].reverse().find(
          (s) => s.kind === 'tool' && s.toolName === tr.toolName && s.status === 'running')
        if (!step) {
          step = { kind: 'tool', toolName: tr.toolName || '未知工具', toolParam: null, time: Date.now() }
          aiMsg.steps.push(step)
        }
        step.toolResult = tr.toolResult ?? ''
        step.status = 'done'
        step.durationMs = step.time ? Date.now() - step.time : null
        return
      }
      // task：任务规划模式（Planner）下发的执行步骤
      if (type === 'task') {
        if (event.task) {
          aiMsg.steps.push({ kind: 'task', text: event.task, status: 'done', time: Date.now() })
        }
        return
      }
      // agent_stream：后端逐 token 增量推送的正文（打字机效果的数据源）
      if (type === 'agent_stream') {
        aiMsg._streamed = true
      }
      // result：正文优先取 result，降级取 taskSummary（仅当 result 为空时）。
      // 注意：先进 pending 缓冲区，由打字机逐字搬运到 content——直接写 content
      // 会导致文本整段出现（打字机拿不到积压，等于没有打字效果）。
      const result = event.result ?? ''
      const summary = event.resultMap?.taskSummary ?? ''
      const text = result || summary
      // 已通过 agent_stream 增量推送过正文时，最终 result 事件只当结束信号，
      // 不再重复拼接（否则整段答案会出现两遍）
      const duplicated = type === 'result' && aiMsg._streamed
      if (text && !duplicated) aiMsg.pending += text
      // 流已到达末尾但打字机还在敲：标记完成，由打字机敲完缓冲后收尾
      if (event.finish) aiMsg._finished = true
    },

    /**
     * 流读取结束：不立即收尾，标记 _finished 让打字机把缓冲区敲完再收尾
     * （否则最后一段文本会整段跳出，破坏打字体验）。
     */
    finish(aiMsg) {
      aiMsg._finished = true
      // 保险：打字机已不在跑（异常场景）则直接落盘收尾
      if (!this.typeTimer) this.finalize(aiMsg)
    }
  }
})
