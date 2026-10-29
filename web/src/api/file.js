import request from '@/utils/request'

/**
 * 文件的接口。
 * 上传走 /api 要令牌，设备下载走 /files 不要令牌——设备下载时不会带令牌。
 */

export function fetchFiles() {
  return request({
    url: '/files',
    method: 'get'
  })
}

export function fetchFileUrl(id) {
  return request({
    url: '/files/' + id + '/url',
    method: 'get'
  })
}

/** 上传：文件本身 + 类型 + 版本，用表单提交。 */
export function uploadFile(formData) {
  return request({
    url: '/files',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/** 下发给一批设备，sns 是逗号分隔的序列号。 */
export function deliverFile(id, sns, delaySeconds) {
  return request({
    url: '/files/' + id + '/deliver',
    method: 'post',
    data: { sns, delaySeconds }
  })
}

export function deleteFile(id) {
  return request({
    url: '/files/' + id,
    method: 'delete'
  })
}
