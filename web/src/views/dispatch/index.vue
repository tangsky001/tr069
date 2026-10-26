<template>
  <div class="app-container">
    <el-form :inline="true" size="small">
      <el-form-item>
        <el-button type="primary" icon="el-icon-plus" @click="openCreate">新建下发任务</el-button>
      </el-form-item>
      <el-form-item label="设备">
        <el-input v-model="query.unitId" placeholder="unitId" clearable style="width:200px" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable placeholder="全部" style="width:130px">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
      </el-form-item>
      <el-form-item label="RPC">
        <el-select v-model="query.rpcName" clearable placeholder="全部" style="width:180px">
          <el-option v-for="r in rpcs" :key="r" :label="r" :value="r" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" @click="handleSearch">查询</el-button>
      </el-form-item>
      <el-form-item>
        <el-button :disabled="!selection.length" @click="handleCancel">批量取消</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="list" border stripe size="small" @selection-change="val => selection = val">
      <el-table-column type="selection" width="45" />
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="taskNo" label="任务号" width="180" show-overflow-tooltip />
      <el-table-column prop="unitId" label="设备" min-width="200" show-overflow-tooltip />
      <el-table-column prop="rpcName" label="RPC" width="170" />
      <el-table-column label="状态" width="110" align="center">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="statusType(row.status)">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="resultPayload" label="结果" min-width="200" show-overflow-tooltip />
      <el-table-column prop="failReason" label="失败原因" min-width="160" show-overflow-tooltip />
      <el-table-column prop="costMs" label="耗时(ms)" width="100" />
      <el-table-column prop="createTime" label="创建时间" width="170" />
    </el-table>

    <el-pagination
      style="margin-top:12px;text-align:right"
      :current-page="query.current"
      :page-size="query.size"
      :total="total"
      layout="total, prev, pager, next"
      @current-change="page => { query.current = page; load() }"
    />

    <!-- 新建任务 -->
    <el-dialog title="新建下发任务" :visible.sync="createVisible" width="640px">
      <el-form :model="form" label-width="110px" size="small">
        <el-form-item label="RPC 方法">
          <el-select v-model="form.rpcName" style="width:100%" @change="form = Object.assign({}, form, payloadReset())">
            <el-option v-for="r in rpcs" :key="r" :label="r" :value="r" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标设备">
          <el-radio-group v-model="targetType">
            <el-radio label="unit">指定设备</el-radio>
            <el-radio label="model">按型号</el-radio>
          </el-radio-group>
          <el-input v-if="targetType === 'unit'" v-model="form.unitIdsText" placeholder="多个 unitId 用英文逗号分隔" />
          <el-input v-else v-model="form.model" placeholder="型号，如 HS8145V6" />
        </el-form-item>
        <el-form-item v-if="form.rpcName === 'SetParameterValues'" label="参数列表">
          <el-input v-model="form.paramsText" type="textarea" :rows="5"
            placeholder='JSON 数组，如 [{"name":"InternetGatewayDevice.WiFi.SSID.1.SSID","value":"MyWifi","type":"xsd:string"}]' />
        </el-form-item>
        <el-form-item v-else-if="isGet" label="参数路径">
          <el-input v-model="form.paramsText" type="textarea" :rows="4"
            placeholder='JSON 数组，如 ["InternetGatewayDevice.DeviceInfo.SoftwareVersion"]' />
        </el-form-item>
        <el-form-item v-else-if="form.rpcName === 'Download' || form.rpcName === 'Upload'" label="文件 ID">
          <el-input v-model="form.fileId" placeholder="在「固件文件」中上传后得到的 ID" />
        </el-form-item>
        <el-form-item v-else-if="isObject" label="对象路径">
          <el-input v-model="form.objectName" placeholder="如 InternetGatewayDevice.WANDevice.1.WANConnectionDevice." />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button size="small" @click="createVisible = false">取消</el-button>
        <el-button size="small" type="primary" @click="submitCreate">提交</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { taskPage, createTask, cancelTasks, supportedRpcs } from '@/api/task'

export default {
  name: 'Dispatch',
  data() {
    return {
      loading: false,
      list: [],
      total: 0,
      selection: [],
      query: { current: 1, size: 20, unitId: '', status: '', rpcName: '' },
      rpcs: [],
      statuses: ['PENDING', 'QUEUED', 'SENDING', 'WAIT_RESULT', 'SUCCESS', 'FAILED', 'TIMEOUT', 'CANCELED'],
      createVisible: false,
      targetType: 'unit',
      form: { rpcName: 'GetParameterValues', unitIdsText: '', model: '', paramsText: '', fileId: null, objectName: '' }
    }
  },
  computed: {
    isGet() {
      return ['GetParameterValues', 'GetParameterNames', 'GetParameterAttributes', 'SetParameterAttributes'].includes(this.form.rpcName)
    },
    isObject() {
      return ['AddObject', 'DeleteObject'].includes(this.form.rpcName)
    }
  },
  created() {
    this.load()
    supportedRpcs().then(res => { this.rpcs = res.data || [] })
  },
  methods: {
    load() {
      this.loading = true
      taskPage(this.query).then(res => {
        this.list = res.data.records || []
        this.total = res.data.total || 0
      }).finally(() => { this.loading = false })
    },
    handleSearch() { this.query.current = 1; this.load() },
    payloadReset() { return { paramsText: '', fileId: null, objectName: '' } },
    statusType(s) {
      return { SUCCESS: 'success', FAILED: 'danger', TIMEOUT: 'warning', CANCELED: 'info' }[s] || ''
    },
    openCreate() { this.createVisible = true },
    submitCreate() {
      const data = { rpcName: this.form.rpcName }
      if (this.targetType === 'unit') {
        data.unitIds = this.form.unitIdsText.split(',').map(s => s.trim()).filter(Boolean)
      } else {
        data.model = this.form.model
      }
      if (this.form.rpcName === 'SetParameterValues' || this.isGet) {
        data.params = this.form.rpcName === 'SetParameterValues' ? this.parse(this.form.paramsText) : this.parse(this.form.paramsText)
        if (this.isGet) { data.names = data.params; delete data.params }
      } else if (this.form.rpcName === 'Download' || this.form.rpcName === 'Upload') {
        data.fileId = Number(this.form.fileId)
      } else if (this.isObject) {
        data.objectName = this.form.objectName
      }
      createTask(data).then(res => {
        this.$message.success('已创建 ' + (res.data.count || 0) + ' 个任务')
        this.createVisible = false
        this.load()
      })
    },
    parse(text) {
      try { return JSON.parse(text) } catch (e) { this.$message.error('JSON 解析失败'); throw e }
    },
    handleCancel() {
      const ids = this.selection.map(r => r.id)
      this.$confirm('确认取消选中的 ' + ids.length + ' 个任务？', '提示', { type: 'warning' })
        .then(() => cancelTasks(ids)).then(() => { this.$message.success('已取消'); this.load() })
        .catch(() => {})
    }
  }
}
</script>

<style scoped>
.app-container { padding: 20px; }
</style>
