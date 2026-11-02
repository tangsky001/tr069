import request from '@/utils/request'

// OLT 接口，后端前缀 /api/olt
// OLT 不走 TR-069，靠 Telnet 登录设备采集，所以「测试」「同步」都是长耗时请求，
// 单独放大超时，避免走默认 5s 被前端先掐断

/** OLT 列表，keyword 匹配名称 / 地址 / 型号 */
export function fetchOltList(keyword) {
  return request({
    url: '/olt/list',
    method: 'get',
    params: keyword ? { keyword } : {}
  })
}

/** OLT 详情：台账 + 板卡 + 最近采集记录 */
export function fetchOltDetail(id) {
  return request({
    url: '/olt/' + id,
    method: 'get'
  })
}

/** 新增或修改，data 见 OltSaveRequest */
export function saveOlt(data) {
  return request({
    url: '/olt/save',
    method: 'post',
    data
  })
}

export function deleteOlt(id) {
  return request({
    url: '/olt/delete',
    method: 'post',
    data: { id }
  })
}

/** 诊断测试：未保存的表单也能测 */
export function testOlt(data) {
  return request({
    url: '/olt/test',
    method: 'post',
    data,
    timeout: 90000
  })
}

/** 对已保存的 OLT 做诊断测试 */
export function testOltById(id) {
  return request({
    url: '/olt/' + id + '/test',
    method: 'post',
    timeout: 90000
  })
}

/** 同步：登录采集版本与板卡并入库，耗时可到分钟级 */
export function syncOlt(id) {
  return request({
    url: '/olt/' + id + '/sync',
    method: 'post',
    timeout: 180000
  })
}

/**
 * 快速同步：只采板卡（display board 0）秒回，机框和型号先出来；
 * 端口状态 / ONT / VLAN / 模板由后端后台补采，页面轮询即可看到补齐的数据。
 */
export function quickSyncOlt(id) {
  return request({
    url: '/olt/' + id + '/quick-sync',
    method: 'post',
    timeout: 60000
  })
}

/** 会话池状态：保活开关、探活周期、每台设备的空闲会话数 */
export function fetchOltSessionPool() {
  return request({
    url: '/olt/session-pool',
    method: 'get'
  })
}

/**
 * 保活开关：开 = 每台 OLT 池中最多 2 个会话轮换，定期 display client 探活；
 * 关（默认）= 会话 1 分钟没人用就 quit 退出登录，轮询采集要用时自己再登录
 */
export function setOltKeepalive(enabled, intervalMs) {
  return request({
    url: '/olt/session-pool/keepalive',
    method: 'post',
    data: { enabled, intervalMs }
  })
}

/**
 * 实时刷新：直连 OLT 采最新数据并写库，返回最新那份。
 * 页面打开、对 OLT 做过操作之后都走它，数据以设备为准。
 */
export function refreshOlt(id) {
  return request({
    url: '/olt/' + id + '/refresh',
    method: 'post',
    timeout: 180000
  })
}

/**
 * 确认所有待确认板卡（华为 board confirm 0）：新插的板卡停在 auto-find 时不加载业务。
 * 返回确认后重采的最新详情，前端据此决定按钮是否继续显示。
 */
export function confirmOltBoards(id) {
  return request({
    url: '/olt/' + id + '/boards/confirm',
    method: 'post',
    timeout: 120000
  })
}

/**
 * 单条 ONT 按需查询：光信息 / 版本 / 注册历史。
 * 点列表里某条的图标时才登录设备查一次，data: { action, slotNo, portNo, ontId }
 */
export function queryOnt(id, data) {
  return request({
    url: '/olt/' + id + '/ont-query',
    method: 'post',
    data,
    timeout: 120000
  })
}

/**
 * 新增 DBA 模板：下发 dba-profile add，成功后自动重采，data 为表单。
 */
export function createDbaProfile(id, data) {
  return request({
    url: '/olt/' + id + '/dba-profile',
    method: 'post',
    data,
    timeout: 180000
  })
}

/**
 * 删除 DBA 模板：逐条下发 dba-profile delete，成功后自动重采。
 */
export function deleteDbaProfiles(id, profileIds) {
  return request({
    url: '/olt/' + id + '/dba-profile',
    method: 'delete',
    data: profileIds,
    timeout: 180000
  })
}

/**
 * 这台 OLT 支持的命令动作目录（鸿升光对应手册 13 章里的功能格）。
 * 返回 [{ code, chapter, label, view, commands, params, query, danger, note }]
 */
export function fetchOltActions(id) {
  return request({ url: '/olt/' + id + '/actions', method: 'get' })
}

/**
 * 执行一条命令动作：查询类返回回显，配置类返回重采后的详情。
 * data: { code, params }
 */
export function execOltAction(id, data) {
  return request({
    url: '/olt/' + id + '/exec',
    method: 'post',
    data,
    timeout: 180000
  })
}

