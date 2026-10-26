<template>
  <div class="app-container">
    <el-form :inline="true" :model="query" size="small">
      <el-form-item label="关键字">
        <el-input v-model="query.keyword" placeholder="unitId/序列号/型号/IP" clearable />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.online" placeholder="全部" clearable style="width:120px">
          <el-option label="在线" :value="1" />
          <el-option label="离线" :value="0" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" @click="handleSearch">查询</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="list" border stripe size="small">
      <el-table-column prop="unitId" label="设备标识" min-width="220" show-overflow-tooltip />
      <el-table-column prop="serialNumber" label="序列号" min-width="160" show-overflow-tooltip />
      <el-table-column prop="model" label="型号" width="140" />
      <el-table-column prop="firmwareVersion" label="固件" width="130" />
      <el-table-column prop="ipAddress" label="IP" width="130" />
      <el-table-column label="在线" width="70" align="center">
        <template slot-scope="{ row }">
          <el-tag :type="row.online === 1 ? 'success' : 'info'" size="mini">
            {{ row.online === 1 ? '在线' : '离线' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="lastOnline" label="最后上线" width="170" />
      <el-table-column label="操作" width="260" fixed="right">
        <template slot-scope="{ row }">
          <el-button type="text" size="mini" @click="openParams(row)">参数</el-button>
          <el-button type="text" size="mini" @click="handleKick(row)">唤醒</el-button>
          <el-button type="text" size="mini" @click="openEdit(row)">编辑</el-button>
          <el-button type="text" size="mini" style="color:#f56c6c" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      style="margin-top:12px;text-align:right"
      :current-page="query.current"
      :page-size="query.size"
      :total="total"
      layout="total, sizes, prev, pager, next"
      @current-change="page => { query.current = page; load() }"
      @size-change="size => { query.size = size; load() }"
    />

    <!-- 参数 -->
    <el-dialog :title="'参数 - ' + current.unitId" :visible.sync="paramVisible" width="70%">
      <el-table :data="params" border size="small" max-height="420">
        <el-table-column prop="name" label="参数名" min-width="320" show-overflow-tooltip />
        <el-table-column prop="value" label="值" min-width="180" show-overflow-tooltip />
        <el-table-column prop="flags" label="标记" width="80" />
        <el-table-column label="操作" width="80">
          <template slot-scope="{ row }">
            <el-button type="text" size="mini" style="color:#f56c6c" @click="removeParam(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-form :inline="true" size="small" style="margin-top:12px">
        <el-form-item><el-input v-model="paramForm.name" placeholder="参数名" style="width:320px" /></el-form-item>
        <el-form-item><el-input v-model="paramForm.value" placeholder="值" style="width:180px" /></el-form-item>
        <el-form-item>
          <el-select v-model="paramForm.flags" style="width:90px">
            <el-option label="RW" value="RW" />
            <el-option label="R" value="R" />
          </el-select>
        </el-form-item>
        <el-form-item><el-button type="primary" @click="submitParam">保存</el-button></el-form-item>
      </el-form>
    </el-dialog>

    <!-- 编辑 -->
    <el-dialog title="编辑设备" :visible.sync="editVisible" width="420px">
      <el-form :model="editForm" label-width="100px" size="small">
        <el-form-item label="设备账号"><el-input v-model="editForm.account" /></el-form-item>
        <el-form-item label="设备密钥"><el-input v-model="editForm.secret" /></el-form-item>
        <el-form-item label="开通模式">
          <el-select v-model="editForm.provisioningMode" style="width:100%">
            <el-option label="REGULAR" value="REGULAR" />
            <el-option label="READALL" value="READALL" />
            <el-option label="PERIODIC" value="PERIODIC" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="editForm.remark" /></el-form-item>
      </el-form>
      <div slot="footer">
        <el-button size="small" @click="editVisible = false">取消</el-button>
        <el-button size="small" type="primary" @click="submitEdit">确定</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { devicePage, deviceParams, setDeviceParam, deleteDeviceParam, kickDevice, updateDevice, deleteDevice } from '@/api/device'

export default {
  name: 'Device',
  data() {
    return {
      loading: false,
      list: [],
      total: 0,
      query: { current: 1, size: 20, keyword: '', online: null },
      current: {},
      params: [],
      paramVisible: false,
      paramForm: { name: '', value: '', flags: 'RW' },
      editVisible: false,
      editForm: {}
    }
  },
  created() { this.load() },
  methods: {
    load() {
      this.loading = true
      devicePage(this.query).then(res => {
        this.list = res.data.records || []
        this.total = res.data.total || 0
      }).finally(() => { this.loading = false })
    },
    handleSearch() { this.query.current = 1; this.load() },
    openParams(row) {
      this.current = row
      this.paramVisible = true
      deviceParams(row.unitId).then(res => { this.params = res.data || [] })
    },
    submitParam() {
      setDeviceParam(this.current.unitId, this.paramForm).then(() => {
        this.$message.success('已保存')
        deviceParams(this.current.unitId).then(res => { this.params = res.data || [] })
      })
    },
    removeParam(row) {
      deleteDeviceParam(this.current.unitId, row.name).then(() => {
        deviceParams(this.current.unitId).then(res => { this.params = res.data || [] })
      })
    },
    handleKick(row) {
      kickDevice(row.unitId).then(res => {
        const ok = res.data && res.data.success
        this.$message[ok ? 'success' : 'warning'](ok ? '已发送连接请求' : '唤醒失败（可能无反向连接地址）')
      })
    },
    openEdit(row) {
      this.current = row
      this.editForm = { account: row.account, secret: row.secret, provisioningMode: row.provisioningMode, remark: row.remark }
      this.editVisible = true
    },
    submitEdit() {
      updateDevice(this.current.id, this.editForm).then(() => {
        this.$message.success('已保存')
        this.editVisible = false
        this.load()
      })
    },
    handleDelete(row) {
      this.$confirm('确认删除设备 ' + row.unitId + '？', '提示', { type: 'warning' })
        .then(() => deleteDevice(row.id)).then(() => { this.$message.success('已删除'); this.load() })
        .catch(() => {})
    }
  }
}
</script>

<style scoped>
.app-container { padding: 20px; }
</style>
