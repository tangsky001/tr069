<template>
  <div>
    <div class="tr069-toolbar">
      <span class="tr069-toolbar-label">查询方式</span>
      <el-select v-model="queryType" size="small" class="tr069-query-type" @change="onQueryTypeChange">
        <el-option label="全部" value="all" />
        <el-option label="按模板 ID" value="id" />
        <el-option label="按模板名" value="name" />
      </el-select>
      <el-input
        v-if="queryType !== 'all'"
        v-model="keyword"
        size="small"
        class="tr069-keyword"
        :placeholder="queryType === 'id' ? '模板 ID' : '模板名关键字'"
        clearable
      />
      <el-button size="small" icon="el-icon-refresh" :loading="loading" @click="load">刷新</el-button>
      <el-button size="small" type="primary" icon="el-icon-plus" @click="openCreate">增加</el-button>
      <span class="tr069-toolbar-tip">模板不存库，每次刷新都实时读设备</span>
    </div>

    <el-table v-loading="loading" :data="rows" size="small" border max-height="520">
      <el-table-column label="模板名" prop="name" min-width="220">
        <template slot-scope="{ row }">
          <span v-if="row.name">{{ row.name }}</span>
          <span v-else class="tr069-empty">-</span>
        </template>
      </el-table-column>
      <el-table-column label="模板 ID" prop="profileId" width="120" align="center" />
      <el-table-column label="绑定 ONU 数" width="130" align="center">
        <template slot-scope="{ row }">{{ row.bindTimes == null ? '-' : row.bindTimes }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110" align="center">
        <template slot-scope="{ row }">
          <el-button type="text" size="mini" @click="openDetail(row)">查看</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      title="增加"
      :visible.sync="createVisible"
      width="560px"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-form
        v-if="meta"
        ref="createForm"
        :model="form"
        :rules="rules"
        label-width="130px"
        size="small"
      >
        <el-form-item label="模板名" prop="profileName">
          <el-input
            v-model="form.profileName"
            :maxlength="meta.nameMax"
            :placeholder="'1-' + meta.nameMax + ' 个字符'"
          />
        </el-form-item>
        <el-form-item label="模板 ID" prop="profileId">
          <el-input-number
            v-model="form.profileId"
            :min="meta.profileIdMin"
            :max="meta.profileIdMax"
            controls-position="right"
          />
        </el-form-item>
        <el-form-item label="ACS 服务器 URL" prop="url">
          <el-input v-model="form.url" :maxlength="meta.urlMax" placeholder="http://192.168.1.10:9090" />
        </el-form-item>
        <el-form-item label="ACS 服务器用户名" prop="acsUsername">
          <el-input
            v-model="form.acsUsername"
            :maxlength="meta.fieldMax"
            :placeholder="'0-' + meta.fieldMax + ' 个字符'"
          />
        </el-form-item>
        <el-form-item label="ACS 服务器密码" prop="acsPassword">
          <el-input
            v-model="form.acsPassword"
            :maxlength="meta.fieldMax"
            show-password
            :placeholder="'0-' + meta.fieldMax + ' 个字符'"
          />
        </el-form-item>
        <el-form-item label="连接用户名" prop="username">
          <el-input
            v-model="form.username"
            :maxlength="meta.fieldMax"
            :placeholder="'0-' + meta.fieldMax + ' 个字符'"
          />
        </el-form-item>
        <el-form-item label="连接密码" prop="password">
          <el-input
            v-model="form.password"
            :maxlength="meta.fieldMax"
            show-password
            :placeholder="'0-' + meta.fieldMax + ' 个字符'"
          />
        </el-form-item>
        <el-form-item label="连接端口" prop="port">
          <el-input-number
            v-model="form.port"
            :min="meta.portMin"
            :max="meta.portMax"
            controls-position="right"
          />
        </el-form-item>
      </el-form>
      <div v-else class="olt-empty">正在读取模板参数…</div>
      <div class="tr069-dialog-tip">
        不改定期通知，模板建出来用设备的默认间隔。
      </div>
      <div slot="footer">
        <el-button size="small" @click="createVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="creating" @click="submit">确认</el-button>
      </div>
    </el-dialog>

    <el-dialog title="模板详情" :visible.sync="detailVisible" width="420px" append-to-body>
      <div v-if="detail" class="tr069-detail">
        <div><span>模板名</span>{{ detail.name || '-' }}</div>
        <div><span>模板 ID</span>{{ detail.profileId }}</div>
        <div><span>绑定 ONU 数</span>{{ detail.bindTimes == null ? '-' : detail.bindTimes }}</div>
        <div v-if="detail.url"><span>ACS 地址</span>{{ detail.url }}</div>
      </div>
      <div slot="footer">
        <el-button size="small" @click="detailVisible = false">关闭</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { fetchTr069Profiles, createTr069Profile, fetchTr069Form } from '@/api/olt'

/** 字段没填时交给后端校验，这里只保证不会把 undefined 发出去 */
function emptyForm() {
  return {
    profileName: '',
    profileId: null,
    url: '',
    acsUsername: '',
    acsPassword: '',
    username: '',
    password: '',
    port: null
  }
}

/**
 * 鸿升光 TR-069（ACS）模板面板。
 *
 * <p>模板不落库：打开页签 / 点「刷新」就是登设备跑一次
 * {@code show onu-tr069-profile all}，「增加」是当场下发
 * {@code onu-tr069-profile add ...}，下发完自动重查。</p>
 *
 * <p>设备的查询命令只有「全部」一种，所以「查询方式」是本地过滤——
 * 不做成三条不同的命令，免得固件差异导致某一种查法报错。</p>
 */
export default {
  name: 'OltHsgqTr069Panel',
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
      queryType: 'all',
      keyword: '',
      createVisible: false,
      detailVisible: false,
      detail: null,
      /** 模板参数的默认值与取值约束，来自后端，和「一键开局」里那份是同一个接口 */
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
        profileName: [
          { required: true, message: '请填写模板名', trigger: 'blur' },
          { max: meta.nameMax, message: '最长 ' + meta.nameMax + ' 个字符', trigger: 'blur' }
        ],
        profileId: [{
          required: true,
          message: '模板 ID 必填，范围 ' + meta.profileIdMin + '-' + meta.profileIdMax,
          trigger: 'change'
        }],
        url: [
          { required: true, message: '请填写 ACS 服务器 URL', trigger: 'blur' },
          {
            pattern: new RegExp(meta.urlPattern, 'i'),
            message: '要以 http:// 或 https:// 开头',
            trigger: 'blur'
          }
        ],
        port: [{
          required: true,
          message: '请填写连接端口（' + meta.portMin + '-' + meta.portMax + '）',
          trigger: 'change'
        }]
      }
    },
    /** 查询方式只是本地过滤：设备那条 show 只有「全部」一种写法 */
    rows() {
      const keyword = (this.keyword || '').trim().toLowerCase()
      if (!keyword) {
        return this.list
      }
      if (this.queryType === 'id') {
        return this.list.filter(item => String(item.profileId) === keyword)
      }
      return this.list.filter(item => (item.name || '').toLowerCase().includes(keyword))
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
    /** 模板参数的默认值和取值约束由后端给，页面上不再写死 */
    loadMeta() {
      if (!this.oltId) {
        return
      }
      fetchTr069Form(this.oltId)
        .then(res => {
          this.meta = res.data
        })
        .catch(() => {
          this.$message.error('读取 TR-069 模板参数失败')
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
          this.$message.error((error && error.message) || '查询 TR-069 模板失败')
        })
        .finally(() => {
          this.loading = false
        })
    },
    onQueryTypeChange() {
      this.keyword = ''
    },
    openCreate() {
      // 默认值用后端的（模板 10 + 现场 ACS 地址），改地址时直接覆盖
      this.form = this.meta && this.meta.defaults ? { ...this.meta.defaults } : emptyForm()
      this.createVisible = true
      this.$nextTick(() => {
        if (this.$refs.createForm) {
          this.$refs.createForm.clearValidate()
        }
      })
    },
    openDetail(row) {
      this.detail = row
      this.detailVisible = true
    },
    submit() {
      this.$refs.createForm.validate(valid => {
        if (!valid) {
          return
        }
        const payload = {
          profileId: this.form.profileId,
          profileName: this.form.profileName.trim(),
          url: this.form.url.trim(),
          acsUsername: this.form.acsUsername.trim(),
          acsPassword: this.form.acsPassword,
          username: this.form.username.trim(),
          password: this.form.password,
          port: this.form.port
        }
        this.creating = true
        createTr069Profile(this.oltId, payload)
          .then(res => {
            this.list = res.data || this.list
            this.createVisible = false
            this.$message.success('模板已下发')
          })
          .catch(error => {
            this.$message.error((error && error.message) || '新增 TR-069 模板失败')
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

  &-label {
    font-size: 13px;
    color: #606266;
  }

  &-tip {
    font-size: 12px;
    color: #909399;
    margin-left: 6px;
  }
}

.tr069-query-type {
  width: 130px;
}

.tr069-keyword {
  width: 160px;
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

.tr069-detail {
  div {
    display: flex;
    line-height: 2;
    font-size: 13px;
    color: #303133;

    span {
      width: 110px;
      color: #909399;
    }
  }
}
</style>
