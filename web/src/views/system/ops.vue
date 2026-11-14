<template>
  <div class="app-container">
    <div class="ops-grid">
      <div v-for="section in sections" :key="section.key" class="ops-block">
        <div class="ops-title">{{ section.title }}</div>
        <div v-for="row in section.rows" :key="row.label" class="ops-row">
          <span class="ops-label">{{ row.label }}</span>
          <a
            v-if="isLink(row)"
            :href="row.value"
            target="_blank"
            rel="noopener"
            class="ops-value ops-link"
          >{{ row.value }}</a>
          <span v-else class="ops-value">{{ row.value }}</span>
        </div>
      </div>
    </div>

    <!-- 远程运维：装维 APP 扫码登录 -->
    <div class="ops-block remote-block">
      <div class="ops-title">远程运维</div>
      <div class="remote-body">
        <div class="remote-qr-row">
          <div class="remote-qr-item">
            <img v-if="qr.lan" class="remote-qr" :src="qr.lan" alt="内网本地登录二维码">
            <div v-else class="remote-qr remote-qr-empty">加载中</div>
            <div class="remote-qr-label">内网本地登录</div>
          </div>
          <div class="remote-qr-item">
            <img v-if="qr.cloud" class="remote-qr" :src="qr.cloud" alt="外网云登录二维码">
            <div v-else class="remote-qr remote-qr-empty">加载中</div>
            <div class="remote-qr-label">外网云登录</div>
          </div>
          <div class="remote-qr-item">
            <img v-if="qr.apk" class="remote-qr" :src="qr.apk" alt="APK 下载安装二维码">
            <div v-else class="remote-qr remote-qr-empty">加载中</div>
            <div class="remote-qr-label">APK 下载安装（Android）</div>
          </div>
        </div>

        <div class="remote-form">
          <div class="remote-line">
            <span class="remote-label">装维APP 扫码登录二维码：</span>
            <el-button type="primary" size="mini" @click="onRegenerate">重新生成</el-button>
            <el-button type="primary" size="mini" plain icon="el-icon-document-copy" @click="onCopyToken">复制 Token</el-button>
          </div>

          <div class="remote-line">
            <span class="remote-label">装维APP 操作权限：</span>
            <el-select v-model="remote.deviceScope" size="mini" style="width: 200px">
              <el-option
                v-for="opt in remote.deviceScopeOptions"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
            <el-checkbox-group v-model="remote.permissions" size="mini">
              <el-checkbox
                v-for="opt in remote.permissionOptions"
                :key="opt.value"
                :label="opt.value"
              >{{ opt.label }}</el-checkbox>
            </el-checkbox-group>
            <el-button type="primary" size="mini" @click="onSave">保存设置</el-button>
          </div>

          <div v-if="remote.tokenCreatedAt" class="remote-line remote-hint">
            Token 生成时间：{{ remote.tokenCreatedAt }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { fetchEnvironment, fetchRemoteOps, saveRemoteOps, regenerateRemoteToken, qrcodeUrl } from '@/api/ops'

const SECTION_TITLES = {
  system: '系统信息',
  server: '服务器配置',
  database: '数据库',
  redis: 'Redis'
}

/** 运维管理。分组与展示行都由后端拼好，页面只渲染。 */
export default {
  name: 'SystemOps',
  data() {
    return {
      sections: [],
      remote: {
        token: '',
        tokenCreatedAt: '',
        permissions: [],
        deviceScope: 'all',
        permissionOptions: [],
        deviceScopeOptions: []
      },
      // 三个二维码的图片地址，内容变化时靠 key 强制刷新
      qr: { lan: '', cloud: '', apk: '' },
      qrSeed: 0
    }
  },
  created() {
    this.load()
    this.loadRemote()
  },
  methods: {
    /** URL 型的值渲染成可点击链接，目前只有 Git 地址 */
    isLink(row) {
      return row.label === 'Git 地址' && /^https?:\/\//.test(row.value)
    },
    load() {
      fetchEnvironment()
        .then(res => {
          const data = res.data || {}
          const sections = Object.keys(SECTION_TITLES)
            .filter(key => data[key])
            .map(key => ({ key, title: SECTION_TITLES[key], rows: [...data[key]] }))
          // 后端单独返回 git 组时并进系统信息，跟系统版本放一起
          const system = sections.find(s => s.key === 'system')
          if (system && Array.isArray(data.git)) {
            system.rows = system.rows.concat(data.git)
          }
          this.sections = sections
        })
        .catch(() => {})
    },
    /** 远程运维配置：Token、权限、可见范围、三个二维码 */
    loadRemote() {
      fetchRemoteOps()
        .then(res => {
          this.applyRemote(res.data || {})
        })
        .catch(() => {})
    },
    applyRemote(data) {
      this.remote.token = data.token || ''
      this.remote.tokenCreatedAt = data.tokenCreatedAt || ''
      this.remote.permissions = Array.isArray(data.permissions) ? [...data.permissions] : []
      this.remote.deviceScope = data.deviceScope || 'all'
      this.remote.permissionOptions = data.permissionOptions || []
      this.remote.deviceScopeOptions = data.deviceScopeOptions || []
      this.refreshQr(data)
    },
    /** 重新拼二维码地址：Token 变了二维码内容也变了，用 seed 破缓存 */
    refreshQr(data) {
      const seed = this.qrSeed++
      this.qr = {
        lan: data.lanQrContent ? qrcodeUrl(data.lanQrContent, 240) + '&t=' + seed : '',
        cloud: data.cloudQrContent ? qrcodeUrl(data.cloudQrContent, 240) + '&t=' + seed : '',
        apk: data.apkQrContent ? qrcodeUrl(data.apkQrContent, 240) + '&t=' + seed : ''
      }
    },
    onRegenerate() {
      this.$confirm('重新生成后，旧 Token 立即失效，确定继续？', '提示', { type: 'warning' })
        .then(() => regenerateRemoteToken())
        .then(res => {
          this.applyRemote(res.data || {})
          this.$message.success('Token 已重新生成')
        })
        .catch(() => {})
    },
    onCopyToken() {
      const token = this.remote.token
      if (!token) {
        this.$message.warning('还没有 Token')
        return
      }
      const text = token
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text)
          .then(() => this.$message.success('Token 已复制'))
          .catch(() => this.copyFallback(text))
      } else {
        this.copyFallback(text)
      }
    },
    /** 老浏览器的兜底复制 */
    copyFallback(text) {
      const input = document.createElement('textarea')
      input.value = text
      input.style.position = 'fixed'
      input.style.opacity = '0'
      document.body.appendChild(input)
      input.select()
      try {
        document.execCommand('copy')
        this.$message.success('Token 已复制')
      } catch (e) {
        this.$message.error('复制失败，请手动选取')
      }
      document.body.removeChild(input)
    },
    onSave() {
      saveRemoteOps({
        permissions: this.remote.permissions,
        deviceScope: this.remote.deviceScope
      })
        .then(res => {
          this.applyRemote(res.data || {})
          this.$message.success('已保存')
        })
        .catch(() => {})
    }
  }
}
</script>

