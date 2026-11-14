import request from '@/utils/request'

// 请求统一以 /api 打头：
//   开发环境  VUE_APP_BASE_API=/dev-api，由 vue.config.js 代理到后端 8888 并改写为 /api
//   部署环境  前端 dist 直接由后端 jar 托管，VUE_APP_BASE_API=/api，同源请求无需代理

export function login(data) {
  return request({
    url: '/user/login',
    method: 'post',
    data
  })
}

export function getInfo(token) {
  return request({
    url: '/user/info',
    method: 'get',
    params: { token }
  })
}

export function logout() {
  return request({
    url: '/user/logout',
    method: 'post'
  })
}
