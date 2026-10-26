<template>
  <div class="app-container">
    <el-form :inline="true" size="small">
      <el-form-item label="设备">
        <el-input v-model="query.unitId" placeholder="unitId" clearable style="width:200px" />
      </el-form-item>
      <el-form-item label="方向">
        <el-select v-model="query.direction" clearable placeholder="全部" style="width:110px">
          <el-option label="收 IN" value="IN" />
          <el-option label="发 OUT" value="OUT" />
        </el-select>
      </el-form-item>
      <el-form-item label="方法">
        <el-select v-model="query.method" clearable placeholder="全部" style="width:180px">
          <el-option v-for="m in methods" :key="m" :label="m" :value="m" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" @click="handleSearch">查询</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="list" border stripe size="small">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="unitId" label="设备" min-width="200" show-overflow-tooltip />
      <el-table-column label="方向" width="70" align="center">
        <template slot-scope="{ row }">
          <el-tag :type="row.direction === 'IN' ? '' : 'success'" size="mini">{{ row.direction }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="method" label="方法" width="150" />
      <el-table-column prop="eventCodes" label="事件" width="130" />
      <el-table-column prop="faultCode" label="错误码" width="90" />
      <el-table-column prop="ipAddress" label="IP" width="130" />
      <el-table-column prop="costMs" label="耗时" width="80" />
      <el-table-column prop="createTime" label="时间" width="170" />
      <el-table-column label="报文" width="80">
        <template slot-scope="{ row }">
          <el-button type="text" size="mini" @click="showXml(row)">查看</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      style="margin-top:12px;text-align:right"
      :current-page="query.current"
      :page-size="query.size"
      :total="total"
      layout="total, prev, pager, next"
      @current-change="page => { query.current = page; load() }"
    />

    <el-dialog title="报文内容" :visible.sync="xmlVisible" width="70%">
      <pre style="white-space:pre-wrap;word-break:break-all;max-height:480px;overflow:auto">{{ xmlContent }}</pre>
    </el-dialog>
  </div>
</template>

<script>
import { cwmpLogs } from '@/api/log'

export default {
  name: 'CwmpLog',
  data() {
    return {
      loading: false,
      list: [],
      total: 0,
      query: { current: 1, size: 50, unitId: '', direction: '', method: '' },
      methods: ['Inform', 'GetParameterNames', 'GetParameterValues', 'SetParameterValues',
        'Download', 'Upload', 'Reboot', 'FactoryReset', 'AddObject', 'DeleteObject',
        'TransferComplete', 'AutonomousTransferComplete', 'GetRPCMethods', 'Empty'],
      xmlVisible: false,
      xmlContent: ''
    }
  },
  created() { this.load() },
  methods: {
    load() {
      this.loading = true
      cwmpLogs(this.query).then(res => {
        this.list = res.data.records || []
        this.total = res.data.total || 0
      }).finally(() => { this.loading = false })
    },
    handleSearch() { this.query.current = 1; this.load() },
    showXml(row) { this.xmlContent = row.xml || '(空)'; this.xmlVisible = true }
  }
}
</script>

<style scoped>
.app-container { padding: 20px; }
</style>