<style scoped>
.ops-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}
.ops-block {
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 10px 12px;
  background: #fff;
}
.ops-title {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 6px;
  padding-bottom: 6px;
  border-bottom: 1px solid #ebeef5;
}
.ops-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 13px;
  line-height: 24px;
}
.ops-label {
  color: #909399;
  white-space: nowrap;
}
.ops-value {
  color: #303133;
  text-align: right;
  word-break: break-all;
}
.ops-link {
  color: #409eff;
  text-decoration: none;
  cursor: pointer;
}
.ops-link:hover {
  text-decoration: underline;
}

/* 远程运维 */
.remote-block {
  margin-top: 10px;
}
.remote-body {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  align-items: flex-start;
}
.remote-qr-row {
  display: flex;
  gap: 20px;
  flex-wrap: wrap;
}
.remote-qr-item {
  text-align: center;
}
.remote-qr {
  width: 140px;
  height: 140px;
  border: 1px solid #ebeef5;
  background: #fff;
}
.remote-qr-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #c0c4cc;
  font-size: 12px;
}
.remote-qr-label {
  margin-top: 6px;
  font-size: 12px;
  color: #606266;
}
.remote-form {
  flex: 1;
  min-width: 320px;
}
.remote-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 14px;
  font-size: 13px;
}
.remote-label {
  color: #909399;
  white-space: nowrap;
}
.remote-hint {
  color: #c0c4cc;
  font-size: 12px;
  margin-bottom: 0;
}
</style>
