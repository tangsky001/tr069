<template>
  <el-dialog
    :title="isEdit ? '编辑 OLT' : '添加 OLT'"
    :visible.sync="innerVisible"
    width="640px"
    top="6vh"
    :close-on-click-modal="false"
    append-to-body
    @open="handleOpen"
  >
    <el-form ref="form" :model="form" :rules="rules" label-width="165px" size="small" class="olt-form">
      <el-form-item label="OLT 名称">
        <el-input v-model="form.name" placeholder="留空则使用 OLT 地址作为名称" clearable />
      </el-form-item>

      <el-form-item label="OLT 地址" prop="host">
        <el-input v-model="form.host" placeholder="输入IP或域名" clearable />
      </el-form-item>

      <el-form-item label="登录类型">
        <el-radio-group v-model="form.protocol" @change="onProtocolChange">
          <el-radio label="TELNET">Telnet</el-radio>
          <el-radio label="SSH">SSH</el-radio>
        </el-radio-group>
      </el-form-item>

      <el-form-item :label="loginLabel + ' 登录账号'">
        <el-input v-model="form.username" placeholder="默认为 root" clearable />
      </el-form-item>

      <el-form-item :label="loginLabel + ' 登录密码'">
        <el-input
          v-model="form.password"
          type="password"
          :placeholder="passwordPlaceholder"
          show-password
          clearable
        />
      </el-form-item>

      <el-form-item :label="loginLabel + ' 端口'">
        <el-input-number
          v-model="form.port"
          :min="1"
          :max="65535"
          :controls="false"
          size="small"
          class="port-input"
        />
        <span class="form-tip">1-65535，默认为 {{ defaultPort }}</span>
      </el-form-item>

      <el-form-item label="字符集编码">
        <el-select v-model="form.charset" size="small" style="width:200px">
          <el-option v-for="item in charsetOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>

      <el-form-item label="绑定本地访问出口IP">
        <div class="ip-row">
          <el-input v-model="form.bindLocalIp" placeholder="选填，多网卡时指定源 IP" clearable />
          <el-tooltip placement="top">
            <div slot="content">
              设备有多条出口路由时，在这里固定源 IP，<br>
              避免采集请求从错误的网卡发出被设备拒绝。
            </div>
            <i class="el-icon-question tip-icon" />
          </el-tooltip>
        </div>
      </el-form-item>

      <el-form-item label="允许访问的Web登录账号">
        <div class="account-box">
          <div class="account-head">
            <el-button type="text" size="mini" @click="selectAllAccounts">全选</el-button>
            <span class="account-sep">/</span>
            <el-button type="text" size="mini" @click="clearAccounts">全不选</el-button>
            <span v-if="form.webAccounts.length === 0" class="form-tip">未选择表示不限制</span>
          </div>
          <el-checkbox-group v-model="form.webAccounts" class="account-group">
            <el-checkbox v-for="account in accountOptions" :key="account.username" :label="account.username">
              {{ account.nickname ? account.nickname + '（' + account.username + '）' : account.username }}
            </el-checkbox>
          </el-checkbox-group>
          <div v-if="accountOptions.length === 0" class="form-tip">暂无可选账号</div>
        </div>
      </el-form-item>

      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="选填" maxlength="200" show-word-limit />
      </el-form-item>

      <el-form-item label="识别厂商">
        <span v-if="form.vendor" class="detected-row">
          <el-tag size="mini" type="success">{{ vendorLabel }}</el-tag>
          <span v-if="form.model" class="detected-model">{{ form.model }}</span>
          <span class="form-tip">由「测试连接」自动识别，保存后按厂商进入对应页面</span>
        </span>
        <span v-else class="form-tip">
          未识别。点下方「测试连接」自动识别；未识别的保存在同步后会补齐，暂时走通用视图
        </span>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="testResult"
      :type="testResult.success ? 'success' : 'error'"
      :title="testResult.title"
      :description="testResult.description"
      :closable="true"
      show-icon
      class="test-result"
      @close="testResult = null"
    />

    <span slot="footer" class="dialog-footer">
      <el-button size="small" icon="el-icon-connection" :loading="testing" @click="handleTest">测试连接</el-button>
      <el-button size="small" @click="innerVisible = false">取消</el-button>
      <el-button size="small" type="primary" :loading="saving" @click="handleSave">保存</el-button>
    </span>
  </el-dialog>
</template>

<script>
import { saveOlt, testOlt, fetchOltWebAccounts } from '@/api/olt'

/** 厂商码 → 中文名，与后端 OltVendor 一致 */
const VENDOR_LABELS = {
  HUAWEI: '华为',
  ZTE: '中兴',
  FIBERHOME: '烽火',
  HSGQ: '鸿升光',
  GENERIC: '通用'
}

/**
 * 添加 / 编辑 OLT 弹窗。
 *
 * 保存前后端都会校验；「测试连接」直接用当前表单里的参数连一次设备，
 * 未保存也能验证账号密码是否正确。
 *
 * 「测试连接」顺带把厂商识别出来（`display version` / `show version` 的回显），
 * 结果写进 form.vendor 随保存一起落库——添加完就知道该进哪个厂商页面，
 * 不用等第一次同步。
 */
