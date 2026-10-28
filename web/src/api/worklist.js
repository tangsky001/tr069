import request from '@/utils/request'

// 工单执行记录接口，后端前缀 /api/worklist
// request 的 baseURL 已带 /api，这里从 /worklist 写起

export function fetchInstances(params) {
  return request({
    url: '/worklist/instances',
    method: 'get',
    params
  })
}

/** 单张工单的执行进度，工单提交后靠它轮询 */
export function fetchInstance(worklistId) {
  return request({
    url: '/worklist/instances/' + worklistId,
    method: 'get'
  })
}

export function fetchDeviceInstances(sn) {
  return request({
    url: '/worklist/instances/device/' + encodeURIComponent(sn),
    method: 'get'
  })
}

export function fetchSteps(worklistId) {
  return request({
    url: '/worklist/instances/' + worklistId + '/steps',
    method: 'get'
  })
}

/** 某类业务需要填写的变量清单，下发表单据此渲染输入框 */
export function fetchVariables(category) {
  return request({
    url: '/worklist/variables',
    method: 'get',
    params: { category }
  })
}

/** 提交下发工单。worklistDesc 是幂等键，重复提交只受理一次 */
export function createWorklist(data) {
  return request({
    url: '/worklist/instances',
    method: 'post',
    data
  })
}

/**
 * 生成幂等流水号。重复提交网络重试时带上同一个号，避免给设备重复开业务。
 */
export function newWorklistDesc(category) {
  const now = new Date()
  const pad = n => String(n).padStart(2, '0')
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}` +
    `${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`
  return `ADMIN-${category || 'biz'}-${stamp}-${Math.random().toString(36).slice(2, 8)}`
}
