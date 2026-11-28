import request from '@/utils/request'

// OLT 接口（后端前缀 /api/olt）：不走 TR-069，靠 Telnet 登录设备采集，长耗时请求单独放大超时

/** OLT 列表，keyword 匹配名称 / 地址 / 型号 */
export function fetchOltList(keyword) {
  return request({
    url: '/olt/list',
    method: 'get',
    params: keyword ? { keyword } : {},
    // 板卡+端口+ONT 数据量大，放大超时
    timeout: 30000
  })
}

/** OLT 详情：台账 + 板卡 + 最近采集记录 */
export function fetchOltDetail(id) {
  return request({
    url: '/olt/' + id,
    method: 'get',
    timeout: 30000
  })
}

/** 新增或修改 */
export function saveOlt(data) {
  return request({
    url: '/olt/save',
    method: 'post',
    data,
    timeout: 30000
  })
}

export function deleteOlt(id) {
  return request({
    url: '/olt/delete',
    method: 'post',
    data: { id },
    timeout: 30000
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

/** 快速同步：只采板卡秒回；scope='boards' 时不再排后台深度补采 */
export function quickSyncOlt(id, scope) {
  return request({
    url: '/olt/' + id + '/quick-sync',
    method: 'post',
    params: scope ? { scope } : {},
    timeout: 60000
  })
}

/** 按页签同步指定数据段（boards / ont / vlan / template），返回最新详情 */
export function syncOltSection(id, scope, force) {
  return request({
    url: '/olt/' + id + '/sync-section',
    method: 'post',
    params: force ? { scope, force: true } : { scope },
    timeout: 180000
  })
}

/** 会话池状态：保活开关、探活周期、每台设备的空闲会话数 */
export function fetchOltSessionPool() {
  return request({
    url: '/olt/session-pool',
    method: 'get',
    timeout: 30000
  })
}

/** 会话池保活开关：开则轮换探活，关（默认）则空闲会话退出登录 */
export function setOltKeepalive(enabled, intervalMs) {
  return request({
    url: '/olt/session-pool/keepalive',
    method: 'post',
    data: { enabled, intervalMs }
  })
}

/** 实时刷新：直连 OLT 采最新数据并写库，页面打开与操作后都走它 */
export function refreshOlt(id) {
  return request({
    url: '/olt/' + id + '/refresh',
    method: 'post',
    timeout: 180000
  })
}

/** 确认所有待确认板卡（board confirm 0），返回重采后的最新详情 */
export function confirmOltBoards(id) {
  return request({
    url: '/olt/' + id + '/boards/confirm',
    method: 'post',
    timeout: 120000
  })
}

/** 单条 ONT 按需查询（光信息 / 版本 / 注册历史），data: { action, slotNo, portNo, ontId } */
export function queryOnt(id, data) {
  return request({
    url: '/olt/' + id + '/ont-query',
    method: 'post',
    data,
    timeout: 120000
  })
}

/** 新增 DBA 模板，成功后自动重采 */
export function createDbaProfile(id, data) {
  return request({
    url: '/olt/' + id + '/dba-profile',
    method: 'post',
    data,
    timeout: 180000
  })
}

/** 新增 ONT 线路模板（建模板+绑 DBA+VLAN 映射+TR069 管理），成功后自动重采 */
export function createLineProfile(id, data) {
  return request({
    url: '/olt/' + id + '/line-profile/create',
    method: 'post',
    data,
    timeout: 180000
  })
}

/** 删除勾选的线路模板（先清模板内配置再删），成功后自动重采 */
export function deleteLineProfiles(id, profileIds) {
  return request({
    url: '/olt/' + id + '/line-profile/delete',
    method: 'post',
    data: profileIds,
    timeout: 180000
  })
}

/** 删除 DBA 模板，成功后自动重采 */
export function deleteDbaProfiles(id, profileIds) {
  return request({
    url: '/olt/' + id + '/dba-profile',
    method: 'delete',
    data: profileIds,
    timeout: 180000
  })
}

/** 线路模板加一条 T-CONT，data: { profileId, ponType, tcontId, dbaProfileId } */
export function addLineTcont(id, data) {
  return request({
    url: '/olt/' + id + '/line-profile/tcont',
    method: 'post',
    data,
    timeout: 120000
  })
}

/** 线路模板加一个 GEM Port，data: { profileId, ponType, tcontId, gemportId } */
export function addLineGemport(id, data) {
  return request({
    url: '/olt/' + id + '/line-profile/gemport',
    method: 'post',
    data,
    timeout: 120000
  })
}

/** 给 GEM Port 加 VLAN 映射，data: { profileId, ponType, gemportId, mappingIds, vlans } */
export function addLineGemMapping(id, data) {
  return request({
    url: '/olt/' + id + '/line-profile/gem-mapping',
    method: 'post',
    data,
    timeout: 120000
  })
}

/** 线路模板弹窗的表单元数据（默认值、已占用索引等）；加映射时需带 gemportId */
export function fetchLineProfileForm(id, profileId, gemportId) {
  return request({
    url: '/olt/' + id + '/line-profile/form',
    method: 'get',
    params: { profileId, gemportId }
  })
}

/** 线路模板弹窗的命令预演（不下发），data: { kind, profileId, tcontId, dbaProfileId, gemportId, mappingIds, vlans } */
export function previewLineProfile(id, data) {
  return request({
    url: '/olt/' + id + '/line-profile/preview',
    method: 'post',
    data
  })
}

/** 命令动作目录 [{ code, chapter, label, view, commands, params, ... }] */
export function fetchOltActions(id) {
  return request({ url: '/olt/' + id + '/actions', method: 'get', timeout: 30000 })
}

/** 执行一条命令动作：查询类返回回显，配置类返回重采后的详情，data: { code, params } */
export function execOltAction(id, data) {
  return request({
    url: '/olt/' + id + '/exec',
    method: 'post',
    data,
    timeout: 180000
  })
}

/** 新增 VLAN（支持一段连续 ID，endVlanId 留空只建一个），成功后自动重采 */
export function createOltVlan(id, data) {
  return request({
    url: '/olt/' + id + '/vlan',
    method: 'post',
    data,
    timeout: 180000
  })
}

/** 删除勾选的 VLAN，成功后自动重采 */
export function deleteOltVlans(id, vlanIds) {
  return request({
    url: '/olt/' + id + '/vlan',
    method: 'delete',
    data: vlanIds,
    timeout: 180000
  })
}

/** 上行口放通 VLAN，成功后立刻只重读 VLAN 并返回最新详情 */
export function addOltPortVlan(id, data) {
  return request({
    url: '/olt/' + id + '/vlan/port-vlan',
    method: 'post',
    data,
    timeout: 120000
  })
}

/** 上行口解除 VLAN 绑定 */
export function removeOltPortVlan(id, data) {
  return request({
    url: '/olt/' + id + '/vlan/port-vlan',
    method: 'delete',
    data,
    timeout: 120000
  })
}

/** TR-069（ACS）模板列表，每次实时登设备查询 */
export function fetchTr069Profiles(id) {
  return request({
    url: '/olt/' + id + '/tr069-profiles',
    method: 'get',
    timeout: 60000
  })
}

/** 待注册 ONT，每次实时登设备查询 */
export function fetchOntAutofind(id) {
  return request({
    url: '/olt/' + id + '/ont-autofind',
    method: 'get',
    timeout: 60000
  })
}

/** PON 口自动发现开关状态（逐口查询，耗时较长） */
export function fetchOntAutoFind(id) {
  return request({
    url: '/olt/' + id + '/ont-auto-find',
    method: 'get',
    timeout: 120000
  })
}

/** 一键开启所有 GPON 业务板 PON 口的自动发现 */
export function enableOntAutoFind(id) {
  return request({
    url: '/olt/' + id + '/ont-auto-find/enable',
    method: 'post',
    timeout: 180000
  })
}

/** 保存配置：下发 save 写设备 flash，掉电不丢 */
export function saveOltConfig(id) {
  return request({
    url: '/olt/' + id + '/save-config',
    method: 'post',
    timeout: 180000
  })
}

/** 整机重启：核验 web 超级管理员密码后下发 reboot */
export function rebootOlt(id, password) {
  return request({
    url: '/olt/' + id + '/reboot',
    method: 'post',
    data: { password },
    timeout: 300000
  })
}

/** 删除业务板上的全部 ONT；slots 留空表示所有业务板 */
export function deleteAllOnts(id, slots) {
  return request({
    url: '/olt/' + id + '/ont/delete-all',
    method: 'post',
    data: { slots: slots || [] },
    timeout: 300000
  })
}

/** 删除单个 ONT（先拆业务虚口再删），data: { slot, port, ontId } */
export function deleteOltOnt(id, data) {
  return request({
    url: '/olt/' + id + '/ont/delete',
    method: 'post',
    data,
    timeout: 300000
  })
}

/** 链路聚合组实时查询 */
export function fetchLinkAggregations(id) {
  return request({
    url: '/olt/' + id + '/link-aggregation',
    method: 'get',
    timeout: 120000
  })
}

/** 重启 ONU：按 slot / port / ontId 缺省分级，复位单个 ONU、整条 PON 口或整块板卡 */
export function resetOltOnt(id, data) {
  return request({
    url: '/olt/' + id + '/ont/reset',
    method: 'post',
    data,
    timeout: 300000
  })
}

/** 创建/删除链路聚合组（op: 'create'|'delete'），返回最新聚合表 */
export function applyLinkAggregation(id, data) {
  return request({
    url: '/olt/' + id + '/link-aggregation',
    method: 'post',
    data,
    timeout: 300000
  })
}

/** 注册模板列表；onlyCurrent=true 时只要「不限设备」和绑在这台 OLT 上的 */
export function fetchOntRegTemplates(id, onlyCurrent) {
  return request({
    url: '/olt/' + id + '/ont-reg/templates',
    method: 'get',
    params: { onlyCurrent: onlyCurrent ? 1 : 0 },
    timeout: 30000
  })
}

/** 新建模板的初始表单：默认值由后端给 */
export function fetchOntRegTemplateDefault(id, oltIp) {
  return request({
    url: '/olt/' + id + '/ont-reg/template/default',
    method: 'get',
    params: { oltIp: oltIp || '' },
    timeout: 30000
  })
}

/** 不等下一轮扫描，立刻按当前参数跑一轮自动注册 */
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

/** 保存自动注册参数 */
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

/** 注册选中的待注册 ONT，一台失败不影响其它台 */
export function registerOnt(id, data) {
  return request({
    url: '/olt/' + id + '/ont-reg/register',
    method: 'post',
    data,
    timeout: 180000
  })
}

/** 一键开局参数；defaults=true 时忽略存过的、直接要默认值 */
export function fetchQuickSetupConfig(id, defaults) {
  return request({
    url: '/olt/' + id + '/quick-setup/config',
    method: 'get',
    params: { defaults: defaults ? 1 : 0 },
    timeout: 30000
  })
}

/** 保存一键开局参数（存库，所有人共用一套） */
export function saveQuickSetupConfig(id, data) {
  return request({
    url: '/olt/' + id + '/quick-setup/config',
    method: 'post',
    data,
    timeout: 30000
  })
}

/** 建 OLT 本地用户（登这台设备用的账号），交互式命令较慢 */
export function createOltTerminalUser(id, data) {
  return request({
    url: '/olt/' + id + '/terminal-user',
    method: 'post',
    data,
    timeout: 180000
  })
}

/** 新增 TR-069 模板，返回最新列表 */
export function createTr069Profile(id, data) {
  return request({
    url: '/olt/' + id + '/tr069-profiles',
    method: 'post',
    data,
    timeout: 60000
  })
}

/** 一键开局：一次下发端口 VLAN 模式、业务 VLAN、TR-069 模板，成功后重采 */
export function quickSetupOlt(id, data) {
  return request({
    url: '/olt/' + id + '/quick-setup',
    method: 'post',
    data,
    timeout: 180000
  })
}

/** 一键开局预演：不下发，返回将执行的命令与提醒 */
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

/** 命令动作预演：返回进视图 + 命令 + 退出，不下发 */
export function previewOltAction(id, data) {
  return request({
    url: '/olt/' + id + '/actions/preview',
    method: 'post',
    data,
    timeout: 30000
  })
}

/** 批量命令预演，items: [{ code, params }]，返回 { 动作编号: 命令文本 } */
export function previewOltActions(id, items) {
  return request({
    url: '/olt/' + id + '/actions/previews',
    method: 'post',
    data: items,
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

/** VLAN 预演：不下发，返回将下发的命令 */
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

/** DBA 模板预演：不下发，返回将下发的命令 */
export function previewDbaProfile(id, data) {
  return request({
    url: '/olt/' + id + '/dba-profile/preview',
    method: 'post',
    data,
    timeout: 30000
  })
}

/** 设备台账表单与各种码表的备选项；结果在模块内缓存复用 */
let optionsCache = null
export function fetchOltOptions() {
  if (optionsCache) {
    return Promise.resolve(optionsCache)
  }
  return request({
    url: '/olt/options',
    method: 'get',
    timeout: 30000
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
    params: { limit: limit || 10 },
    timeout: 30000
  })
}

/** 允许访问的 Web 登录账号（来自管理端用户表） */
export function fetchOltWebAccounts() {
  return request({
    url: '/olt/web-accounts',
    method: 'get'
  })
}
