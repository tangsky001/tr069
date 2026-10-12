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
 * 设备业务信息（WAN / WLAN / SIP），只读库里最近一次采集的快照。
 */
export function fetchDeviceBusiness(sn) {
  return request({
    url: '/device/' + encodeURIComponent(sn) + '/business',
    method: 'get'
  })
}

/**
 * 批量业务信息：设备列表页整页一次拉取，sns 为序列号数组。
 */
export function fetchDeviceBusinessBatch(sns) {
  return request({
    url: '/device/business/batch',
    method: 'get',
    params: { sns: sns.join(',') }
  })
}

/**
 * 重新采集：向在线设备下发 GetParameterValues 刷新业务参数后再回库读。
 * 后端 collectStatus：success / timeout / fail / empty。
 */
export function collectDeviceBusiness(sn) {
  return request({
    url: '/device/' + encodeURIComponent(sn) + '/business/collect',
    method: 'post'
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
