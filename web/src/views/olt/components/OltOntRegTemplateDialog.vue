<template>
  <el-dialog
    :title="isEdit ? '编辑注册模板' : '新建注册模板'"
    :visible.sync="dialogVisible"
    width="700px"
    append-to-body
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <el-form :model="form" label-width="200px" size="small" class="reg-form">
      <el-form-item label="名称" required>
        <el-input v-model="form.name" class="reg-input" placeholder="如 Auto-GPON" />
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="OLT 地址"
          tip="这条模板绑在哪台 OLT 上。自动注册只对这台设备生效，新建时光猫上线后按优先级依次匹配。"
        />
        <el-input v-model="form.oltIp" class="reg-input" placeholder="如 10.11.104.2" />
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="优先级"
          tip="0-999，0 最优先。一台 OLT 可以有多条模板，光猫上线时从 0 开始匹配，第一个条件满足的模板被采用。"
        />
        <el-input v-model="form.priority" class="reg-input" placeholder="0-999，0 优先级最高" />
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="注册方式"
          tip="光猫上送什么就按什么认：GPON 多用 SN，EPON 多用 MAC；LOID / Password 是运营商给光猫配的逻辑标识。"
        />
        <el-radio-group v-model="form.authMode">
          <el-radio label="SN">SN（设备序列号）</el-radio>
          <el-radio label="MAC">MAC</el-radio>
          <el-radio label="LOID">LOID（逻辑ID）</el-radio>
          <el-radio label="PASSWORD">Password（密码）</el-radio>
        </el-radio-group>
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="PON 类型"
          tip="按板卡选。GPON/XGPON 与 EPON/10G EPON 的注册命令、模板 ID 空间都不通用，选错模板套不上。"
        />
        <el-radio-group v-model="form.ponType">
          <el-radio label="GPON">GPON/XGPON</el-radio>
          <el-radio label="EPON">EPON/10G EPON</el-radio>
        </el-radio-group>
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="线路模板 ID"
          tip="光猫注册时绑的 ont-lineprofile。要和开局时建的那套对上（带宽 1000 就是 1000）。"
        />
        <el-input v-model="form.lineProfileId" class="reg-input" placeholder="如 100" />
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="业务模版 ID"
          tip="光猫注册时绑的 ont-srvprofile，决定端口数与 ETH 口 VLAN 行为。和线路模板通常成对配置。"
        />
        <el-input v-model="form.srvProfileId" class="reg-input" placeholder="如 100" />
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="绑定板卡/PON口"
          tip="限定这条模板只作用于哪几个 PON 口（如 0/1/0、0/1/1）。留空表示不限端口，任何口上线的光猫都按这条模板走。"
        />
        <div class="reg-textarea-row">
          <el-input
            v-model="form.bindPorts"
            type="textarea"
            :rows="2"
            class="reg-input"
            placeholder="为空表示不限，多个用逗号分隔"
          />
          <el-button type="text" size="mini" @click="form.bindPorts = ''">清空</el-button>
        </div>
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="指定 ONT 设备型号"
          tip="只对指定型号生效（如 HG8010H、HG8245A）。留空表示不限制型号，现场混用多种光猫时才需要填。"
        />
        <div class="reg-textarea-row">
          <el-input
            v-model="form.ontModels"
            type="textarea"
            :rows="2"
            class="reg-input"
            placeholder="为空表示不限，多个用逗号分隔"
          />
          <el-button type="text" size="mini" @click="form.ontModels = ''">清空</el-button>
        </div>
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="光猫工作模式"
          tip="HGU 网关型由光猫自己拨号、发 Wi-Fi；SFU 桥接型只做二层透传，由后面的路由或 BRAS 认；混合型两种都支持；MDU 是楼道外置型。选错光猫起来也没网。"
        />
        <el-select v-model="form.workMode" size="small" class="reg-select">
          <el-option label="HGU/网关型（默认）" value="HGU" />
          <el-option label="SFU/桥接型（不带路由功能）" value="SFU" />
          <el-option label="HGU + SFU 兼容/混合型" value="MIX" />
          <el-option label="ONU/外置型光猫（MDU）" value="MDU" />
        </el-select>
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="OLT 侧业务 VLAN 映射"
          tip="光猫业务流落到 OLT 侧用哪些 VLAN，一行一条，写法如 41 vlan 41 或直接写 VLAN 列表。留空表示沿用线路模板里的 GEM 映射。"
        />
        <div class="reg-textarea-row">
          <el-input
            v-model="form.serviceVlanMap"
            type="textarea"
            :rows="3"
            class="reg-input"
            placeholder="如 41,45-46"
          />
          <el-button type="text" size="mini" @click="form.serviceVlanMap = ''">清空</el-button>
        </div>
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="下发 TR069 VLAN 及 ACS 地址"
          tip="光猫管理通道：填 VLAN 和 ACS 模板 ID（如 46 10），自动注册时一并下发，否则光猫上线后不回连平台。"
        />
        <el-input v-model="form.tr069Vlan" class="reg-input" placeholder="如 46 10" />
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="IPTV 组播 VLAN"
          tip="做 IPTV 才填。会加到这条模板的业务 VLAN 里，并参与组播配置。"
        />
        <el-input v-model="form.iptvVlan" class="reg-input" placeholder="可选" />
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="透传未知多播 VLAN"
          tip="未知组播报文在这几个 VLAN 上直接透传。多个用逗号分隔，不填就不透传。"
        />
        <el-input v-model="form.multicastVlan" class="reg-input" placeholder="可选，多个 VLAN 用逗号分隔" />
      </el-form-item>
    </el-form>

    <div slot="footer">
      <el-button size="small" type="primary" @click="submit">保存</el-button>
      <el-button size="small" @click="dialogVisible = false">取消</el-button>
    </div>
  </el-dialog>
