<template>
  <div class="app-container">
    <!-- 横向筛选条：一行放下设备标识/SN/MAC、工单号、房间号、执行时间与执行状态 -->
    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" size="small" class="filter-form" @submit.native.prevent="handleSearch">
        <el-form-item label="设备标识/SN/MAC">
          <el-input
            v-model="query.keyword"
            clearable
            placeholder="SN / MAC 模糊匹配"
            style="width: 190px"
            @keyup.enter.native="handleSearch"
          />
        </el-form-item>
        <el-form-item label="工单号">
          <el-input
            v-model="query.worklistNo"
            clearable
            placeholder="流水号 / 单号"
            style="width: 170px"
            @keyup.enter.native="handleSearch"
          />
        </el-form-item>
        <el-form-item label="房间号">
          <el-input
            v-model="query.room"
            clearable
            placeholder="如 0001"
            style="width: 130px"
            @keyup.enter.native="handleSearch"
          />
        </el-form-item>
        <el-form-item label="执行时间">
          <el-date-picker
            v-model="timeRange"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            value-format="yyyy-MM-dd HH:mm:ss"
            :default-time="['00:00:00', '23:59:59']"
            style="width: 340px"
          />
        </el-form-item>
        <el-form-item label="执行状态">
          <el-select
            v-model="statuses"
            multiple
            collapse-tags
            clearable
            placeholder="全部"
            style="width: 180px"
          >
            <el-option v-for="s in statusOptions" :key="s" :label="statusLabel(s)" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="handleSearch">查询</el-button>
          <el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button>
          <!-- 新增：页面内弹窗建单 -->
          <el-button
            v-if="$has('worklist:update')"
            type="success"
            icon="el-icon-plus"
            @click="openCreate"
          >新增</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-table v-loading="loading" :data="list" border fit style="width:100%">
      <el-table-column label="设备标识/SN/MAC" min-width="210">
        <template slot-scope="{ row }">
          <div class="cell-main">{{ row.sn }}</div>
          <div v-if="row.macText" class="cell-sub">{{ row.macText }}</div>
        </template>
      </el-table-column>
      <el-table-column label="工单号" min-width="160" show-overflow-tooltip>
        <template slot-scope="{ row }">
          <span>{{ row.worklistDesc || row.id }}</span>
        </template>
      </el-table-column>
      <el-table-column label="工单名称" prop="worklistName" min-width="140" show-overflow-tooltip />
      <el-table-column label="房间号" width="100">
        <template slot-scope="{ row }">
          <span>{{ row.roomNo || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="执行时间" min-width="300">
        <template slot-scope="{ row }">
          <span>{{ execTimeText(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="执行状态" width="110">
        <template slot-scope="{ row }">
          <el-tag :type="row.statusLevel || 'info'" size="small">{{ row.statusText || row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template slot-scope="{ row }">
          <el-button type="text" size="mini" @click="viewSteps(row)">步骤</el-button>
          <el-button
            v-if="$has('worklist:update')"
            type="text"
            size="mini"
            @click="openEdit(row)"
          >编辑</el-button>
          <el-button
            v-if="$has('worklist:update')"
            type="text"
            size="mini"
            class="danger-btn"
            @click="handleDelete(row)"
          >删除</el-button>
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
            <el-tag :type="row.statusLevel || 'info'" size="small">{{ row.statusText || row.status }}</el-tag>
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

    <!-- 新增工单：照现场系统样式做的弹窗表单；分组/工单类型/扩展属性等字段先按约定提交，后端就绪即生效 -->
    <el-dialog title="新增工单" :visible.sync="createVisible" width="640px" :close-on-click-modal="false">
      <el-form ref="createForm" :model="createForm" :rules="createRules" label-width="110px" size="small">
        <el-form-item label="设备唯一标识" prop="sn">
          <el-input v-model="createForm.sn" clearable placeholder="SN 或 MAC 或 LOID" />
        </el-form-item>
        <el-form-item label="分组">
          <el-select
            v-model="createForm.groupId"
            filterable
            clearable
            placeholder="=== 选择分组 ==="
            style="width: 100%"
          >
            <el-option v-for="g in groupOptions" :key="g.id" :label="g.name" :value="g.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="房间号">
          <el-input v-model="createForm.roomNo" clearable placeholder="可选" />
        </el-form-item>
        <el-form-item label="配置业务模板" prop="templateSel">
          <el-select
            v-model="createForm.templateSel"
            filterable
            clearable
            placeholder="=== 业务模板 ==="
            style="width: 100%"
            @change="onTemplateChange"
          >
            <el-option-group label="业务模板">
              <el-option
                v-for="t in bizTemplates"
                :key="'biz-' + t.id"
                :label="t.groupName ? t.groupName + ' / ' + t.name : t.name"
                :value="'biz:' + t.id"
              />
            </el-option-group>
            <el-option-group label="配置模板">
              <el-option
                v-for="c in categories"
                :key="'config-' + c.code"
                :label="c.label"
                :value="'config:' + c.code"
              />
            </el-option-group>
          </el-select>
        </el-form-item>
        <template v-if="variables.length > 0">
          <el-divider content-position="left">业务参数</el-divider>
          <el-form-item v-for="v in variables" :key="v.name" :label="v.label">
            <el-input
              v-model="createForm.values[v.name]"
              :type="v.secret ? 'password' : 'text'"
              :placeholder="v.example"
              :show-password="v.secret"
              clearable
            />
          </el-form-item>
        </template>
        <el-form-item label="工单类型">
          <el-select v-model="createForm.worklistType" style="width: 100%">
            <el-option label="普通工单" value="normal" />
            <el-option label="复位下发工单" value="reset" />
            <el-option label="重启下发工单" value="reboot" />
          </el-select>
        </el-form-item>
        <el-form-item label="扩展属性">
          <el-input v-model="createForm.extra" clearable placeholder="可选" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.remark" type="textarea" :rows="2" placeholder="可选" />
        </el-form-item>
        <el-form-item label="激活">
          <el-switch
            v-model="createForm.active"
            active-color="#13ce66"
            active-text="是"
            inactive-text="否"
          />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button type="primary" icon="el-icon-check" :loading="creating" @click="submitCreate">保存设置</el-button>
        <el-button type="warning" plain icon="el-icon-close" @click="createVisible = false">关闭</el-button>
      </span>
    </el-dialog>
    <!-- 编辑工单：设备标识与工单类型只有未执行的单能改，房间号/备注等随时可改 -->
    <el-dialog title="编辑工单" :visible.sync="editVisible" width="560px" :close-on-click-modal="false">
      <el-form ref="editForm" :model="editForm" label-width="110px" size="small">
        <el-form-item label="工单号">
          <span>{{ editForm.worklistDesc || editForm.id }}</span>
          <el-tag :type="editForm.statusLevel || 'info'" size="mini" class="status-tag">
            {{ editForm.statusText || editForm.status }}
          </el-tag>
        </el-form-item>
        <el-form-item label="设备唯一标识">
          <el-input v-model="editForm.sn" :disabled="!editSnEditable" clearable />
        </el-form-item>
        <el-form-item label="房间号">
          <el-input v-model="editForm.roomNo" clearable placeholder="改了会同步设备台账" />
        </el-form-item>
        <el-form-item label="工单类型">
          <el-select v-model="editForm.worklistType" :disabled="!editSnEditable" style="width: 100%">
            <el-option label="普通工单" value="normal" />
            <el-option label="复位下发工单" value="reset" />
            <el-option label="重启下发工单" value="reboot" />
          </el-select>
        </el-form-item>
        <el-form-item label="扩展属性">
          <el-input v-model="editForm.extra" clearable />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editForm.remark" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="激活">
          <el-switch
            v-model="editForm.active"
            active-color="#13ce66"
            active-text="是"
            inactive-text="否"
          />
          <span v-if="!editSnEditable" class="edit-hint">已执行的单仅可改房间号 / 备注 / 扩展属性</span>
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button type="primary" icon="el-icon-check" :loading="editing" @click="submitEdit">保存设置</el-button>
        <el-button type="warning" plain icon="el-icon-close" @click="editVisible = false">关闭</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import { fetchInstances, fetchSteps, fetchWorklistStatuses, fetchTemplateMeta, fetchVariables, createWorklist, updateWorklist, deleteWorklist } from '@/api/worklist'
import { fetchBizTemplates } from '@/api/bizTemplate'

/**
 * 工单：横向筛选条 + 执行记录列表。
 *
 * 筛选参数（keyword / worklistNo / room / timeStart / timeEnd / status 多选）
 * 先按约定带上，后端支持后即自动生效；当前后端只认 sn 与 status，其余参数会被忽略。
 */
export default {
  name: 'WorklistOrders',
  data() {
    return {
      loading: false,
      list: [],
      total: 0,
      statusOptions: [],
      timeRange: null,
      statuses: [],
      query: {
        page: 1,
        size: 20,
        keyword: '',
        worklistNo: '',
        room: ''
      },
      steps: [],
      stepsVisible: false,
      stepDetail: {},
      stepDetailVisible: false,
      // 新增工单弹窗
      createVisible: false,
      creating: false,
      categories: [],
      bizTemplates: [],
      variables: [],
      groupOptions: [],
      createForm: { sn: '', groupId: '', roomNo: '', templateSel: '', values: {}, worklistType: 'normal', extra: '', remark: '', active: true },
      createRules: {
        sn: [{ required: true, message: '请输入设备唯一标识', trigger: 'blur' }],
        templateSel: [{ required: true, message: '请选择业务模板', trigger: 'change' }]
      },
      // 编辑工单弹窗
      editVisible: false,
      editing: false,
      editForm: { id: null, worklistDesc: '', status: '', statusText: '', statusLevel: '', sn: '', roomNo: '', worklistType: 'normal', extra: '', remark: '', active: true }
    }
  },
  computed: {
    /** 设备标识与工单类型只有未执行（构建中/等待执行/预约中）的单能改 */
    editSnEditable() {
      return ['build', 'standby', 'reserve'].includes(this.editForm.status)
    }
  },
  created() {
    // 状态枚举由后端维护，后端加一个状态，下拉自动跟上
    fetchWorklistStatuses()
      .then(res => { this.statusOptions = (res && res.data) || [] })
      .catch(() => {})
    this.load()
  },
  methods: {
    async load() {
      this.loading = true
      try {
        const params = {
          page: this.query.page,
          size: this.query.size,
          keyword: this.query.keyword,
          worklistNo: this.query.worklistNo,
          room: this.query.room,
          status: this.statuses.join(',')
        }
        if (this.timeRange && this.timeRange.length === 2) {
          params.timeStart = this.timeRange[0]
          params.timeEnd = this.timeRange[1]
        }
        const res = await fetchInstances(params)
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
    goCreate() {
      this.openCreate()
    },
    /** 打开新增弹窗：模板清单懒加载，弹窗各拉一次 */
    openCreate() {
      this.createVisible = true
      if (this.categories.length === 0) {
        fetchTemplateMeta()
          .then(res => { this.categories = (res && res.data && res.data.categories) || [] })
          .catch(() => {})
      }
      if (this.bizTemplates.length === 0) {
        fetchBizTemplates()
          .then(res => {
            // 列表返回 { template, items } 结构，模板本体在 template 字段里，
            // 拍平后再过滤：停用的业务模板不允许下发，不进下拉
            this.bizTemplates = ((res && res.data) || [])
              .map(d => d.template || {})
              .filter(t => t.enabled !== false)
          })
          .catch(() => {})
      }
      this.resetCreateForm()
    },
    resetCreateForm() {
      this.createForm = { sn: '', groupId: '', roomNo: '', templateSel: '', values: {}, worklistType: 'normal', extra: '', remark: '', active: true }
      this.variables = []
      if (this.$refs.createForm) {
        this.$refs.createForm.clearValidate()
      }
    },
    onTemplateChange(value) {
      this.createForm.values = {}
      this.variables = []
      if (!value || !value.startsWith('config:')) {
        // 业务模板是整包下发，不需要单填业务参数
        return
      }
      // 配置模板的变量清单由后端按业务类型给，新增业务不用改页面
      fetchVariables(value.slice('config:'.length))
        .then(res => { this.variables = (res && res.data) || [] })
        .catch(() => {})
    },
    async submitCreate() {
      try {
        await this.$refs.createForm.validate()
      } catch (e) {
        return
      }
      const sel = this.createForm.templateSel
      // 分组 / 房间号 / 工单类型 / 扩展属性 / 备注 / 激活先随 payload 提交，
      // 后端暂不识别的字段会被忽略，补齐后自动生效
      const extras = {
        groupId: this.createForm.groupId || null,
        roomNo: this.createForm.roomNo || null,
        worklistType: this.createForm.worklistType,
        extra: this.createForm.extra || null,
        remark: this.createForm.remark || null,
        active: this.createForm.active
      }
      this.creating = true
      try {
        if (sel.startsWith('biz:')) {
          // 业务模板：同样走建单通道（bizTemplateId 标记），
          // 由后端统一「等待执行 → 设备在线 → 10 秒后整包下发」的调度
          const bizId = Number(sel.slice('biz:'.length))
          const tpl = this.bizTemplates.find(t => t.id === bizId)
          await createWorklist({
            sn: this.createForm.sn.trim(),
            category: '',
            values: {},
            worklistDesc: '',
            bizTemplateId: bizId,
            bizTemplateName: tpl ? tpl.name : null,
            ...extras
          })
        } else {
          // 配置模板：建单接口
          await createWorklist({
            sn: this.createForm.sn.trim(),
            category: sel.slice('config:'.length),
            values: this.createForm.values,
            worklistDesc: '',
            ...extras
          })
        }
        this.$message.success('工单已提交')
        this.createVisible = false
        this.handleSearch()
      } catch (e) {
        /* 拦截器已提示 */
      } finally {
        this.creating = false
      }
    },
    /** 打开编辑弹窗：worklistType 是后端的 bizType 字段 */
    openEdit(row) {
      this.editForm = {
        id: row.id,
        worklistDesc: row.worklistDesc,
        status: row.status,
        statusText: row.statusText || row.status,
        statusLevel: row.statusLevel,
        sn: row.sn,
        roomNo: row.roomNo || '',
        worklistType: row.bizType || 'normal',
        extra: row.extra || '',
        remark: row.remark || '',
        active: row.active === null || row.active === undefined ? true : row.active === 1
      }
      this.editVisible = true
    },
    async submitEdit() {
      this.editing = true
      try {
        await updateWorklist(this.editForm.id, {
          sn: this.editForm.sn ? this.editForm.sn.trim() : null,
          roomNo: this.editForm.roomNo || '',
          worklistType: this.editForm.worklistType,
          extra: this.editForm.extra || '',
          remark: this.editForm.remark || '',
          active: this.editForm.active
        })
        this.$message.success('工单已更新')
        this.editVisible = false
        this.load()
      } catch (e) {
        /* 拦截器已提示 */
      } finally {
        this.editing = false
      }
    },
    handleDelete(row) {
      this.$confirm(
        '确定删除工单「' + (row.worklistDesc || row.id) + '」吗？删除后不再下发，也不可恢复。',
        '删除确认',
        { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
      ).then(async() => {
        try {
          await deleteWorklist(row.id)
          this.$message.success('工单已删除')
          if (this.list.length === 1 && this.query.page > 1) {
            this.query.page -= 1
          }
          this.load()
        } catch (e) {
          /* 拦截器已提示 */
        }
      }).catch(() => {})
    },
    resetQuery() {
      this.query = { page: 1, size: 20, keyword: '', worklistNo: '', room: '' }
      this.timeRange = null
      this.statuses = []
      this.load()
    },
    statusLabel(status) {
      // 下拉项中文跟随后端列表页同款对照；枚举没认出的原样展示
      const map = {
        build: '构建中', bind: '绑定中', reserve: '预约中', standby: '等待执行',
        running: '执行中', sending: '下发中', success: '成功',
        fail: '失败', failed: '失败', abnormal: '异常', expire: '已过期'
      }
      return map[status] || status
    },
    execTimeText(row) {
      const start = row.timeExecStartText || row.timeInitText || '-'
      const end = row.timeExecFinishText || '-'
      return start + ' ~ ' + end
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
/* 压一行：标签与控件间距收紧，尽量把筛选条控制在一行内 */
.filter-form >>> .el-form-item {
  margin-bottom: 0;
  margin-right: 12px;
}
.pager {
  margin-top: 16px;
  text-align: right;
}
.cell-main {
  line-height: 1.4;
}
.cell-sub {
  color: #909399;
  font-size: 12px;
  line-height: 1.4;
}
.danger-btn {
  color: #f56c6c;
}
.status-tag {
  margin-left: 8px;
}
.edit-hint {
  margin-left: 12px;
  color: #909399;
  font-size: 12px;
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
