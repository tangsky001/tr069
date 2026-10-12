import request from '@/utils/request'

// 设备管理接口，后端前缀 /api/device
// request 的 baseURL 已带 /api，这里从 /device 写起

export function fetchDevicePage(params) {
  return request({
    url: '/device/page',
    method: 'get',
    params
  })
}

export function fetchDeviceDetail(sn) {
  return request({
    url: '/device/' + encodeURIComponent(sn),
    method: 'get'
  })
}

export function fetchDeviceStatus(sn) {
  return request({
    url: '/device/' + encodeURIComponent(sn) + '/status',
    method: 'get'
  })
}

export function fetchOnlineDevices() {
  return request({
    url: '/device/online',
    method: 'get'
  })
}

/**
 * 手动下发一条 CWMP RPC。
 * args 为 JSON 对象，由后端透传给设备。
 */
export function dispatchRpc(sn, rpcName, args) {
  return request({
    url: '/device/' + encodeURIComponent(sn) + '/rpc/' + rpcName,
    method: 'post',
    data: args || {}
  })
}

/** 常用 RPC 方法，与后端 RpcMethod 对应，同时允许手工输入。 */
export const RPC_METHODS = [
  'GetRPCMethods',
  'GetParameterNames',
  'GetParameterValues',
  'SetParameterValues',
  'GetParameterAttributes',
  'SetParameterAttributes',
  'AddObject',
  'DeleteObject',
  'Reboot',
  'FactoryReset',
  'Download',
  'Upload',
  'ScheduleInform',
  'ChangeDUState'
]
