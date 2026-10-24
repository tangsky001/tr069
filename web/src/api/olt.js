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
