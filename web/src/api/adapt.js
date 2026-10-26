import request from '@/utils/request'

export function templates() {
  return request({ url: '/api/adapt/templates', method: 'get' })
}

export function templateDetail(id) {
  return request({ url: `/api/adapt/templates/${id}`, method: 'get' })
}

export function templateParams(id, keyword) {
  const params = { keyword }
  return request({ url: `/api/adapt/templates/${id}/params`, method: 'get', params })
}

export function deleteTemplate(id) {
  return request({ url: `/api/adapt/templates/${id}`, method: 'delete' })
}

export function paramMaps(templateId) {
  const params = { templateId }
  return request({ url: '/api/adapt/param-maps', method: 'get', params })
}

export function saveParamMap(data) {
  return request({ url: '/api/adapt/param-maps', method: 'post', data })
}

export function deleteParamMap(id) {
  return request({ url: `/api/adapt/param-maps/${id}`, method: 'delete' })
}

export function adaptedDevices() {
  return request({ url: '/api/adapt/devices', method: 'get' })
}
