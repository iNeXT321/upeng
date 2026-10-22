<template>
  <!-- 登录页：全屏，无侧栏 -->
  <router-view v-if="$route.meta.public" />

  <el-container v-else class="app-shell">
    <el-aside width="220px" class="app-aside">
      <div class="app-logo">
        <el-icon :size="26" color="#409eff"><MagicStick /></el-icon>
        <span>AI Agent 控制台</span>
      </div>
      <el-menu :default-active="$route.path" router class="app-menu">
        <el-menu-item index="/chat">
          <el-icon><ChatDotRound /></el-icon><span>对话</span>
        </el-menu-item>
        <el-menu-item index="/sessions">
          <el-icon><Clock /></el-icon><span>历史会话</span>
        </el-menu-item>
        <el-menu-item index="/knowledge">
          <el-icon><Collection /></el-icon><span>知识库</span>
        </el-menu-item>
        <el-menu-item index="/monitor">
          <el-icon><Odometer /></el-icon><span>运行监控</span>
        </el-menu-item>
      </el-menu>
      <div class="app-footer-status">
        <span class="status-dot" :class="platform.backendUp ? 'up' : 'down'"></span>
        <span>{{ platform.backendUp ? '后端在线' : '后端离线' }}</span>
        <el-tag v-if="platform.backendUp" size="small" type="info" effect="plain">
          运行中 {{ platform.activeRequests }}
        </el-tag>
      </div>
      <div class="app-user">
        <el-icon><UserFilled /></el-icon>
        <span class="username">{{ auth.user?.displayName || auth.user?.username || '未登录' }}</span>
        <el-tooltip content="退出登录" placement="top">
          <el-button link size="small" @click="logout"><el-icon><SwitchButton /></el-icon></el-button>
        </el-tooltip>
      </div>
    </el-aside>
    <el-main class="app-main">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
import { onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { usePlatformStore } from '@/stores/platform'
import { useAuthStore } from '@/stores/auth'
import { useChatStore } from '@/stores/chat'

const platform = usePlatformStore()
const auth = useAuthStore()
const chat = useChatStore()
const router = useRouter()

onMounted(() => platform.startPolling(5000))
onUnmounted(() => platform.stopPolling())

function logout() {
  chat.abort()
  auth.logout()
  router.replace('/login')
}
</script>

<style scoped>
.app-shell { height: 100%; }
.app-aside {
  display: flex; flex-direction: column;
  background: #fff; border-right: 1px solid #e4e7ed;
}
.app-logo {
  display: flex; align-items: center; gap: 10px;
  padding: 20px 16px; font-weight: 600; font-size: 16px;
}
.app-menu { flex: 1; border-right: none; }
.app-footer-status {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 16px; font-size: 12px; color: #909399;
  border-top: 1px solid #e4e7ed;
}
.app-user {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 16px 14px; font-size: 13px; color: #303133;
}
.app-user .username { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.status-dot { width: 8px; height: 8px; border-radius: 50%; }
.status-dot.up { background: #67c23a; box-shadow: 0 0 4px #67c23a; }
.status-dot.down { background: #f56c6c; }
.app-main { padding: 0; background: #f5f7fa; overflow: hidden; }
</style>
