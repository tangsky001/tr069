import request from '@/utils/request'
import { getToken } from '@/utils/auth'
import axios from 'axios'

// 检查版本更新
export function checkUpgrade() {
  return request({ url: '/upgrade/check', method: 'get' })
}

// 触发后台异步多线程下载升级包
export function startDownload(downloadUrl, fileSize) {
  return request({
    url: '/upgrade/download',
    method: 'post',
    params: { downloadUrl, fileSize: fileSize || 0 }
  })
}

// 查询后台下载进度
export function getDownloadProgress() {
  return request({ url: '/upgrade/progress', method: 'get' })
}

// 确认升级并退出当前进程
export function confirmRestart() {
  return request({ url: '/upgrade/restart', method: 'post' })
}

// 取消升级并回滚备份
export function cancelUpgrade() {
  return request({ url: '/upgrade/cancel', method: 'post' })
}

// 手动上传JAR升级包
export function uploadUpgrade(formData, onUploadProgress) {
  return axios({
    baseURL: process.env.VUE_APP_BASE_API,
    url: '/upgrade/upload',
    method: 'post',
    data: formData,
    timeout: 300000,
    headers: {
      'X-Token': getToken(),
      'Content-Type': 'multipart/form-data'
    },
    onUploadProgress
  })
}