/**
 * 新增 VLAN：鸿升光下发 vlan standard <id>（一段连续 ID 写成 vlan standard 101 110），
 * 成功后自动重采。data: { vlanId, endVlanId }，只建一个时 endVlanId 留空。
 */
export function createOltVlan(id, data) {
  return request({
    url: '/olt/' + id + '/vlan',
    method: 'post',
    data,
    timeout: 180000
  })
}

/**
 * 删除勾选的 VLAN：逐条（连续段合并）下发 no vlan，成功后自动重采。
 */
export function deleteOltVlans(id, vlanIds) {
  return request({
    url: '/olt/' + id + '/vlan',
    method: 'delete',
    data: vlanIds,
    timeout: 180000
  })
}

/**
 * TR-069（ACS）模板列表：不读库，每次实时登设备执行 show onu-tr069-profile all。
 */
export function fetchTr069Profiles(id) {
  return request({
    url: '/olt/' + id + '/tr069-profiles',
    method: 'get',
    timeout: 60000
  })
}

/**
 * 待注册 ONT：不读库，每次实时登设备执行 display ont autofind all。
 *
 * 这是设备上等着授权的光猫，注册完就不在这个表里了，所以每次点都重查。
 */
export function fetchOntAutofind(id) {
  return request({
    url: '/olt/' + id + '/ont-autofind',
    method: 'get',
    timeout: 60000
  })
}

/**
 * PON 口自动发现开关状态：后端逐口登设备查 display ont-auto-find。
 *
 * 一个口一条命令，口多的设备要跑十几秒，所以超时放宽到 2 分钟。
 */
export function fetchOntAutoFind(id) {
  return request({
    url: '/olt/' + id + '/ont-auto-find',
    method: 'get',
    timeout: 120000
  })
}

/**
 * 一键开启所有 GPON 业务板 PON 口的自动发现。
 *
 * 下发完后端会重采一次整机，连同重采一起给足超时。
 */
export function enableOntAutoFind(id) {
  return request({
    url: '/olt/' + id + '/ont-auto-find/enable',
    method: 'post',
    timeout: 180000
  })
}

/**
 * 注册模板列表：从库里读（原来存在浏览器本地，换台电脑就没了）。
 *
 * @param onlyCurrent true 时只要「不限设备」和绑在这台 OLT 上的
 */
export function fetchOntRegTemplates(id, onlyCurrent) {
  return request({
    url: '/olt/' + id + '/ont-reg/templates',
    method: 'get',
    params: { onlyCurrent: onlyCurrent ? 1 : 0 },
    timeout: 30000
  })
}

/** 新建模板的初始表单：默认值由后端给，页面不自带一份 */
export function fetchOntRegTemplateDefault(id, oltIp) {
  return request({
    url: '/olt/' + id + '/ont-reg/template/default',
    method: 'get',
    params: { oltIp: oltIp || '' },
    timeout: 30000
  })
}

/** 不等下一轮扫描，立刻按当前参数跑一轮自动注册（和后台定时任务同一条路径） */
export function runOntRegScan(id) {
  return request({
    url: '/olt/' + id + '/ont-reg/scan',
    method: 'post',
    timeout: 120000
  })
}

/** 新增或修改一条注册模板 */
export function saveOntRegTemplate(id, data) {
  return request({
    url: '/olt/' + id + '/ont-reg/template',
    method: 'post',
    data,
    timeout: 30000
  })
}

/** 批量删除注册模板 */
export function deleteOntRegTemplates(id, ids) {
  return request({
    url: '/olt/' + id + '/ont-reg/templates',
    method: 'delete',
    data: ids,
    timeout: 30000
  })
}

/** 自动注册参数；没存过后端给一份默认值 */
export function fetchOntRegConfig(id) {
  return request({
    url: '/olt/' + id + '/ont-reg/config',
    method: 'get',
    timeout: 30000
  })
}

/**
 * 保存自动注册参数。
 *
 * 开关打开后后台定时任务按 scanSeconds 去设备上扫待注册光猫，命中模板就注册。
 */
export function saveOntRegConfig(id, data) {
  return request({
    url: '/olt/' + id + '/ont-reg/config',
    method: 'post',
    data,
    timeout: 30000
  })
}

/** 注册记录：哪台注册了、套了哪个模板、没成的因为什么 */
export function fetchOntRegRecords(id, limit) {
  return request({
    url: '/olt/' + id + '/ont-reg/records',
    method: 'get',
    params: { limit: limit || 50 },
    timeout: 30000
  })
}

/** 只编命令不下发，下发前先在页面上核对一遍 */
export function previewOntRegister(id, data) {
  return request({
    url: '/olt/' + id + '/ont-reg/preview',
    method: 'post',
    data,
    timeout: 60000
  })
}

/**
 * 注册选中的待注册 ONT。
 *
 * 逐台下发命令，一台失败不影响其它台，结果逐条返回。
 */
