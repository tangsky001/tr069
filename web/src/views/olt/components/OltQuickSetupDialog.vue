<template>
  <el-dialog
    title="一键开局"
    :visible.sync="dialogVisible"
    width="660px"
    append-to-body
    :close-on-click-modal="false"
    @open="reset"
  >
    <div class="quick-desc">
      把开局要做的几件事放在一个弹窗里一次下发。默认已经勾选：
      <b>所有 PON 口和 GE 口 → trunk</b>，确认端口清单没问题直接点「下发」。
    </div>

    <el-form label-width="120px" size="small">
      <div class="quick-section">
        <el-checkbox v-model="form.portModeEnabled">① 统一端口 VLAN 模式</el-checkbox>
        <span class="quick-section-hint">端口默认是 hybrid，开局一般全改成 trunk</span>
      </div>

      <template v-if="form.portModeEnabled">
        <el-form-item label="端口">
          <div v-if="!portGroups.length" class="quick-empty">
            还没采到端口数据，先点上面的「同步」刷新一下。
          </div>
          <div v-for="group in portGroups" :key="group.type" class="quick-port-group">
            <el-checkbox
              class="quick-group-all"
              :value="groupChecked(group)"
              :indeterminate="groupIndeterminate(group)"
              @change="checked => toggleGroup(group, checked)"
            >
              全选{{ group.title }}（{{ group.ports.length }}）
            </el-checkbox>
            <el-checkbox-group v-model="form.portKeys">
              <el-checkbox v-for="port in group.ports" :key="port.key" :label="port.key">
                {{ port.label }}
              </el-checkbox>
            </el-checkbox-group>
          </div>
        </el-form-item>

        <el-form-item label="VLAN 模式">
          <el-radio-group v-model="form.mode">
            <el-radio label="trunk">trunk</el-radio>
            <el-radio label="access">access</el-radio>
            <el-radio label="hybrid">hybrid</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="VLAN">
          <el-input v-model="form.vlanList" :placeholder="vlanPlaceholder" />
          <div class="quick-field-tip">
            {{ vlanTip }}
          </div>
        </el-form-item>
        <el-form-item label=" ">
          <el-checkbox v-model="form.createVlans">
            这些 VLAN 设备上还没有的话，顺带创建出来
          </el-checkbox>
        </el-form-item>
      </template>

      <div class="quick-section">
        <el-checkbox v-model="form.tr069Enabled">③ 创建 TR-069（ACS）模板</el-checkbox>
        <span class="quick-section-hint">填好 ACS 地址，开局时一并下发 onu-tr069-profile add</span>
      </div>

      <template v-if="form.tr069Enabled">
        <el-form-item label="模板名">
          <el-input v-model="form.tr069.profileName" maxlength="32" placeholder="选填，0-32 characters" />
        </el-form-item>
        <el-form-item label="模板 ID">
          <el-input-number v-model="form.tr069.profileId" :min="1" :max="32" controls-position="right" />
          <span class="quick-range">range: 1 - 32</span>
        </el-form-item>
        <el-form-item label="ACS 服务器 URL">
          <el-input v-model="form.tr069.url" maxlength="64" placeholder="http://192.168.1.10:9090" />
          <span class="quick-range">ex: 1 - 64 characters</span>
        </el-form-item>
        <el-form-item label="ACS 服务器用户名">
          <el-input v-model="form.tr069.acsUsername" maxlength="24" placeholder="选填，0 - 24 characters" />
        </el-form-item>
        <el-form-item label="ACS 服务器密码">
          <el-input v-model="form.tr069.acsPassword" maxlength="24" show-password placeholder="选填，0 - 24 characters" />
        </el-form-item>
        <el-form-item label="连接用户名">
          <el-input v-model="form.tr069.username" maxlength="24" placeholder="选填，0 - 24 characters" />
        </el-form-item>
        <el-form-item label="连接密码">
          <el-input v-model="form.tr069.password" maxlength="24" show-password placeholder="选填，0 - 24 characters" />
        </el-form-item>
        <el-form-item label="连接端口">
          <el-input-number v-model="form.tr069.port" :min="1" :max="65535" controls-position="right" />
          <span class="quick-range">range: 1 - 65535</span>
        </el-form-item>
      </template>
    </el-form>

    <div class="quick-preview">
      <div class="quick-preview-head">
        命令预览<span class="quick-preview-count">共 {{ commands.length }} 条</span>
      </div>
      <pre class="quick-preview-body">{{ previewText }}</pre>
    </div>

    <div slot="footer">
      <el-button size="small" @click="dialogVisible = false">取消</el-button>
      <el-button size="small" type="primary" :loading="submitting" @click="submit">下发</el-button>
    </div>
  </el-dialog>
