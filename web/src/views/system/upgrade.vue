<template>
  <div class="app-container">
    <el-card shadow="never" style="margin-bottom: 20px;">
      <div slot="header" class="card-header">
        <span>版本信息</span>
        <div style="float: right;">
          <el-button size="small" type="primary" :loading="checkLoading" icon="el-icon-refresh" @click="handleCheckUpgrade">检查更新</el-button>
          <el-button size="small" type="warning" icon="el-icon-upload2" @click="uploadDialogVisible = true">手动上传升级</el-button>
        </div>
      </div>

      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="当前版本">
          <el-tag type="success" size="small">{{ currentVersion || '-' }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="服务器最新版本">
          <span v-if="!serverVersionInfo">
            <el-tag type="info" size="small">未检查</el-tag>
          </span>
          <span v-else-if="serverVersionInfo.hasUpdate">
            <el-tag type="danger" size="small">{{ serverVersionInfo.serverVersion }}</el-tag>
            <el-button size="mini" type="danger" style="margin-left: 10px;" @click="handleShowUpgradeDialog">立即升级</el-button>
          </span>
          <span v-else>
            <el-tag type="success" size="small">{{ serverVersionInfo.serverVersion || '已是最新' }}</el-tag>
            <span style="color: #67c23a; font-size: 12px; margin-left: 8px;">已是最新版本</span>
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="升级说明" :span="2">
          <span v-if="serverVersionInfo && serverVersionInfo.description" style="white-space: pre-wrap;">{{ serverVersionInfo.description }}</span>
          <span v-else style="color: #909399;">-</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-dialog title="版本升级" :visible.sync="upgradeDialogVisible" width="520px" :close-on-click-modal="false" @close="handleUpgradeDialogClose">
      <div v-if="serverVersionInfo">
        <el-alert :title="`发现新版本 ${serverVersionInfo.serverVersion}（当前：${currentVersion}）`" type="warning" :closable="false" show-icon style="margin-bottom: 16px;" />
        <div style="background: #f5f7fa; padding: 12px; border-radius: 4px; margin-bottom: 16px;">
          <div style="font-size: 13px; color: #606266; font-weight: 600; margin-bottom: 8px;">升级说明：</div>
          <div style="font-size: 13px; color: #303133; white-space: pre-wrap; line-height: 1.6;">{{ serverVersionInfo.description || '暂无说明' }}</div>
        </div>
        <div style="font-size: 13px; color: #606266; margin-bottom: 8px;">
          文件大小：<strong>{{ formatSize(serverVersionInfo.fileSize) }}</strong>
        </div>

        <div v-if="downloading" style="margin-top: 16px;">
          <div style="display: flex; justify-content: space-between; margin-bottom: 6px; font-size: 13px; color: #606266;">
            <span>下载进度（后台多线程）</span>
            <span>{{ formatSize(downloadedBytes) }} / {{ formatSize(totalFileSize) }}（{{ downloadPercent }}%）</span>
          </div>
          <el-progress :percentage="downloadPercent" :status="downloadPercent === 100 ? 'success' : ''" :stroke-width="14" />
          <div style="font-size: 12px; color: #909399; margin-top: 6px; text-align: center;">
            服务端正在多线程下载，完成后将自动保存为 pilotacs-server.jar ...
          </div>
        </div>

        <div v-if="downloadDone" style="margin-top: 12px; padding: 12px; background: #f0f9eb; border-radius: 4px; border: 1px solid #e1f3d8;">
          <div style="text-align: center; color: #67c23a; font-weight: 600; margin-bottom: 8px;">
            <i class="el-icon-circle-check" /> 下载完成！文件已保存为 pilotacs-server.jar
          </div>
          <div style="font-size: 12px; color: #909399; text-align: center;">
            点击下方"确认升级并退出"按钮，进程将退出，请确保守护程序会自动使用新版本重启服务。
          </div>
        </div>

        <div v-if="downloadError" style="margin-top: 12px; color: #f56c6c; font-size: 13px;">
          <i class="el-icon-warning-outline" /> 下载失败：{{ downloadError }}
        </div>
      </div>

      <div slot="footer">
        <el-button @click="upgradeDialogVisible = false" :disabled="downloading || restarting">取消</el-button>
        <el-button v-if="!downloadDone" type="primary" :loading="downloading" @click="handleStartDownload">
          {{ downloading ? '下载中...' : '确认升级（下载）' }}
        </el-button>
        <template v-else>
          <el-button @click="upgradeDialogVisible = false" :disabled="restarting">稍后升级</el-button>
          <el-button type="danger" :loading="restarting" icon="el-icon-switch-button" @click="handleConfirmRestart">
            {{ restarting ? '正在退出...' : '确认升级并退出' }}
          </el-button>
        </template>
      </div>
    </el-dialog>

    <el-dialog title="手动上传升级包" :visible.sync="uploadDialogVisible" width="480px" :close-on-click-modal="false">
      <el-alert title="上传的JAR文件将保存为运行目录下的 pilotacs-server.jar" type="info" :closable="false" show-icon style="margin-bottom: 16px;" />
      <el-upload ref="uploadRef" :auto-upload="false" accept=".jar" :limit="1" :on-change="handleUploadFileChange" :on-exceed="() => $message.warning('只能上传一个JAR文件')" drag style="width: 100%;">
        <i class="el-icon-upload" />
        <div class="el-upload__text">拖拽或 <em>点击上传</em> .jar 文件</div>
        <div slot="tip" class="el-upload__tip">只支持 .jar 格式文件</div>
      </el-upload>

      <div v-if="uploadProgress > 0" style="margin-top: 14px;">
        <div style="display: flex; justify-content: space-between; margin-bottom: 4px; font-size: 13px; color: #606266;">
          <span>上传进度</span>
          <span>{{ formatSize(uploadedBytes) }} / {{ formatSize(uploadTotalBytes) }}（{{ uploadProgress }}%）</span>
        </div>
        <el-progress :percentage="uploadProgress" :status="uploadProgress === 100 ? 'success' : ''" :stroke-width="12" />
      </div>

      <div v-if="uploadDone" style="margin-top: 12px; text-align: center; color: #67c23a; font-weight: 600;">
        <i class="el-icon-circle-check" /> 上传成功！文件已保存为 pilotacs-server.jar
      </div>

      <div slot="footer">
        <el-button @click="handleCloseUploadDialog" :disabled="uploading">取消</el-button>
        <el-button v-if="!uploadDone" type="primary" :loading="uploading" @click="handleSubmitUpload">
          {{ uploading ? '上传中...' : '开始上传' }}
        </el-button>
        <el-button v-else type="success" @click="handleCloseUploadDialog">关闭</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import {
  checkUpgrade,
  startDownload,
  getDownloadProgress,
  uploadUpgrade,
  confirmRestart,
  cancelUpgrade
} from '@/api/upgrade'

export default {
  name: 'SystemUpgrade',
  data() {
    return {
      currentVersion: '',
      checkLoading: false,
      serverVersionInfo: null,
      upgradeDialogVisible: false,
      downloading: false,
      downloadedBytes: 0,
      totalFileSize: 0,
      downloadPercent: 0,
      downloadDone: false,
      downloadError: '',
      progressTimer: null,
      restarting: false,
      uploadDialogVisible: false,
      uploading: false,
      uploadFile: null,
      uploadProgress: 0,
      uploadedBytes: 0,
      uploadTotalBytes: 0,
      uploadDone: false
    }
  },
  created() {
    this.handleCheckUpgrade()
  },
  beforeDestroy() {
    this.stopProgressTimer()
  },
  methods: {
    handleCheckUpgrade() {
      this.checkLoading = true
      this.serverVersionInfo = null
      checkUpgrade()
        .then(res => {
          const data = res.data
          this.serverVersionInfo = data
          this.currentVersion = data.currentVersion || ''
          if (data.hasUpdate) {
            this.$message({ type: 'warning', message: `发现新版本 ${data.serverVersion}，当前版本 ${data.currentVersion}` })
          } else {
            this.$message({ type: 'success', message: '已是最新版本' })
          }
        })
        .catch(err => {
          this.$message({ type: 'error', message: '检查更新失败：' + (err.message || '网络错误') })
        })
        .finally(() => {
          this.checkLoading = false
        })
    },

    handleShowUpgradeDialog() {
      this.downloadDone = false
      this.downloadError = ''
      this.downloadedBytes = 0
      this.downloadPercent = 0
      this.totalFileSize = this.serverVersionInfo ? (this.serverVersionInfo.fileSize || 0) : 0
      this.upgradeDialogVisible = true
    },

    handleUpgradeDialogClose() {
      this.stopProgressTimer()
      if (!this.restarting) {
        cancelUpgrade().catch(() => {})
      }
      this.downloading = false
      this.downloadDone = false
      this.downloadError = ''
      this.downloadedBytes = 0
      this.downloadPercent = 0
    },

    handleStartDownload() {
      if (!this.serverVersionInfo || !this.serverVersionInfo.downloadUrl) {
        this.$message.error('下载地址无效，请重新检查更新')
        return
      }
      this.downloading = true
      this.downloadDone = false
      this.downloadError = ''
      this.downloadedBytes = 0
      this.downloadPercent = 0
      this.totalFileSize = this.serverVersionInfo.fileSize || 0

      startDownload(this.serverVersionInfo.downloadUrl, this.serverVersionInfo.fileSize || 0)
        .then(res => {
          if (res.data && res.data.success === false) {
            this.downloading = false
            this.$message.error(res.data.message || '启动下载失败')
            return
          }
          this.startProgressTimer()
        })
        .catch(err => {
          this.downloading = false
          this.$message.error('启动下载失败：' + (err.message || '网络错误'))
        })
    },

    startProgressTimer() {
      this.stopProgressTimer()
      this.progressTimer = setInterval(() => {
        getDownloadProgress()
          .then(res => {
            const d = res.data || res
            this.downloadedBytes = d.loaded || 0
            this.totalFileSize = d.total || this.totalFileSize
            this.downloadPercent = d.percent || 0
            if (d.status === 'done') {
              this.downloadPercent = 100
              this.downloadDone = true
              this.downloading = false
              this.stopProgressTimer()
              this.$message.success('升级包下载完成！已保存为 pilotacs-server.jar')
            } else if (d.status === 'error') {
              this.downloadError = d.error || '未知错误'
              this.downloading = false
              this.stopProgressTimer()
              this.$message.error('下载失败：' + this.downloadError)
            }
          })
          .catch(() => {})
      }, 1000)
    },

    stopProgressTimer() {
      if (this.progressTimer) {
        clearInterval(this.progressTimer)
        this.progressTimer = null
      }
    },

    handleConfirmRestart() {
      this.$confirm(
        '进程退出后服务将暂时不可用，请确保守护程序（如 systemd/supervisor/start.sh）会自动用新版本重启。是否继续？',
        '确认升级并退出',
        { confirmButtonText: '确认退出', cancelButtonText: '取消', type: 'warning', confirmButtonClass: 'el-button--danger' }
      ).then(() => {
        this.restarting = true
        confirmRestart()
          .then(res => {
            const data = res.data
            if (data && data.success) {
              this.$message({ type: 'success', message: '进程已退出，服务即将重启，请稍候刷新页面', duration: 8000 })
              this.upgradeDialogVisible = false
            } else {
              this.$message.error((data && data.message) || '退出失败')
            }
          })
          .catch(() => {
            this.$message({ type: 'success', message: '进程已退出，服务即将重启，请稍候刷新页面', duration: 8000 })
            this.upgradeDialogVisible = false
          })
          .finally(() => {
            this.restarting = false
          })
      }).catch(() => {})
    },

    handleUploadFileChange(file) {
      this.uploadFile = file.raw
    },

    handleCloseUploadDialog() {
      this.uploadDialogVisible = false
      this.uploadProgress = 0
      this.uploadedBytes = 0
      this.uploadTotalBytes = 0
      this.uploadDone = false
      this.uploadFile = null
      this.$refs.uploadRef && this.$refs.uploadRef.clearFiles()
    },

    handleSubmitUpload() {
      if (!this.uploadFile) {
        this.$message.warning('请先选择JAR文件')
        return
      }
      this.uploading = true
      this.uploadProgress = 0
      this.uploadedBytes = 0
      this.uploadTotalBytes = this.uploadFile.size

      const formData = new FormData()
      formData.append('file', this.uploadFile)

      uploadUpgrade(formData, (progressEvent) => {
        if (progressEvent.total) {
          this.uploadTotalBytes = progressEvent.total
          this.uploadedBytes = progressEvent.loaded
          this.uploadProgress = Math.min(100, Math.round((progressEvent.loaded / progressEvent.total) * 100))
        }
      })
        .then(res => {
          const data = res.data
          if (data && data.code === 200) {
            this.uploadDone = true
            this.uploadProgress = 100
            this.$message.success('上传成功！文件已保存为 pilotacs-server.jar')
          } else {
            this.$message.error('上传失败：' + ((data && data.message) || '未知错误'))
          }
        })
        .catch(err => {
          this.$message.error('上传失败：' + (err.message || '网络错误'))
        })
        .finally(() => {
          this.uploading = false
        })
    },

    formatSize(bytes) {
      if (!bytes || bytes === 0) return '0 B'
      const k = 1024
      const sizes = ['B', 'KB', 'MB', 'GB']
      const i = Math.floor(Math.log(bytes) / Math.log(k))
      return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
    }
  }
}
</script>

<style scoped>
.card-header {
  font-weight: 600;
}
</style>