export function registerOnt(id, data) {
  return request({
    url: '/olt/' + id + '/ont-reg/register',
    method: 'post',
    data,
    timeout: 180000
  })
}

/**
 * 一键开局参数：默认值和已存的都在后端，页面只管要。
 *
 * @param defaults true 时忽略存过的、直接要默认值（「默认设置」按钮）
 */
export function fetchQuickSetupConfig(id, defaults) {
  return request({
    url: '/olt/' + id + '/quick-setup/config',
    method: 'get',
    params: { defaults: defaults ? 1 : 0 },
    timeout: 30000
  })
}

/** 保存一键开局参数：存库，换台电脑、换个人打开都是同一套 */
export function saveQuickSetupConfig(id, data) {
  return request({
    url: '/olt/' + id + '/quick-setup/config',
    method: 'post',
    data,
    timeout: 30000
  })
}

/**
 * 建一个 OLT 本地用户（登这台设备用的账号）。
 *
 * 华为这边 backend 走的是 terminal user name 那条交互式命令，一问一答比较慢，
 * 超时放宽到 3 分钟。
 */
export function createOltTerminalUser(id, data) {
  return request({
    url: '/olt/' + id + '/terminal-user',
    method: 'post',
    data,
    timeout: 180000
  })
}

/**
 * 新增 TR-069 模板：下发 onu-tr069-profile add ...，返回下发后的最新列表。
 */
export function createTr069Profile(id, data) {
  return request({
    url: '/olt/' + id + '/tr069-profiles',
    method: 'post',
    data,
    timeout: 60000
  })
}

/**
 * 一键开局：一次下发端口 VLAN 模式、业务 VLAN、TR-069 模板，成功后重采返回最新数据。
 */
export function quickSetupOlt(id, data) {
  return request({
    url: '/olt/' + id + '/quick-setup',
    method: 'post',
    data,
    timeout: 180000
  })
}

/**
 * 一键开局预演：不下发，让后端把这次会执行的命令和提醒算出来。
 * 预览与实际下发同源，前端不再自己拼命令。
 */
export function previewQuickSetup(id, data) {
  return request({
    url: '/olt/' + id + '/quick-setup/preview',
    method: 'post',
    data,
    timeout: 30000
  })
}

/** 一键开局表单的取值约束与默认值 */
export function fetchQuickSetupForm(id) {
  return request({
    url: '/olt/' + id + '/quick-setup/form',
    method: 'get'
  })
}

/** TR-069 模板表单的默认值与取值约束 */
export function fetchTr069Form(id) {
  return request({
    url: '/olt/' + id + '/tr069/form',
    method: 'get'
  })
}

/** 命令动作预演：后端把进视图 + 命令 + 退出算好返回，不下发 */
export function previewOltAction(id, data) {
  return request({
    url: '/olt/' + id + '/actions/preview',
    method: 'post',
    data,
    timeout: 30000
  })
}

/** 「新建 VLAN」表单的元数据：VLAN ID 范围、一次最多几个、哪些端口能做成员 */
export function fetchVlanForm(id) {
  return request({
    url: '/olt/' + id + '/vlan/form',
    method: 'get'
  })
}

/** VLAN 预演：不下发，让后端把 vlan standard / port ... tagged 算出来 */
export function previewVlan(id, data) {
  return request({
    url: '/olt/' + id + '/vlan/preview',
    method: 'post',
    data,
    timeout: 30000
  })
}

/** 「新增 DBA 模板」表单的元数据：五类模板各要填什么、取值上下限 */
export function fetchDbaForm(id) {
  return request({
    url: '/olt/' + id + '/dba-profile/form',
    method: 'get'
  })
}

/** DBA 模板预演：不下发，让后端把 dba-profile add 算出来 */
export function previewDbaProfile(id, data) {
  return request({
    url: '/olt/' + id + '/dba-profile/preview',
    method: 'post',
    data,
    timeout: 30000
  })
}

/**
 * 设备台账表单与各种码表的备选项：厂商、板卡档案、视图名、ONU 动作、VLAN 类型、温度阈值…
 *
 * 码表不会变，页面里好几个组件都要用，这里缓存一份，不用每个组件各发一次。
 */
let optionsCache = null
export function fetchOltOptions() {
  if (optionsCache) {
    return Promise.resolve(optionsCache)
  }
  return request({
    url: '/olt/options',
    method: 'get'
  }).then(res => {
    optionsCache = res
    return res
  })
}

/** 采集记录 */
export function fetchOltRecords(id, limit) {
  return request({
    url: '/olt/' + id + '/records',
    method: 'get',
    params: { limit: limit || 10 }
  })
}

/** 允许访问的 Web 登录账号（来自管理端用户表） */
export function fetchOltWebAccounts() {
  return request({
    url: '/olt/web-accounts',
    method: 'get'
  })
}
