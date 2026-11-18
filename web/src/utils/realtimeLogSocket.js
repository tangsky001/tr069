import { getToken } from '@/utils/auth'

/**
 * 实时日志 WebSocket：连接 /ws/realtime-log?token=xxx。
 * 与概览 socket 不共用逻辑：不自动重连，打开页面即建会话，close() 即结束会话。
 *
 * 用法：
 *   const socket = createRealtimeLogSocket(msg => {...}, online => {...})
 *   组件 beforeDestroy 时调用 socket.close()
 */
export function createRealtimeLogSocket(onMessage, onStatusChange) {
  let socket = null

  function connect() {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    const token = encodeURIComponent(getToken() || '')
    socket = new WebSocket(`${proto}://${location.host}/ws/realtime-log?token=${token}`)

    socket.onopen = () => onStatusChange && onStatusChange(true)
    socket.onmessage = event => {
      try {
        onMessage(JSON.parse(event.data))
      } catch (e) {
        // 非 JSON 消息忽略
      }
    }
    socket.onclose = () => {
      socket = null
      onStatusChange && onStatusChange(false)
    }
    socket.onerror = () => {
      try { socket.close() } catch (e) { /* 已关闭 */ }
    }
  }

  connect()

  return {
    close() {
      if (socket) {
        try { socket.close() } catch (e) { /* 已关闭 */ }
      }
    }
  }
}
