<template>
  <el-card v-if="!bare" shadow="never" class="china-map-card">
    <div slot="header" class="china-map-head">
      <span class="china-map-title">{{ title }}</span>
      <span v-if="loaded" class="china-map-summary">覆盖 {{ covered }} 个省 · 共 {{ total }} 台</span>
      <slot name="extra" />
    </div>
    <div ref="chart" class="china-map-chart" :style="{ height: height + 'px' }" />
  </el-card>
  <!-- 裸模式：不带卡片壳，宿主页面自己包（比如嵌进别的卡片里）；右侧带一列省份设备数清单 -->
  <div v-else class="china-map-bare">
    <div ref="chart" class="china-map-chart" :style="{ height: height + 'px' }" />
    <div class="china-map-side">
      <div v-if="!loaded" class="china-map-side-hint">加载中…</div>
      <div v-else-if="deviceRows.length === 0" class="china-map-side-hint">暂无设备</div>
      <div v-for="row in deviceRows" :key="row.name" class="china-map-side-row">
        <span class="china-map-side-count" :title="row.name" :style="{ background: row.color }">{{ row.value }}</span>
      </div>
    </div>
  </div>
</template>

<script>
import * as echarts from 'echarts'
import { fetchProvinceMap } from '@/api/group'
import chinaJson from '@/assets/map/china.json'

// echarts 5 不再内置中国地图，GeoJSON 打包在项目里，全项目只注册一次
let registered = false
function ensureMap() {
  if (!registered) {
    echarts.registerMap('china', chinaJson)
    registered = true
  }
}

/**
 * 地图省名 → 分组树省级节点名的对齐。
 * 两侧都来自民政部区划，全称基本一致；对不上的先去掉后缀再比，避免「广西」vs「广西壮族自治区」这类写法差异漏掉。
 */
function normalizeName(name) {
  return String(name || '')
    .replace(/(维吾尔|壮族|回族)?自治区$|特别行政区$|[省市]$/, '')
}

/**
 * 中国地图卡片：按资源分组树（/group/tree）的省级节点给省份着色。
 *
 * 数据口径：省级节点 = level 1（区划骨架自带 34 省，自定义节点 level 0 不会混进来），
 * 取值 memberTotal（含下级汇总）。树里没有的省一律填 0，画满全国底色。
 */
