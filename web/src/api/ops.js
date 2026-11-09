import request from '@/utils/request'
import { getToken } from '@/utils/auth'

// 运维环境总览：系统版本、服务器、数据库、Redis、Git，数据全部由后端采集
export function fetchEnvironment() {
  return request({ url: '/ops/environment', method: 'get' })
}

// 远程运维配置：Token、权限项、可见范围、三个二维码内容
export function fetchRemoteOps() {
  return request({ url: '/ops/remote', method: 'get' })
}

// 保存远程运维设置
export function saveRemoteOps(data) {
  return request({ url: '/ops/remote', method: 'post', data })
}

// 重新生成 Token：旧 Token 立刻失效
export function regenerateRemoteToken() {
  return request({ url: '/ops/remote/token/regenerate', method: 'post' })
}

// 二维码图片地址：后端生成 PNG，前端直接用 img 的 src
// img 标签带不了 X-Token 请求头，只能走后端支持的 token 查询参数
export function qrcodeUrl(content, size = 240) {
  const base = process.env.VUE_APP_BASE_API || '/api'
  return base + '/ops/remote/qrcode?content=' + encodeURIComponent(content) +
    '&size=' + size + '&token=' + encodeURIComponent(getToken() || '')
}
