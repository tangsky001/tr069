<template>
  <el-dialog
    title="添加线路模板"
    :visible.sync="show"
    width="480px"
    append-to-body
    :close-on-click-modal="false"
  >
    <el-form
      ref="form"
      :model="form"
      :rules="rules"
      label-width="140px"
      size="small"
    >
      <el-form-item label="模板 ID" prop="profileId">
        <el-input-number
          v-model="form.profileId"
          :min="1"
          :max="8192"
          controls-position="right"
          placeholder="1-8192, 不能重复"
          class="line-id-input"
        />
      </el-form-item>
      <el-form-item label="模板名" prop="name">
        <el-input v-model="form.name" :maxlength="32" placeholder="数字、字母及下划线组成" />
      </el-form-item>
      <el-form-item label="绑定 DBA 模板" prop="dbaProfileId">
        <el-select v-model="form.dbaProfileId" placeholder="选这条模板用的 DBA 模板" style="width: 100%">
          <el-option
            v-for="item in dbaOptions"
            :key="item.profileId"
            :label="item.label"
            :value="item.profileId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="PON 类型" prop="ponType">
        <el-radio-group v-model="form.ponType">
          <el-radio label="GPON">GPON</el-radio>
          <el-radio label="EPON">EPON</el-radio>
        </el-radio-group>
      </el-form-item>
      <!-- TR069 管理与 VLAN 映射只有 GPON 线路模板支持 -->
      <template v-if="form.ponType === 'GPON'">
        <el-form-item label="启用TR069管理">
          <el-switch
            v-model="form.tr069Enable"
            active-text="是"
            :active-value="true"
            :inactive-value="false"
          />
        </el-form-item>
        <el-form-item prop="vlans">
          <template slot="label">
            VLAN 列表
            <el-tooltip content="每个 VLAN 挂一个 GEM 映射（gem mapping … vlan …），逗号分隔，留空只建模板不配映射" placement="top">
              <i class="el-icon-info line-vlan-info" />
            </el-tooltip>
          </template>
          <div class="line-vlan-toolbar">
            <el-button type="text" size="mini" class="line-vlan-clear" @click="form.vlans = ''">清空</el-button>
          </div>
          <el-input
            v-model="form.vlans"
            type="textarea"
            :rows="5"
            placeholder="多个 VLAN 用逗号分隔，例如 100,200,300"
          />
        </el-form-item>
      </template>
    </el-form>

    <div slot="footer">
      <el-button
        size="small"
        type="primary"
        icon="el-icon-folder-checked"
        :loading="saving"
        @click="submit"
      >保存设置</el-button>
      <el-button size="small" type="warning" icon="el-icon-close" @click="show = false">关闭</el-button>
    </div>
  </el-dialog>
</template>

<script>

export default {
  name: 'OltLineProfileCreateDialog',
  props: {
    visible: {
      type: Boolean,
      default: false
    },
    oltId: {
      type: [Number, String],
      default: null
    },
    /** DBA 模板快照，用来渲染「绑定 DBA 模板」下拉 */
    dbaProfiles: {
      type: Array,
      default: () => []
    },
    /** 提交处理：由页面（mixin）下发，返回 Promise */
    createHandler: {
      type: Function,
      default: null
    }
  },
  data() {
    return {
      saving: false,
      form: this.emptyForm()
    }
  },
  computed: {
    show: {
      get() {
        return this.visible
      },
      set(value) {
        this.$emit('update:visible', value)
      }
    },
    /** DBA 下拉选项：「100 / 100M」= 模板 ID / 模板名（没名字按峰值带宽折算 M） */
    dbaOptions() {
      return this.dbaProfiles
        .filter(profile => profile.profileId != null)
        .map(profile => ({
          profileId: profile.profileId,
          label: profile.profileId + ' / ' + this.dbaLabel(profile)
        }))
    },
    rules() {
      return {
        profileId: [{
          required: true,
          message: '模板 ID 必填，范围 1-8192',
          trigger: 'change'
        }],
        name: [
          { required: true, message: '请填写模板名', trigger: 'blur' },
          {
            pattern: /^[A-Za-z0-9_]{1,32}$/,
            message: '数字、字母及下划线组成，最长 32 个字符',
            trigger: 'blur'
          }
        ],
        dbaProfileId: [{
          required: true,
          message: '请选择要绑定的 DBA 模板',
          trigger: 'change'
        }],
        vlans: [
          {
            validator: (rule, value, callback) => {
              const ids = this.parseVlans(value)
              if (ids.some(vlan => vlan < 1 || vlan > 4094)) {
                callback(new Error('VLAN 要在 1-4094 之间'))
              } else {
                callback()
              }
            },
            trigger: 'blur'
          }
        ]
      }
    }
  },
  watch: {
    // 每次打开都重置：默认 GPON、TR069 开着，上一次填的残留不带走
    visible(value) {
      if (value) {
        this.form = this.emptyForm()
        this.$nextTick(() => {
          if (this.$refs.form) {
            this.$refs.form.clearValidate()
          }
        })
      }
    }
  },
  methods: {
    emptyForm() {
      return {
        profileId: undefined,
        name: '',
        dbaProfileId: null,
        ponType: 'GPON',
        tr069Enable: true,
        vlans: ''
      }
    },
    dbaLabel(profile) {
      if (profile.name) {
        return profile.name
      }
      if (profile.maxKbps) {
        return Math.round(profile.maxKbps / 1024) + 'M'
      }
      return '-'
    },
    /** 「100,200」→ [100, 200]；空、非法 → [] */
    parseVlans(value) {
      if (!value || !String(value).trim()) {
        return []
      }
      return String(value)
        .split(/[,，\s]+/)
        .filter(piece => piece !== '')
        .map(piece => Number(piece))
        .filter(num => Number.isInteger(num))
    },
    submit() {
      this.$refs.form.validate(valid => {
        if (!valid) {
          return
        }
        if (!this.createHandler) {
          this.$message.info('下发能力未接入')
          return
        }
        this.saving = true
        Promise.resolve(this.createHandler({
          profileId: this.form.profileId,
          name: (this.form.name || '').trim(),
          dbaProfileId: this.form.dbaProfileId,
          ponType: this.form.ponType,
          tr069Enable: this.form.ponType === 'GPON' ? this.form.tr069Enable : false,
          vlans: this.form.ponType === 'GPON'
            ? this.parseVlans(this.form.vlans).join(',')
            : ''
        }))
          .then(() => {
            this.show = false
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

<style lang="scss" scoped>
@import '~@/styles/olt.scss';

.line-id-input {
  width: 100%;
}
.line-vlan-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 4px;
}
.line-vlan-info {
  margin-left: 4px;
  color: #909399;
}
.line-vlan-clear {
  margin-left: auto;
  padding: 0;
}
.line-dialog-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
  padding: 0 4px 6px;
}
</style>
