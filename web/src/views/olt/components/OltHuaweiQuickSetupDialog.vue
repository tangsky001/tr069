<template>
  <el-dialog
    title="一键开局"
    :visible.sync="dialogVisible"
    width="760px"
    append-to-body
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <div class="hq-desc">
      按下面这套参数建四套模板：DBA → ONT 线路模板（引用 DBA，含 T-CONT / GEM / GEM 映射）→
      ONT 业务模板 → TR-069 管理模板。模板 ID 取 DBA 带宽值（1000M 就是模板 1000），
      四套共用同一个 ID。字段名拿不准的，鼠标移到问号上看说明。
    </div>

    <el-form :model="form" label-position="right" label-width="200px" size="small" class="hq-form">
      <el-form-item>
        <olt-help-label
          slot="label"
          text="管理 VLAN"
          tip="某些版本的 OLT 需要独立的管理 VLAN，不能和 TR069 VLAN 相同。开局会为它建三层接口并配缺省路由。"
        />
        <el-input v-model="form.manageVlan" class="hq-input" placeholder="如 46" />
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="管理 VLAN IP 地址"
          tip="这台 OLT 在管理 VLAN 上的三层地址，掩码一起写（10.172.10.254/255.255.255.0 或 /24 都认）。"
        />
        <el-input v-model="form.manageVlanIp" class="hq-input" placeholder="如 10.172.10.254/255.255.255.0" />
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="缺省路由"
          tip="管理 VLAN 的下一跳网关。ONT 的 TR-069 报文回连 ACS 全靠它，不填就只建三层接口、不写路由。"
        />
        <el-input v-model="form.defaultRoute" class="hq-input" placeholder="如 10.172.10.1" />
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="TR069 VLAN"
          tip="ONT 回连 ACS 用的 VLAN，排在 GEM 映射的第一条（索引 0）。某些 OLT 版本要求它与管理 VLAN 分开。"
        />
        <el-input v-model="form.tr069Vlan" class="hq-input" placeholder="如 46" />
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="业务 VLAN"
          tip="宽带等用户业务 VLAN，逐个映射到同一个 GEM 上（索引 1、2、3…）。多个用逗号分隔，支持连续段 41-45。"
        />
        <el-input v-model="form.serviceVlan" class="hq-input" placeholder="如 41,45" />
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="IPTV 组播 VLAN"
          tip="做了组播就填，会追加到 GEM 映射的最后一条。不做组播留空，不会下发任何组播配置。"
        />
        <el-input v-model="form.iptvMulticastVlan" class="hq-input" placeholder="可选，不做组播就留空" />
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="开局 PON 类型"
          tip="决定下发 ont-lineprofile gpon 还是 epon，同时决定模板名写成 FTTH-GPON-1000 还是 FTTH-EPON-1000。选错整段命令都会被设备拒绝。"
        />
        <el-select v-model="form.ponType" size="small" class="hq-select">
          <el-option label="GPON(默认)" value="GPON" />
          <el-option label="EPON" value="EPON" />
        </el-select>
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="TR069 ACS 地址"
          tip="写进 TR-069 管理模板（TR069_ACS）的 URL，账号密码默认 acs/acs。地址填错的表现是 ONT 在线但平台上一直不出现。"
        />
        <el-input v-model="form.acsUrl" class="hq-input" placeholder="如 http://10.172.10.1:9090/" />
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="DBA 模版带宽(Mbps)"
          :tip="'只决定上行限速和模板名字：1000 → DBA 模板名 1000M、线路与业务模板名 FTTH-GPON-1000。范围 ' + minBandwidthMbps + '-' + maxBandwidthMbps + 'M（设备上下限由后端给出）。'"
        />
        <el-input-number
          v-model="form.dbaBandwidth"
          :min="minBandwidthMbps"
          :max="maxBandwidthMbps"
          :step="10"
          controls-position="right"
          size="small"
        />
      </el-form-item>

      <!-- 模板 ID：以前拿带宽顶，1000M 撞上设备 10~512 的限制被压成 512，页面填的和下发下去的不是一个数 -->
      <el-form-item>
        <olt-help-label
          slot="label"
          text="DBA 模板 ID"
          :tip="'dba-profile add 用的 profile-id，设备认 ' + minProfileId + '-' + maxProfileId + '。'"
        />
        <el-input-number
          v-model="form.dbaProfileId"
          :min="minProfileId"
          :max="maxProfileId"
          :step="1"
          controls-position="right"
          size="small"
        />
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="ONT 线路模板 ID"
          :tip="'ont-lineprofile 用的 profile-id，设备认 ' + minProfileId + '-' + maxProfileId + '。模板里的 T-CONT 按上面 DBA 的 ID 引用，所以两个 ID 可以不一样。'"
        />
        <el-input-number
          v-model="form.lineProfileId"
          :min="minProfileId"
          :max="maxProfileId"
          :step="1"
          controls-position="right"
          size="small"
        />
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="ONT 业务模板 ID"
          :tip="'ont-srvprofile 用的 profile-id，设备认 ' + minProfileId + '-' + maxProfileId + '。注册光猫时和线路模板成对指定。'"
        />
        <el-input-number
          v-model="form.srvProfileId"
          :min="minProfileId"
          :max="maxProfileId"
          :step="1"
          controls-position="right"
          size="small"
        />
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="TR069 模板 ID"
          :tip="'ont tr069-server-profile 用的 profile-id，设备只认 ' + minTr069ProfileId + '-' + maxTr069ProfileId + '，比另外三套窄（所以不能也填 100）。'"
        />
        <el-input-number
          v-model="form.tr069ProfileId"
          :min="minTr069ProfileId"
          :max="maxTr069ProfileId"
          :step="1"
          controls-position="right"
          size="small"
        />
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="上联端口"
          tip="要写到端口号，例如 0/6/2、0/0/6/1（最后一段是端口，前面是框/槽）。只写 0/6 不知道是第几个口，会直接报错让你补。"
        />
        <el-input v-model="form.uplinkPorts" class="hq-input" placeholder="如 0/6/2,0/0/6/1" />
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="上联口缺省 VLAN"
          tip="上联口的默认 VLAN（PVID）。留空表示不动设备现有配置，只按上面的端口清单放通 VLAN。"
        />
        <div class="hq-textarea-row">
          <el-input
            v-model="form.uplinkDefaultVlan"
            type="textarea"
            :rows="2"
            class="hq-input"
            placeholder="每行一个，或用逗号分隔"
          />
          <el-button type="text" size="mini" @click="form.uplinkDefaultVlan = ''">清空</el-button>
        </div>
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="二层互通 VLAN"
          tip="需要在 OLT 内部二层互通的 VLAN，一般用于管理网与业务网互联。留空表示不配置。"
        />
        <el-input v-model="form.l2InteropVlan" class="hq-input" placeholder="可选" />
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="允许透传未知组播/多播"
          tip="开启后未知组播报文直接透传。一般保持关闭，否则组播泛洪会占满上联带宽。"
        />
        <el-switch v-model="form.allowUnknownMulticast" active-text="是" inactive-text="否" />
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="组播业务开局"
          tip="开了才把 IPTV 组播 VLAN 加进 GEM 映射。IGMP / 组播用户面命令还没接入，只有组播 VLAN 的映射会下发。"
        />
        <el-switch v-model="form.multicastService" active-text="是" inactive-text="否" />
      </el-form-item>
    </el-form>

    <div class="quick-preview">
      <div class="quick-preview-head">
        命令预览
        <span class="quick-preview-count">共 {{ preview.commands.length }} 条</span>
        <span v-if="previewing" class="quick-preview-count">计算中…</span>
      </div>
      <div v-if="preview.warnings.length" class="quick-preview-warn">
        <div v-for="(warning, index) in preview.warnings" :key="index">{{ warning }}</div>
      </div>
      <pre class="quick-preview-body" :class="{ 'is-error': previewError }">{{ previewText }}</pre>
    </div>

    <div slot="footer">
      <el-button size="small" type="primary" :loading="saving" @click="save">保存设置</el-button>
      <el-button size="small" @click="resetDefaults">默认设置</el-button>
      <el-button size="small" type="warning" @click="close">关闭</el-button>
    </div>
  </el-dialog>
