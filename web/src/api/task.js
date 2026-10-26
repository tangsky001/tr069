import request from '@/utils/request'

export function taskPage(params) {
  return request({ url: '/api/tasks', method: 'get', params })
}

export function taskDetail(id) {
  return request({ url: `/api/tasks/${id}`, method: 'get' })
}

export function createTask(data) {
  return request({ url: '/api/tasks', method: 'post', data })
}

export function cancelTasks(ids) {
  const data = { ids }
  return request({ url: '/api/tasks/cancel', method: 'post', data })
}

export function supportedRpcs() {
  return request({ url: '/api/tasks/rpcs', method: 'get' })
}

export function taskStats() {
  return request({ url: '/api/tasks/stats', method: 'get' })
}
