<template>
  <el-dialog
    title="新增 TR069 模板"
    :visible.sync="show"
    width="460px"
    append-to-body
    :close-on-click-modal="false"
  >
    <el-form
      ref="form"
      :model="form"
      :rules="rules"
      label-width="110px"
      size="small"
    >
      <el-form-item label="模板 ID" prop="profileId">
        <el-input-number
          v-model="form.profileId"
          :min="1"
          :max="32"
          controls-position="right"
        />
      </el-form-item>
      <el-form-item label="模板名" prop="profileName">
        <el-input v-model="form.profileName" :maxlength="32" placeholder="1-32 个字符，不带空格" />
      </el-form-item>
      <el-form-item label="ACS 地址" prop="url">
        <el-input v-model="form.url" :maxlength="64" placeholder="http://10.10.10.1:9090/" />
      </el-form-item>
      <el-form-item label="ACS 登录账号" prop="acsUsername">
        <el-input v-model="form.acsUsername" :maxlength="24" placeholder="选填，最长 24 个字符" />
      </el-form-item>
      <el-form-item label="ACS 登录密码" prop="acsPassword">
        <el-input v-model="form.acsPassword" :maxlength="24" show-password placeholder="选填，最长 24 个字符" />
      </el-form-item>
    </el-form>

    <div class="tr069-dialog-tip">
      保存后当场下发 ont tr069-server-profile add …，模板不落库，列表以设备回显为准
    </div>

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
import { createTr069Profile } from '@/api/olt'

function emptyForm() {
  return {
    profileId: 10,
    profileName: '',
    url: '',
    acsUsername: '',
    acsPassword: ''
  }
}

/** 华为 MA5680T 的 TR069 模板新增弹窗：填完保存即下发，模板不落库 */
export default {
  name: 'OltHuaweiTr069Dialog',
  props: {
    visible: {
      type: Boolean,
      default: false
    },
    oltId: {
      type: [Number, String],
      default: null
    }
  },
  data() {
    return {
      saving: false,
      form: emptyForm()
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
    rules() {
      const noSpace = (rule, value, callback) => {
        if (value && /\s/.test(value)) {
          callback(new Error('不能包含空格'))
        } else {
          callback()
        }
      }
      return {
        profileId: [{
          required: true,
          message: '模板 ID 必填，范围 1-32',
          trigger: 'change'
        }],
        profileName: [
          { required: true, message: '请填写模板名', trigger: 'blur' },
          { max: 32, message: '最长 32 个字符', trigger: 'blur' },
          { validator: noSpace, trigger: 'blur' }
        ],
        url: [
          { required: true, message: '请填写 ACS 地址', trigger: 'blur' },
          { max: 64, message: '最长 64 个字符', trigger: 'blur' },
          {
            pattern: /^(https?|tr069):\/\/\S+$/i,
            message: '要以 http:// 或 https:// 开头',
            trigger: 'blur'
          }
        ],
        acsUsername: [
          { max: 24, message: '最长 24 个字符', trigger: 'blur' },
          { validator: noSpace, trigger: 'blur' }
        ],
        acsPassword: [
          { max: 24, message: '最长 24 个字符', trigger: 'blur' }
        ]
      }
    }
  },
  watch: {
    // 每次打开都重置成默认值，上一次填的残留不带走
    visible(value) {
      if (value) {
        this.form = emptyForm()
        this.$nextTick(() => {
          if (this.$refs.form) {
            this.$refs.form.clearValidate()
          }
        })
      }
    }
  },
  methods: {
    submit() {
      this.$refs.form.validate(valid => {
        if (!valid) {
          return
        }
        this.saving = true
        createTr069Profile(this.oltId, {
          profileId: this.form.profileId,
          profileName: (this.form.profileName || '').trim(),
          url: (this.form.url || '').trim(),
          acsUsername: (this.form.acsUsername || '').trim(),
          acsPassword: this.form.acsPassword,
          port: null
        })
          .then(res => {
            this.$emit('saved', res.data || [])
            this.show = false
            this.$message.success('模板已下发')
          })
          .catch(error => {
            this.$message.error((error && error.message) || '新增 TR069 模板失败')
          })
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

.tr069-dialog-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
  padding: 0 4px 6px;
}
</style>
