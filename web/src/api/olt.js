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
