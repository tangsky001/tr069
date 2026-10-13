import request from '@/utils/request'

// 配置意图模板：只描述开通意图并入库存档，下发时按型号映射生成真实参数
// 后端前缀 /api/config-templates

/** 模板类型与各类别的意图字段定义 */
export function fetchConfigTemplateMeta() {
  return request({
    url: '/config-templates/meta',
    method: 'get'
  })
}

/** 模板列表，可按类型筛选与关键字搜索 */
export function fetchConfigTemplates(params) {
  return request({
    url: '/config-templates',
    method: 'get',
    params
  })
}

export function fetchConfigTemplate(id) {
  return request({
    url: '/config-templates/' + id,
    method: 'get'
  })
}

/** data: { id, name, category, groupName, enabled, remark, fields } */
export function saveConfigTemplate(data) {
  return request({
    url: '/config-templates',
    method: 'post',
    data
  })
}

export function deleteConfigTemplate(id) {
  return request({
    url: '/config-templates/' + id + '/delete',
    method: 'post'
  })
}
