<template>
  <div class="app-container">
    <el-card shadow="never" class="desc-card">
      <div class="desc">
        业务模板是指一个完整的业务流程所需要的模板配置集合，一个业务模板包含多个配置模板。
        选好业务模板后对设备整包下发，成员按编排顺序串行执行。
      </div>
      <div class="action-bar">
        <el-button type="primary" icon="el-icon-plus" size="small" @click="openCreate">新增业务模板</el-button>
        <el-input v-model="keyword" size="small" placeholder="名称 / 分组" clearable style="width:220px" @keyup.enter.native="load" />
        <el-button size="small" icon="el-icon-search" @click="load">搜索</el-button>
        <el-button size="small" icon="el-icon-refresh" @click="load">刷新</el-button>
      </div>
    </el-card>

    <el-table v-loading="loading" :data="list" border fit size="small" style="width:100%">
      <el-table-column label="ID" prop="id" width="70" />
      <el-table-column label="名称 / 备注分组" min-width="220">
        <template slot-scope="{ row }">
          <div class="cell-main">{{ row.template.name }}</div>
          <div class="cell-sub">{{ row.template.groupName || '-' }}<span v-if="row.template.remark"> · {{ row.template.remark }}</span></div>
        </template>
      </el-table-column>
      <el-table-column label="配置模板成员" min-width="420">
        <template slot-scope="{ row }">
          <template v-if="row.items.length === 0">
            <span class="cell-sub">未配置成员</span>
          </template>
          <template v-else>
            <el-tooltip
              v-for="item in row.items"
              :key="item.id"
              :content="item.templateKey"
              placement="top"
            >
              <el-tag
                size="mini"
                :type="item.available ? (item.executable ? 'success' : 'warning') : 'danger'"
                class="member-tag"
              >
                {{ item.categoryLabel || '模板' }} {{ item.templateName }}
              </el-tag>
            </el-tooltip>
          </template>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="row.template.enabled ? 'success' : 'info'">
            {{ row.template.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="编辑" width="230" fixed="right">
        <template slot-scope="{ row }">
          <el-button type="text" size="mini" icon="el-icon-edit" @click="openEdit(row)">编辑</el-button>
          <el-button type="text" size="mini" icon="el-icon-s-promotion" @click="openExecute(row)">下发</el-button>
          <el-button type="text" size="mini" icon="el-icon-copy-document" @click="openCopy(row)">复制</el-button>
          <el-button type="text" size="mini" icon="el-icon-delete" class="danger-text" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 编辑 / 新增 -->
    <el-dialog :title="editTitle" :visible.sync="editVisible" width="1000px" top="5vh" :close-on-click-modal="false">
      <el-form :model="editForm" label-width="110px" size="small">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="业务模板名称" required>
              <el-input v-model="editForm.name" placeholder="如 宽带+语音+WiFi" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="业务分组">
              <el-input v-model="editForm.groupName" placeholder="如 家庭宽带" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="备注">
              <el-input v-model="editForm.remark" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="6">
            <el-form-item label="启用">
              <el-switch v-model="editForm.enabled" />
            </el-form-item>
          </el-col>
          <el-col :span="18">
            <el-form-item label="失败处理">
              <el-switch v-model="editForm.continueOnError" active-text="成员失败仍继续" inactive-text="成员失败即中止" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <div class="member-head">
        <span>配置模板成员（按表格顺序执行）</span>
        <el-button size="mini" icon="el-icon-plus" @click="addMember">添加成员</el-button>
      </div>
      <el-table :data="editForm.items" border size="small">
        <el-table-column label="顺序" width="90">
          <template slot-scope="{ row, $index }">
            <span>{{ $index + 1 }}</span>
            <el-button type="text" size="mini" icon="el-icon-top" :disabled="$index === 0" @click="moveMember($index, -1)" />
            <el-button type="text" size="mini" icon="el-icon-bottom" :disabled="$index === editForm.items.length - 1" @click="moveMember($index, 1)" />
          </template>
        </el-table-column>
        <el-table-column label="配置模板" min-width="300">
          <template slot-scope="{ row }">
            <el-select v-model="row.templateKey" filterable placeholder="选择配置模板" size="mini" style="width:100%">
              <el-option
                v-for="t in templateOptions"
                :key="t.key"
                :label="t.label"
                :value="t.key"
              >
                <span>{{ t.label }}</span>
                <span class="option-key">{{ t.key }}</span>
              </el-option>
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="参数覆盖" min-width="180">
          <template slot-scope="{ row, $index }">
            <span class="cell-sub">{{ paramsText(row.params) || '使用模板默认值' }}</span>
            <el-button type="text" size="mini" @click="openParams($index)">编辑</el-button>
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="140">
          <template slot-scope="{ row }">
            <el-input v-model="row.remark" size="mini" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="70">
          <template slot-scope="{ row, $index }">
            <el-button type="text" size="mini" class="danger-text" @click="removeMember($index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <span slot="footer">
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </span>
    </el-dialog>

    <!-- 成员参数覆盖 -->
    <el-dialog title="成员参数覆盖" :visible.sync="paramsVisible" width="560px" append-to-body>
      <div class="tab-tip">
        下发时这些值会覆盖配置模板的参数默认值；页面下发时手工填的值优先级最高。
      </div>
      <el-row v-for="(p, i) in paramRows" :key="i" :gutter="8" class="param-row">
        <el-col :span="9">
          <el-input v-model="p.key" size="mini" placeholder="参数名，如 VLAN_ID" />
        </el-col>
        <el-col :span="12">
          <el-input v-model="p.value" size="mini" placeholder="值" />
        </el-col>
        <el-col :span="3">
          <el-button type="text" size="mini" class="danger-text" @click="removeParamRow(i)">删除</el-button>
        </el-col>
      </el-row>
      <el-button size="mini" icon="el-icon-plus" @click="addParamRow">添加参数</el-button>
      <span slot="footer">
        <el-button @click="paramsVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmParams">确定</el-button>
      </span>
    </el-dialog>

    <!-- 下发 -->
    <el-dialog :title="'下发业务模板 - ' + execTitle" :visible.sync="execVisible" width="560px">
      <el-form label-width="110px" size="small">
        <el-form-item label="设备序列号" required>
          <el-input v-model="execForm.sn" placeholder="光猫序列号" />
          <el-button type="text" size="mini" @click="openDevicePicker">从设备列表选择</el-button>
        </el-form-item>
        <el-form-item label="操作人">
          <el-input v-model="execForm.username" placeholder="可空" />
        </el-form-item>
      </el-form>
      <el-alert
        v-if="execIssues.length > 0"
        type="warning"
        :closable="false"
        :title="'下发前检查未通过'"
        :description="execIssues.join('；')"
        class="issue-alert"
      />
      <span slot="footer">
        <el-button @click="execVisible = false">取消</el-button>
        <el-button type="primary" :loading="executing" :disabled="execIssues.length > 0" @click="doExecute">开始下发</el-button>
      </span>
    </el-dialog>

    <!-- 设备选择 -->
    <el-dialog title="选择设备" :visible.sync="deviceVisible" width="800px" append-to-body>
      <el-input v-model="deviceKeyword" size="small" placeholder="序列号模糊匹配" clearable style="width:240px;margin-bottom:8px" @keyup.enter.native="loadDevices" />
      <el-button size="small" icon="el-icon-search" @click="loadDevices">查询</el-button>
      <el-table :data="devices" border size="small" max-height="380">
        <el-table-column label="序列号" prop="sn" min-width="180" show-overflow-tooltip />
        <el-table-column label="厂商" prop="manufacturer" width="120" show-overflow-tooltip />
        <el-table-column label="设备类型" prop="deviceType" width="120" />
        <el-table-column label="最近上报" min-width="160">
          <template slot-scope="{ row }">
            <span>{{ formatTime(row.lastContactTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template slot-scope="{ row }">
            <el-button type="text" size="mini" @click="pickDevice(row)">选择</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 下发进度 -->
    <el-dialog title="下发进度" :visible.sync="progressVisible" width="760px">
      <el-descriptions :column="2" size="small" border>
        <el-descriptions-item label="业务模板">{{ progress.templateName }}</el-descriptions-item>
        <el-descriptions-item label="设备">{{ progress.sn }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag size="mini" :type="statusType(progress.status)">{{ statusText(progress.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="进度">
          {{ progress.finishedCount || 0 }} / {{ progress.totalCount || 0 }}
          （成功 {{ progress.successCount || 0 }}，失败 {{ progress.failCount || 0 }}）
        </el-descriptions-item>
      </el-descriptions>
      <el-alert
        v-if="progress.errorMessage"
        type="error"
        :closable="false"
        :title="progress.errorMessage"
        style="margin-top:10px"
      />
      <el-table :data="progressItems" border size="small" style="margin-top:10px" max-height="300">
        <el-table-column label="顺序" prop="order" width="70" />
        <el-table-column label="配置模板" prop="templateKey" min-width="280" show-overflow-tooltip />
        <el-table-column label="工单号" prop="worklistId" width="110" />
        <el-table-column label="结果" width="100">
          <template slot-scope="{ row }">
            <el-tag size="mini" :type="row.success ? 'success' : 'danger'">
              {{ row.success ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="说明" prop="error" min-width="160" show-overflow-tooltip />
      </el-table>
      <span slot="footer">
        <el-button @click="progressVisible = false">关闭</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import {
  fetchBizTemplates,
  saveBizTemplate,
  deleteBizTemplate,
  validateBizTemplate,
  executeBizTemplate,
  fetchBizExecution
} from '@/api/bizTemplate'
import { fetchTemplates } from '@/api/worklist'
import { fetchDevicePage } from '@/api/device'

export default {
  name: 'BizTemplateList',
  data() {
    return {
      loading: false,
      saving: false,
      executing: false,
      keyword: '',
      list: [],
      templateOptions: [],
      editVisible: false,
      editTitle: '新增业务模板',
      editForm: { id: null, name: '', groupName: '', remark: '', enabled: true, continueOnError: false, items: [] },
      paramsVisible: false,
      paramRows: [],
      paramIndex: -1,
      execVisible: false,
      execTitle: '',
      execForm: { templateId: null, sn: '', username: '' },
      execIssues: [],
      deviceVisible: false,
      deviceKeyword: '',
      devices: [],
      progressVisible: false,
      progress: {},
      progressTimer: null
    }
  },
  computed: {
    progressItems() {
      const raw = this.progress.result
      if (!raw) return []
      try {
        return JSON.parse(raw)
      } catch (e) {
        return []
      }
    }
  },
  created() {
    this.load()
    this.loadTemplates()
  },
  beforeDestroy() {
    if (this.progressTimer) clearInterval(this.progressTimer)
  },
  methods: {
    async load() {
      this.loading = true
      try {
        const res = await fetchBizTemplates(this.keyword)
        this.list = res.data || []
      } catch (e) {
        this.list = []
      } finally {
        this.loading = false
      }
    },
    async loadTemplates() {
      try {
        const res = await fetchTemplates()
        this.templateOptions = (res.data || []).map(t => ({
          key: t.key,
          label: `[${t.category}] ${t.name} · ${t.deviceType}`
        }))
      } catch (e) {
        this.templateOptions = []
      }
    },

    // ---------------- 编辑 ----------------
    openCreate() {
      this.editTitle = '新增业务模板'
      this.editForm = { id: null, name: '', groupName: '', remark: '', enabled: true, continueOnError: false, items: [] }
      this.editVisible = true
    },
    openEdit(row) {
      this.editTitle = '编辑业务模板 - ' + row.template.name
      this.editForm = {
        id: row.template.id,
        name: row.template.name,
        groupName: row.template.groupName || '',
        remark: row.template.remark || '',
        enabled: row.template.enabled !== false,
        continueOnError: row.template.continueOnError === true,
        items: (row.items || []).map(i => ({
          templateKey: i.templateKey,
          remark: i.remark || '',
          params: { ...(i.params || {}) }
        }))
      }
      this.editVisible = true
    },
    openCopy(row) {
      this.editTitle = '复制业务模板 - ' + row.template.name
      this.editForm = {
        id: null,
        name: row.template.name + '_copy',
        groupName: row.template.groupName || '',
        remark: row.template.remark || '',
        enabled: row.template.enabled !== false,
        continueOnError: row.template.continueOnError === true,
        items: (row.items || []).map(i => ({
          templateKey: i.templateKey,
          remark: i.remark || '',
          params: { ...(i.params || {}) }
        }))
      }
      this.editVisible = true
    },
    addMember() {
      this.editForm.items.push({ templateKey: '', remark: '', params: {} })
    },
    removeMember(index) {
      this.editForm.items.splice(index, 1)
    },
    moveMember(index, offset) {
      const target = index + offset
      if (target < 0 || target >= this.editForm.items.length) return
      const item = this.editForm.items[index]
      this.editForm.items.splice(index, 1)
      this.editForm.items.splice(target, 0, item)
    },
    openParams(index) {
      this.paramIndex = index
      const params = this.editForm.items[index].params || {}
      this.paramRows = Object.keys(params).map(k => ({ key: k, value: params[k] }))
      if (this.paramRows.length === 0) this.paramRows = [{ key: '', value: '' }]
      this.paramsVisible = true
    },
    addParamRow() {
      this.paramRows.push({ key: '', value: '' })
    },
    removeParamRow(index) {
      this.paramRows.splice(index, 1)
    },
    confirmParams() {
      const params = {}
      this.paramRows.forEach(p => {
        if (p.key) params[p.key] = p.value || ''
      })
      this.editForm.items[this.paramIndex].params = params
      this.paramsVisible = false
    },
    paramsText(params) {
      if (!params) return ''
      const keys = Object.keys(params)
      if (keys.length === 0) return ''
      return keys.map(k => `${k}=${params[k]}`).join('  ')
    },
    async submit() {
      if (!this.editForm.name) {
        this.$message.warning('业务模板名称不能为空')
        return
      }
      const items = this.editForm.items.filter(i => i.templateKey)
      if (items.length === 0) {
        this.$message.warning('至少添加一个配置模板成员')
        return
      }
      this.saving = true
      try {
        await saveBizTemplate({
          template: {
            id: this.editForm.id,
            name: this.editForm.name,
            groupName: this.editForm.groupName,
            remark: this.editForm.remark,
            enabled: this.editForm.enabled,
            continueOnError: this.editForm.continueOnError
          },
          items: items.map((i, index) => ({
            templateKey: i.templateKey,
            itemOrder: index + 1,
            remark: i.remark,
            params: i.params || {}
          }))
        })
        this.$message.success('已保存')
        this.editVisible = false
        this.load()
      } catch (e) {
        this.$message.error((e && e.message) || '保存失败')
      } finally {
        this.saving = false
      }
    },
    async handleDelete(row) {
      await this.$confirm('确认删除业务模板 ' + row.template.name + '？', '删除确认', { type: 'warning' }).catch(() => {
        throw new Error('cancel')
      })
      await deleteBizTemplate(row.template.id)
      this.$message.success('已删除')
      this.load()
    },

    // ---------------- 下发 ----------------
    async openExecute(row) {
      this.execTitle = row.template.name
      this.execForm = { templateId: row.template.id, sn: '', username: '' }
      this.execIssues = []
      this.execVisible = true
      try {
        const res = await validateBizTemplate(row.template.id)
        this.execIssues = (res.data && res.data.issues) || []
      } catch (e) {
        this.execIssues = []
      }
    },
    async doExecute() {
      if (!this.execForm.sn) {
        this.$message.warning('请填写设备序列号')
        return
      }
      this.executing = true
      try {
        const res = await executeBizTemplate({
          templateId: this.execForm.templateId,
          sn: this.execForm.sn,
          username: this.execForm.username || null
        })
        this.execVisible = false
        this.progress = { id: res.data.executionId, status: 'running' }
        this.progressVisible = true
        this.startPolling(res.data.executionId)
      } catch (e) {
        this.$message.error((e && e.message) || '下发失败')
      } finally {
        this.executing = false
      }
    },
    startPolling(id) {
      if (this.progressTimer) clearInterval(this.progressTimer)
      const tick = async() => {
        try {
          const res = await fetchBizExecution(id)
          this.progress = res.data || {}
          if (this.progress.status !== 'running') {
            clearInterval(this.progressTimer)
            this.progressTimer = null
            this.load()
          }
        } catch (e) {
          clearInterval(this.progressTimer)
          this.progressTimer = null
        }
      }
      tick()
      this.progressTimer = setInterval(tick, 3000)
    },
    statusText(status) {
      const map = { running: '执行中', success: '全部成功', partial: '部分成功', fail: '失败' }
      return map[status] || status || '-'
    },
    statusType(status) {
      const map = { running: '', success: 'success', partial: 'warning', fail: 'danger' }
      return map[status] || 'info'
    },

    // ---------------- 设备选择 ----------------
    openDevicePicker() {
      this.deviceVisible = true
      this.deviceKeyword = ''
      this.loadDevices()
    },
    async loadDevices() {
      try {
        const res = await fetchDevicePage({ page: 1, size: 50, sn: this.deviceKeyword })
        this.devices = (res.data && res.data.items) || []
      } catch (e) {
        this.devices = []
      }
    },
    pickDevice(row) {
      this.execForm.sn = row.sn
      this.deviceVisible = false
    },
    formatTime(value) {
      if (!value) return '-'
      return String(value).replace('T', ' ')
    }
  }
}
</script>

<style scoped>
.desc-card {
  margin-bottom: 12px;
}
.desc {
  color: #606266;
  font-size: 13px;
  line-height: 1.8;
}
.action-bar {
  margin-top: 10px;
  display: flex;
  align-items: center;
}
.action-bar > * {
  margin-right: 8px;
}
.member-tag {
  margin: 2px 4px 2px 0;
}
.cell-main {
  font-weight: 600;
  color: #303133;
}
.cell-sub {
  color: #909399;
  font-size: 12px;
}
.member-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 12px 0 8px;
  font-weight: 600;
  font-size: 14px;
}
.option-key {
  float: right;
  color: #8492a6;
  font-size: 12px;
}
.param-row {
  margin-bottom: 6px;
}
.danger-text {
  color: #f56c6c;
}
.issue-alert {
  margin-bottom: 4px;
}
.tab-tip {
  color: #909399;
  font-size: 12px;
  margin-bottom: 8px;
}
</style>