export default {
  name: 'ChinaMap',
  props: {
    /** 分组成员类型：OLT / ONU（/group/tree 的 memberType 参数） */
    memberType: {
      type: String,
      default: 'OLT'
    },
    title: {
      type: String,
      default: '设备省份分布'
    },
    height: {
      type: Number,
      default: 420
    },
    /** 裸模式：不渲染卡片壳，只出地图本身，宿主自己安排标题和布局 */
    bare: {
      type: Boolean,
      default: false
    }
  },
  data() {
    return {
      loaded: false,
      covered: 0,
      total: 0,
      /** 右侧清单用：有设备的省，按设备数降序 */
      deviceRows: []
    }
  },
  mounted() {
    this.load()
    this.resizeHandler = () => this.chart && this.chart.resize()
    window.addEventListener('resize', this.resizeHandler)
    // 容器尺寸可能在首帧后才稳定（卡片内 flex 布局、字体加载等），尺寸一变就重算画布；
    // 否则 echarts 在 0 尺寸时 init 出来的画布永远是空的
    if (typeof ResizeObserver !== 'undefined') {
      this.observer = new ResizeObserver(() => this.chart && this.chart.resize())
      this.$nextTick(() => this.observer.observe(this.$refs.chart))
    }
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.resizeHandler)
    if (this.observer) {
      this.observer.disconnect()
      this.observer = null
    }
    if (this.chart) {
      this.chart.dispose()
      this.chart = null
    }
  },
  methods: {
    /**
     * 颜色与设备数都由后端 /group/province-map 裁定，前端只按 GeoJSON 省名对齐渲染。
     * 后端没返回的省（理论上不会）兜底中性灰。
     */
    load() {
      fetchProvinceMap(this.memberType)
        .then(res => {
          const rows = (res.data && res.data.provinces) || []
          const byKey = {}
          rows.forEach(row => {
            byKey[normalizeName(row.name)] = row
          })
          const names = chinaJson.features
            .map(f => f.properties && f.properties.name)
            .filter(Boolean)
          const data = names.map(name => {
            const hit = byKey[normalizeName(name)]
            const value = hit ? hit.value : 0
            const color = hit ? hit.color : '#eef1f6'
            return {
              name,
              value,
              color,
              // 颜色后端已裁定：直接逐省套用；有设备的省直接标设备数
              itemStyle: { areaColor: color },
              label: value > 0
                ? { show: true, formatter: name + '\n' + value + ' 台' }
                : { show: false }
            }
          })
          // 右侧清单：后端已按设备数降序（有设备的省在前）
          this.deviceRows = rows
            .filter(row => row.value > 0)
            .map(row => ({ name: row.name, value: row.value, color: row.color }))
          this.render(data)
        })
        .catch(() => {
          this.deviceRows = []
          this.render([])
        })
    },
    render(data) {
      this.$nextTick(() => {
        const el = this.$refs.chart
        if (!el) return
        try {
          ensureMap()
          if (!this.chart) {
            this.chart = echarts.init(el)
            this.chart.on('click', params => {
              // 后端模式下行数据里没有分组节点，直接把省份行（name/value/color）抛给宿主
              if (params.data && params.data.value > 0) {
                this.$emit('province-select', params.data)
              }
            })
          }
          const hit = data.filter(item => item.value > 0)
          this.covered = hit.length
          this.total = hit.reduce((sum, item) => sum + item.value, 0)
          this.loaded = true
          this.chart.setOption({
            tooltip: {
              trigger: 'item',
              formatter: params => {
                const node = params.data && params.data.node
                const lines = [`<b>${params.name}</b>`]
                lines.push(`设备数：${params.value || 0}`)
                if (node && node.memberTotal !== node.memberCount) {
                  lines.push(`直属 ${node.memberCount} 台 / 含下级 ${node.memberTotal} 台`)
                }
                return lines.join('<br/>')
              }
            },
            series: [{
              type: 'map',
              map: 'china',
              roam: true,
              // 不留白边，地图撑满容器（容器内想再大也靠滚轮缩放）
              layoutCenter: ['60%', '60%'],
              layoutSize: '100%',
              zoom: 1,
              // 各省颜色已逐项指定（粉彩底 / 高亮蓝），不再用 visualMap 整体映射
              label: { show: false, color: '#1f2d3d', fontSize: 10 },
              // 底图样式：有没有数据都要能看到全国轮廓，别白底融进卡片里
              itemStyle: { areaColor: '#f2f6fc', borderColor: '#b8c4d6', borderWidth: 0.6 },
              emphasis: { label: { show: true }},
              data
            }]
          })
          // 容器尺寸晚于 init 稳定时（flex 布局），强制按当前尺寸重算一次画布
          this.chart.resize()
        } catch (e) {
          console.error('[ChinaMap] 渲染失败', e)
        }
      })
    }
  }
}
</script>

<style scoped>
.china-map-head {
  display: flex;
  align-items: center;
}
.china-map-title {
  font-weight: 600;
}
.china-map-summary {
  margin-left: 12px;
  color: #909399;
  font-size: 12px;
}
.china-map-chart {
  width: 100%;
}
/* 裸模式：地图撑满 + 右侧省份设备数清单 */
.china-map-bare {
  display: flex;
  align-items: center;
  width: 100%;
}
.china-map-side {
  flex: 0 0 auto;
  width: 40px;
  margin-left: 8px;
  padding: 2px 0 2px 8px;
  overflow-y: auto;
  align-self: stretch;
  display: flex;
  flex-direction: column;
  /* 从卡片顶部开始排，不再垂直居中 */
  justify-content: flex-start;
}
.china-map-side-row {
  display: flex;
  align-items: center;
  margin: 2px 0;
  font-size: 12px;
}
/* 拉长的胶囊色块，数量直接显示在里面（悬浮可看省名） */
.china-map-side-count {
  flex: 1 1 auto;
  height: 16px;
  line-height: 16px;
  border-radius: 3px;
  padding: 0 6px;
  color: #fff;
  font-size: 12px;
  text-align: center;
  overflow: hidden;
  box-shadow: inset 0 0 0 1px rgba(0, 0, 0, 0.04);
}
.china-map-side-hint {
  color: #c0c4cc;
  font-size: 12px;
  text-align: center;
}
</style>
