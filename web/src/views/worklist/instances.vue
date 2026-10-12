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
          <el-button type="success" icon="el-icon-s-promotion" @click="openExecute">下发工单</el-button>
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

    <el-dialog title="下发工单" :visible.sync="executeVisible" width="600px">
      <el-form :model="executeForm" label-width="110px" size="small">
        <el-form-item label="设备序列号" required>
          <el-input v-model="executeForm.sn" placeholder="必填" />
        </el-form-item>
        <el-form-item label="工单模板" required>
          <el-select v-model="executeForm.templateKey" filterable placeholder="选择模板" style="width:100%">
            <el-option
              v-for="t in templates"
              :key="t.key"
              :label="(t.name || t.key) + '（' + t.key + '）'"
              :value="t.key"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="操作人">
          <el-input v-model="executeForm.username" placeholder="可留空" />
        </el-form-item>
        <el-form-item label="入参">
          <el-input
            v-model="executeForm.paramsText"
            type="textarea"
            :rows="4"
            placeholder='JSON 格式，例如 {"username":"user1"}'
          />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="executeVisible = false">取消</el-button>
        <el-button type="primary" :loading="executeLoading" @click="submitExecute">下发</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import { fetchInstances, fetchInstance, fetchSteps, fetchTemplates, executeWorklist } from '@/api/worklist'

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
      templates: [],
      executeVisible: false,
      executeLoading: false,
      executeForm: { sn: '', templateKey: '', username: '', paramsText: '' },
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
    async openExecute() {
      this.executeVisible = true
      if (this.templates.length === 0) {
        try {
          const res = await fetchTemplates()
          this.templates = res.data || []
        } catch (e) {
          this.templates = []
        }
      }
    },
    async submitExecute() {
      if (!this.executeForm.sn || !this.executeForm.templateKey) {
        this.$message.warning('设备序列号与工单模板必填')
        return
      }
      let params = {}
      if (this.executeForm.paramsText && this.executeForm.paramsText.trim()) {
        try {
          params = JSON.parse(this.executeForm.paramsText)
        } catch (e) {
          this.$message.error('入参不是合法 JSON：' + e.message)
          return
        }
      }
      this.executeLoading = true
      try {
        const res = await executeWorklist({
          sn: this.executeForm.sn,
          templateKey: this.executeForm.templateKey,
          username: this.executeForm.username,
          params
        })
        const data = res.data || {}
        const wlId = data.worklistId
        // 工单是异步执行的，这里只表示受理，进度靠后面轮询
        this.$message.success('工单已受理，工单号 ' + wlId)
        this.executeVisible = false
        this.load()
        if (wlId) {
          this.trackWorklist(wlId)
        }
      } catch (e) {
        /* 拦截器已提示失败原因 */
      } finally {
        this.executeLoading = false
      }
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
