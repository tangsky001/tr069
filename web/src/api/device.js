import request from '@/utils/request'

export function devicePage(params) {
  return request({ url: '/api/devices', method: 'get', params })
}

export function deviceDetail(unitId) {
  return request({ url: `/api/devices/${unitId}`, method: 'get' })
}

export function updateDevice(id, data) {
  return request({ url: `/api/devices/${id}`, method: 'put', data })
}

export function deleteDevice(id) {
  return request({ url: `/api/devices/${id}`, method: 'delete' })
}

export function deviceParams(unitId) {
  return request({ url: `/api/devices/${unitId}/params`, method: 'get' })
}

export function setDeviceParam(unitId, data) {
  return request({ url: `/api/devices/${unitId}/params`, method: 'post', data })
}

export function deleteDeviceParam(unitId, name) {
  const params = { name }
  return request({ url: `/api/devices/${unitId}/params`, method: 'delete', params })
}

export function kickDevice(unitId) {
  return request({ url: `/api/devices/${unitId}/kick`, method: 'post' })
}

export function onlineSummary() {
  return request({ url: '/api/devices/online', method: 'get' })
}
