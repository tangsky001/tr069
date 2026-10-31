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
 * 设备台账统计：{ total, online, offline }。
 * 口径与列表行的在线标签一致（cpe_device.status），与「在线会话」不是一回事。
 */
export function fetchDeviceStat() {
  return request({
    url: '/device/stat',
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
 * 批量下发任务进度：设备列表页每行显示「正在下发 xxx」。
 *
 * 后端返回 sn -> { tasks: [{ label, phase, status, ... }], rpcPending: n }。
 * 注意 tasks 只给还在跑的（PENDING / RUNNING），跑完就不在这一份里了。
 */
export function fetchDeviceActiveTasks(sns) {
  return request({
    url: '/device/tasks/active',
    method: 'get',
    params: { sns: sns.join(',') }
  })
}

/**
 * 批量机型适配状态：以 SQLite 模板库里的适配结果为准（下发查的也是它）。
 * 返回 sn -> { adapted, adaptedFrom, templateLibId, configPath, isp, productClass, ... }
 */
export function fetchAdaptStatus(sns) {
  return request({
    url: '/models/adapt-status',
    method: 'get',
    params: { sns: sns.join(',') }
  })
}

/**
 * 机型适配能力测试：向光猫下发采集 RPC，解析实测能力并与申报比对。
 * data: { isp, lanPorts, lanSpeed, sipCount, wifiSpec }
 * 后端要等光猫回参数树（默认最长 120s）+ 逐组试探下发，全局 5s 超时必须放开。
 */
export function testAdaptation(sn, data) {
  return request({
    url: '/device/' + encodeURIComponent(sn) + '/adaptation/test',
    method: 'post',
    data,
    timeout: 300000
  })
}

/**
 * 机型适配入库：测试通过后导出型号能力树 XML 并写 cpe_model。
 */
export function saveAdaptation(sn, data) {
  return request({
    url: '/device/' + encodeURIComponent(sn) + '/adaptation/save',
    method: 'post',
    data,
    timeout: 60000
  })
}

/**
 * 重新采集：向在线设备下发 GetParameterValues 刷新业务参数后再回库读。
 * 后端 collectStatus：success / timeout / fail / empty。
 *
 * 采集可能需要等 CPE 下一次空 POST（尤其是反向轮询或长周期上报），默认 5s 太短，
 * 这里给到 60s，和后端 acs.device.business.collect-timeout-seconds 默认值对齐。
 */
export function collectDeviceBusiness(sn) {
  return request({
    url: '/device/' + encodeURIComponent(sn) + '/business/collect',
    method: 'post',
    timeout: 60000
  })
}

/**
 * 查询设备本地账号（超管 telecomadmin）及口令。
 *
 * 后端对在线设备下发 GetParameterValues 读 User.{i}.Username / Password，
 * 要等设备回值（默认最长 30s），axios 默认超时不够，这里单独放宽。
 * 返回 data：{ sn, online, status, accounts: [{ instance, path, username, password }], message }
 * status：success / empty / timeout / fail / offline
 */
export function fetchAdminAccounts(sn) {
  return request({
    url: '/device/' + encodeURIComponent(sn) + '/admin-account',
    method: 'get',
    timeout: 90000
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

/**
 * 保存设备的房间号与备注。
 * 房间号是下发时替换模板 %room% 变量的取值来源，例如设为 0001，
 * 下发 WiFi 名称就会用 0001 替换模板里的 %room%。
 * data: { roomNo, remark }，两个都允许空串表示清空。
 */
export function updateDeviceRemark(sn, data) {
  return request({
    url: '/device/' + encodeURIComponent(sn) + '/remark',
    method: 'post',
    data
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

/**
 * 下发 IPPing 诊断。
 *
 * 诊断对象在 TR-098 与 TR-181 下的挂载位置完全不同，这个分支由后端按设备
 * 登记的数据模型选，页面只提交目标地址。
 * 返回 data：{ taskId, target, path }，path 是实际下发的路径前缀，便于核对型号。
 */
export function pingDiagnostics(sn, host) {
  return request({
    url: '/device/' + encodeURIComponent(sn) + '/diagnostics/ping',
    method: 'post',
    data: { host }
  })
}

/**
 * 手动设置若干 CWMP 参数。
 *
 * @param {Array} items [{ name, value, type }]，type 为 xsd 类型，如 unsignedInt
 * SetParameterValues 的报文结构由后端拼，页面只给三元组。
 */
export function setDeviceParams(sn, items) {
  return request({
    url: '/device/' + encodeURIComponent(sn) + '/params/set',
    method: 'post',
    data: { items }
  })
}

/**
 * 机型能力申报的候选值（运营商 / 网口数量 / 网口速率 / WIFI 规格）。
 * 取值口径由后端定义，页面下拉直接用这些候选，不再自己写一份。
 */
export function fetchCapabilityOptions() {
  return request({
    url: '/models/capability-options',
    method: 'get'
  })
}
