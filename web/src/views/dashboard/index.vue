<template>
  <div v-loading="loading" class="app-container">
    <!-- 顶部统计：左半 OLT，右半光猫 -->
    <el-row :gutter="16" class="stat-row">
      <el-col v-for="card in statCards" :key="card.key" :xs="12" :sm="6" :md="6" :lg="3">
        <div class="stat-card">
          <div class="stat-icon" :class="card.cls"><i :class="card.icon" /></div>
          <div class="stat-body">
            <div class="stat-value">{{ card.value }}</div>
            <div class="stat-label">{{ card.label }}</div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 第一行：两张状态环形图 -->
    <el-row :gutter="16">
      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="panel">
          <div slot="header" class="panel-header">
            <span><i class="el-icon-monitor" /> OLT 状态分布</span>
            <el-button type="text" icon="el-icon-refresh" @click="load">刷新</el-button>
          </div>
          <div ref="oltChart" class="chart chart-donut" />
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="panel">
          <div slot="header" class="panel-header">
            <span><i class="el-icon-cpu" /> 光猫在线率</span>
            <el-button type="text" icon="el-icon-refresh" @click="load">刷新</el-button>
          </div>
          <div ref="ontChart" class="chart chart-donut" />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <!-- 左：OLT 设备表 + 实时事件 -->
      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="panel">
          <div slot="header" class="panel-header">
            <span>OLT 设备</span>
          </div>
          <div v-if="oltDevices.length === 0" class="empty">暂无 OLT，去「OLT 管理」添加设备</div>
          <el-table v-else :data="oltDevices" size="mini" :show-header="true" style="width: 100%">
            <el-table-column label="状态" width="70" align="center">
              <template slot-scope="{ row }">
                <span class="lamp" :class="lampCls(row.status)" />
              </template>
            </el-table-column>
            <el-table-column prop="name" label="名称" min-width="110" show-overflow-tooltip />
            <el-table-column prop="host" label="地址" min-width="110" show-overflow-tooltip />
            <el-table-column label="登录方式" width="80" align="center">
              <template slot-scope="{ row }">
                <el-tag size="mini" :type="row.protocol === 'SSH' ? 'warning' : 'info'">
                  {{ row.protocolText }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="厂商" width="70" align="center">
              <template slot-scope="{ row }">{{ row.vendorText }}</template>
            </el-table-column>
            <el-table-column label="最近同步" width="150">
              <template slot-scope="{ row }">
                <span :class="{ dim: !row.lastSyncAt }">{{ row.lastSyncAt || '未同步' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="离线原因" min-width="130">
              <template slot-scope="{ row }">
                <el-tooltip
                  v-if="row.status === 'OFFLINE' && row.lastError"
                  :content="row.lastError"
                  placement="top"
                >
                  <span class="bad reason">{{ row.lastError }}</span>
                </el-tooltip>
                <span v-else-if="row.status === 'OFFLINE'" class="dim">未知原因</span>
                <span v-else class="dim">—</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never" class="panel">
          <div slot="header" class="panel-header">
            <span><i class="el-icon-bell" /> 实时事件（在线 / 离线）</span>
            <el-button v-if="events.length" type="text" icon="el-icon-delete" @click="events = []">清空</el-button>
          </div>
          <div v-if="events.length === 0" class="empty">暂无事件，设备上下线会实时推送到这里</div>
          <div v-for="(event, index) in events" :key="event.time + '-' + index" class="event-row">
            <span class="lamp" :class="event.status === 'online' ? 'on' : 'off'" />
            <span class="event-time">{{ event.timeText }}</span>
            <span class="event-name" :title="event.host">{{ event.name }}</span>
            <el-tag size="mini" :type="event.status === 'online' ? 'success' : 'danger'">
              {{ event.statusText }}
            </el-tag>
            <span v-if="event.reason" class="bad reason" :title="event.reason">{{ event.reason }}</span>
          </div>
        </el-card>
      </el-col>

      <!-- 右：光猫按设备分布条形图 + 摘要 -->
      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="panel">
          <div slot="header" class="panel-header">
            <span>光猫分布（按 OLT）</span>
          </div>
          <div v-if="ontByOlt.length === 0" class="empty">暂无光猫数据，同步 OLT 后生成</div>
          <div ref="ontBarChart" :class="['chart', ontByOlt.length > 0 ? 'chart-bar' : 'hidden']" />
        </el-card>

        <el-card shadow="never" class="panel">
          <div slot="header" class="panel-header">
            <span>OLT 同步摘要</span>
          </div>
          <div v-if="ontByOlt.length === 0" class="empty">暂无数据</div>
          <div v-for="row in ontByOlt" :key="'sum-' + row.oltId" class="kv-row">
            <span>{{ row.name }}（{{ row.vendorText }}）</span>
            <span>
              <span class="lamp" :class="lampCls(row.status)" />
              <b class="ok">{{ row.online }}</b> 在线 / {{ row.total }} 台
            </span>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script>
import * as echarts from 'echarts'
import { fetchOverview } from '@/api/dashboard'
import { createDashboardSocket } from '@/utils/dashboardSocket'

const COLOR_ONLINE = '#67c23a'
const COLOR_OFFLINE = '#f56c6c'
const COLOR_UNKNOWN = '#c0c4cc'

export default {
  name: 'Dashboard',
  data() {
    return {
      loading: false,
      closeSocket: null,
      events: [],
      olt: { total: 0, online: 0, offline: 0, unknown: 0, devices: [] },
      ont: { total: 0, online: 0, offline: 0, byOlt: [] }
    }
  },
  computed: {
    statCards() {
      return [
        { key: 'olt-total', value: this.olt.total, label: 'OLT 总数', icon: 'el-icon-monitor', cls: 'total' },
        { key: 'olt-online', value: this.olt.online, label: 'OLT 在线', icon: 'el-icon-success', cls: 'online' },
        { key: 'olt-offline', value: this.olt.offline, label: 'OLT 离线', icon: 'el-icon-error', cls: 'offline' },
        { key: 'olt-unknown', value: this.olt.unknown, label: '未采集', icon: 'el-icon-question', cls: 'unknown' },
        { key: 'ont-total', value: this.ont.total, label: '光猫总数', icon: 'el-icon-cpu', cls: 'brand' },
        { key: 'ont-online', value: this.ont.online, label: '光猫在线', icon: 'el-icon-circle-check', cls: 'online' },
        { key: 'ont-offline', value: this.ont.offline, label: '光猫离线', icon: 'el-icon-remove-outline', cls: 'offline' }
      ]
    },
    oltDevices() {
      return this.olt.devices || []
    },
    ontByOlt() {
      return this.ont.byOlt || []
    },
    ontRate() {
      return this.ont.onlineRate || 0
    },
    oltRate() {
      return this.olt.onlineRate || 0
    }
  },
  created() {
    this.load()
    this.closeSocket = createDashboardSocket(this.onWsMessage)
  },
  async mounted() {
    await this.$nextTick()
    this.initCharts()
    this.updateCharts()
    this.resizeHandler = () => {
      Object.values(this.charts).forEach(chart => chart && chart.resize())
    }
    window.addEventListener('resize', this.resizeHandler)
  },
  beforeDestroy() {
    if (this.closeSocket) {
      this.closeSocket()
    }
    window.removeEventListener('resize', this.resizeHandler)
    Object.values(this.charts).forEach(chart => chart && chart.dispose())
  },
  methods: {
    initCharts() {
      this.charts = {
        olt: echarts.init(this.$refs.oltChart),
        ont: echarts.init(this.$refs.ontChart),
        ontBar: echarts.init(this.$refs.ontBarChart)
      }
    },
    updateCharts() {
      // OLT 状态环形图
      if (this.charts.olt) {
        this.charts.olt.setOption({
          tooltip: { trigger: 'item', formatter: '{b}: {c} 台 ({d}%)' },
          legend: { bottom: 0, icon: 'circle', itemWidth: 10, itemHeight: 10 },
          title: {
            text: this.oltRate + '%',
            subtext: '在线率',
            left: 'center',
            top: '38%',
            textStyle: { fontSize: 26, fontWeight: 600, color: '#303133' },
            subtextStyle: { fontSize: 13, color: '#909399' }
          },
          series: [{
            type: 'pie',
            radius: ['55%', '78%'],
            center: ['50%', '45%'],
            label: { show: false },
            itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
            data: [
              { name: '在线', value: this.olt.online, itemStyle: { color: COLOR_ONLINE }},
              { name: '离线', value: this.olt.offline, itemStyle: { color: COLOR_OFFLINE }},
              { name: '未采集', value: this.olt.unknown, itemStyle: { color: COLOR_UNKNOWN }}
            ].filter(item => item.value > 0)
          }]
        })
      }
      // 光猫在线率环形图
      if (this.charts.ont) {
        this.charts.ont.setOption({
          tooltip: { trigger: 'item', formatter: '{b}: {c} 台 ({d}%)' },
          legend: { bottom: 0, icon: 'circle', itemWidth: 10, itemHeight: 10 },
          title: {
            text: this.ontRate + '%',
            subtext: '在线率',
            left: 'center',
            top: '38%',
            textStyle: { fontSize: 26, fontWeight: 600, color: '#303133' },
            subtextStyle: { fontSize: 13, color: '#909399' }
          },
          series: [{
            type: 'pie',
            radius: ['55%', '78%'],
            center: ['50%', '45%'],
            label: { show: false },
            itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
            data: [
              { name: '在线', value: this.ont.online, itemStyle: { color: COLOR_ONLINE }},
              { name: '离线', value: this.ont.offline, itemStyle: { color: COLOR_OFFLINE }}
            ].filter(item => item.value > 0)
          }]
        })
      }
      // 光猫按 OLT 堆叠条形图
      if (this.charts.ontBar) {
        const rows = this.ontByOlt
        this.charts.ontBar.setOption({
          tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }},
          legend: { bottom: 0, icon: 'circle', itemWidth: 10, itemHeight: 10 },
          grid: { left: 8, right: 24, top: 8, bottom: 32, containLabel: true },
          xAxis: { type: 'value', splitLine: { lineStyle: { type: 'dashed' }}},
          yAxis: {
            type: 'category',
            data: rows.map(r => r.name),
            axisLabel: { width: 90, overflow: 'truncate' }
          },
          series: [
            {
              name: '在线',
              type: 'bar',
              stack: 'ont',
              barMaxWidth: 18,
              itemStyle: { color: COLOR_ONLINE, borderRadius: [4, 0, 0, 4] },
              data: rows.map(r => r.online)
            },
            {
              name: '离线',
              type: 'bar',
              stack: 'ont',
              barMaxWidth: 18,
              itemStyle: { color: COLOR_OFFLINE, borderRadius: [0, 4, 4, 0] },
              data: rows.map(r => r.offline)
            }
          ]
        }, true)
      }
    },
    async load() {
      this.loading = true
      try {
        const res = await fetchOverview()
        const data = res.data || {}
        this.olt = data.olt || this.olt
        this.ont = data.ont || this.ont
      } catch (e) {
        // 接口失败保持现有数据，置空兜底
        this.olt = { total: 0, online: 0, offline: 0, unknown: 0, devices: [] }
        this.ont = { total: 0, online: 0, offline: 0, byOlt: [] }
      } finally {
        this.loading = false
        this.$nextTick(this.updateCharts)
      }
    },
    onWsMessage(message) {
      if (!message || message.type !== 'olt-status') {
        return
      }
      const data = message.data || {}
      // 事件流：最新在上，最多留 30 条；字段全部由后端算好，前端只渲染
      this.events.unshift({
        status: data.status,
        statusText: data.statusText,
        name: data.name,
        host: data.host,
        reason: data.reason,
        timeText: data.timeText
      })
      if (this.events.length > 30) {
        this.events.length = 30
      }
      // 局部更新设备行状态，再整体刷新统计数字与图表
      const row = (this.olt.devices || []).find(d => d.id === data.deviceId)
      if (row) {
        row.status = data.deviceStatus || row.status
        row.statusText = data.statusText
        row.lastError = data.reason || row.lastError
        if (data.status === 'offline') {
          row.offlineAt = data.timeText
        }
      }
      this.load()
    },
    lampCls(status) {
      return status === 'ONLINE' ? 'on' : status === 'OFFLINE' ? 'off' : 'unknown'
    }
  }
}
</script>

<style scoped>
.stat-row {
  margin-bottom: 16px;
}
.stat-card {
  display: flex;
  align-items: center;
  padding: 16px;
  margin-bottom: 16px;
  background: #fff;
  border-radius: 4px;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
}
.stat-icon {
  width: 48px;
  height: 48px;
  margin-right: 12px;
  border-radius: 4px;
  color: #fff;
  font-size: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.stat-icon.total { background: #409eff; }
.stat-icon.online { background: #67c23a; }
.stat-icon.offline { background: #909399; }
.stat-icon.unknown { background: #c0c4cc; }
.stat-icon.brand { background: #8e44ad; }
.stat-value {
  font-size: 24px;
  font-weight: 600;
  line-height: 1.2;
}
.stat-label {
  color: #909399;
  font-size: 13px;
}
.panel {
  margin-bottom: 16px;
}
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}
.chart {
  width: 100%;
}
.chart-donut {
  height: 260px;
}
.chart-bar {
  height: 240px;
}
.hidden {
  display: none;
}
.kv-row {
  display: flex;
  justify-content: space-between;
  padding: 6px 0;
  font-size: 14px;
  color: #606266;
}
.kv-row b {
  color: #303133;
}
.event-row {
  display: flex;
  align-items: center;
  padding: 7px 0;
  border-bottom: 1px dashed #ebeef5;
  font-size: 13px;
}
.event-row:last-child {
  border-bottom: none;
}
.event-time {
  width: 150px;
  color: #909399;
  flex-shrink: 0;
}
.event-name {
  width: 130px;
  color: #303133;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex-shrink: 0;
}
.reason {
  margin-left: 8px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.lamp {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
}
.lamp.on { background: #67c23a; box-shadow: 0 0 4px rgba(103, 194, 58, 0.8); }
.lamp.off { background: #f56c6c; box-shadow: 0 0 4px rgba(245, 108, 108, 0.8); }
.lamp.unknown { background: #c0c4cc; }
.ok { color: #67c23a !important; }
.bad { color: #f56c6c !important; }
.dim { color: #c0c4cc; }
.empty {
  color: #909399;
  font-size: 13px;
  padding: 12px 0;
}
</style>
