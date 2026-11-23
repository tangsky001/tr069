import request from '@/utils/request'

// SIP 软交换接口，后端前缀 /api/sip

/** 分机注册列表（含未注册分机与通话占用状态） */
export function fetchSipExtensions() {
  return request({
    url: '/sip/extensions',
    method: 'get'
  })
}

/** 活跃呼叫列表（振铃/通话中） */
export function fetchSipCalls() {
  return request({
    url: '/sip/calls',
    method: 'get'
  })
}

/** H.248 网关与终结点列表 */
export function fetchSipGateways() {
  return request({
    url: '/sip/gateways',
    method: 'get'
  })
}

/** 添加分机 */
export function addSipExt(data) {
  return request({
    url: '/sip/extensions',
    method: 'post',
    data
  })
}

/** 修改分机 */
export function updateSipExt(id, data) {
  return request({
    url: `/sip/extensions/${id}`,
    method: 'put',
    data
  })
}

/** 删除分机 */
export function deleteSipExt(id) {
  return request({
    url: `/sip/extensions/${id}`,
    method: 'delete'
  })
}

/** 批量导入分机（FormData） */
export function importSipExts(formData) {
  return request({
    url: '/sip/extensions/import',
    method: 'post',
    data: formData
  })
}

/** 批量生成号段 */
export function generateSipExts(data) {
  return request({
    url: '/sip/extensions/generate',
    method: 'post',
    data
  })
}

/** 呼叫队列列表 */
export function fetchSipQueues() {
  return request({
    url: '/sip/queues',
    method: 'get'
  })
}

/** 保存队列（id 为空新建） */
export function saveSipQueue(data) {
  return request({
    url: '/sip/queues',
    method: 'post',
    data
  })
}

/** 删除队列 */
export function deleteSipQueue(id) {
  return request({
    url: `/sip/queues/${id}`,
    method: 'delete'
  })
}

/** 通话记录分页查询（params: page/size/ext） */
export function fetchSipRecords(params) {
  return request({
    url: '/sip/records',
    method: 'get',
    params
  })
}

/** 下载通话录音（blob） */
export function downloadSipRecord(id) {
  return request({
    url: `/sip/records/${id}/download`,
    method: 'get',
    responseType: 'blob'
  })
}