</template>

<script>
import OltHelpLabel from './OltHelpLabel'
import { previewQuickSetup, fetchQuickSetupConfig, saveQuickSetupConfig } from '@/api/olt'

/** 表单改动后隔多久重新算命令：输入 IP 时别每敲一个字符就打一次接口 */
const PREVIEW_DELAY_MS = 300

/**
 * 华为 MA56XXT 一键开局。
 *
 * <p><b>这个组件不写任何设备命令。</b>四套模板的创建顺序、ID 与命名规则、
 * T-CONT 与 GEM 的绑定关系全由后端 {@code HuaweiQuickSetupPlanner} 编：
 * 打开后每次改动都调一遍预览，看到的必然就是即将下发的那条序列。</p>
 *
 * <p><b>参数不存本机、也不自带默认值。</b>打开时向后端要（没存过就是后端那份默认值），
 * 「保存设置」写库，「默认设置」再去后端要一份默认值——前端抄一份默认值，
 * 就会出现「页面让填到 1000、设备其实能到 1024」这种两处对不上的情况。</p>
 */

/**
 * 后端给的参数翻成表单：后端是 Integer/Boolean，输入框里是字符串/开关。
 */
function toForm(request) {
  const req = request || {}
  return {
    manageVlan: req.manageVlan == null ? '' : String(req.manageVlan),
    manageVlanIp: req.manageVlanIp || '',
    defaultRoute: req.defaultRoute || '',
    tr069Vlan: req.tr069Vlan == null ? '' : String(req.tr069Vlan),
    serviceVlan: req.serviceVlan || '',
    iptvMulticastVlan: req.iptvMulticastVlan == null ? '' : String(req.iptvMulticastVlan),
    ponType: req.ponType || 'GPON',
    acsUrl: req.acsUrl || '',
    // ACS 账号密码后端给默认值，页面上不写死
    acsUsername: req.acsUsername || '',
    acsPassword: req.acsPassword || '',
    dbaBandwidth: req.dbaBandwidthMbps == null ? null : Number(req.dbaBandwidthMbps),
    // 模板 ID 由后端给默认值和区间，页面上不写死
    dbaProfileId: req.dbaProfileId,
    lineProfileId: req.lineProfileId,
    srvProfileId: req.srvProfileId,
    tr069ProfileId: req.tr069ProfileId,
    uplinkPorts: req.uplinkPorts || '',
    uplinkDefaultVlan: req.uplinkDefaultVlan || '',
    l2InteropVlan: req.l2InteropVlan || '',
    allowUnknownMulticast: req.allowUnknownMulticast === true,
    multicastService: req.multicastService === true
  }
}

