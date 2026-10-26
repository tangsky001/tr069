import request from '@/utils/request'

export function summary() {
  return request({ url: '/api/dashboard/summary', method: 'get' })
}

export function recentDevices() {
  return request({ url: '/api/dashboard/recent', method: 'get' })
}

export function fileList() {
  return request({ url: '/api/files', method: 'get' })
}

export function deleteFile(id) {
  return request({ url: `/api/files/${id}`, method: 'delete' })
}

export function downloadUrl(id) {
  return `/api/files/${id}/download`
}
