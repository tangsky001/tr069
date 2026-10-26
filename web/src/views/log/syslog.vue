<template>
  <div class="app-container">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      :title="'设备通过 UDP ' + port + ' 端口上报 syslog，按来源 IP 关联设备台账'"
      style="margin-bottom:12px"
    />
    <el-form :inline="true" size="small">
      <el-form-item label="设备">
        <el-input v-model="query.unitId" placeholder="unitId" clearable style="width:200px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" @click="handleSearch">查询</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="list" border stripe size="small">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="unitId" label="设备" min-width="200" show-overflow-tooltip />
      <el-table-column prop="ipAddress" label="来源IP" width="130" />
      <el-table-column prop="severity" label="级别" width="70" align="center">
        <template slot-scope="{ row }">
          <el-tag :type="severityType(row.severity)" size="mini">{{ row.severity }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="deviceTime" label="设备时间" width="170" />
      <el-table-column prop="tag" label="标识" width="140" />
      <el-table-column prop="content" label="内容" min-width="320" show-overflow-tooltip />
      <el-table-column prop="createTime" label="接收时间" width="170" />
    </el-table>

    <el-pagination
      style="margin-top:12px;text-align:right"
      :current-page="query.current"
      :page-size="query.size"
      :total="total"
      layout="total, prev, pager, next"
      @current-change="page => { query.current = page; load() }"
    />
  </div>
</template>

<script>
import { syslogLogs } from '@/api/log'

export default {
  name: 'SyslogLog',
  data() {
    return {
      loading: false,
      list: [],
      total: 0,
      port: 5141,
      query: { current: 1, size: 50, unitId: '' }
    }
  },
  created() { this.load() },
  methods: {
    load() {
      this.loading = true
      syslogLogs(this.query).then(res => {
        this.list = res.data.records || []
        this.total = res.data.total || 0
      }).finally(() => { this.loading = false })
    },
    handleSearch() { this.query.current = 1; this.load() },
    severityType(s) {
      if (s <= 3) return 'danger'
      if (s === 4) return 'warning'
      return 'info'
    }
  }
}
</script>

<style scoped>
.app-container { padding: 20px; }
</style>