</template>

<script>
import OltHelpLabel from './OltHelpLabel'
import { fetchOntRegTemplateDefault } from '@/api/olt'

/**
 * 自动注册模板的编辑弹窗。
 * 默认值只在后端一处，页面不自带。</p>
 */

/**
 * 后端给的模板翻成表单：数字和开关在输入框里用字符串，没值的给空串。
 *
 * <p>这里不写死任何默认值（认证方式、PON 类型、优先级、工作模式都是后端给的），
 * 前端抄一份就会出现「页面默认 SN、后端默认也是 SN，哪天改了一处」这种对不上。</p>
 */
function toForm(template) {
  const tpl = template || {}
  return {
    id: tpl.id || null,
    name: tpl.name || '',
    oltIp: tpl.oltIp || '',
    priority: tpl.priority == null ? '' : String(tpl.priority),
    authMode: tpl.authMode || '',
    ponType: tpl.ponType || '',
    lineProfileId: tpl.lineProfileId == null ? '' : String(tpl.lineProfileId),
    srvProfileId: tpl.srvProfileId == null ? '' : String(tpl.srvProfileId),
    bindPorts: tpl.bindPorts || '',
    ontModels: tpl.ontModels || '',
    workMode: tpl.workMode || '',
    serviceVlanMap: tpl.serviceVlanMap || '',
    tr069Vlan: tpl.tr069Vlan || '',
    iptvVlan: tpl.iptvVlan || '',
    multicastVlan: tpl.multicastVlan || ''
  }
}

export default {
  name: 'OltOntRegTemplateDialog',
  components: { OltHelpLabel },
  props: {
    visible: {
      type: Boolean,
      default: false
    },
    /** 编辑时传进来的一条模板；null 表示新建 */
    template: {
      type: Object,
      default: null
    },
    /** 所属 OLT，向后端要默认值用 */
    oltId: {
      type: [Number, String],
      default: null
    },
    /** 所属 OLT 的地址，新建时预填 */
    oltIp: {
      type: String,
      default: ''
    }
  },
  data() {
    return {
      // 打开时才由后端填默认值；这里是空壳，不放任何业务默认值
      form: toForm({ oltIp: this.oltIp })
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
    isEdit() {
      return !!(this.template && this.template.id)
    }
  },
  methods: {
    async onOpen() {
      // 先向后端要一份带默认值的空表单，再拿编辑的那条盖上去
      let base = toForm({ oltIp: this.oltIp })
      if (this.oltId) {
        try {
          const result = await fetchOntRegTemplateDefault(this.oltId, this.oltIp)
          base = toForm(result.data)
        } catch (e) {
          this.$message.error(e && e.message ? e.message : '取模板默认值失败')
        }
      }
      this.form = this.template ? { ...base, ...toForm(this.template) } : base
    },
    submit() {
      if (!String(this.form.name || '').trim()) {
        this.$message.warning('请填写模板名称')
        return
      }
      // 优先级只让填 0-999，写别的设备那边对不上
      const priority = Number(this.form.priority)
      if (Number.isNaN(priority) || priority < 0 || priority > 999) {
        this.$message.warning('优先级要填 0-999 之间的数字')
        return
      }
      this.$emit('submit', { ...this.form, priority: String(priority) })
      this.dialogVisible = false
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';

/* 截图那套：label 灰、输入框只留下划线 */
.reg-form {
  max-width: 620px;

  ::v-deep .el-form-item {
    margin-bottom: 0;
    padding: 3px 0;
    border-bottom: 1px dashed #ebeef5;
  }

  ::v-deep .el-form-item__label {
    color: #909399;
    font-weight: 400;
    line-height: 28px;
    white-space: nowrap;
  }

  ::v-deep .el-form-item__label .help-label {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    line-height: 28px;
  }

  ::v-deep .el-input__inner,
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

.reg-input {
  max-width: 380px;
}

.reg-select {
  width: 220px;
}

.reg-textarea-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  max-width: 380px;
}
</style>
