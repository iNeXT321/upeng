import { defineStore } from 'pinia'
import { getHealth, getStats, getTools } from '@/api'

/** 平台可观测性 store：健康检查 / 指标 / 工具清单，供监控页与侧栏状态灯使用 */
export const usePlatformStore = defineStore('platform', {
  state: () => ({
    health: null,
    stats: null,
    tools: [],
    toolsLoading: false,
    lastError: null,
    timer: null
  }),

  getters: {
    /** 后端是否可用（模型路由可用即视为 UP） */
    backendUp: (s) => s.health?.status === 'UP' || s.health?.status === 'DEGRADED',
    activeRequests: (s) => s.stats?.runtime?.activeRequests ?? 0
  },

  actions: {
    async refreshHealth() {
      try {
        this.health = await getHealth()
        this.lastError = null
      } catch (e) {
        this.lastError = e.message
        this.health = { status: 'DOWN' }
      }
    },
    async refreshStats() {
      try { this.stats = await getStats() } catch { /* 忽略轮询错误 */ }
    },
    async refreshTools() {
      this.toolsLoading = true
      try { this.tools = await getTools() } catch { this.tools = [] }
      this.toolsLoading = false
    },
    /** 启动轮询（监控页用 5s，侧栏状态灯共用同一份数据） */
    startPolling(intervalMs = 5000) {
      this.refreshHealth()
      this.refreshStats()
      if (this.timer) clearInterval(this.timer)
      this.timer = setInterval(() => {
        this.refreshHealth()
        this.refreshStats()
      }, intervalMs)
    },
    stopPolling() {
      if (this.timer) clearInterval(this.timer)
      this.timer = null
    }
  }
})