export default {
  name: 'OltFormDialog',
  props: {
    visible: {
      type: Boolean,
      default: false
    },
    // 编辑时传入列表里的 OLT 对象，新增传 null
    olt: {
      type: Object,
      default: null
    }
  },
  data() {
    return {
      form: this.emptyForm(),
      accountOptions: [],
      charsetOptions: [
        { label: '自动', value: 'AUTO' },
        { label: 'UTF-8', value: 'UTF-8' },
        { label: 'GBK', value: 'GBK' },
        { label: 'GB18030', value: 'GB18030' }
      ],
      rules: {
        host: [
          { required: true, message: '请输入 OLT 地址', trigger: 'blur' },
          {
            pattern: /^[A-Za-z0-9][A-Za-z0-9._:-]*$/,
            message: '只填 IP 或域名，不要带 http:// 等前缀',
            trigger: 'blur'
          }
        ]
      },
      saving: false,
      testing: false,
      testResult: null
    }
  },
  computed: {
    innerVisible: {
      get() {
        return this.visible
      },
      set(val) {
        this.$emit('update:visible', val)
      }
    },
    isEdit() {
      return !!(this.olt && this.olt.id)
    },
    loginLabel() {
      return this.form.protocol === 'SSH' ? 'SSH' : 'Telnet'
    },
    defaultPort() {
      return this.form.protocol === 'SSH' ? 22 : 23
    },
    passwordPlaceholder() {
      return this.isEdit ? '留空表示不修改（默认 admin）' : '默认为 admin'
    },
    vendorLabel() {
      return VENDOR_LABELS[this.form.vendor] || this.form.vendor
    }
  },
  methods: {
    emptyForm() {
      return {
        id: null,
        name: '',
        host: '',
        protocol: 'TELNET',
        username: '',
        password: '',
        port: 23,
        charset: 'AUTO',
        bindLocalIp: '',
        webAccounts: [],
        remark: '',
        // 厂商/型号：测试连接时由回显自动识别，保存时一并落库，决定进哪个厂商页面
        vendor: '',
        model: ''
      }
    },
    handleOpen() {
      this.testResult = null
      this.form = this.emptyForm()
      if (this.olt) {
        this.form.id = this.olt.id
        this.form.name = this.olt.name || ''
        this.form.host = this.olt.host || ''
        this.form.protocol = this.olt.protocol || 'TELNET'
        this.form.username = this.olt.username || ''
        this.form.password = ''
        this.form.port = this.olt.port || this.defaultPort
        this.form.charset = this.olt.charset || 'AUTO'
        this.form.bindLocalIp = this.olt.bindLocalIp || ''
        this.form.webAccounts = (this.olt.webAccounts || []).slice()
        this.form.remark = this.olt.remark || ''
        this.form.vendor = this.olt.vendor || ''
        this.form.model = this.olt.model || ''
      }
      this.$nextTick(() => {
        if (this.$refs.form) this.$refs.form.clearValidate()
      })
      this.loadAccounts()
    },
    loadAccounts() {
      fetchOltWebAccounts()
        .then(res => {
          this.accountOptions = res.data || []
        })
        .catch(() => {
          this.accountOptions = []
        })
    },
    onProtocolChange() {
      // 协议切换时只在端口仍是默认值的情况下跟随，避免覆盖用户手填的端口
      if (this.form.port === 23 || this.form.port === 22 || !this.form.port) {
        this.form.port = this.defaultPort
      }
    },
    selectAllAccounts() {
      this.form.webAccounts = this.accountOptions.map(item => item.username)
    },
    clearAccounts() {
      this.form.webAccounts = []
    },
    handleTest() {
      this.$refs.form.validate(valid => {
        if (!valid) return
        this.testing = true
        this.testResult = null
        testOlt(this.form)
          .then(res => {
            const data = res.data || {}
            // 认出厂商就回填，后面即使改了地址也跟随这次的识别结果
            if (data.success && data.vendor) {
              this.form.vendor = data.vendor
              this.form.model = data.model || this.form.model
            }
            this.testResult = {
              success: !!data.success,
              title: data.success ? '连接成功' : '连接失败',
              description: this.buildTestDescription(data)
            }
          })
          .catch(err => {
            this.testResult = {
              success: false,
              title: '连接失败',
              description: (err && err.message) || '请求异常'
            }
          })
          .finally(() => {
            this.testing = false
          })
      })
    },
    buildTestDescription(data) {
      if (!data.success) return data.message || '未知原因'
      const parts = []
      if (data.message) parts.push(data.message)
      if (data.prompt) parts.push('提示符：' + data.prompt)
      if (data.costMs) parts.push('耗时 ' + data.costMs + ' ms')
      return parts.join('；')
    },
    handleSave() {
      this.$refs.form.validate(valid => {
        if (!valid) return
        this.saving = true
        saveOlt(this.form)
          .then(res => {
            this.$message.success(this.isEdit ? 'OLT 已更新' : 'OLT 已添加')
            this.$emit('saved', res.data)
            this.innerVisible = false
          })
          .catch(() => {})
          .finally(() => {
            this.saving = false
          })
      })
    }
  }
}
</script>

<style scoped>
.olt-form {
  padding-right: 12px;
}
.form-tip {
  color: #909399;
  font-size: 12px;
  margin-left: 8px;
}
.port-input {
  width: 120px;
}
.ip-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.tip-icon {
  color: #909399;
  cursor: help;
  margin-left: 6px;
}
.account-box {
  width: 100%;
}
.account-head {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-bottom: 2px;
}
.account-sep {
  color: #c0c4cc;
  font-size: 12px;
}
.account-group {
  display: flex;
  flex-direction: column;
  line-height: 1.6;
}
.account-group .el-checkbox {
  margin-right: 0;
}
.test-result {
  margin: 0 12px 4px 12px;
}
.detected-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.detected-model {
  color: #303133;
  font-size: 13px;
}
.test-result ::v-deep .el-alert__description {
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
