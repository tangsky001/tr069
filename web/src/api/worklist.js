import request from '@/utils/request'

// 光猫适配档案与工单执行记录接口，后端前缀 /api/worklist
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

/** 配置总览：根目录、档案条目数、版本号 */
export function fetchConfigStatus() {
  return request({
    url: '/worklist/status',
    method: 'get'
  })
}

export function fetchProfiles() {
  return request({
    url: '/worklist/profiles',
    method: 'get'
  })
}

export function fetchProfile(key) {
  return request({
    url: '/worklist/profiles/' + encodeURIComponent(key),
    method: 'get'
  })
}

/** 按设备标识匹配应使用的适配档案 */
export function matchProfile(params) {
  return request({
    url: '/worklist/profiles/match',
    method: 'get',
    params
  })
}

/** 触发一次全量热加载 */
export function reloadConfig() {
  return request({
    url: '/worklist/reload',
    method: 'post'
  })
}

/** 释放示例配置到配置根目录 */
export function unpackSamples() {
  return request({
    url: '/worklist/samples',
    method: 'post'
  })
}
