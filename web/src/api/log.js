import request from '@/utils/request'

export function cwmpLogs(params) {
  return request({ url: '/api/logs/cwmp', method: 'get', params })
}

export function auditLogs(params) {
  return request({ url: '/api/logs/audit', method: 'get', params })
}

export function syslogLogs(params) {
  return request({ url: '/api/logs/syslog', method: 'get', params })
}
