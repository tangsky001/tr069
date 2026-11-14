import { getToken } from '@/utils/auth'

/**
 * 首页概览 WebSocket：连接 /ws/dashboard?token=xxx，断线自动重连（间隔递增，封顶 30 秒）
 *
 * 用法：
 *   const close = createDashboardSocket(msg => {...}, online => {...})
 *   组件 beforeDestroy 时调用 close()
 */
export function createDashboardSocket(onMessage, onStatusChange) {
  let socket = null
  let manualClosed = false
  let retry = 0

  function connect() {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    const token = encodeURIComponent(getToken() || '')
    socket = new WebSocket(`${proto}://${location.host}/ws/dashboard?token=${token}`)

    socket.onopen = () => {
      retry = 0
      onStatusChange && onStatusChange(true)
    }
    socket.onmessage = event => {
      try {
        onMessage(JSON.parse(event.data))
      } catch (e) {
        // 非 JSON 消息忽略
      }
    }
    socket.onclose = () => {
      onStatusChange && onStatusChange(false)
      if (!manualClosed) {
        retry += 1
        setTimeout(connect, Math.min(retry * 3000, 30000))
      }
    }
    socket.onerror = () => {
      try { socket.close() } catch (e) { /* 已关闭 */ }
    }
  }

  connect()

  return function close() {
    manualClosed = true
    if (socket) {
      try { socket.close() } catch (e) { /* 已关闭 */ }
    }
  }
}
