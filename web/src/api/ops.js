import request from '@/utils/request'

// 运维环境总览：系统版本、服务器、数据库、Redis、Git，数据全部由后端采集
export function fetchEnvironment() {
  return request({ url: '/ops/environment', method: 'get' })
}
