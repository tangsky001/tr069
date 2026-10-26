<template>
  <div class="app-container">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="上传的固件/配置文件存于 MySQL，供 Download RPC 下发；设备下载地址为 /api/files/{id}/download"
      style="margin-bottom:12px"
    />

    <el-form :inline="true" size="small">
      <el-form-item label="文件">
        <el-upload
          :show-file-list="false"
          :before-upload="beforeUpload"
          :http-request="doUpload"
          accept="*"
        >
          <el-button type="primary" icon="el-icon-upload">上传文件</el-button>
        </el-upload>
      </el-form-item>
      <el-form-item label="类型">
        <el-select v-model="meta.type" style="width:150px">
          <el-option label="固件 SOFTWARE" value="SOFTWARE" />
          <el-option label="配置 VENDOR" value="VENDOR" />
        </el-select>
      </el-form-item>
      <el-form-item label="版本">
        <el-input v-model="meta.version" placeholder="版本号" style="width:150px" />
      </el-form-item>
      <el-form-item label="下发目标名">
        <el-input v-model="meta.targetName" placeholder="设备端保存的文件名" style="width:200px" />
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="list" border stripe size="small">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="文件名" min-width="220" show-overflow-tooltip />
      <el-table-column prop="type" label="类型" width="120" />
      <el-table-column prop="version" label="版本" width="140" />
      <el-table-column prop="targetName" label="下发目标名" min-width="180" show-overflow-tooltip />
      <el-table-column label="大小" width="110">
        <template slot-scope="{ row }">{{ formatSize(row.length) }}</template>
      </el-table-column>
      <el-table-column prop="createTime" label="上传时间" width="170" />
      <el-table-column label="操作" width="200">
        <template slot-scope="{ row }">
          <el-button type="text" size="mini" @click="copyUrl(row)">复制下载地址</el-button>
          <el-button type="text" size="mini" style="color:#f56c6c" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script>
import axios from 'axios'
import { fileList, deleteFile, downloadUrl } from '@/api/dashboard'

export default {
  name: 'FileManage',
  data() {
    return {
      loading: false,
      list: [],
      meta: { type: 'SOFTWARE', version: '', targetName: '' }
    }
  },
  created() { this.load() },
  methods: {
    load() {
      this.loading = true
      fileList().then(res => { this.list = res.data || [] }).finally(() => { this.loading = false })
    },
    beforeUpload() { return true },
    doUpload({ file }) {
      const form = new FormData()
      form.append('file', file)
      form.append('type', this.meta.type)
      form.append('version', this.meta.version)
      form.append('targetName', this.meta.targetName || file.name)
      const token = this.$store.getters.token
      return axios.post('/api/files', form, { headers: { 'X-Token': token } })
        .then(() => { this.$message.success('上传成功'); this.load() })
        .catch(() => { this.$message.error('上传失败') })
    },
    formatSize(n) {
      if (!n) return '-'
      if (n < 1024) return n + ' B'
      if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
      return (n / 1024 / 1024).toFixed(1) + ' MB'
    },
    copyUrl(row) {
      const url = window.location.origin + downloadUrl(row.id)
      this.$message.success('下载地址：' + url)
    },
    handleDelete(row) {
      this.$confirm('确认删除文件 ' + row.name + '？', '提示', { type: 'warning' })
        .then(() => deleteFile(row.id)).then(() => { this.$message.success('已删除'); this.load() })
        .catch(() => {})
    }
  }
}
</script>

<style scoped>
.app-container { padding: 20px; }
</style>
