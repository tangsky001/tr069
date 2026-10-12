import request from '@/utils/request'

// 工单与光猫适配接口，后端前缀 /api/worklist
// request 的 baseURL 已带 /api，这里从 /worklist 写起

/** 下发一张工单。data: { sn, templateKey, params, username } */
export function executeWorklist(data) {
  return request({
    url: '/worklist/execute',
    method: 'post',
    data
  })
}

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

/** 配置总览：根目录、条目数、版本号 */
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

export function fetchTemplates(params) {
  return request({
    url: '/worklist/templates',
    method: 'get',
    params
  })
}

/** 模板类型枚举，用于分类按钮与筛选 */
export function fetchTemplateCategories() {
  return request({
    url: '/worklist/templates/categories',
    method: 'get'
  })
}

/** 新建模板时可选的层级取值，从现有模板聚合而来 */
export function fetchTemplateOptions() {
  return request({
    url: '/worklist/templates/options',
    method: 'get'
  })
}

/** 单个模板详情（含参数定义与执行步骤），供编辑页回填 */
export function fetchTemplateDetail(key) {
  return request({
    url: '/worklist/templates/detail',
    method: 'get',
    params: { key }
  })
}

/** 新建配置模板。data: { template, format } */
export function createTemplate(data) {
  return request({
    url: '/worklist/templates',
    method: 'post',
    data
  })
}

/** 保存配置模板。data: { key, template, format } */
export function updateTemplate(data) {
  return request({
    url: '/worklist/templates/update',
    method: 'post',
    data
  })
}

/** 复制另存。data: { sourceKey, template, format } */
export function copyTemplate(data) {
  return request({
    url: '/worklist/templates/copy',
    method: 'post',
    data
  })
}

export function deleteTemplate(key) {
  return request({
    url: '/worklist/templates/delete',
    method: 'post',
    data: { key }
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
