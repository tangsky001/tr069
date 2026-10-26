<template>
  <div class="app-container">
    <el-tabs v-model="tab">
      <el-tab-pane label="适配模板" name="template">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="适配数据保存在本机 SQLite（型号 + 固件版本）。未知型号上线且开启 discovery 时会自动采集 GetParameterNames 能力树入库。"
          style="margin-bottom:12px"
        />
        <el-table v-loading="loading" :data="templates" border stripe size="small">
          <el-table-column prop="templateId" label="ID" width="70" />
          <el-table-column prop="deviceBrand" label="厂商" width="120" />
          <el-table-column prop="deviceModel" label="型号" width="160" />
          <el-table-column prop="firmwareVersion" label="固件版本" width="160" />
          <el-table-column prop="paramCount" label="参数数" width="90" />
          <el-table-column prop="templateHash" label="指纹" min-width="180" show-overflow-tooltip />
          <el-table-column prop="updateTime" label="更新时间" width="170" />
          <el-table-column label="操作" width="180">
            <template slot-scope="{ row }">
              <el-button type="text" size="mini" @click="openParams(row)">能力树</el-button>
              <el-button type="text" size="mini" @click="openMaps(row)">参数映射</el-button>
              <el-button type="text" size="mini" style="color:#f56c6c" @click="removeTemplate(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="已适配设备" name="device">
        <el-table :data="adapted" border stripe size="small">
          <el-table-column prop="serialNumber" label="序列号" min-width="200" />
          <el-table-column prop="templateId" label="模板ID" width="100" />
          <el-table-column prop="successCount" label="命中次数" width="100" />
          <el-table-column prop="lastMatchAt" label="最近命中" width="180" />
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 能力树 -->
    <el-dialog :title="'能力树 - ' + current.deviceModel + ' ' + current.firmwareVersion" :visible.sync="paramVisible" width="70%">
      <el-input v-model="keyword" placeholder="按参数名过滤" size="small" clearable style="margin-bottom:10px" @input="loadParams" />
      <el-table :data="paramList" border size="small" max-height="440">
        <el-table-column prop="name" label="参数名" min-width="360" show-overflow-tooltip />
        <el-table-column label="可写" width="80" align="center">
          <template slot-scope="{ row }">
            <el-tag :type="row.writable ? 'success' : 'info'" size="mini">{{ row.writable ? 'RW' : 'R' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="type" label="类型" width="140" />
      </el-table>
      <div style="margin-top:8px;color:#909399;font-size:12px">共 {{ paramTotal }} 个参数</div>
    </el-dialog>

    <!-- 参数映射 -->
    <el-dialog :title="'参数映射 - 模板 ' + current.templateId" :visible.sync="mapVisible" width="70%">
      <el-table :data="maps" border size="small">
        <el-table-column prop="informPath" label="Inform 路径" min-width="220" show-overflow-tooltip />
        <el-table-column prop="getPath" label="取值路径" min-width="220" show-overflow-tooltip />
        <el-table-column prop="setPath" label="写值路径" min-width="220" show-overflow-tooltip />
        <el-table-column prop="valueType" label="类型" width="110" />
        <el-table-column label="操作" width="80">
          <template slot-scope="{ row }">
            <el-button type="text" size="mini" style="color:#f56c6c" @click="removeMap(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-form :inline="true" size="small" style="margin-top:12px">
        <el-form-item><el-input v-model="mapForm.getPath" placeholder="取值路径" style="width:220px" /></el-form-item>
        <el-form-item><el-input v-model="mapForm.setPath" placeholder="写值路径" style="width:220px" /></el-form-item>
        <el-form-item><el-input v-model="mapForm.informPath" placeholder="Inform 路径" style="width:200px" /></el-form-item>
        <el-form-item>
          <el-select v-model="mapForm.valueType" style="width:110px">
            <el-option label="string" value="string" />
            <el-option label="int" value="int" />
            <el-option label="boolean" value="boolean" />
            <el-option label="base64" value="base64" />
          </el-select>
        </el-form-item>
        <el-form-item><el-button type="primary" @click="submitMap">新增</el-button></el-form-item>
      </el-form>
    </el-dialog>
  </div>
</template>

<script>
import { templates, deleteTemplate, templateParams, paramMaps, saveParamMap, deleteParamMap, adaptedDevices } from '@/api/adapt'

export default {
  name: 'Adapt',
  data() {
    return {
      tab: 'template',
      loading: false,
      templates: [],
      adapted: [],
      current: {},
      paramVisible: false,
      paramList: [],
      paramTotal: 0,
      keyword: '',
      mapVisible: false,
      maps: [],
      mapForm: { templateId: null, informPath: '', getPath: '', setPath: '', valueType: 'string' }
    }
  },
  created() {
    this.load()
    adaptedDevices().then(res => { this.adapted = res.data || [] })
  },
  methods: {
    load() {
      this.loading = true
      templates().then(res => { this.templates = res.data || [] }).finally(() => { this.loading = false })
    },
    openParams(row) {
      this.current = row
      this.keyword = ''
      this.paramVisible = true
      this.loadParams()
    },
    loadParams() {
      templateParams(this.current.templateId, this.keyword).then(res => {
        const d = res.data || {}
        this.paramList = d.list || []
        this.paramTotal = d.total || 0
      })
    },
    removeTemplate(row) {
      this.$confirm('确认删除模板 ' + row.deviceModel + '？', '提示', { type: 'warning' })
        .then(() => deleteTemplate(row.templateId)).then(() => { this.$message.success('已删除'); this.load() })
        .catch(() => {})
    },
    openMaps(row) {
      this.current = row
      this.mapVisible = true
      this.mapForm.templateId = row.templateId
      paramMaps(row.templateId).then(res => { this.maps = res.data || [] })
    },
    submitMap() {
      saveParamMap(this.mapForm).then(() => {
        this.$message.success('已保存')
        paramMaps(this.current.templateId).then(res => { this.maps = res.data || [] })
      })
    },
    removeMap(row) {
      deleteParamMap(row.id).then(() => {
        paramMaps(this.current.templateId).then(res => { this.maps = res.data || [] })
      })
    }
  }
}
</script>

<style scoped>
.app-container { padding: 20px; }
</style>
