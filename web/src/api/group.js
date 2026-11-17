import request from '@/utils/request'

/**
 * 分组管理 API：固定根节点 + 逐级落库的分组节点、组内成员、绑定/解绑。
 * 节点引用 nodeRef：ROOT（根，默认设备归属）/ G:分组 id。
 * memberType=OLT/ONU 是「表域」：OLT 与光猫各自一套物理表
 * （olt_res_group* / onu_res_group*，权限也分 olt_res_acl / onu_res_acl），
 * 两棵树互相独立——树、成员、建组/改名/删除、授权都按 memberType 落到各自的表。
 * 建组流程：右键节点 → 弹窗列出下级区划候选（根→省，省→市，市→区县）→ 选中确定落库。
 * 展示字段（路径文案、状态文案等）后端算好，前端只渲染。
 */

// 分组树：固定根节点 + 已落库的分组节点，节点带成员数量（直接数/含下级汇总数）
// memberType：OLT/ONU（表域），不传默认 OLT
export function fetchGroupTree(memberType) {
  return request({ url: '/group/tree', method: 'get', params: { memberType }})
}

// 省份地图汇总：省级节点设备数与地图填充色（颜色后端裁定，前端只渲染）
// 返回 { provinces: [{ name, value, color }] }，有设备的省按设备数降序在前
export function fetchProvinceMap(memberType) {
  return request({ url: '/group/province-map', method: 'get', params: { memberType }})
}

// 建组候选：父节点下一级可选区划（根→34 省，省→市，市→区县；自定义节点无候选）
export function fetchGroupCandidates(parentRef, memberType) {
  return request({ url: '/group/candidates', method: 'get', params: { parentRef: parentRef || 'ROOT', memberType }})
}

// 创建分组（落库）：parentRef 父节点引用；civilCode 所选区划（名称默认区划名）；name 自定义名称
export function createGroup(data) {
  return request({ url: '/group/create', method: 'post', data })
}

// 重命名分组
export function renameGroup(id, name, memberType) {
  return request({ url: '/group/rename', method: 'post', data: { id, name, memberType }})
}

// 删除分组：有子分组或绑定成员会被后端拒绝；根节点不可删
export function deleteGroup(nodeRef, memberType) {
  return request({ url: '/group/delete', method: 'post', data: { id: nodeRef, memberType }})
}

// 组内成员：某节点的设备台账与在线状态（根节点=未绑定设备）；memberType 不传默认 OLT
export function fetchGroupMembers(nodeRef, memberType) {
  return request({ url: '/group/members', method: 'get', params: { nodeRef, memberType }})
}
// 移动设备到目标分组（ROOT=移回根节点）；换分组直接改归属，无需先解绑（OLT 用）
export function moveGroupMember(nodeRef, oltId) {
  return request({ url: '/group/move', method: 'post', data: { nodeRef, oltId }})
}
// 通用归属落库：光猫等设备模块接入时调用（memberRef=设备业务标识，ONU 为光猫 SN）
export function attachGroupMember(data) {
  return request({ url: '/group/attach', method: 'post', data })
}
// 通用移动：光猫等设备模块接入时调用（groupRef=目标节点，ROOT=移回根节点）
export function moveGroupMemberByRef(data) {
  return request({ url: '/group/move-member', method: 'post', data })
}
