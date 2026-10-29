<template>
  <div class="app-container">
    <el-button type="primary" icon="el-icon-plus" size="small" @click="openCreate">新建用户</el-button>
    <span class="tip">系统管理员拥有全部权限，至少要留一个，删不掉也停不掉最后一个</span>

    <el-table v-loading="loading" :data="list" border size="small" style="margin-top:12px">
      <!-- 用 min-width 不用 width：固定宽加起来比屏幕窄，表格右边就空一截；min-width 的列会分掉剩下的空间 -->
      <el-table-column prop="username" label="登录名" min-width="110" />
      <el-table-column prop="nickname" label="显示名" min-width="110" />
      <el-table-column label="角色" min-width="150">
        <template slot-scope="{ row }">
          <el-select
            v-model="row.role"
            size="mini"
            :disabled="superAdminCount <= 1 && row.role === 'SUPER_ADMIN'"
            @change="v => changeRole(row, v)"
          >
            <el-option v-for="r in roles" :key="r.code" :label="r.name" :value="r.code" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="状态" min-width="80" align="center">
        <template slot-scope="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="mini">
            {{ row.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最近登录" min-width="150">
        <template slot-scope="{ row }">{{ formatTime(row.lastLoginAt) }}</template>
      </el-table-column>
      <el-table-column label="登录地址" min-width="120">
        <template slot-scope="{ row }">{{ row.lastLoginIp || '-' }}</template>
      </el-table-column>
      <el-table-column label="登录标识" min-width="90" align="center">
        <template slot-scope="{ row }">
          <!-- 来源只是个标签，用来知道这个人是从哪儿登进来的 -->
          <el-tag v-if="row.lastLoginSource" size="mini" type="info">
            {{ sourceText(row.lastLoginSource) }}
          </el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" align="center">
        <template slot-scope="{ row }">
          <el-button type="text" @click="openPassword(row)">改密码</el-button>
          <el-button
            type="text"
            :disabled="superAdminCount <= 1 && row.role === 'SUPER_ADMIN'"
            @click="toggleStatus(row)"
          >{{ row.status === 1 ? '禁用' : '启用' }}</el-button>
          <el-button
            type="text"
            class="danger-text"
            :disabled="row.builtin"
            :title="row.builtin ? '系统用户禁止删除' : ''"
            @click="onDelete(row)"
          >删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog title="新建用户" :visible.sync="createVisible" width="420px" append-to-body>
      <el-form label-width="80px">
        <el-form-item label="登录名">
          <el-input v-model="createForm.username" placeholder="字母数字下划线，4-32 位" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="createForm.password" type="password" placeholder="至少 6 位" show-password />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="createForm.nickname" placeholder="不填就用登录名" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="createForm.role" placeholder="选一个角色" style="width:100%">
            <el-option v-for="r in roles" :key="r.code" :label="r.name" :value="r.code" />
          </el-select>
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">保存</el-button>
      </span>
    </el-dialog>

    <!-- 改密码：先拿自己的密码验一次，新密码再输两遍 -->
    <el-dialog title="修改密码" :visible.sync="pwdVisible" width="420px" append-to-body>
      <el-form label-width="100px">
        <el-form-item label="用户名：">
          <span>{{ pwdRow ? pwdRow.username : '-' }}</span>
        </el-form-item>
        <el-form-item label="旧密码：">
          <el-input
            v-model="myPassword"
            type="password"
            placeholder="旧密码"
            show-password
          />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="pwd" type="password" placeholder="至少 6 位" show-password />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="pwd2" type="password" placeholder="和上面一致" show-password />
        </el-form-item>
      </el-form>
      <div class="tip">修改密码需验证已成功登录的旧密码</div>
      <span slot="footer">
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitPassword">保存</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import { fetchUsers, createUser, updateUser, removeUser, fetchRoles } from '@/api/system'

const SOURCE_TEXT = { WEB: '网页端', CLIENT: '客户端', MOBILE: '移动端' }

export default {
  name: 'SystemUsers',
  data() {
    return {
      list: [],
      roles: [],
      loading: false,
      saving: false,
      createVisible: false,
      createForm: { username: '', password: '', nickname: '', role: '' },
      pwdVisible: false,
      pwdRow: null,
      pwd: '',
      pwd2: '',
      myPassword: ''
    }
  },
  computed: {
    // 只剩一个系统管理员时，它的角色与状态都不许动，不然系统就没人能管了
    superAdminCount() {
      return this.list.filter(u => u.role === 'SUPER_ADMIN').length
    }
  },
  created() {
    this.load()
  },
  methods: {
    load() {
      this.loading = true
      fetchRoles()
        .then(res => { this.roles = res.data || [] })
        .catch(() => {})
      fetchUsers()
        .then(res => { this.list = res.data || [] })
        .catch(() => {})
        .then(() => { this.loading = false })
    },
    sourceText(source) {
      return SOURCE_TEXT[source] || source
    },
    // 后端给的是 ISO 格式（2026-10-29T21:50:02），把中间的 T 换成空格才像给人看的
    formatTime(value) {
      return value ? String(value).replace('T', ' ') : '从未登录'
    },
    openCreate() {
      this.createForm = { username: '', password: '', nickname: '', role: '' }
      this.createVisible = true
    },
    submitCreate() {
      if (!this.createForm.username || !this.createForm.role) {
        this.$message.warning('登录名与角色都要填')
        return
      }
      this.saving = true
      createUser(this.createForm)
        .then(() => {
          this.$message.success('已创建')
          this.createVisible = false
          this.load()
        })
        .catch(() => {})
        .then(() => { this.saving = false })
    },
    // 改角色立即生效，对方下次请求就按新角色判权限，不用重新登录
    changeRole(row, role) {
      updateUser(row.id, { role })
        .then(() => { this.$message.success('角色已改成 ' + role) })
        .catch(() => { this.load() })
    },
    toggleStatus(row) {
      const status = row.status === 1 ? 0 : 1
      updateUser(row.id, { status })
        .then(() => {
          row.status = status
          this.$message.success(status === 1 ? '已启用' : '已禁用')
        })
        .catch(() => {})
    },
    openPassword(row) {
      this.pwdRow = row
      this.pwd = ''
      this.pwd2 = ''
      this.myPassword = ''
      this.pwdVisible = true
    },
    submitPassword() {
      if (!this.pwdRow) return
      if (!this.myPassword) {
        this.$message.warning('先输入你自己的密码做验证')
        return
      }
      if (this.pwd.length < 6) {
        this.$message.warning('新密码至少 6 位')
        return
      }
      // 两遍不一致就别提交，省得改成一个自己都不知道的密码
      if (this.pwd !== this.pwd2) {
        this.$message.warning('两次输入的新密码不一样')
        return
      }
      this.saving = true
      updateUser(this.pwdRow.id, {
        password: this.pwd,
        currentPassword: this.myPassword
      })
        .then(() => {
          this.$message.success('密码已改')
          this.pwdVisible = false
        })
        .catch(() => {})
        .then(() => { this.saving = false })
    },
    async onDelete(row) {
      try {
        await this.$confirm('删除用户「' + row.username + '」？', '提示', { type: 'warning' })
      } catch (e) {
        return
      }
      removeUser(row.id)
        .then(() => {
          this.$message.success('已删除')
          this.load()
        })
        .catch(() => {})
    }
  }
}
</script>

<style scoped>
.tip {
  margin-left: 10px;
  color: #909399;
  font-size: 12px;
}
</style>
