import request from '@/utils/request'

// 首页概览统计，后端前缀 /api/dashboard

/** 概览：设备总量/在线/离线、品牌分布、已下发未下发、工单与模板统计 */
export function fetchOverview() {
  return request({
    url: '/dashboard/overview',
    method: 'get'
  })
}
