import request from '@/utils/request'

export function login(data) {
  return request({
    url: '/api/auth/login',
    method: 'post',
    data
  })
}

export function getInfo(token) {
  return request({
    url: '/api/auth/info',
    method: 'get',
    params: { token }
  })
}

export function logout() {
  return request({
    url: '/api/auth/logout',
    method: 'post'
  })
}

export function listUsers() {
  return request({ url: '/api/auth/users', method: 'get' })
}

export function createUser(data) {
  return request({ url: '/api/auth/users', method: 'post', data })
}

export function deleteUser(id) {
  return request({ url: `/api/auth/users/${id}`, method: 'delete' })
}

export function changePassword(id, password) {
  const data = { password }
  return request({ url: `/api/auth/users/${id}/password`, method: 'post', data })
}

export function updateStatus(id, status) {
  const data = { status }
  return request({ url: `/api/auth/users/${id}/status`, method: 'post', data })
}
