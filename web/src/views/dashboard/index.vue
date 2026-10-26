<template>
  <div class="dashboard-container">
    <el-row :gutter="16">
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-title">设备总数</div>
          <div class="stat-value">{{ summary.deviceTotal || 0 }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-title">在线设备</div>
          <div class="stat-value success">{{ summary.deviceOnline || 0 }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-title">待执行任务</div>
          <div class="stat-value warning">{{ pendingTotal }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-title">适配模板</div>
          <div class="stat-value">{{ summary.templateCount || 0 }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card style="margin-top:16px" shadow="never">
      <div slot="header">最近上线设备</div>
      <el-table :data="recent" border stripe size="small">
        <el-table-column prop="unitId" label="设备标识" min-width="220" />
        <el-table-column prop="serialNumber" label="序列号" min-width="160" />
        <el-table-column prop="model" label="型号" width="140" />
        <el-table-column prop="firmwareVersion" label="固件版本" width="140" />
        <el-table-column prop="ipAddress" label="IP" width="130" />
        <el-table-column label="在线" width="80" align="center">
          <template slot-scope="{ row }">
            <el-tag :type="row.online === 1 ? 'success' : 'info'" size="mini">
              {{ row.online === 1 ? '在线' : '离线' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastOnline" label="最后上线" width="170" />
      </el-table>
    </el-card>
  </div>
</template>

<script>
import { summary, recentDevices } from '@/api/dashboard'

export default {
  name: 'Dashboard',
  data() {
    return {
      summary: {},
      recent: []
    }
  },
  computed: {
    pendingTotal() {
      const t = this.summary.tasks || {}
      return (t.PENDING || 0) + (t.SENDING || 0) + (t.WAIT_RESULT || 0)
    }
  },
  created() {
    this.load()
  },
  methods: {
    load() {
      summary().then(res => { this.summary = res.data || {} }).catch(() => {})
      recentDevices().then(res => { this.recent = res.data || [] }).catch(() => {})
    }
  }
}
</script>

<style lang="scss" scoped>
.dashboard-container { margin: 20px; }
.stat-title { font-size: 13px; color: #909399; }
.stat-value { font-size: 26px; font-weight: 600; margin-top: 6px; }
.success { color: #67c23a; }
.warning { color: #e6a23c; }
</style>