</template>

<script>
/** 各类端口在页面上叫什么 */
const PORT_TITLES = {
  epon: 'PON 口',
  gpon: 'PON 口',
  ge: 'GE 以太网口',
  xge: '10GE 以太网口'
}

/** 默认勾哪些：用户开局改的就是 PON 和上联 GE，10GE 不默认动它 */
const DEFAULT_TYPES = ['epon', 'gpon', 'ge']

/**
 * TR-069 段的默认值：现场就是这一套（模板 10 + 10.172.10.1），
 * 开局时不用每次手填，改地址的时候覆盖就行。
 */
function emptyTr069() {
  return {
    profileName: 'ACS_TR069',
    profileId: 10,
    url: 'http://10.172.10.1',
    acsUsername: 'acs',
    acsPassword: 'acs',
    username: 'acs',
    password: 'acs',
    port: 9090
  }
}

function emptyForm() {
  return {
    portModeEnabled: true,
    portKeys: [],
    mode: 'trunk',
    vlanList: '',
    // 一个 VLAN 输入框、一个「顺带创建」开关：放通和创建说的是同一批 VLAN，
    // 分成两个输入框会出现「填了允许的 VLAN 却忘了建」这种开局事故
    createVlans: true,
    tr069Enabled: false,
    tr069: emptyTr069()
  }
}

/**
 * 一键开局弹窗。
 *
 * <p>目前只做前端：选项是照 devices pi工程的开局步骤列的，
 * 命令预览用现有目录里的写法拼出来（端口视图 {@code vlan mode trunk} +
 * {@code vlan trunk <list>}，全局 {@code vlan standard <ids>}），
 * 点「下发」把 payload 抛出去，后端接口接上来就能跑。</p>
 *
 * <p>将来接后端：{@code POST /api/olt/{id}/quick-setup}，请求体就是
 * {@code $emit('submit')} 的那个对象：</p>
 * <pre>{@code
 * {
 *   ports: [{ type: 'epon', no: 1 }, ...],   // 按页面勾选顺序
 *   mode: 'trunk',                            // access / hybrid
 *   vlanList: '41,45-46',                      // 端口放通的 VLAN，空表示只改模式
 *   createVlanIds: '41,45-46',                 // 勾了「顺带创建」就是同一份清单，否则空串
 *   addCreatedToPorts: true,                   // 建出来的 VLAN 要不要加进这些端口
 *   tr069: {                                   // 没勾③就是 null
 *     profileId: 20, profileName: 'acs',
 *     url: 'http://192.168.1.10:9090',
 *     acsUsername: 'acs', acsPassword: 'acs',
 *     username: 'acs', password: 'acs', port: 9090
 *   }
 * }
 * }</pre>
 *
 * <p>TR-069 那一段按现场要求不带「定期通知 / 间隔」，命令里也就没有
 * {@code inform-interval}——设备上模板建出来是默认的间隔。</p>
 */
