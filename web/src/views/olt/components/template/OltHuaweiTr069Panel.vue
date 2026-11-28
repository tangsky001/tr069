<template>
  <div>
    <div class="tr069-toolbar">
      <el-button size="small" icon="el-icon-refresh" :loading="loading" @click="load">刷新</el-button>
      <el-button size="small" type="primary" icon="el-icon-plus" @click="openCreate">增加</el-button>
      <span class="tr069-toolbar-tip">模板不存库，每次刷新都实时读设备</span>
    </div>

    <el-table v-loading="loading" :data="list" size="small" border max-height="520">
      <el-table-column label="模板 ID" prop="profileId" width="110" align="center" />
      <el-table-column label="模板名" prop="name" min-width="140">
        <template slot-scope="{ row }">
          <span v-if="row.name">{{ row.name }}</span>
          <span v-else class="tr069-empty">-</span>
        </template>
      </el-table-column>
      <el-table-column label="ACS 地址" min-width="220">
        <template slot-scope="{ row }">{{ row.url || '-' }}</template>
      </el-table-column>
      <el-table-column label="ACS 登录账号" min-width="140">
        <template slot-scope="{ row }">{{ row.user || '-' }}</template>
      </el-table-column>
    </el-table>

    <el-dialog
      title="新增 TR069 模板"
      :visible.sync="createVisible"
      width="460px"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-form
        v-if="meta"
        ref="createForm"
        :model="form"
        :rules="rules"
        label-width="110px"
        size="small"
      >
        <el-form-item label="模板 ID" prop="profileId">
          <el-input-number
            v-model="form.profileId"
            :min="meta.profileIdMin"
            :max="meta.profileIdMax"
            controls-position="right"
          />
        </el-form-item>
        <el-form-item label="模板名" prop="profileName">
          <el-input
            v-model="form.profileName"
            :maxlength="meta.nameMax"
            :placeholder="'1-' + meta.nameMax + ' 个字符，不带空格'"
          />
        </el-form-item>
        <el-form-item label="ACS 地址" prop="url">
          <el-input v-model="form.url" :maxlength="meta.urlMax" placeholder="http://10.10.10.1:9090/" />
        </el-form-item>
        <el-form-item label="ACS 登录账号" prop="acsUsername">
          <el-input v-model="form.acsUsername" :maxlength="meta.fieldMax" />
        </el-form-item>
        <el-form-item label="ACS 登录密码" prop="acsPassword">
          <el-input v-model="form.acsPassword" :maxlength="meta.fieldMax" show-password />
        </el-form-item>
      </el-form>
      <div v-else class="olt-empty">正在读取模板参数…</div>

      <div class="tr069-dialog-tip">
        保存后当场下发 ont tr069-server-profile add …，模板不落库，列表以设备回显为准
      </div>

      <div slot="footer">
        <el-button
          size="small"
          type="primary"
          icon="el-icon-folder-checked"
          :loading="creating"
          @click="submit"
        >保存设置</el-button>
        <el-button size="small" type="warning" icon="el-icon-close" @click="createVisible = false">关闭</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { fetchTr069Profiles, createTr069Profile, fetchTr069Form } from '@/api/olt'

/** 字段没填时交给后端校验，这里只保证不会把 undefined 发出去 */
function emptyForm() {
  return {
    profileId: null,
    profileName: '',
    url: '',
    acsUsername: '',
    acsPassword: ''
  }
}

/** 华为 MA5680T 的 TR069 模板面板：模板不落库，实时读设备 */
export default {
  name: 'OltHuaweiTr069Panel',
  props: {
    oltId: {
      type: [Number, String],
      default: null
    }
  },
  data() {
    return {
      loading: false,
      creating: false,
      list: [],
      createVisible: false,
      /** 模板参数的默认值与取值约束，来自后端 */
      meta: null,
      form: emptyForm()
    }
  },
  computed: {
    /** 校验规则按后端给的约束生成：范围、长度、地址写法都只有一份 */
    rules() {
      const meta = this.meta
      if (!meta) {
        return {}
      }
      return {
        profileId: [{
          required: true,
          message: '模板 ID 必填，范围 ' + meta.profileIdMin + '-' + meta.profileIdMax,
          trigger: 'change'
        }],
        profileName: [
          { required: true, message: '请填写模板名', trigger: 'blur' },
          { max: meta.nameMax, message: '最长 ' + meta.nameMax + ' 个字符', trigger: 'blur' }
        ],
        url: [
          { required: true, message: '请填写 ACS 地址', trigger: 'blur' },
          {
            pattern: new RegExp(meta.urlPattern, 'i'),
            message: '要以 http:// 或 https:// 开头',
            trigger: 'blur'
          }
        ]
      }
    }
  },
  watch: {
    // 台账页里切换设备时页签不会重建，得跟着 oltId 重查
    oltId() {
      this.list = []
      this.load()
    }
  },
  mounted() {
    this.load()
    this.loadMeta()
  },
  methods: {
    /** 模板参数由后端下发 */
    loadMeta() {
      if (!this.oltId) {
        return
      }
      fetchTr069Form(this.oltId)
        .then(res => {
          this.meta = res.data || null
        })
        .catch(() => {
          this.$message.error('读取 TR069 模板参数失败')
        })
    },
    load() {
      if (!this.oltId) {
        return
      }
      this.loading = true
      fetchTr069Profiles(this.oltId)
        .then(res => {
          this.list = res.data || []
        })
        .catch(error => {
          this.$message.error((error && error.message) || '查询 TR069 模板失败')
        })
        .finally(() => {
          this.loading = false
        })
    },
    openCreate() {
      // 默认值用后端的
      this.form = this.meta && this.meta.defaults
        ? {
          profileId: this.meta.defaults.profileId,
          profileName: this.meta.defaults.profileName || '',
          url: this.meta.defaults.url || '',
          acsUsername: this.meta.defaults.acsUsername || '',
          acsPassword: this.meta.defaults.acsPassword || ''
        }
        : emptyForm()
      this.createVisible = true
      this.$nextTick(() => {
        if (this.$refs.createForm) {
          this.$refs.createForm.clearValidate()
        }
      })
    },
    submit() {
      if (!this.meta) {
        this.$message.warning('还没读到模板参数，稍后再试')
        return
      }
      this.$refs.createForm.validate(valid => {
        if (!valid) {
          return
        }
        this.creating = true
        createTr069Profile(this.oltId, {
          profileId: this.form.profileId,
          profileName: (this.form.profileName || '').trim(),
          url: (this.form.url || '').trim(),
          acsUsername: (this.form.acsUsername || '').trim(),
          acsPassword: this.form.acsPassword,
          port: null
        })
          .then(res => {
            // 下发接口顺带返回最新列表，直接用
            this.list = res.data || this.list
            this.createVisible = false
            this.$message.success('模板已下发')
          })
          .catch(error => {
            this.$message.error((error && error.message) || '新增 TR069 模板失败')
          })
          .finally(() => {
            this.creating = false
          })
      })
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';

.tr069-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;

  &-tip {
    font-size: 12px;
    color: #909399;
    margin-left: 6px;
  }
}

.tr069-empty {
  color: #c0c4cc;
}

.tr069-dialog-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
  padding: 0 4px 6px;
}
</style>
