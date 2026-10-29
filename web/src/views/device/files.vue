<template>
  <div class="app-container">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="设备从上报端口的 /files/{id} 取文件"
      description="上传走 /api 要令牌，设备下载走 /files/{id} 不要令牌。默认地址按 public-host + 上报端口拼，设备网段不同就在服务端配 acs.file.base-url。"
      style="margin-bottom: 12px"
    />

    <el-upload
      ref="upload"
      :auto-upload="false"
      :limit="1"
      :file-list="fileList"
      :on-change="onFileChange"
    >
      <el-button slot="trigger" size="small" type="primary">选择文件</el-button>
      <!-- 上传、删除、下发都会动设备上的东西，没有写权限就别摆出来 -->
      <el-button
        v-if="$has('file:manage')"
        slot="tip"
        size="small"
        type="success"
        style="margin-left: 10px"
        :loading="uploading"
        @click="onUpload"
      >上传</el-button>
    </el-upload>

    <el-form inline size="small" style="margin-top: 10px">
      <el-form-item label="类型">
        <el-select v-model="fileType" style="width: 160px">
          <el-option label="固件 FIRMWARE" value="FIRMWARE" />
          <el-option label="配置 CONFIG" value="CONFIG" />
          <el-option label="厂商文件 VENDOR" value="VENDOR" />
        </el-select>
      </el-form-item>
      <el-form-item label="版本">
        <el-input v-model="version" placeholder="如 V1.0" style="width: 140px" />
      </el-form-item>
      <span class="tip">单个文件上限 512MB</span>
    </el-form>

    <el-table v-loading="loading" :data="list" border style="width: 100%">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="文件名" min-width="180" />
      <el-table-column label="类型" width="110">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="row.fileType === 'FIRMWARE' ? 'warning' : 'info'">
            {{ row.fileType }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="version" label="版本" width="120" />
      <el-table-column label="大小" width="110">
        <template slot-scope="{ row }">
          <span>{{ sizeText(row.size) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="摘要" min-width="180">
        <template slot-scope="{ row }">
          <span class="sha">{{ short(row.sha256) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="240">
        <template slot-scope="{ row }">
          <el-button v-if="$has('file:manage')" type="text" @click="openDeliver(row)">下发</el-button>
          <el-button type="text" @click="onCopyUrl(row)">取地址</el-button>
          <el-button v-if="$has('file:manage')" type="text" class="danger-text" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog title="下发文件" :visible.sync="dialogVisible" width="620px">
      <el-alert
        v-if="delivering"
        type="info"
        :closable="false"
        title="序列号一行一个，或用逗号分隔"
        description="每台设备各排一条 Download，队列按设备分流，多设备之间不会互相堵。下发后设备会来取文件，下载完上报 TransferComplete。"
        style="margin-bottom: 12px"
      />
      <el-form label-width="100px">
        <el-form-item label="文件">
          <span>{{ delivering ? delivering.name : '' }}</span>
        </el-form-item>
        <el-form-item label="设备">
          <el-input
            v-model="snsText"
            type="textarea"
            :rows="6"
            placeholder="ZXHN000001&#10;ZXHN000002"
          />
        </el-form-item>
        <el-form-item label="延迟秒数">
          <el-input-number v-model="delaySeconds" :min="0" :max="86400" />
        </el-form-item>
        <el-form-item label="按房间号挑">
          <el-input
            v-model="roomNoPicked"
            placeholder="支持模糊匹配，如 0001"
            clearable
            style="width: 220px"
          />
          <el-button
            size="mini"
            style="margin-left: 8px"
            :disabled="!roomNoPicked"
            @click="pickByRoomNo"
          >填进设备</el-button>
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button v-if="$has('file:manage')" type="primary" :loading="saving" @click="onDeliver">下发</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import {
  fetchFiles, fetchFileUrl, uploadFile, deliverFile, deleteFile
} from '@/api/file'
import { fetchDevicePage } from '@/api/device'

export default {
  name: 'FileList',
  data() {
    return {
      list: [],
      loading: false,
      uploading: false,
      saving: false,
      fileList: [],
      fileType: 'FIRMWARE',
      version: '',
      dialogVisible: false,
      delivering: null,
      snsText: '',
      delaySeconds: 0,
      // 按房间号挑设备：台账上登记的分群依据，型号之外最省事的分法
      roomNoPicked: ''
    }
  },
  created() {
    this.load()
  },
  methods: {
    load() {
      this.loading = true
      fetchFiles()
        .then(res => { this.list = (res && res.data) || [] })
        .catch(() => {})
        .finally(() => { this.loading = false })
    },

    sizeText(size) {
      const bytes = Number(size) || 0
      if (bytes < 1024) return bytes + ' B'
      if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
      return (bytes / 1024 / 1024).toFixed(1) + ' MB'
    },

    short(sha) {
      return !sha ? '-' : sha.substring(0, 16) + '…'
    },

    onFileChange(file, list) {
      this.fileList = list.slice(-1)
    },

    onUpload() {
      const file = this.fileList.length ? this.fileList[0] : null
      if (!file) {
        this.$message.warning('先选一个文件')
        return
      }
      const form = new FormData()
      form.append('file', file.raw)
      form.append('fileType', this.fileType)
      if (this.version) {
        form.append('version', this.version)
      }
      this.uploading = true
      uploadFile(form)
        .then(() => {
          this.$message.success('上传成功')
          this.fileList = []
          this.version = ''
          this.load()
        })
        .catch(err => {
          this.$message.error((err && err.message) || '上传失败')
        })
        .finally(() => {
          this.uploading = false
        })
    },

    onCopyUrl(row) {
      fetchFileUrl(row.id)
        .then(res => {
          const url = res && res.data
          this.$alert(url, '设备取文件的地址', { confirmButtonText: '关闭' })
        })
        .catch(err => {
          this.$message.error((err && err.message) || '取地址失败')
        })
    },

    openDeliver(row) {
      this.delivering = row
      this.snsText = ''
      this.delaySeconds = 0
      this.dialogVisible = true
    },

    // 按房间号把设备挑出来，追加到已有的序列号后面，不清掉手填的
    pickByRoomNo() {
      const roomNo = (this.roomNoPicked || '').trim()
      if (!roomNo) return
      fetchDevicePage({ page: 1, size: 200, roomNo })
        .then(res => {
          const data = (res && res.data) || {}
          const sns = (data.items || []).map(d => d.sn).filter(Boolean)
          if (!sns.length) {
            this.$message.warning('这个房间号下面还没有设备')
            return
          }
          const current = this.snsText
            .split(/[\n,;\s]+/)
            .map(s => s.trim())
            .filter(Boolean)
          const merged = current.concat(sns.filter(s => !current.includes(s)))
          this.snsText = merged.join('\n')
          this.$message.success('房间号「' + roomNo + '」下 ' + sns.length + ' 台设备已填上')
        })
        .catch(err => {
          this.$message.error((err && err.message) || '按房间号取设备失败')
        })
    },

    onDeliver() {
      const sns = this.snsText
        .split(/[\n,;\s]+/)
        .map(s => s.trim())
        .filter(Boolean)
      if (!sns.length) {
        this.$message.warning('至少填一台设备')
        return
      }
      this.saving = true
      deliverFile(this.delivering.id, sns.join(','), this.delaySeconds)
        .then(res => {
          const rows = (res && res.data) || []
          const failed = rows.filter(r => !r.queued).length
          this.$message.success(
            failed ? '已提交 ' + rows.length + ' 台，其中 ' + failed + ' 台队列已满'
              : '已下发给 ' + rows.length + ' 台设备'
          )
          this.dialogVisible = false
        })
        .catch(err => {
          this.$message.error((err && err.message) || '下发失败')
        })
        .finally(() => {
          this.saving = false
        })
    },

    onDelete(row) {
      this.$confirm('确定删除文件「' + row.name + '」？', '提示', { type: 'warning' })
        .then(() => deleteFile(row.id))
        .then(() => {
          this.$message.success('已删除')
          this.load()
        })
        .catch(() => {})
    }
  }
}
</script>

<style scoped>
.tip {
  color: #909399;
  font-size: 12px;
  margin-left: 8px;
}
.sha {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  color: #606266;
}
</style>
