import request from '@/utils/request'

// RPC 指令任务接口，后端前缀 /api/rpc
// 下发已改为异步：提交只返回任务号，结果靠这里按任务号查询

export function fetchRpcResult(taskId) {
  return request({
    url: '/rpc/' + taskId,
    method: 'get'
  })
}

export function fetchDevicePending(sn) {
  return request({
    url: '/rpc/device/' + encodeURIComponent(sn) + '/pending',
    method: 'get'
  })
}
