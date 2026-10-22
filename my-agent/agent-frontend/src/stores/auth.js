import { defineStore } from 'pinia'
import { login as apiLogin, register as apiRegister } from '@/api'

const TOKEN_KEY = 'agent_token'
const USER_KEY = 'agent_user'

function safeParse(str) {
  try { return JSON.parse(str) } catch { return null }
}

/**
 * 认证状态：token 存 localStorage（刷新不丢）。
 * 面试考点：token 放 localStorage 有 XSS 风险、放 httpOnly cookie 有 CSRF 风险——
 * 本项目后端无 cookie 会话 + 前端渲染层关闭了 v-html 原始 HTML 透传（markdown-it html:false），
 * 选择 localStorage + 严格输出转义的组合。
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: safeParse(localStorage.getItem(USER_KEY)) || null
  }),

  getters: {
    isAuthenticated: (s) => !!s.token
  },

  actions: {
    async login(username, password) {
      const data = await apiLogin(username, password)
      this.token = data.token
      this.user = { username: data.username, displayName: data.displayName }
      localStorage.setItem(TOKEN_KEY, data.token)
      localStorage.setItem(USER_KEY, JSON.stringify(this.user))
      return data
    },

    async register(username, password, displayName) {
      await apiRegister(username, password, displayName)
    },

    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
    },

    /** 401 时由 axios 拦截器调用：清状态跳登录 */
    sessionExpired() {
      this.logout()
      if (window.location.pathname !== '/login') {
        window.location.href = '/login?expired=1'
      }
    }
  }
})
