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
/**
 * 导出台账 CSV：后端生成表格内容（列、在线文案、时间格式都在后端），
 * 返回 { fileName, content, count }，页面只负责存成文件。
 * params: { sns } 勾选导出，或 { sn, isp, deviceType, roomNo } 按筛选导出。
 */
export function exportDevices(params) {
  return request({
    url: '/device/export',
    method: 'get',
    params
  })
}

export function fetchDeviceBusiness(sn) {
  return request({
    url: '/device/' + encodeURIComponent(sn) + '/business',
    method: 'get'
  })
}

/**
 * 批量业务信息：设备列表页整页一次拉取，sns 为序列号数组。
 */
/**
 * 列表页轮询节奏：{ listSeconds, taskSeconds, bizSeconds, rpcSeconds, rpcMaxAttempts }。
 * 页面按它建定时器，不再写死几秒一刷。
 */
export function fetchPollConfig() {
  return request({ url: '/device/poll-config', method: 'get' })
}

/**
 * RPC 行内措辞：{ Reboot: { running, success, fail }, FactoryReset: {...}, default: {...} }。
 * 页面不再自己做「去掉正在再拼完成」的字符串处理。
 */
export function fetchRpcProgressLabels() {
  return request({ url: '/device/rpc/progress-labels', method: 'get' })
}

/**
 * SIP 语音：可编辑的线路清单。
 * 路数（申报能力与实际采到取大值）、每路当前号码、注册/代理服务器与端口全由后端算，
 * 端口没配的后端已经按 5060 填好。
 */
export function fetchSipLines(sn) {
  return request({
    url: '/device/' + encodeURIComponent(sn) + '/sip/lines',
    method: 'get'
  })
}

/**
 * SIP 语音下发：只提交业务字段。
 * 按路拆单、占位符翻译都在后端；直接下发不建工单，返回 { lines, submitted, failed, tasks, failures }。
 */
export function submitSipEdit(sn, data) {
  return request({
    url: '/device/' + encodeURIComponent(sn) + '/sip',
    method: 'post',
    data
  })
}

/**
 * 设备页直接下发：逐台受理即发，不建工单，进度看下发任务本身。
 * data: { sns(数组), category, values, templateId, wifi(可选 WLAN 业务字段), username }。
 * 返回 { submitted, failed, rows: [{ sn, taskId, status, errorMsg }] }。
 */
export function dispatchDevices(data) {
  return request({
    url: '/device/dispatch',
    method: 'post',
    data
  })
}

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
 * 查询设备 Telnet 使能状态。
 * 后端会按运营商适配路径向设备实时读取，离线设备会先发连接请求唤醒。
 * 返回 data: { sn, online, status, enabled, path, message, statusText, statusLevel }
 */
export function fetchTelnetStatus(sn) {
  return request({
    url: '/device/' + encodeURIComponent(sn) + '/telnet',
    method: 'get',
    timeout: 90000
  })
}

/**
 * 开启或关闭设备 Telnet。
 * enabled 为 true 时开启，false 时关闭。
 * 返回 data: { sn, status, taskId, path, message, statusText, statusLevel }
 */
export function setTelnetEnabled(sn, enabled) {
  return request({
    url: '/device/' + encodeURIComponent(sn) + '/telnet',
    method: 'post',
    data: { enabled },
    timeout: 30000
  })
}

/**
 * 查询 Telnet 状态及默认账号密码。
 * 后端会先读使能状态：已开启就直接读账号密码；未开启会自动下发开启指令，
 * 等设备应用成功后再读取 TelnetUserName / TelnetPassword / TelnetPort。
 * 返回 data: { sn, online, status, enabled, path, message,
 *   username, password, port, credentialStatus, enablePath, enableTaskId, ... }
 */
export function fetchTelnetCredentials(sn) {
  return request({
    url: '/device/' + encodeURIComponent(sn) + '/telnet/credentials',
    method: 'get',
    timeout: 120000
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

/**
 * 可下发方法与快捷操作的「命令 → RPC」映射由后端下发（/device/rpc/methods、
 * /device/rpc/quick-commands），前端不做任何清单兜底：接口没给就是后端问题。
 */
export function fetchRpcMethods() {
  return request({
    url: '/device/rpc/methods',
    method: 'get'
  })
}

export function fetchQuickCommands() {
  return request({
    url: '/device/rpc/quick-commands',
    method: 'get'
  })
}

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
