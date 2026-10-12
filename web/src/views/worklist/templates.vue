<template>
  <div class="app-container">
    <el-card shadow="never" class="desc-card">
      <div class="desc">
        配置模板用于创建 ONU 上各个配置项的设置，比如局域网、广域网、WiFi、语音等。
        模板以文件形式存放在配置根目录，保存后立即热加载，无需重启服务。
      </div>
      <div class="create-bar">
        <span class="create-label">新增模板：</span>
        <el-button
          v-for="c in categories"
          :key="c.code"
          size="small"
          :class="['cat-btn', 'cat-' + c.code]"
          @click="openCreate(c.code)"
        >{{ c.label }}</el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" :model="query" size="small">
        <el-form-item label="业务分组">
          <el-select v-model="query.group" clearable placeholder="全部分组" style="width:150px">
            <el-option v-for="g in groups" :key="g" :label="g" :value="g" />
          </el-select>
        </el-form-item>
        <el-form-item label="模板类型">
          <el-select v-model="query.category" clearable placeholder="全部类型" style="width:150px">
            <el-option v-for="c in categories" :key="c.code" :label="c.label" :value="c.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="设备类型">
          <el-select v-model="query.deviceType" clearable filterable placeholder="全部" style="width:180px">
            <el-option v-for="d in options.deviceTypes" :key="d" :label="d" :value="d" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="模板名 / 说明" clearable style="width:180px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="handleSearch">搜索</el-button>
          <el-button icon="el-icon-refresh" @click="resetQuery">清除</el-button>
          <el-button icon="el-icon-refresh-right" @click="reload">重新加载配置</el-button>
        </el-form-item>
      </el-form>
      <div class="batch-bar">
        <span>共 {{ filtered.length }} 条，已选 {{ selection.length }} 条</span>
        <el-button
          type="danger"
          size="mini"
          icon="el-icon-delete"
          :disabled="selection.length === 0"
          @click="batchDelete"
        >批量删除</el-button>
      </div>
    </el-card>

    <el-table
      v-loading="loading"
      :data="pagedList"
      border
      fit
      size="small"
      highlight-current-row
      style="width:100%"
      @selection-change="onSelectionChange"
    >
      <el-table-column type="selection" width="45" />
      <el-table-column label="ID" width="60">
        <template slot-scope="{ row, $index }">
          <span>{{ (query.page - 1) * query.size + $index + 1 }}</span>
        </template>
      </el-table-column>
      <el-table-column label="名称 / 业务分组" min-width="200" show-overflow-tooltip>
        <template slot-scope="{ row }">
          <div class="cell-main">{{ row.name }}</div>
          <div class="cell-sub">{{ row.group || '-' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="模板类型" width="120">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="tagType(row.category)">{{ categoryLabel(row.category) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="内容" min-width="240" show-overflow-tooltip>
        <template slot-scope="{ row }">
          <span>{{ contentSummary(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="备注" min-width="180" show-overflow-tooltip>
        <template slot-scope="{ row }">
          <span>{{ firstLine(row.doc) || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="130">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="row.enabled ? 'success' : 'info'">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
          <el-tooltip v-if="!row.stepsMigrated" content="执行步骤未迁移，下发会被拒绝" placement="top">
            <el-tag size="mini" type="warning">未迁移</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="编辑" width="170" fixed="right">
        <template slot-scope="{ row }">
          <el-button type="text" size="mini" icon="el-icon-edit" @click="openEdit(row)">编辑</el-button>
          <el-button type="text" size="mini" icon="el-icon-copy-document" @click="openCopy(row)">复制</el-button>
          <el-button type="text" size="mini" icon="el-icon-delete" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      :current-page.sync="query.page"
      :page-size.sync="query.size"
      :page-sizes="[20, 50, 100, 200]"
      :total="filtered.length"
      layout="total, sizes, prev, pager, next, jumper"
      @size-change="handleSearch"
      @current-change="handleSearch"
    />

    <el-dialog
      :title="dialogTitle"
      :visible.sync="dialogVisible"
      width="1080px"
      top="4vh"
      :close-on-click-modal="false"
    >
      <el-tabs v-model="activeTab">
        <el-tab-pane label="基本信息" name="basic">
          <el-form :model="form" label-width="110px" size="small">
            <el-row :gutter="12">
              <el-col :span="8">
                <el-form-item label="模板名称" required>
                  <el-input v-model="form.name" placeholder="如 WAN_DHCP_41" />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="模板类型">
                  <el-select v-model="form.category" style="width:100%">
                    <el-option v-for="c in categories" :key="c.code" :label="c.label" :value="c.code" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="业务分组">
                  <el-input v-model="form.group" placeholder="可空" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="12">
              <el-col :span="6">
                <el-form-item label="运营商">
                  <el-select v-model="form.isp" filterable allow-create default-first-option style="width:100%">
                    <el-option v-for="i in options.isps" :key="i" :label="i" :value="i" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="6">
                <el-form-item label="规范版本">
                  <el-select v-model="form.version" filterable allow-create default-first-option style="width:100%">
                    <el-option v-for="v in options.versions" :key="v" :label="v" :value="v" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="6">
                <el-form-item label="业务域">
                  <el-select v-model="form.domain" filterable allow-create default-first-option style="width:100%">
                    <el-option v-for="d in options.domains" :key="d" :label="d" :value="d" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="6">
                <el-form-item label="设备类型">
                  <el-select v-model="form.deviceType" filterable allow-create default-first-option style="width:100%">
                    <el-option v-for="d in options.deviceTypes" :key="d" :label="d" :value="d" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="12">
              <el-col :span="6">
                <el-form-item label="文件形式">
                  <el-select v-model="form.format" style="width:100%">
                    <el-option label="YAML（推荐，支持步骤 options 与参数枚举）" value="yaml" />
                    <el-option label="XML（存量格式，不支持 options/枚举）" value="xml" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="4">
                <el-form-item label="启用">
                  <el-switch v-model="form.enabled" />
                </el-form-item>
              </el-col>
              <el-col :span="4">
                <el-form-item label="优先级">
                  <el-input-number v-model="form.priority" :min="1" :max="999" controls-position="right" style="width:100%" />
                </el-form-item>
              </el-col>
              <el-col :span="10">
                <el-form-item label="模板 key">
                  <span class="key-text">{{ buildKey(form) }}</span>
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item label="说明">
              <el-input v-model="form.doc" type="textarea" :rows="3" placeholder="用途说明，列表页备注列取首行" />
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="参数定义" name="args">
          <div class="tab-tip">
            参数在下发时作为 <code>${参数名}</code> 占位符使用，页面下发可填值，未填取这里的默认值。
          </div>
          <el-table :data="form.args" border size="small">
            <el-table-column label="参数名" width="180">
              <template slot-scope="{ row }">
                <el-input v-model="row.name" size="mini" placeholder="如 VLAN_ID" />
              </template>
            </el-table-column>
            <el-table-column label="默认值" width="160">
              <template slot-scope="{ row }">
                <el-input v-model="row.defaultValue" size="mini" />
              </template>
            </el-table-column>
            <el-table-column label="类型" width="120">
              <template slot-scope="{ row }">
                <el-select v-model="row.type" size="mini">
                  <el-option label="string" value="string" />
                  <el-option label="int" value="int" />
                  <el-option label="boolean" value="boolean" />
                  <el-option label="enum" value="enum" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="必填" width="70">
              <template slot-scope="{ row }">
                <el-checkbox v-model="row.required" />
              </template>
            </el-table-column>
            <el-table-column label="枚举值（逗号分隔，仅 enum）" min-width="180">
              <template slot-scope="{ row }">
                <el-input v-model="row.enumText" size="mini" placeholder="仅 enum 类型需要" />
              </template>
            </el-table-column>
            <el-table-column label="说明" min-width="180">
              <template slot-scope="{ row }">
                <el-input v-model="row.description" size="mini" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110">
              <template slot-scope="{ row, $index }">
                <el-button type="text" size="mini" @click="moveArg($index, -1)" :disabled="$index === 0">上移</el-button>
                <el-button type="text" size="mini" @click="removeArg($index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-button size="mini" icon="el-icon-plus" style="margin-top:8px" @click="addArg">添加参数</el-button>
        </el-tab-pane>

        <el-tab-pane label="执行步骤" name="steps">
          <div class="tab-tip">
            按顺序串行下发给设备。参数值支持 <code>${参数名}</code> 与 <code>${ctx.变量名}</code>（引用前面步骤 saveAs 的结果）。
            步骤为空的模板不允许下发。
          </div>
          <div v-for="(step, index) in form.steps" :key="index" class="step-item">
            <div class="step-head">
              <span class="step-index">步骤 {{ index + 1 }}</span>
              <el-button type="text" size="mini" @click="moveStep(index, -1)" :disabled="index === 0">上移</el-button>
              <el-button type="text" size="mini" @click="moveStep(index, 1)" :disabled="index === form.steps.length - 1">下移</el-button>
              <el-button type="text" size="mini" @click="copyStep(index)">复制</el-button>
              <el-button type="text" size="mini" class="danger-text" @click="removeStep(index)">删除</el-button>
            </div>
            <el-row :gutter="10">
              <el-col :span="7">
                <el-select v-model="step.rpc" size="mini" filterable placeholder="CWMP 方法" style="width:100%">
                  <el-option v-for="r in rpcMethods" :key="r" :label="r" :value="r" />
                </el-select>
              </el-col>
              <el-col :span="9">
                <el-input v-model="step.comment" size="mini" placeholder="步骤说明" />
              </el-col>
              <el-col :span="4">
                <el-input v-model="step.saveAs" size="mini" placeholder="结果存入变量" />
              </el-col>
              <el-col :span="4">
                <el-checkbox v-model="step.abortOnError" size="mini">失败中断</el-checkbox>
              </el-col>
            </el-row>
            <el-row :gutter="10" style="margin-top:6px">
              <el-col :span="10">
                <el-input v-model="step.objectName" size="mini" placeholder="objectName：AddObject/DeleteObject 的对象路径" />
              </el-col>
              <el-col :span="10">
                <el-input v-model="step.parameterPath" size="mini" placeholder="parameterPath：GetParameterNames 的参数路径" />
              </el-col>
              <el-col :span="4">
                <el-input-number
                  v-model="step.timeoutSeconds"
                  size="mini"
                  :min="0"
                  :max="600"
                  controls-position="right"
                  placeholder="超时秒"
                  style="width:100%"
                />
              </el-col>
            </el-row>
            <div class="step-sub">
              <div class="step-sub-head">
                <span>参数列表（SetParameterValues）</span>
                <el-button type="text" size="mini" @click="addStepParam(step)">添加</el-button>
              </div>
              <el-row v-for="(p, pi) in step.parameters" :key="'p' + pi" :gutter="8" class="step-sub-row">
                <el-col :span="11">
                  <el-input v-model="p.name" size="mini" placeholder="参数路径，如 InternetGatewayDevice.WANDevice.1..." />
                </el-col>
                <el-col :span="11">
                  <el-input v-model="p.value" size="mini" placeholder="值，如 ${VLAN_ID}" />
                </el-col>
                <el-col :span="2">
                  <el-button type="text" size="mini" class="danger-text" @click="removeStepParam(step, pi)">删除</el-button>
                </el-col>
              </el-row>
            </div>
            <div class="step-sub">
              <div class="step-sub-head">
                <span>options（Download/Reboot 等专用，写 YAML 才生效）</span>
                <el-button type="text" size="mini" @click="addStepOption(step)">添加</el-button>
              </div>
              <el-row v-for="(o, oi) in step.optionList" :key="'o' + oi" :gutter="8" class="step-sub-row">
                <el-col :span="8">
                  <el-input v-model="o.key" size="mini" placeholder="如 CommandKey / FileType / URL" />
                </el-col>
                <el-col :span="14">
                  <el-input v-model="o.value" size="mini" placeholder="值" />
                </el-col>
                <el-col :span="2">
                  <el-button type="text" size="mini" class="danger-text" @click="removeStepOption(step, oi)">删除</el-button>
                </el-col>
              </el-row>
            </div>
          </div>
          <el-button size="mini" icon="el-icon-plus" @click="addStep">添加步骤</el-button>
        </el-tab-pane>
      </el-tabs>

      <span slot="footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import {
  fetchTemplates,
  fetchTemplateCategories,
  fetchTemplateOptions,
  fetchTemplateDetail,
  createTemplate,
  updateTemplate,
  copyTemplate,
  deleteTemplate,
  reloadConfig
} from '@/api/worklist'

const RPC_METHODS = [
  'SetParameterValues',
  'GetParameterValues',
  'GetParameterNames',
  'SetParameterAttributes',
  'GetParameterAttributes',
  'AddObject',
  'DeleteObject',
  'Download',
  'Upload',
  'Reboot',
  'FactoryReset',
  'ScheduleInform',
  'ScheduleDownload',
  'CancelTransfer',
  'ChangeDUState',
  'GetRPCMethods',
  'GetQueuedTransfers',
  'GetAllQueuedTransfers',
  'SetVouchers',
  'GetOptions'
]

function emptyForm() {
  return {
    key: '',
    sourceKey: '',
    name: '',
    category: 'CUSTOM',
    group: '',
    isp: 'standard',
    version: 'v4.0',
    domain: 'business',
    deviceType: 'GPON_4+1',
    enabled: true,
    priority: 100,
    doc: '',
    format: 'yaml',
    args: [],
    steps: []
  }
}

export default {
  name: 'TemplateList',
  data() {
    return {
      loading: false,
      saving: false,
      list: [],
      selection: [],
      categories: [],
      options: { isps: [], versions: [], domains: [], deviceTypes: [] },
      query: { page: 1, size: 50, group: '', category: '', deviceType: '', keyword: '' },
      dialogVisible: false,
      dialogTitle: '新增配置模板',
      mode: 'create',
      activeTab: 'basic',
      rpcMethods: RPC_METHODS,
      form: emptyForm()
    }
  },
  computed: {
    groups() {
      const set = new Set()
      this.list.forEach(t => {
        if (t.group) set.add(t.group)
      })
      return Array.from(set)
    },
    filtered() {
      const keyword = this.query.keyword.trim().toLowerCase()
      return this.list.filter(t => {
        if (this.query.group && t.group !== this.query.group) return false
        if (this.query.deviceType && t.deviceType !== this.query.deviceType) return false
        if (this.query.category && t.category !== this.query.category) return false
        if (keyword) {
          const hay = (t.name + ' ' + (t.doc || '') + ' ' + t.key).toLowerCase()
          if (!hay.includes(keyword)) return false
        }
        return true
      })
    },
    pagedList() {
      const start = (this.query.page - 1) * this.query.size
      return this.filtered.slice(start, start + this.query.size)
    }
  },
  created() {
    this.loadMeta()
    this.load()
  },
  methods: {
    async loadMeta() {
      try {
        const [cat, opt] = await Promise.all([fetchTemplateCategories(), fetchTemplateOptions()])
        this.categories = cat.data || []
        this.options = opt.data || this.options
        const isps = this.options.isps || []
        const versions = this.options.versions || []
        const domains = this.options.domains || []
        const deviceTypes = this.options.deviceTypes || []
        this.form.isp = isps.includes('standard') ? 'standard' : (isps[0] || 'standard')
        this.form.version = versions[0] || 'v4.0'
        this.form.domain = domains[0] || 'business'
        this.form.deviceType = deviceTypes[0] || 'GPON_4+1'
      } catch (e) {
        this.categories = []
      }
    },
    async load() {
      this.loading = true
      try {
        const res = await fetchTemplates()
        this.list = res.data || []
      } catch (e) {
        this.list = []
      } finally {
        this.loading = false
      }
    },
    async reload() {
      await reloadConfig()
      this.$message.success('配置已重新加载')
      this.load()
    },
    handleSearch() {
      this.query.page = 1
    },
    resetQuery() {
      this.query = { page: 1, size: 50, group: '', category: '', deviceType: '', keyword: '' }
    },
    onSelectionChange(rows) {
      this.selection = rows
    },
    categoryLabel(code) {
      const hit = this.categories.find(c => c.code === code)
      return hit ? hit.label : code
    },
    tagType(code) {
      const map = {
        LAN: '', WAN_ROUTE: 'success', WAN_STATIC: 'success', WAN_DHCP: 'success',
        WAN_PPPOE: 'success', SIP: 'warning', WIFI_24G: 'danger', WIFI_58G: 'danger',
        FIRMWARE: 'info', OTHER: 'info', CUSTOM: 'info'
      }
      return map[code] || 'info'
    },
    contentSummary(row) {
      const args = row.args || {}
      const keys = Object.keys(args)
      if (keys.length === 0) return '-'
      return keys
        .slice(0, 3)
        .map(k => `${k}=${args[k] && args[k].defaultValue !== undefined ? args[k].defaultValue : ''}`)
        .join('  ')
    },
    firstLine(doc) {
      if (!doc) return ''
      const line = String(doc).split('\n').find(l => l.trim().length > 0)
      return line ? line.trim() : ''
    },
    buildKey(form) {
      return [form.isp, form.version, form.domain, form.deviceType, form.name].filter(Boolean).join('/')
    },

    // ---------------- 对话框 ----------------
    openCreate(category) {
      this.mode = 'create'
      this.dialogTitle = '新增配置模板'
      this.activeTab = 'basic'
      this.form = emptyForm()
      if (category) this.form.category = category
      const isps = this.options.isps || []
      const versions = this.options.versions || []
      const domains = this.options.domains || []
      const deviceTypes = this.options.deviceTypes || []
      this.form.isp = isps.includes('standard') ? 'standard' : (isps[0] || 'standard')
      this.form.version = versions[0] || 'v4.0'
      this.form.domain = domains[0] || 'business'
      this.form.deviceType = deviceTypes[0] || 'GPON_4+1'
      this.dialogVisible = true
    },
    async openEdit(row) {
      this.mode = 'edit'
      this.dialogTitle = '编辑配置模板 - ' + row.name
      this.activeTab = 'basic'
      const res = await fetchTemplateDetail(row.key)
      const detail = res.data || row
      this.form = this.toForm(detail, 'edit')
      this.dialogVisible = true
    },
    async openCopy(row) {
      this.mode = 'copy'
      this.dialogTitle = '复制模板 - ' + row.name
      this.activeTab = 'basic'
      const res = await fetchTemplateDetail(row.key)
      const detail = res.data || row
      this.form = this.toForm(detail, 'copy')
      this.form.name = detail.name + '_copy'
      this.dialogVisible = true
    },
    toForm(detail, mode) {
      const form = emptyForm()
      form.key = detail.key
      form.sourceKey = detail.key
      form.name = detail.name
      form.category = detail.category || 'OTHER'
      form.group = detail.group || ''
      form.isp = detail.isp
      form.version = detail.version
      form.domain = detail.domain
      form.deviceType = detail.deviceType
      form.enabled = detail.enabled !== false
      form.priority = detail.priority || 100
      form.doc = detail.doc || ''
      form.format = String(detail.sourceFile || '').toLowerCase().endsWith('.xml') ? 'xml' : 'yaml'
      if (mode === 'copy') {
        form.format = 'yaml'
      }

      const args = []
      const argMap = detail.args || {}
      Object.keys(argMap).forEach(name => {
        const a = argMap[name] || {}
        args.push({
          name,
          defaultValue: a.defaultValue === undefined || a.defaultValue === null ? '' : String(a.defaultValue),
          type: a.type || 'string',
          required: a.required !== false,
          enumText: (a.enumValues || []).join(','),
          description: a.description || ''
        })
      })
      form.args = args

      form.steps = (detail.steps || []).map(s => ({
        rpc: s.rpc || '',
        comment: s.comment || '',
        objectName: s.objectName || '',
        parameterPath: s.parameterPath || '',
        saveAs: s.saveAs || '',
        abortOnError: s.abortOnError !== false,
        nextLevel: !!s.nextLevel,
        timeoutSeconds: s.timeoutSeconds || 0,
        parameters: (s.parameters || []).map(p => ({ name: p.name || '', value: p.value || '' })),
        optionList: Object.keys(s.options || {}).map(k => ({ key: k, value: String(s.options[k]) }))
      }))
      return form
    },
    toPayload() {
      const args = {}
      this.form.args.forEach((a, index) => {
        if (!a.name) return
        const arg = {
          defaultValue: a.defaultValue,
          order: index + 1,
          required: !!a.required,
          type: a.type || 'string',
          description: a.description || ''
        }
        const enums = String(a.enumText || '')
          .split(',')
          .map(v => v.trim())
          .filter(Boolean)
        if (enums.length > 0) arg.enumValues = enums
        args[a.name] = arg
      })

      const steps = this.form.steps.map(s => {
        const step = {
          rpc: s.rpc,
          comment: s.comment || '',
          objectName: s.objectName || '',
          parameterPath: s.parameterPath || '',
          nextLevel: !!s.nextLevel,
          saveAs: s.saveAs || '',
          abortOnError: s.abortOnError !== false,
          timeoutSeconds: s.timeoutSeconds || 0
        }
        const parameters = (s.parameters || [])
          .filter(p => p.name || p.value)
          .map(p => ({ name: p.name || '', value: p.value || '' }))
        if (parameters.length > 0) step.parameters = parameters
        const options = {}
        ;(s.optionList || []).forEach(o => {
          if (o.key) options[o.key] = o.value
        })
        if (Object.keys(options).length > 0) step.options = options
        return step
      })

      return {
        name: this.form.name,
        category: this.form.category,
        group: this.form.group || '',
        isp: this.form.isp,
        version: this.form.version,
        domain: this.form.domain,
        deviceType: this.form.deviceType,
        enabled: this.form.enabled,
        priority: this.form.priority || 100,
        doc: this.form.doc || '',
        args,
        steps
      }
    },
    async submit() {
      if (!this.form.name) {
        this.$message.warning('模板名称不能为空')
        return
      }
      if (!this.form.steps.length) {
        await this.$confirm('该模板没有执行步骤，保存后不允许下发，仅能作为归档。是否继续？', '提示', {
          type: 'warning'
        }).catch(() => {
          throw new Error('cancel')
        })
      }
      this.saving = true
      try {
        const template = this.toPayload()
        if (this.mode === 'create') {
          await createTemplate({ template, format: this.form.format })
        } else if (this.mode === 'edit') {
          await updateTemplate({ key: this.form.key, template, format: this.form.format })
        } else {
          await copyTemplate({ sourceKey: this.form.sourceKey, template, format: this.form.format })
        }
        this.$message.success('已保存')
        this.dialogVisible = false
        this.load()
      } catch (e) {
        if (e && e.message !== 'cancel') {
          this.$message.error((e && e.message) || '保存失败')
        }
      } finally {
        this.saving = false
      }
    },

    // ---------------- 参数 / 步骤编辑 ----------------
    addArg() {
      this.form.args.push({ name: '', defaultValue: '', type: 'string', required: true, enumText: '', description: '' })
    },
    removeArg(index) {
      this.form.args.splice(index, 1)
    },
    moveArg(index, offset) {
      const target = index + offset
      if (target < 0 || target >= this.form.args.length) return
      const item = this.form.args[index]
      this.form.args.splice(index, 1)
      this.form.args.splice(target, 0, item)
    },
    addStep() {
      this.form.steps.push({
        rpc: 'SetParameterValues',
        comment: '',
        objectName: '',
        parameterPath: '',
        saveAs: '',
        abortOnError: true,
        nextLevel: false,
        timeoutSeconds: 0,
        parameters: [{ name: '', value: '' }],
        optionList: []
      })
    },
    removeStep(index) {
      this.form.steps.splice(index, 1)
    },
    moveStep(index, offset) {
      const target = index + offset
      if (target < 0 || target >= this.form.steps.length) return
      const item = this.form.steps[index]
      this.form.steps.splice(index, 1)
      this.form.steps.splice(target, 0, item)
    },
    copyStep(index) {
      const copy = JSON.parse(JSON.stringify(this.form.steps[index]))
      this.form.steps.splice(index + 1, 0, copy)
    },
    addStepParam(step) {
      step.parameters.push({ name: '', value: '' })
    },
    removeStepParam(step, index) {
      step.parameters.splice(index, 1)
    },
    addStepOption(step) {
      step.optionList.push({ key: '', value: '' })
    },
    removeStepOption(step, index) {
      step.optionList.splice(index, 1)
    },

    // ---------------- 删除 ----------------
    async handleDelete(row) {
      await this.$confirm('确认删除配置模板 ' + row.name + '？对应文件会被移除。', '删除确认', {
        type: 'warning'
      }).catch(() => {
        throw new Error('cancel')
      })
      await deleteTemplate(row.key)
      this.$message.success('已删除')
      this.load()
    },
    async batchDelete() {
      await this.$confirm('确认删除选中的 ' + this.selection.length + ' 个配置模板？', '批量删除', {
        type: 'warning'
      }).catch(() => {
        throw new Error('cancel')
      })
      for (const row of this.selection) {
        await deleteTemplate(row.key)
      }
      this.$message.success('已删除')
      this.selection = []
      this.load()
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
.create-bar {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
}
.create-label {
  font-size: 13px;
  color: #909399;
  margin-right: 8px;
}
.cat-btn {
  margin: 0 6px 6px 0;
}
.cat-LAN { background: #409eff; border-color: #409eff; color: #fff; }
.cat-WAN_ROUTE { background: #67c23a; border-color: #67c23a; color: #fff; }
.cat-WAN_STATIC { background: #5cb85c; border-color: #5cb85c; color: #fff; }
.cat-WAN_DHCP { background: #36cfc9; border-color: #36cfc9; color: #fff; }
.cat-WAN_PPPOE { background: #1890ff; border-color: #1890ff; color: #fff; }
.cat-SIP { background: #e6a23c; border-color: #e6a23c; color: #fff; }
.cat-WIFI_24G { background: #f78989; border-color: #f78989; color: #fff; }
.cat-WIFI_58G { background: #f56c6c; border-color: #f56c6c; color: #fff; }
.cat-FIRMWARE { background: #9254de; border-color: #9254de; color: #fff; }
.cat-OTHER { background: #909399; border-color: #909399; color: #fff; }
.cat-CUSTOM { background: #606266; border-color: #606266; color: #fff; }
.filter-card {
  margin-bottom: 12px;
}
.batch-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  color: #606266;
}
.cell-main {
  font-weight: 600;
  color: #303133;
}
.cell-sub {
  color: #909399;
  font-size: 12px;
}
.key-text {
  color: #909399;
  font-size: 12px;
  word-break: break-all;
}
.pager {
  margin-top: 12px;
  text-align: right;
}
.tab-tip {
  color: #909399;
  font-size: 12px;
  margin-bottom: 8px;
  line-height: 1.6;
}
.step-item {
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 10px;
  margin-bottom: 10px;
  background: #fafafa;
}
.step-head {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
}
.step-index {
  font-weight: 600;
  font-size: 13px;
  margin-right: 12px;
  color: #303133;
}
.step-sub {
  margin-top: 8px;
  padding-left: 8px;
  border-left: 2px solid #e4e7ed;
}
.step-sub-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: #606266;
}
.step-sub-row {
  margin-top: 4px;
}
.danger-text {
  color: #f56c6c;
}
</style>
