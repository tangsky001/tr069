<template>
  <div class="app-container">
    <el-table v-loading="loading" :data="list" border stripe size="small">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="operatorId" label="操作人ID" width="100" />
      <el-table-column prop="action" label="动作" width="180" />
      <el-table-column prop="target" label="目标" min-width="200" show-overflow-tooltip />
      <el-table-column prop="detail" label="详情" min-width="260" show-overflow-tooltip />
      <el-table-column prop="ip" label="IP" width="130" />
      <el-table-column prop="createTime" label="时间" width="170" />
    </el-table>

    <el-pagination
      style="margin-top:12px;text-align:right"
      :current-page="current"
      :page-size="size"
      :total="total"
      layout="total, prev, pager, next"
      @current-change="page => { current = page; load() }"
    />
  </div>
</template>

<script>
import { auditLogs } from '@/api/log'

export default {
  name: 'AuditLog',
  data() {
    return {
      loading: false,
      list: [],
      total: 0,
      current: 1,
      size: 50
    }
  },
  created() { this.load() },
  methods: {
    load() {
      this.loading = true
      auditLogs({ current: this.current, size: this.size }).then(res => {
        this.list = res.data.records || []
        this.total = res.data.total || 0
      }).finally(() => { this.loading = false })
    }
  }
}
</script>

<style scoped>
.app-container { padding: 20px; }
</style>
