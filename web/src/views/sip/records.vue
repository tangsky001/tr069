<template>
  <div class="app-container">
    <el-card shadow="never">
      <div slot="header" class="card-header">
        <span>通话记录</span>
        <div>
          <el-input
            v-model="query.ext"
            placeholder="按分机号过滤"
            size="mini"
            clearable
            style="width:160px;margin-right:8px"
            @keyup.enter.native="loadRecords(1)"
            @clear="loadRecords(1)"
          />
          <el-button size="mini" @click="loadRecords(1)">查询</el-button>
        </div>
      </div>
      <el-table v-loading="loading" :data="records" border size="small">
        <el-table-column label="主叫" prop="caller" width="110" align="center" />
        <el-table-column label="被叫" prop="callee" width="110" align="center" />
        <el-table-column label="开始时间" width="150" align="center">
          <template slot-scope="{ row }">{{ row.startedAt || '-' }}</template>
        </el-table-column>
        <el-table-column label="是否接通" width="100" align="center">
          <template slot-scope="{ row }">
            <el-tag size="mini" :type="row.answered ? 'success' : 'info'">
              {{ row.answered ? '已接通' : '未接通' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="通话时长" width="110" align="center">
          <template slot-scope="{ row }">
            {{ row.answered ? formatDuration(row.durationSeconds) : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="结束原因" prop="endReason" width="140">
          <template slot-scope="{ row }">{{ row.endReason || '-' }}</template>
        </el-table-column>
        <el-table-column label="录音" width="90" align="center">
          <template slot-scope="{ row }">
            <el-button v-if="row.recordPath" type="text" size="mini" @click="onDownload(row)">下载</el-button>
            <span v-else class="tip-line">无</span>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        style="margin-top:10px;text-align:right"
        :current-page.sync="query.page"
        :page-size="query.size"
        :total="total"
        layout="total, prev, pager, next, sizes"
        :page-sizes="[10, 20, 50]"
        @current-change="loadRecords"
        @size-change="loadRecords(1)"
      />
    </el-card>
  </div>
</template>

<script>
import { fetchSipRecords, downloadSipRecord } from '@/api/sip'

/** 通话记录：分机互打自动落库，支持过滤、分页与录音下载 */
export default {
  name: 'SipRecords',
  data() {
    return {
      records: [],
      total: 0,
      loading: false,
      query: { page: 1, size: 20, ext: '' }
    }
  },
  created() {
    this.loadRecords()
  },
  methods: {
    loadRecords(page) {
      if (page) {
        this.query.page = page
      }
      this.loading = true
      fetchSipRecords({ page: this.query.page, size: this.query.size, ext: this.query.ext || undefined })
        .then(res => {
          const r = res.data || {}
          this.records = r.list || []
          this.total = r.total || 0
        })
        .catch(() => {})
        .then(() => {
          this.loading = false
        })
    },
    /** 下载录音文件 */
    onDownload(row) {
      downloadSipRecord(row.id).then(res => {
        const blob = new Blob([res.data], { type: 'audio/wav' })
        const link = document.createElement('a')
        link.href = URL.createObjectURL(blob)
        link.download = `通话录音-${row.caller}-${row.callee}-${(row.startedAt || '').replace(/[- :]/g, '')}.wav`
        link.click()
        URL.revokeObjectURL(link.href)
      }).catch(() => {})
    },
    formatDuration(sec) {
      const pad = n => String(n).padStart(2, '0')
      const m = Math.floor(sec / 60)
      return pad(m) + ':' + pad(sec % 60)
    }
  }
}
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.tip-line {
  color: #909399;
  font-size: 12px;
}
</style>