/** 字符串转整数，空值返回 null（后端按可空字段处理） */
function toInt(value) {
  if (value === null || value === undefined || String(value).trim() === '') {
    return null
  }
  const num = Number(String(value).trim())
  return Number.isNaN(num) ? null : num
}

function toText(value) {
  return value === null || value === undefined ? '' : String(value).trim()
}

export default {
  name: 'OltHuaweiQuickSetupDialog',
  components: { OltHelpLabel },
  props: {
    visible: {
      type: Boolean,
      default: false
    },
    /** 设备 ID，取参数与预览都要用 */
    oltId: {
      type: [Number, String],
      default: null
    },
    /** 下发回调，返回 Promise；没给就只保存参数不下发 */
    submitHandler: {
      type: Function,
      default: null
    }
  },
  data() {
    return {
      saving: false,
      previewing: false,
      /** 后端算出来的命令和提醒 */
      preview: { commands: [], warnings: [] },
      previewError: '',
      form: toForm({}),
      /** 上下限：设备只认到多少由后端说了算，页面不自带 */
      minBandwidthMbps: 1,
      maxBandwidthMbps: 0,
      minProfileId: 0,
      maxProfileId: 0,
      minTr069ProfileId: 0,
      maxTr069ProfileId: 0
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
    previewText() {
      if (this.previewError) {
        return this.previewError
      }
      if (!this.preview.commands.length) {
        return '（参数填完整后这里会显示命令）'
      }
      return this.preview.commands.join('\n')
    }
  },
  watch: {
    // 表单一变就让后端重新算一遍，页面不自己拼命令
    form: {
      deep: true,
      handler() {
        this.schedulePreview()
      }
    }
  },
  beforeDestroy() {
    if (this.previewTimer) {
      clearTimeout(this.previewTimer)
    }
  },
  methods: {
    /** 打开时向后端要参数：存过的给存过的，没存过给后端那份默认值 */
    async onOpen() {
      await this.loadConfig(false)
      this.runPreview()
    },
    /** 默认设置：再去后端要一份默认值，前端不自带 */
    async resetDefaults() {
      await this.loadConfig(true)
      this.$message.info('已恢复默认设置')
    },
    async loadConfig(forceDefaults) {
      if (!this.oltId) {
        return
      }
      try {
        const result = await fetchQuickSetupConfig(this.oltId, forceDefaults)
        const data = result.data || {}
        this.form = toForm(data.request)
        this.minBandwidthMbps = data.minBandwidthMbps || 1
        this.maxBandwidthMbps = data.maxBandwidthMbps || 0
        this.minProfileId = data.minProfileId || 0
        this.maxProfileId = data.maxProfileId || 0
        this.minTr069ProfileId = data.minTr069ProfileId || 0
        this.maxTr069ProfileId = data.maxTr069ProfileId || 0
      } catch (e) {
        this.$message.error(e && e.message ? e.message : '读取开局参数失败')
      }
    },
    close() {
      this.dialogVisible = false
    },
    /** 命令序列由后端拿这套 huawei 参数编出来，ID 与命名规则都在后端 */
    payload() {
      const form = this.form
      return {
        huawei: {
          manageVlan: toInt(form.manageVlan),
          manageVlanIp: toText(form.manageVlanIp),
          defaultRoute: toText(form.defaultRoute),
          tr069Vlan: toInt(form.tr069Vlan),
          serviceVlan: toText(form.serviceVlan),
          iptvMulticastVlan: toInt(form.iptvMulticastVlan),
          ponType: form.ponType,
          acsUrl: toText(form.acsUrl),
          // ACS 账号密码也来自后端给的默认值，页面上改不了就照传
          acsUsername: toText(form.acsUsername),
          acsPassword: toText(form.acsPassword),
          dbaBandwidthMbps: toInt(form.dbaBandwidth),
          dbaProfileId: toInt(form.dbaProfileId),
          lineProfileId: toInt(form.lineProfileId),
          srvProfileId: toInt(form.srvProfileId),
          tr069ProfileId: toInt(form.tr069ProfileId),
          uplinkPorts: toText(form.uplinkPorts),
          uplinkDefaultVlan: toText(form.uplinkDefaultVlan),
          l2InteropVlan: toText(form.l2InteropVlan),
          allowUnknownMulticast: !!form.allowUnknownMulticast,
          multicastService: !!form.multicastService
        }
      }
    },
    schedulePreview() {
      if (this.previewTimer) {
        clearTimeout(this.previewTimer)
      }
      this.previewTimer = setTimeout(() => this.runPreview(), PREVIEW_DELAY_MS)
    },
    /** 让后端把这次要下发的命令算出来；参数不合法也由后端指出 */
    async runPreview() {
      if (!this.oltId) {
        return
      }
      this.previewing = true
      try {
        const result = await previewQuickSetup(this.oltId, this.payload())
        const data = result.data || {}
        this.preview = {
          commands: data.commands || [],
          warnings: data.warnings || []
        }
        this.previewError = ''
      } catch (e) {
        // 参数填了一半：预览区直接显示原因，命令清空，保存也就点不动
        this.preview = { commands: [], warnings: [] }
        this.previewError = e && e.message ? e.message : '命令预演失败'
      } finally {
        this.previewing = false
      }
    },
    async save() {
      // 命令和校验都在后端：预览算不出来（参数有误）就不保存也不下发
      if (this.previewError) {
        this.$message.warning(this.previewError)
        return
      }
      if (!this.preview.commands.length) {
        this.$message.warning('没有算出要下发的命令，检查参数是否填完整')
        return
      }

      try {
        await saveQuickSetupConfig(this.oltId, this.payload().huawei)
      } catch (e) {
        this.$message.error(e && e.message ? e.message : '保存失败')
        return
      }

      // 父组件没给下发回调就只保存参数，别点了没反应
      if (!this.submitHandler) {
        this.$message.success('开局参数已保存')
        return
      }

      const data = this.payload()
      this.$confirm('将对设备下发 ' + this.preview.commands.length + ' 条命令，确认开局？', '一键开局', {
        confirmButtonText: '下发',
        cancelButtonText: '取消',
        type: 'warning'
      })
        .then(() => {
          this.saving = true
          return this.submitHandler(data)
        })
        .then(() => {
          this.dialogVisible = false
        })
        .catch(() => {})
        .finally(() => {
          this.saving = false
        })
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';

.hq-desc {
  padding: 8px 12px;
  margin-bottom: 10px;
  color: #5a6b7d;
  font-size: 12px;
  line-height: 1.6;
  background: #f5f8fc;
  border-left: 3px solid #c0d4ea;
}

/* 截图那套紧凑表单：label 右对齐，值只留一条下划线 */
.hq-form {
  max-width: 660px;

  ::v-deep .el-form-item {
    margin-bottom: 0;
    padding: 3px 0;
    border-bottom: 1px dashed #ebeef5;
  }

  ::v-deep .el-form-item__label {
    color: #909399;
    font-weight: 400;
    line-height: 28px;
    /* 字段名一律单行：折成两行会把这一行撑高，下面几条虚线就对不齐了 */
    white-space: nowrap;
  }

  ::v-deep .el-form-item__label .help-label {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    line-height: 28px;
  }

  ::v-deep .el-input__inner {
    border: none;
    border-bottom: 1px solid #dcdfe6;
    border-radius: 0;
    padding-left: 0;
  }

  ::v-deep .el-textarea__inner {
    border: none;
    border-bottom: 1px solid #dcdfe6;
    border-radius: 0;
    padding-left: 0;
  }

  ::v-deep .el-input__inner:focus,
  ::v-deep .el-textarea__inner:focus {
    border-bottom-color: #409eff;
  }
}

.hq-input {
  max-width: 380px;
}

.hq-select {
  width: 180px;
}

.hq-textarea-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  max-width: 380px;
}

/* 命令预览：和鸿升光那套一键开局共用样式 */
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

.quick-preview-warn {
  padding: 6px 10px;
  color: #8a6d3b;
  font-size: 12px;
  line-height: 1.6;
  background: #fcf8e3;
  border-bottom: 1px solid #f5e6c0;
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

  &.is-error {
    color: #c0392b;
  }
}
</style>
