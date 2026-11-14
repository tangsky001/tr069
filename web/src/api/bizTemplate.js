import request from '@/utils/request'

// 业务模板接口，后端前缀 /api/biz-template
// 业务模板 = 多张配置模板的有序集合，对设备整包下发

/** 业务模板列表，keyword 匹配名称/分组 */
export function fetchBizTemplates(keyword) {
  return request({
    url: '/biz-template/list',
    method: 'get',
    params: keyword ? { keyword } : {}
  })
}

export function fetchBizTemplate(id) {
  return request({
    url: '/biz-template/' + id,
    method: 'get'
  })
}

/** 新增或更新，data: { template, items } */
export function saveBizTemplate(data) {
  return request({
    url: '/biz-template/save',
    method: 'post',
    data
  })
}

export function deleteBizTemplate(id) {
  return request({
    url: '/biz-template/delete',
    method: 'post',
    data: { id }
  })
}

/** 下发前自检 */
export function validateBizTemplate(id) {
  return request({
    url: '/biz-template/' + id + '/validate',
    method: 'get'
  })
}

/** 对设备整包下发，data: { templateId, sn, params, username } */
export function executeBizTemplate(data) {
  return request({
    url: '/biz-template/execute',
    method: 'post',
    data
  })
}

/** 下发记录列表 */
export function fetchBizExecutions(params) {
  return request({
    url: '/biz-template/executions',
    method: 'get',
    params
  })
}

/** 单条下发记录，用于轮询进度 */
export function fetchBizExecution(id) {
  return request({
    url: '/biz-template/executions/' + id,
    method: 'get'
  })
}
