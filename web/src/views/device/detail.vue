<template>
  <div class="app-container" v-loading="loading">
    <div class="page-head">
      <el-button icon="el-icon-arrow-left" size="small" @click="goBack">返回</el-button>
      <span class="page-title">设备详情</span>
      <el-button
        v-if="loadError"
        type="primary"
        size="small"
        icon="el-icon-refresh"
        style="margin-left:12px"
        @click="load"
      >重新加载</el-button>
    </div>

    <!-- 台账里没有这个 SN 时不要只留一句「设备不存在」：
         清过档案、设备刚换机、URL 手敲错的 SN 都会走到这里，得告诉人下一步干什么 -->
    <el-alert
      v-if="loadError"
      :title="loadError"
      :description="errorHint"
      type="error"
      show-icon
      :closable="false"
      class="section"
    />

    <el-card shadow="never" class="section">
      <div slot="header">基本信息</div>
      <el-row :gutter="12" class="kv">
        <el-col :span="8"><span class="k">序列号</span><span class="v">{{ device.sn || '-' }}</span></el-col>
        <el-col :span="8"><span class="k">OUI</span><span class="v">{{ device.oui || '-' }}</span></el-col>
        <el-col :span="8"><span class="k">厂商</span><span class="v">{{ device.manufacturer || '-' }}</span></el-col>
        <el-col :span="8"><span class="k">产品型号</span><span class="v">{{ device.productClass || '-' }}</span></el-col>
        <el-col :span="8"><span class="k">软件版本</span><span class="v">{{ device.softwareVersion || '-' }}</span></el-col>
        <el-col :span="8"><span class="k">硬件版本</span><span class="v">{{ device.hardwareVersion || '-' }}</span></el-col>
        <el-col :span="8"><span class="k">运营商</span><span class="v">{{ device.isp || '-' }} {{ device.ispVersion || '' }}</span></el-col>
        <el-col :span="8"><span class="k">设备类型</span><span class="v">{{ device.deviceType || '-' }}</span></el-col>
        <el-col :span="8"><span class="k">根节点</span><span class="v">{{ device.rootNode || '-' }}</span></el-col>
        <el-col :span="8"><span class="k">CWMP 版本</span><span class="v">{{ device.cwmpVersion || '-' }}</span></el-col>
        <el-col :span="8"><span class="k">认证方式</span><span class="v">{{ device.authType || '-' }}</span></el-col>
        <el-col :span="8"><span class="k">状态</span><span class="v">{{ device.status || '-' }}</span></el-col>
        <el-col :span="24"><span class="k">最近上报</span><span class="v">{{ formatTime(device.lastContactTime) }}</span></el-col>
        <el-col :span="24"><span class="k">连接请求地址</span><span class="v">{{ device.connectionRequestUrl || '-' }}</span></el-col>
      </el-row>
    </el-card>

    <el-card shadow="never" class="section">
      <div slot="header">
        会话状态
        <el-button type="text" style="float:right;padding:0" icon="el-icon-refresh" @click="loadStatus">刷新</el-button>
      </div>
      <el-row :gutter="12" class="kv">
        <el-col :span="6">
          <span class="k">在线</span>
          <span class="v">
            <el-tag :type="session.online ? 'success' : 'info'" size="small">
              {{ session.online ? '在线' : '离线' }}
            </el-tag>
          </span>
        </el-col>
        <el-col :span="6"><span class="k">状态</span><span class="v">{{ session.state || '-' }}</span></el-col>
        <el-col :span="6"><span class="k">待发消息</span><span class="v">{{ session.pendingCount == null ? '-' : session.pendingCount }}</span></el-col>
        <el-col :span="6"><span class="k">最近交互</span><span class="v">{{ formatTime(session.lastContactTime) }}</span></el-col>
      </el-row>
    </el-card>

    <el-card shadow="never" class="section">
      <div slot="header">业务信息（WAN / WLAN / SIP）</div>
      <device-business-panel :sn="sn" />
    </el-card>

    <el-card shadow="never" class="section">
      <div slot="header">下发 RPC</div>
      <el-form :inline="true" size="small">
        <el-form-item label="方法">
          <el-select v-model="rpcName" filterable allow-create default-first-option placeholder="选择或输入 CWMP 方法" style="width:260px">
            <el-option v-for="m in rpcMethods" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="rpcLoading" :disabled="!rpcName" @click="dispatch">下发</el-button>
        </el-form-item>
      </el-form>
      <el-input
        v-model="rpcArgs"
        type="textarea"
        :rows="5"
        placeholder='下发参数，JSON 格式。例如 GetParameterValues：{"ParameterNames":["InternetGatewayDevice.DeviceInfo.SerialNumber"]}'
      />
      <div v-if="rpcTask" class="rpc-result">
        <div class="rpc-result-title">
          任务号 {{ rpcTask.taskId }}：
          <el-tag :type="rpcStatusType" size="small">{{ rpcStatusText }}</el-tag>
          <el-button v-if="polling" type="text" size="mini" @click="stopPoll">停止等待</el-button>
        </div>
        <pre>{{ formatJson(rpcTask.resultContent || rpcTask) }}</pre>
      </div>
    </el-card>
  </div>
</template>

<script>
import { fetchDeviceDetail, fetchDeviceStatus, dispatchRpc, RPC_METHODS } from '@/api/device'
import { fetchRpcResult } from '@/api/rpc'
import DeviceBusinessPanel from '@/components/DeviceBusinessPanel'

