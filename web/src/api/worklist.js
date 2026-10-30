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

/**
 * 提交下发工单。
 * worklistDesc 是幂等键，重复提交只受理一次；不传则由后端统一发号。
 */
export function createWorklist(data) {
  return request({
    url: '/worklist/instances',
    method: 'post',
    data
  })
}

/**
 * 业务类型清单（代码 + 展示名），由后端维护。
 * 后端加一个业务类型，页面的下拉就自动多一项，不用再改前端。
 */
export function fetchTemplateMeta() {
  return request({
    url: '/config-templates/meta',
    method: 'get'
  })
}

/**
 * 工单状态全集，由后端维护（状态流转定义在 Worklist 实体上）。
 */
export function fetchWorklistStatuses() {
  return request({
    url: '/worklist/statuses',
    method: 'get'
  })
}
