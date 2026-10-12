<template>
  <div class="app-container">
    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" :model="query" size="small">
        <el-form-item label="序列号">
          <el-input v-model="query.sn" placeholder="支持模糊匹配" clearable style="width:180px" />
        </el-form-item>
        <el-form-item label="运营商">
          <el-select v-model="query.isp" clearable placeholder="全部" style="width:140px">
            <el-option label="电信 CT" value="CT" />
            <el-option label="联通 CU" value="CU" />
            <el-option label="移动 CMCC" value="CMCC" />
            <el-option label="通用 standard" value="standard" />
          </el-select>
        </el-form-item>
        <el-form-item label="设备类型">
          <el-input v-model="query.deviceType" placeholder="如 ADSL_4+1" clearable style="width:160px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="handleSearch">查询</el-button>
          <el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
      <div class="online-bar">
        当前在线会话：<b>{{ onlineCount }}</b> 台
        <el-button type="text" icon="el-icon-refresh" @click="loadOnline">刷新</el-button>
        <el-button type="text" @click="onlineVisible = true">查看明细</el-button>
      </div>
    </el-card>

    <el-table v-loading="loading" :data="list" border fit highlight-current-row style="width:100%">
      <el-table-column label="序列号" prop="sn" min-width="180" show-overflow-tooltip />
      <el-table-column label="厂商" prop="manufacturer" width="120" show-overflow-tooltip />
      <el-table-column label="OUI" prop="oui" width="90" />
      <el-table-column label="产品型号" prop="productClass" width="130" show-overflow-tooltip />
      <el-table-column label="软件版本" prop="softwareVersion" width="140" show-overflow-tooltip />
      <el-table-column label="运营商" width="110">
        <template slot-scope="{ row }">
          <span>{{ row.isp }} {{ row.ispVersion }}</span>
        </template>
      </el-table-column>
      <el-table-column label="设备类型" prop="deviceType" width="120" />
      <el-table-column label="最近上报" width="170">
        <template slot-scope="{ row }">
          <span>{{ formatTime(row.lastContactTime) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template slot-scope="{ row }">
          <el-button type="text" size="mini" @click="goDetail(row.sn)">详情</el-button>
          <el-button type="text" size="mini" @click="openBiz(row.sn)">下发业务</el-button>
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

    <el-dialog title="在线会话明细" :visible.sync="onlineVisible" width="900px">
      <el-table :data="onlineList" border size="small" max-height="460">
        <el-table-column label="序列号" prop="sn" min-width="180" show-overflow-tooltip />
        <el-table-column label="OUI" prop="oui" width="90" />
        <el-table-column label="状态" prop="state" width="110" />
        <el-table-column label="软件版本" prop="softwareVersion" width="130" show-overflow-tooltip />
        <el-table-column label="待发消息" prop="pendingCount" width="90" />
        <el-table-column label="最近上报" min-width="170">
          <template slot-scope="{ row }">
            <span>{{ formatTime(row.lastContactTime) }}</span>
          </template>
        </el-table-column>
      </el-table>
      <span slot="footer">
        <el-button @click="onlineVisible = false">关闭</el-button>
      </span>
    </el-dialog>

    <el-dialog title="下发业务模板" :visible.sync="bizVisible" width="620px">
      <el-form label-width="110px" size="small">
        <el-form-item label="设备序列号">
          <span class="sn-text">{{ bizForm.sn }}</span>
        </el-form-item>
        <el-form-item label="业务模板" required>
          <el-select v-model="bizForm.templateId" filterable placeholder="选择业务模板" style="width:100%">
            <el-option v-for="t in bizTemplates" :key="t.template.id" :label="t.template.name" :value="t.template.id">
              <span>{{ t.template.name }}</span>
              <span class="member-hint">{{ (t.items || []).length }} 个配置模板</span>
            </el-option>
          </el-select>
        </el-form-item>
      </el-form>

      <div v-if="bizProgress.id" class="biz-progress">
        <div>
          状态：<el-tag size="mini" :type="bizStatusType(bizProgress.status)">{{ bizStatusText(bizProgress.status) }}</el-tag>
          进度：{{ bizProgress.finishedCount || 0 }} / {{ bizProgress.totalCount || 0 }}
        </div>
        <el-table :data="bizItems" border size="small" max-height="240" style="margin-top:8px">
          <el-table-column label="配置模板" prop="templateKey" min-width="260" show-overflow-tooltip />
          <el-table-column label="结果" width="90">
            <template slot-scope="{ row }">
              <el-tag size="mini" :type="row.success ? 'success' : 'danger'">{{ row.success ? '成功' : '失败' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="说明" prop="error" min-width="150" show-overflow-tooltip />
        </el-table>
      </div>

      <span slot="footer">
        <el-button @click="closeBiz">关闭</el-button>
        <el-button type="primary" :loading="bizExecuting" @click="doBizExecute">开始下发</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import { fetchDevicePage, fetchOnlineDevices } from '@/api/device'
import { fetchBizTemplates, executeBizTemplate, fetchBizExecution } from '@/api/bizTemplate'

export default {
  name: 'DeviceList',
  data() {
    return {
      loading: false,
      list: [],
      total: 0,
      onlineCount: 0,
      onlineList: [],
      onlineVisible: false,
      bizVisible: false,
      bizExecuting: false,
      bizTemplates: [],
      bizForm: { sn: '', templateId: null },
      bizProgress: {},
      bizTimer: null,
      query: {
        page: 1,
        size: 20,
        sn: '',
        isp: '',
        deviceType: ''
      }
    }
  },
  created() {
    this.load()
    this.loadOnline()
  },
  methods: {
    async load() {
      this.loading = true
      try {
        const res = await fetchDevicePage(this.query)
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
    async loadOnline() {
      try {
        const res = await fetchOnlineDevices()
        this.onlineList = res.data || []
        this.onlineCount = this.onlineList.length
      } catch (e) {
        this.onlineList = []
        this.onlineCount = 0
      }
    },
    handleSearch() {
      this.query.page = 1
      this.load()
    },
    resetQuery() {
      this.query = { page: 1, size: 20, sn: '', isp: '', deviceType: '' }
      this.load()
    },
    goDetail(sn) {
      this.$router.push({ name: 'DeviceDetail', params: { sn }})
    },

    // ---------------- 下发业务模板 ----------------
    async openBiz(sn) {
      this.bizForm = { sn, templateId: null }
      this.bizProgress = {}
      this.bizVisible = true
      if (this.bizTemplates.length === 0) {
        try {
          const res = await fetchBizTemplates()
          this.bizTemplates = res.data || []
        } catch (e) {
          this.bizTemplates = []
        }
      }
    },
    async doBizExecute() {
      if (!this.bizForm.templateId) {
        this.$message.warning('请选择业务模板')
        return
      }
      this.bizExecuting = true
      try {
        const res = await executeBizTemplate({
          templateId: this.bizForm.templateId,
          sn: this.bizForm.sn
        })
        this.$message.success('已提交下发')
        this.startBizPolling(res.data.executionId)
      } catch (e) {
        this.$message.error((e && e.message) || '下发失败')
      } finally {
        this.bizExecuting = false
      }
    },
    startBizPolling(id) {
      this.stopBizPolling()
      const tick = async() => {
        try {
          const res = await fetchBizExecution(id)
          this.bizProgress = res.data || {}
          if (this.bizProgress.status !== 'running') {
            this.stopBizPolling()
          }
        } catch (e) {
          this.stopBizPolling()
        }
      }
      tick()
      this.bizTimer = setInterval(tick, 3000)
    },
    stopBizPolling() {
      if (this.bizTimer) {
        clearInterval(this.bizTimer)
        this.bizTimer = null
      }
    },
    closeBiz() {
      this.stopBizPolling()
      this.bizVisible = false
    },
    bizStatusText(status) {
      const map = { running: '执行中', success: '全部成功', partial: '部分成功', fail: '失败' }
      return map[status] || status || '-'
    },
    bizStatusType(status) {
      const map = { running: '', success: 'success', partial: 'warning', fail: 'danger' }
      return map[status] || 'info'
    },
    formatTime(value) {
      if (!value) return '-'
      return String(value).replace('T', ' ')
    }
  },
  computed: {
    bizItems() {
      const raw = this.bizProgress.result
      if (!raw) return []
      try {
        return JSON.parse(raw)
      } catch (e) {
        return []
      }
    }
  },
  beforeDestroy() {
    this.stopBizPolling()
  }
}
</script>

<style scoped>
.filter-card {
  margin-bottom: 16px;
}
.online-bar {
  color: #606266;
  font-size: 13px;
}
.pager {
  margin-top: 16px;
  text-align: right;
}
.sn-text {
  color: #303133;
  font-weight: 600;
}
.member-hint {
  float: right;
  color: #8492a6;
  font-size: 12px;
}
.biz-progress {
  border-top: 1px solid #ebeef5;
  padding-top: 10px;
  font-size: 13px;
  color: #606266;
}
</style>
