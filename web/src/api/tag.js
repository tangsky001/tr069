import request from '@/utils/request'

// 设备标签：型号、运营商之外的分群维度，预设与批量升级都按它挑设备
export function tagList() {
  return request({ url: '/device-tags', method: 'get' })
}

// 这台设备身上的标签
export function tagsOfDevice(sn) {
  return request({ url: `/device-tags/${sn}`, method: 'get' })
}

// 带这个标签的设备序列号
export function devicesOfTag(tag) {
  return request({ url: `/device-tags/tag/${tag}/devices`, method: 'get' })
}

// 打标签，tags 是数组，返回新增条数
export function addTags(sn, tags) {
  return request({ url: `/device-tags/${sn}`, method: 'put', data: tags })
}

// 取消标签，tags 是数组，返回实际删掉的条数
export function removeTags(sn, tags) {
  return request({ url: `/device-tags/${sn}`, method: 'delete', data: tags })
}

// 批量打标签，返回 { devices, added }
export function addTagsBatch(sns, tags) {
  return request({ url: '/device-tags/batch', method: 'post', data: { sns, tags } })
}

// 批量取消标签，返回 { devices, removed }
export function removeTagsBatch(sns, tags) {
  return request({ url: '/device-tags/batch', method: 'delete', data: { sns, tags } })
}

// 删掉这个标签本身（把它从所有设备身上摘掉），返回摘掉的条数
export function removeTagEverywhere(tag) {
  return request({ url: `/device-tags/tag/${encodeURIComponent(tag)}`, method: 'delete' })
}
