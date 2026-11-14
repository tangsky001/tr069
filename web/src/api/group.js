import request from '@/utils/request'

/**
 * 分组管理 API：固定根节点 + 逐级落库的分组节点、组内成员、绑定/解绑。
 * 节点引用 nodeRef：ROOT（根，默认设备归属）/ G:分组 id。
 * 建组流程：右键节点 → 弹窗列出下级区划候选（根→省，省→市，市→区县）→ 选中确定落库。
 * 展示字段（路径文案、状态文案等）后端算好，前端只渲染。
 */

// 分组树：固定根节点 + 已落库的分组节点，节点带成员数量（直接数/含下级汇总数）
export function fetchGroupTree() {
  return request({ url: '/group/tree', method: 'get' })
}

// 建组候选：父节点下一级可选区划（根→34 省，省→市，市→区县；自定义节点无候选）
export function fetchGroupCandidates(parentRef) {
  return request({ url: '/group/candidates', method: 'get', params: { parentRef: parentRef || 'ROOT' }})
}

// 创建分组（落库）：parentRef 父节点引用；civilCode 所选区划（名称默认区划名）；name 自定义名称
export function createGroup(data) {
  return request({ url: '/group/create', method: 'post', data })
}

// 重命名分组
export function renameGroup(id, name) {
  return request({ url: '/group/rename', method: 'post', data: { id, name }})
}

// 删除分组：有子分组或绑定成员会被后端拒绝；根节点不可删
export function deleteGroup(nodeRef) {
  return request({ url: '/group/delete', method: 'post', data: { id: nodeRef }})
}

// 组内成员：某节点的 OLT 台账与在线状态（根节点=未绑定设备）
export function fetchGroupMembers(nodeRef) {
  return request({ url: '/group/members', method: 'get', params: { nodeRef }})
}
// 移动设备到目标分组（ROOT=移回根节点）；换分组直接改归属，无需先解绑
export function moveGroupMember(nodeRef, oltId) {
  return request({ url: '/group/move', method: 'post', data: { nodeRef, oltId }})
}
