import axios from 'axios'

/**
 * REST API 客户端。
 * 开发环境走 vite 代理（/api → http://localhost:8123）；
 * 生产环境由 nginx 把 /api 转发到后端，前端代码零改动。
 */
const http = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' }
})

/* 请求拦截：统一附加 JWT */
http.interceptors.request.use((config) => {
  const token = localStorage.getItem('agent_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (res) => res.data,
  (err) => {
    const status = err.response?.status
    const body = err.response?.data
    // 401：token 缺失/过期 —— 清会话跳登录（SSE 的 401 由 sse.js 同步处理）
    if (status === 401 && !err.config?.url?.includes('/auth/login')) {
      localStorage.removeItem('agent_token')
      localStorage.removeItem('agent_user')
      if (!window.location.pathname.startsWith('/login')) {
        window.location.href = '/login?expired=1'
      }
    }
    const msg = body?.message || err.message || '网络错误'
    return Promise.reject(new Error(msg))
  }
)

/* ---------------- 认证 ---------------- */
export const login = (username, password) => http.post('/auth/login', { username, password })
export const register = (username, password, displayName) =>
  http.post('/auth/register', { username, password, displayName })
export const getMe = () => http.get('/auth/me')

/* ---------------- 平台运维 ---------------- */
export const getHealth = () => http.get('/ai/health')
export const getStats = () => http.get('/ai/stats')
export const getTools = () => http.get('/ai/tools')
export const stopAgent = (requestId) => http.post(`/ai/stop/${requestId}`)

/* ---------------- 会话 ---------------- */
export const listSessions = (userId, limit = 50) =>
  http.get('/ai/sessions', { params: { userId, limit } })
export const getSessionDetail = (sessionId, limit = 100) =>
  http.get(`/ai/sessions/${sessionId}`, { params: { limit } })
export const clearSession = (sessionId) => http.delete(`/ai/sessions/${sessionId}`)

/* ---------------- 知识库 ---------------- */
export const getKnowledge = () => http.get('/ai/knowledge')
export const searchKnowledge = (q, topK = 4) =>
  http.get('/ai/knowledge/search', { params: { q, topK } })
export const ingestKnowledge = (payload) => http.post('/ai/knowledge', payload)

/* ---------------- 数据源浏览（MySQL / ES / Milvus 只读体检窗口） ---------------- */
export const listMysqlTables = () => http.get('/ai/datasource/mysql/tables')
export const getMysqlRows = (table, page = 1, size = 20) =>
  http.get(`/ai/datasource/mysql/tables/${table}/rows`, { params: { page, size } })
export const listEsIndices = () => http.get('/ai/datasource/es/indices')
export const getEsDocs = (index, page = 1, size = 20, q = '') =>
  http.get(`/ai/datasource/es/indices/${index}/docs`, { params: { page, size, q: q || undefined } })
export const listMilvusCollections = () => http.get('/ai/datasource/milvus/collections')
export const getMilvusRows = (name, limit = 50) =>
  http.get(`/ai/datasource/milvus/collections/${name}/rows`, { params: { limit } })