export default {
  name: 'DeviceDetail',
  components: { DeviceBusinessPanel },
  data() {
    return {
      loading: false,
      rpcLoading: false,
      // 台账里查不到该 SN 时记下原因，页面顶部给可读的提示而不是只弹一下红条
      loadError: '',
      device: {},
      session: {},
      rpcMethods: RPC_METHODS,
      rpcName: 'GetParameterValues',
      rpcArgs: '{}',
      // 下发是异步的：提交只拿到任务号，结果靠轮询获取
      rpcTask: null,
      polling: false,
      pollTimer: null,
      pollCount: 0
    }
  },
  computed: {
    sn() {
      return this.$route.params.sn
    },
    /** 出错时的补充说明：清过档案、设备还没上报入库、或 SN 敲错，都会落到这里。 */
    errorHint() {
      return '当前 SN：' + (this.sn || '（空）') +
        '。设备上报过一次 Inform 才会登记到台账；' +
        '如果刚清过该型号的档案，等设备下一次上报后点「重新加载」，' +
        '或核对序列号是否填错。'
    },
    rpcStatusText() {
      const text = { queued: '排队中', running: '执行中', success: '成功', fail: '失败' }
      return text[this.rpcTask && this.rpcTask.status] || '未知'
    },
    rpcStatusType() {
      const type = { queued: 'info', running: 'warning', success: 'success', fail: 'danger' }
      return type[this.rpcTask && this.rpcTask.status] || 'info'
    }
  },
  created() {
    this.load()
  },
  beforeDestroy() {
    this.stopPoll()
  },
  methods: {
    async load() {
      this.loading = true
      this.loadError = ''
      try {
        const res = await fetchDeviceDetail(this.sn)
        const data = res.data || {}
        this.device = data.device || {}
        this.session = data.session || { online: false }
      } catch (e) {
        this.device = {}
        this.session = { online: false }
        this.loadError = (e && (e.message || e.msg)) || '设备不存在'
      } finally {
        this.loading = false
      }
    },
    async loadStatus() {
      try {
        const res = await fetchDeviceStatus(this.sn)
        this.session = res.data || {}
      } catch (e) {
        /* 状态查询失败不影响页面 */
      }
    },
    async dispatch() {
      let args = {}
      try {
        args = this.rpcArgs && this.rpcArgs.trim() ? JSON.parse(this.rpcArgs) : {}
      } catch (e) {
        this.$message.error('参数不是合法 JSON：' + e.message)
        return
      }
      this.rpcLoading = true
      try {
        const res = await dispatchRpc(this.sn, this.rpcName, args)
        const data = res.data || {}
        // 异步语义：这里只表示指令被受理，设备回应要等后续的空 POST 捎带，靠轮询拿
        if (!data.queued) {
          this.$message.error('下发未受理：队列不可用或该设备队列已满')
          this.rpcTask = { taskId: data.taskId, status: 'fail' }
          return
        }
        this.rpcTask = { taskId: data.taskId, status: 'queued' }
        this.$message.success('指令已受理，任务号 ' + data.taskId)
        this.startPoll(data.taskId)
      } catch (e) {
        this.$message.error('下发失败：' + (e.message || ''))
        this.rpcTask = null
      } finally {
        this.rpcLoading = false
      }
    },
    startPoll(taskId) {
      this.stopPoll()
      this.polling = true
      this.pollCount = 0
      this.pollTimer = setInterval(() => { this.pollResult(taskId) }, 2000)
      this.pollResult(taskId)
    },
    async pollResult(taskId) {
      this.pollCount += 1
      try {
        const res = await fetchRpcResult(taskId)
        const data = res.data
        if (data) {
          this.rpcTask = data
          if (data.status === 'success') {
            this.stopPoll()
            this.$message.success('执行成功')
          } else if (data.status === 'fail') {
            this.stopPoll()
            this.$message.warning('执行失败：' + (data.faultString || data.faultCode || ''))
          }
        }
      } catch (e) {
        /* 单次查询失败不打断，由次数上限兜底 */
      }
      // 约 5 分钟仍没有结果就停下，避免页面一直转
      if (this.pollCount >= 150) {
        this.stopPoll()
        this.$message.info('指令仍在执行，稍后可再查任务号 ' + taskId)
      }
    },
    stopPoll() {
      this.polling = false
      if (this.pollTimer) {
        clearInterval(this.pollTimer)
        this.pollTimer = null
      }
    },
    goBack() {
      this.$router.push({ name: 'DeviceList' })
    },
    formatTime(value) {
      if (!value) return '-'
      return String(value).replace('T', ' ')
    },
    formatJson(value) {
      if (value === null || value === undefined) return ''
      if (typeof value === 'string') return value
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
.section {
  margin-top: 16px;
}
.kv .k {
  display: inline-block;
  width: 96px;
  color: #909399;
}
.kv .v {
  color: #303133;
  word-break: break-all;
}
.kv .el-col {
  margin-bottom: 8px;
}
.rpc-result {
  margin-top: 12px;
}
.rpc-result-title {
  margin-bottom: 6px;
}
.rpc-result pre {
  max-height: 320px;
  overflow: auto;
  background: #f5f7fa;
  padding: 12px;
  border-radius: 4px;
  margin: 0;
}
</style>