export default {
  name: 'OltQuickSetupDialog',
  props: {
    visible: {
      type: Boolean,
      default: false
    },
    /**
     * 可选端口，来自已经采到的成员端口清单：{ type: 'epon'|'ge'|'xge', no: 1, label: 'PON01' }
     */
    ports: {
      type: Array,
      default: () => []
    },
    /** 下发回调，返回 Promise；没给就只提示「后端还没接入」 */
    submitHandler: {
      type: Function,
      default: null
    }
  },
  data() {
    return {
      submitting: false,
      form: emptyForm()
    }
  },
  computed: {
    dialogVisible: {
      get() {
        return this.visible
      },
      set(value) {
        this.$emit('update:visible', value)
      }
    },
    /** 同名端口按类型分组，页面按组展示 + 给个「全选」 */
    portGroups() {
      const groups = []
      const index = {}
      const ports = this.ports || []
      ports.forEach(port => {
        const type = port.type || ''
        if (!index[type]) {
          index[type] = { type, title: PORT_TITLES[type] || type, ports: [] }
          groups.push(index[type])
        }
        index[type].ports.push({
          key: port.type + ':' + port.no,
          type: port.type,
          no: port.no,
          label: port.label || port.type + port.no
        })
      })
      return groups
    },
    /** 勾选的端口还原成 {type, no}，保持页面上的顺序 */
    selectedPorts() {
      const ports = []
      this.portGroups.forEach(group => {
        group.ports.forEach(port => {
          if (this.form.portKeys.includes(port.key)) {
            ports.push({ type: port.type, no: port.no })
          }
        })
      })
      return ports
    },
    /**
     * 按勾选项拼出要下发的命令，只给预览——真正下发由后端按同一套写法执行。
     *
     * <p><b>顺序是「改端口模式 → 建 VLAN 绑端口」，不能反。</b>端口从 hybrid 换成 trunk 时
     * 设备会把原有 VLAN 成员清掉，先绑后改等于白绑，设备回显还一切正常。</p>
     */
    commands() {
      const lines = []
      const vlanText = this.compactVlans()
      // VLAN 必须先建出来：端口上的 vlan trunk 在 VLAN 不存在时会被设备顶回来
      if (this.createsVlans) {
        this.vlanSegments().forEach(segment => {
          lines.push('vlan standard ' + (segment[1] > segment[0]
            ? segment[0] + ' ' + segment[1]
            : String(segment[0])))
          lines.push('exit')
        })
      }
      if (this.form.portModeEnabled) {
        this.selectedPorts.forEach(port => {
          lines.push('interface ' + port.type + ' ' + port.no)
          lines.push('vlan mode ' + this.form.mode)
          if (vlanText) {
            // 三种模式写法不同：trunk/hybrid 接列表，access 只接一个 VLAN
            if (this.form.mode === 'access') {
              lines.push('vlan access ' + this.firstVlan(vlanText))
            } else if (this.form.mode === 'hybrid') {
              lines.push('vlan hybrid ' + vlanText + ' tagged')
            } else {
              lines.push('vlan trunk ' + vlanText)
            }
          }
          lines.push('exit')
        })
      }
      const tr069 = this.tr069Command()
      if (tr069) {
        lines.push(tr069)
      }
      // 最后存盘：设备只写运行配置，不保存的话重启/掉电就回到开局前
      if (lines.length) {
        lines.push('save')
      }
      return lines
    },
    /** 真的要在设备上建 VLAN：① 开着、勾了顺带创建、VLAN 填了 */
    createsVlans() {
      return this.form.portModeEnabled &&
        this.form.createVlans &&
        !!this.compactVlans()
    },
    vlanPlaceholder() {
      return this.form.mode === 'access'
        ? '如 41；access 端口只属于一个 VLAN'
        : '如 41,45-46；留空表示只改模式，不动 VLAN'
    },
    vlanTip() {
      return this.form.mode === 'access'
        ? 'access 端口只配这一个 VLAN（填多个取第一个）。设备上还没有的，勾下面一条一并建出来。'
        : '端口放通/归属的 VLAN。设备上还没有的，勾下面一条一并建出来。'
    },
    previewText() {
      if (!this.commands.length) {
        return '（没有选中任何操作）'
      }
      return this.commands.join('\n')
    }
  },
  watch: {
    // 端口清单第一次采到（或换了设备）时，把默认值补上
    ports: {
      immediate: true,
      handler(ports) {
        if (!ports || !ports.length) {
          return
        }
        // 已经勾过就不再覆盖，避免用户自己取消勾选后被强制勾回来
        if (this.form.portKeys.length) {
          return
        }
        this.reset()
      }
    }
  },
  methods: {
    reset() {
      const form = emptyForm()
      const ports = this.ports || []
      form.portKeys = ports
        .filter(port => DEFAULT_TYPES.includes(port.type))
        .map(port => port.type + ':' + port.no)
      this.form = form
    },
    groupChecked(group) {
      return group.ports.every(port => this.form.portKeys.includes(port.key))
    },
    groupIndeterminate(group) {
      const hit = group.ports.filter(port => this.form.portKeys.includes(port.key)).length
      return hit > 0 && hit < group.ports.length
    },
    toggleGroup(group, checked) {
      const keys = new Set(this.form.portKeys)
      group.ports.forEach(port => {
        if (checked) {
          keys.add(port.key)
        } else {
          keys.delete(port.key)
        }
      })
      this.form.portKeys = Array.from(keys)
    },
    /** 去掉空格的 VLAN 表达式，命令和 payload 都用它 */
    compactVlans() {
      return String(this.form.vlanList || '').replace(/\s+/g, '')
    },
    /** VLAN 表达式 → 连续段 [[41,41],[45,46]]；写错就当作没填，反正后端还会校验一遍 */
    vlanSegments() {
      const text = this.compactVlans()
      if (!text) {
        return []
      }
      const segments = []
      text.split(',').forEach(piece => {
        if (!piece) {
          return
        }
        const dash = piece.indexOf('-')
        const from = parseInt(dash < 0 ? piece : piece.slice(0, dash), 10)
        const to = parseInt(dash < 0 ? piece : piece.slice(dash + 1), 10)
        if (isNaN(from) || isNaN(to) || to < from) {
          return
        }
        segments.push([from, to])
      })
      return segments.sort((left, right) => left[0] - right[0])
    },
    /** {@code 41,45-46} → {@code 41}：access 端口只配一个 VLAN */
    firstVlan(vlanText) {
      const head = String(vlanText || '').split(',')[0].trim()
      const dash = head.indexOf('-')
      return dash > 0 ? head.slice(0, dash) : head
    },
    payload() {
      const vlanText = this.form.portModeEnabled ? this.compactVlans() : ''
      return {
        ports: this.selectedPorts,
        mode: this.form.mode,
        // 放通和创建用的是同一份 VLAN 清单，勾了「顺带创建」就把它们也建出来
        vlanList: vlanText,
        createVlanIds: this.form.createVlans ? vlanText : '',
        addCreatedToPorts: this.form.createVlans,
        tr069: this.form.tr069Enabled ? this.tr069Payload() : null
      }
    },
    tr069Payload() {
      const form = this.form.tr069
      return {
        profileId: form.profileId,
        profileName: String(form.profileName || '').trim(),
        url: String(form.url || '').trim(),
        acsUsername: String(form.acsUsername || '').trim(),
        acsPassword: form.acsPassword,
        username: String(form.username || '').trim(),
        password: form.password,
        port: form.port
      }
    },
    /**
     * 拼 onu-tr069-profile add，顺序和后端 HsgqOltProfile.createTr069ProfileCommands 一致：
     * id → name → url → acs-password → acs-username → port → password → username。
     * 空的可选参数不写（设备按关键字解析，空串会顶掉下一个参数名）。
     */
    tr069Command() {
      if (!this.form.tr069Enabled) {
        return null
      }
      const data = this.tr069Payload()
      if (!data.profileId) {
        return null
      }
      let command = 'onu-tr069-profile add profile-id ' + data.profileId
      const optional = [
        ['profile-name', data.profileName],
        ['url', data.url],
        ['acs-password', data.acsPassword],
        ['acs-username', data.acsUsername]
      ]
      optional.forEach(([key, value]) => {
        const text = String(value || '').trim()
        if (text) {
          command += ' ' + key + ' ' + text
        }
      })
      if (data.port) {
        command += ' port ' + data.port
      }
      if (String(data.password || '').trim()) {
        command += ' password ' + String(data.password).trim()
      }
      if (data.username) {
        command += ' username ' + data.username
      }
      return command
    },
    /** TR-069 必填的就三项：模板 ID、ACS 地址、连接端口，其余留空就当设备默认 */
    validateTr069() {
      const data = this.tr069Payload()
      if (!data.profileId) {
        this.$message.warning('填一下 TR-069 模板 ID（1-32）')
        return false
      }
      if (!data.url) {
        this.$message.warning('填一下 ACS 服务器 URL')
        return false
      }
      if (!/^(https?|tr069):\/\/\S+$/i.test(data.url)) {
        this.$message.warning('ACS 服务器 URL 要以 http:// 或 https:// 开头')
        return false
      }
      if (!data.port) {
        this.$message.warning('填一下连接端口（1-65535）')
        return false
      }
      return true
    },
    submit() {
      if (!this.form.portModeEnabled && !this.form.tr069Enabled) {
        this.$message.warning('先勾选要做的开局项')
        return
      }
      if (this.form.tr069Enabled && !this.validateTr069()) {
        return
      }
      if (this.form.portModeEnabled && !this.selectedPorts.length) {
        this.$message.warning('至少勾选一个端口')
        return
      }
      if (this.createsVlans) {
        // 建 VLAN 时不能只有 VLAN 没有端口，否则建出来没人用，还得多跑一次会话
        if (!this.selectedPorts.length) {
          this.$message.warning('要建 VLAN 就得先把端口勾上')
          return
        }
      } else if (this.form.portModeEnabled && !this.compactVlans()) {
        // 允许：只改模式不配 VLAN，但提醒一下，别让人以为是漏配
        this.$message.info('没填 VLAN，只会改端口模式')
      }
      if (!this.commands.length) {
        this.$message.warning('没有可下发的命令')
        return
      }
      const data = this.payload()
      this.$emit('submit', data)
      if (!this.submitHandler) {
        this.$message.info('一键开局的后端接口还没接入，当前只做命令预览')
        return
      }
      this.$confirm('将对设备下发 ' + this.commands.length + ' 条命令，确认开局？', '一键开局', {
        confirmButtonText: '下发',
        cancelButtonText: '取消',
        type: 'warning'
      })
        .then(() => {
          this.submitting = true
          return this.submitHandler(data)
        })
        .then(() => {
          this.dialogVisible = false
        })
        .catch(() => {})
        .finally(() => {
          this.submitting = false
        })
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';

.quick-desc {
  padding: 8px 12px;
  margin-bottom: 12px;
  color: #5a6b7d;
  font-size: 12px;
  line-height: 1.6;
  background: #f5f8fc;
  border-left: 3px solid #c0d4ea;
}

.quick-section {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;

  &-hint {
    color: #9aa7b4;
    font-size: 12px;
  }
}

.quick-empty {
  color: #9aa7b4;
  font-size: 12px;
}

.quick-port-group {
  padding: 4px 0;
  border-bottom: 1px dashed #e6ebf0;

  &:last-child {
    border-bottom: none;
  }
}

.quick-group-all {
  margin-right: 8px;
  font-weight: 600;
}

.quick-preview {
  margin-top: 12px;
  border: 1px solid #dfe6ee;
  border-radius: 4px;
}

.quick-preview-head {
  padding: 4px 10px;
  background: #f5f8fc;
  color: #4a5b6c;
  font-size: 12px;
}

.quick-preview-count {
  margin-left: 8px;
  color: #9aa7b4;
}

/* 输入框右侧的 range / ex 提示，和参考图一样灰字靠右 */
.quick-range {
  margin-left: 8px;
  color: #9aa7b4;
  font-size: 12px;
}

/* 输入框下方的说明，一行灰字 */
.quick-field-tip {
  color: #9aa7b4;
  font-size: 12px;
  line-height: 1.5;
}

.quick-preview-body {
  max-height: 220px;
  margin: 0;
  padding: 10px;
  overflow: auto;
  color: #2c3e50;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
