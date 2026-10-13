<template>
  <div class="app-container">
    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" size="small">
        <el-form-item label="序列号">
          <el-input v-model="query.sn" clearable placeholder="精确匹配" style="width:180px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width:140px">
            <el-option v-for="s in statusOptions" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="handleSearch">查询</el-button>
          <el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-table v-loading="loading" :data="list" border fit style="width:100%">
      <el-table-column label="ID" prop="id" width="70" />
      <el-table-column label="工单名称" prop="worklistName" min-width="150" show-overflow-tooltip />
      <el-table-column label="序列号" prop="sn" min-width="170" show-overflow-tooltip />
      <el-table-column label="运营商" width="120">
        <template slot-scope="{ row }">
          <span>{{ row.isp }} {{ row.ispVersion }}</span>
        </template>
      </el-table-column>
      <el-table-column label="业务域" prop="domain" width="110" />
      <el-table-column label="状态" width="100">
        <template slot-scope="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作人" prop="username" width="100" />
      <el-table-column label="创建时间" min-width="160">
        <template slot-scope="{ row }">
          <span>{{ formatTime(row.timeInit) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="完成时间" min-width="160">
        <template slot-scope="{ row }">
          <span>{{ formatTime(row.timeExecFinish) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template slot-scope="{ row }">
          <el-button type="text" size="mini" @click="viewSteps(row)">步骤</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      :current-page.sync="query.page"
      :page-size.sync="query.size"
      :page-sizes="[10, 20, 50, 100]"
      :total="total"
      layout="total, sizes, prev, pager, next, jumper"
      @size-change="handleSearch"
      @current-change="load"
    />

    <el-dialog title="执行步骤" :visible.sync="stepsVisible" width="1000px">
      <el-table :data="steps" border size="small" max-height="500">
        <el-table-column label="#" prop="stepIndex" width="50" />
        <el-table-column label="说明" prop="comment" min-width="140" show-overflow-tooltip />
        <el-table-column label="方法" prop="rpc" width="170" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template slot-scope="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时(ms)" prop="durationMs" width="90" />
        <el-table-column label="错误信息" prop="errorMsg" min-width="160" show-overflow-tooltip />
        <el-table-column label="明细" width="80" fixed="right">
          <template slot-scope="{ row }">
            <el-button type="text" size="mini" @click="showStepDetail(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
      <span slot="footer">
        <el-button @click="stepsVisible = false">关闭</el-button>
      </span>
    </el-dialog>

    <el-dialog title="步骤明细" :visible.sync="stepDetailVisible" width="720px">
      <div class="block-title">下发参数</div>
      <pre>{{ formatJson(stepDetail.requestArgs) }}</pre>
      <div class="block-title">设备响应</div>
      <pre>{{ formatJson(stepDetail.responseContent) }}</pre>
      <span slot="footer">
        <el-button @click="stepDetailVisible = false">关闭</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import { fetchInstances, fetchInstance, fetchSteps } from '@/api/worklist'

const STATUS_OPTIONS = ['build', 'bind', 'reserve', 'standby', 'running', 'success', 'fail', 'abnormal', 'expire']

export default {
  name: 'WorklistInstances',
  data() {
    return {
      loading: false,
      list: [],
      total: 0,
      statusOptions: STATUS_OPTIONS,
      query: { page: 1, size: 20, sn: '', status: '' },
      steps: [],
      stepsVisible: false,
      stepDetail: {},
      stepDetailVisible: false,
      // 已提交、正在跟踪执行进度的工单号
      tracking: []
    }
  },
  created() {
    this.load()
  },
  methods: {
    async load() {
      this.loading = true
      try {
        const res = await fetchInstances(this.query)
        const data = res.data || {}
        this.list = data.items || []
        this.total = data.total || 0
      } catch (e) {
        this.list = []
        this.total = 0
      } finally {
        this.loading = false
      }
    },
    handleSearch() {
      this.query.page = 1
      this.load()
    },
    resetQuery() {
      this.query = { page: 1, size: 20, sn: '', status: '' }
      this.load()
    },
    async viewSteps(row) {
      try {
        const res = await fetchSteps(row.id)
        this.steps = res.data || []
        this.stepsVisible = true
      } catch (e) {
        /* 拦截器已提示 */
      }
    },
    showStepDetail(row) {
      this.stepDetail = row
      this.stepDetailVisible = true
    },
    /** 跟踪一张工单直到它结束，期间定期刷新列表让进度可见 */
    async trackWorklist(id) {
      if (this.tracking.indexOf(id) >= 0) {
        return
      }
      this.tracking.push(id)
      let finished = false
      let result = ''
      // 每 3 秒查一次，最多约 10 分钟
      for (let i = 0; i < 200; i++) {
        await this.sleep(3000)
        try {
          const res = await fetchInstance(id)
          const record = res.data
          if (record && record.status) {
            result = record.status
            if (record.status === 'success' || record.status === 'fail' || record.status === 'abnormal') {
              finished = true
              break
            }
          }
        } catch (e) {
          /* 单次查询失败继续等 */
        }
        this.load()
      }
      this.tracking = this.tracking.filter(x => x !== id)
      this.load()
      if (finished) {
        if (result === 'success') {
          this.$message.success('工单 ' + id + ' 执行成功')
        } else {
          this.$message.warning('工单 ' + id + ' 执行结束，状态 ' + result)
        }
      } else {
        this.$message.info('工单 ' + id + ' 仍在执行，稍后可刷新查看')
      }
    },
    sleep(ms) {
      return new Promise(resolve => setTimeout(resolve, ms))
    },
    statusType(status) {
      if (status === 'success') return 'success'
      if (status === 'fail' || status === 'abnormal') return 'danger'
      if (status === 'running') return 'primary'
      return 'info'
    },
    formatTime(value) {
      if (!value) return '-'
      return String(value).replace('T', ' ')
    },
    formatJson(value) {
      if (!value) return '-'
      if (typeof value === 'string') {
        try {
          return JSON.stringify(JSON.parse(value), null, 2)
        } catch (e) {
          return value
        }
      }
      try {
        return JSON.stringify(value, null, 2)
      } catch (e) {
        return String(value)
      }
    }
  }
}
</script>

<style scoped>
.filter-card {
  margin-bottom: 16px;
}
.pager {
  margin-top: 16px;
  text-align: right;
}
.block-title {
  margin: 8px 0 4px;
  color: #909399;
  font-size: 13px;
}
pre {
  max-height: 240px;
  overflow: auto;
  background: #f5f7fa;
  padding: 12px;
  border-radius: 4px;
  margin: 0;
}
</style>
