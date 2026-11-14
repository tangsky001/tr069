<template>
  <el-dialog
    :title="'设备级权限：' + (title || '')"
    :visible.sync="visibleSync"
    width="560px"
    top="8vh"
  >
    <div class="acl-tip">
      给指定用户授予本{{ scopeType === 'device' ? '台设备' : '个分组' }}的权限；
      一旦配置了 ACL，未授权的用户将看不到（或操作不了）该范围，管理员不受限制。
    </div>

    <!-- 新增授权 -->
    <div class="acl-form">
      <el-select v-model="userId" size="small" filterable placeholder="选择用户" style="width:170px">
        <el-option v-for="u in users" :key="u.id" :label="u.nickname ? u.nickname + '（' + u.username + '）' : u.username" :value="u.id" />
      </el-select>
      <el-checkbox-group v-model="actions" size="small" style="margin-left:10px">
        <el-checkbox label="view">查看</el-checkbox>
        <el-checkbox label="operate">操作</el-checkbox>
        <el-checkbox label="update">编辑</el-checkbox>
        <el-checkbox label="delete">删除</el-checkbox>
      </el-checkbox-group>
      <el-button type="primary" size="small" :loading="saving" :disabled="!userId" @click="grant">授予</el-button>
    </div>

    <!-- 已授权清单 -->
    <el-table v-loading="loading" :data="rows" size="mini" border empty-text="还没有授权记录：所有人按默认策略可见可操作">
      <el-table-column label="用户" min-width="140">
        <template slot-scope="{ row }">{{ userName(row.principalId) }}</template>
      </el-table-column>
      <el-table-column label="权限" min-width="180">
        <template slot-scope="{ row }">
          <el-tag v-for="p in (row.perms || '').split(',')" :key="p" size="mini" class="acl-tag">{{ actionText(p) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="授予人" width="90" prop="createdBy" show-overflow-tooltip />
      <el-table-column label="操作" width="60" align="center">
        <template slot-scope="{ row }">
          <el-button type="text" size="mini" class="danger-text" @click="revoke(row)">移除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div slot="footer">
      <el-button size="small" @click="visibleSync = false">关闭</el-button>
    </div>
  </el-dialog>
</template>

<script>
import { fetchAclUsers, fetchAclList, grantAcl, revokeAcl } from '@/api/acl'

const ACTION_TEXT = { view: '查看', operate: '操作', update: '编辑', delete: '删除' }

/**
 * 设备/分组级授权弹窗：配置谁能看、谁能操作某一台设备或整个分组。
 * 授权后立即生效（后端 API 层拦截），无需重启。
 */
export default {
  name: 'AclDialog',
  props: {
    visible: { type: Boolean, default: false },
    // device / group
    scopeType: { type: String, default: 'device' },
    scopeId: { type: String, default: null },
    title: { type: String, default: '' }
  },
  data() {
    return {
      loading: false,
      saving: false,
      users: [],
      rows: [],
      userId: null,
      actions: ['view']
    }
  },
  computed: {
    visibleSync: {
      get() {
        return this.visible
      },
      set(val) {
        this.$emit('update:visible', val)
      }
    }
  },
  watch: {
    visible(val) {
      if (val) {
        this.open()
      }
    }
  },
  methods: {
    open() {
      this.userId = null
      this.actions = ['view']
      this.loadUsers()
      this.loadRows()
    },
    loadUsers() {
      fetchAclUsers().then(res => {
        this.users = res.data || []
      }).catch(() => {})
    },
    loadRows() {
      if (!this.scopeId) {
        return
      }
      this.loading = true
      fetchAclList(this.scopeType, this.scopeId)
        .then(res => {
          this.rows = res.data || []
        })
        .catch(() => {})
        .finally(() => {
          this.loading = false
        })
    },
    userName(id) {
      const u = this.users.find(item => String(item.id) === String(id))
      if (!u) {
        return '用户 #' + id
      }
      return u.nickname ? u.nickname + '（' + u.username + '）' : u.username
    },
    actionText(code) {
      return ACTION_TEXT[code] || code
    },
    grant() {
      if (!this.actions.length) {
        this.$message.warning('至少勾选一个动作')
        return
      }
      this.saving = true
      grantAcl({
        principalId: this.userId,
        scopeType: this.scopeType,
        scopeId: this.scopeId,
        perms: this.actions
      })
        .then(() => {
          this.$message.success('已授权，立即生效')
          this.loadRows()
        })
        .catch(() => {})
        .finally(() => {
          this.saving = false
        })
    },
    revoke(row) {
      revokeAcl(row.id)
        .then(() => {
          this.$message.success('已移除授权')
          this.loadRows()
        })
        .catch(() => {})
    }
  }
}
</script>

<style scoped>
.acl-tip {
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
  margin-bottom: 10px;
}
.acl-form {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 10px;
}
.acl-tag {
  margin-right: 4px;
}
</style>
