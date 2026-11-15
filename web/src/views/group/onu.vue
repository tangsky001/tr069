<template>
  <div v-loading="loading" class="app-container onu-page">
    <div class="onu-row">
      <!-- 左：光猫分组树（与 OLT 页共用同一棵资源分组树，根节点=资源根组） -->
      <div class="tree-col">
        <el-card shadow="never" class="group-card">
          <div slot="header" class="panel-header">
            <span>光猫分组树</span>
            <el-button
              type="primary"
              size="mini"
              icon="el-icon-plus"
              @click="openCreate(currentNode)"
            >新建分组</el-button>
          </div>

          <el-input
            v-model="keyword"
            size="mini"
            prefix-icon="el-icon-search"
            placeholder="搜索分组名称"
            clearable
            class="tree-filter"
          />

          <div class="tree-wrap" @contextmenu.prevent>
            <el-tree
              ref="tree"
              :data="tree"
              :props="{ label: 'name', children: 'children' }"
              :filter-node-method="filterNode"
              node-key="nodeRef"
              highlight-current
              @node-click="onNodeClick"
              @node-contextmenu="onNodeContext"
            >
              <span slot-scope="{ data }" class="tree-node">
                <i :class="nodeIcon(data)" />
                <span>{{ data.name }}</span>
                <el-badge :value="data.memberTotal" type="info" class="tree-count" />
              </span>
            </el-tree>
          </div>
          <div class="tree-tip">右键节点（或点上方按钮）新建子分组：按 civilCode.csv 逐级选区划落库（根→省→市→区县）</div>
        </el-card>
      </div>

      <!-- 右：光猫设备区（设备台账待光猫接入后开放） -->
      <div class="onu-main-col">
        <el-card shadow="never" class="group-card">
          <div slot="header" class="panel-header">
            <span>
              光猫设备
              <span v-if="currentPath" class="member-path">{{ currentPath }}</span>
            </span>
          </div>
          <div class="empty">
            <i class="el-icon-monitor empty-icon" />
            <div>光猫设备管理建设中</div>
            <div class="empty-sub">当前可先在左侧维护光猫的分组结构（与 OLT 共用同一棵资源分组树），设备台账接入后在此展示与归属。</div>
          </div>
        </el-card>
      </div>
    </div>

    <!-- 右键菜单：区划节点只能新建子分组；自定义节点可改名/删除 -->
    <div
      v-show="menuVisible"
      class="ctx-menu"
      :style="{ left: menuX + 'px', top: menuY + 'px' }"
    >
      <template v-if="menuNode && menuNode.kind !== 'group'">
        <div class="ctx-item" @click="openCreate(menuNode)"><i class="el-icon-plus" /> 新建子分组</div>
      </template>
      <template v-else-if="menuNode">
        <div class="ctx-item" @click="openCreate(menuNode)"><i class="el-icon-plus" /> 新建子分组</div>
        <div class="ctx-item" @click="openRename(menuNode)"><i class="el-icon-edit" /> 重命名</div>
        <div v-if="$has('acl:manage')" class="ctx-item" @click="openGroupAcl(menuNode)"><i class="el-icon-lock" /> 设备权限</div>
        <div class="ctx-item danger" @click="removeGroup(menuNode)"><i class="el-icon-delete" /> 删除</div>
      </template>
    </div>

    <!-- 新建子分组弹窗：下级区划候选整齐排列，选中即落库；也可手输名称建自定义组 -->
    <el-dialog
      :title="'新建子分组（父：' + (dialogParentName || '资源根组') + '）'"
      :visible.sync="dialogVisible"
      width="860px"
      top="6vh"
      @closed="resetDialog"
    >
      <div v-if="dialogCandidates.length > 0" class="cand-block">
        <div class="cand-title">选择下级区划（点击选中）：</div>
        <div v-loading="dialogCandidateLoading" class="cand-grid">
          <div
            v-for="option in dialogCandidates"
            :key="option.code"
            class="cand-item"
            :class="{ selected: dialogSelectedCivil === option.code }"
            @click="pickCandidate(option)"
          >
            <i class="el-icon-location-outline" />
            <span>{{ option.name }}</span>
          </div>
        </div>
      </div>
      <div v-else class="cand-empty">
        当前节点没有下级区划候选，可填写名称创建自定义分组（如「北京其他区域」）
      </div>
      <el-form size="small" label-width="80px" class="cand-form">
        <el-form-item label="分组名称">
          <el-input
            v-model="dialogName"
            :placeholder="dialogSelectedCivil ? '区划名已带出，可改' : '自定义名称，如「北京其他区域」'"
            maxlength="64"
          />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button size="small" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" size="small" :loading="dialogSaving" @click="saveDialog">确定</el-button>
      </div>
    </el-dialog>

    <!-- 重命名弹窗 -->
    <el-dialog title="重命名分组" :visible.sync="renameVisible" width="420px" @closed="renameName = ''">
      <el-form size="small" label-width="80px">
        <el-form-item label="分组名称">
          <el-input v-model="renameName" maxlength="64" />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button size="small" @click="renameVisible = false">取消</el-button>
        <el-button type="primary" size="small" :loading="dialogSaving" @click="saveRename">确定</el-button>
      </div>
    </el-dialog>

    <!-- 分组级授权弹窗 -->
    <acl-dialog
      :visible.sync="aclVisible"
      :scope-type="aclScopeType"
      :scope-id="aclScopeId"
      :title="aclTitle"
    />
  </div>
</template>

<script>
import {
  fetchGroupTree,
  fetchGroupCandidates,
  createGroup,
  renameGroup,
  deleteGroup
} from '@/api/group'
import AclDialog from './components/AclDialog'

/**
 * 设备根域 - 光猫：与 OLT 页共用同一棵资源分组树（根节点=资源根组），
 * 但树计数/成员按 memberType=ONU 单独统计，与 OLT 数据互不混入。
 * 右键节点新建子分组 / 重命名 / 删除 / 分组授权；光猫设备台账待设备模块接入后，
 * 通过 /group/attach、/group/move-member 落归属，即可在此展示。
 */
export default {
  name: 'GroupOnu',
  components: { AclDialog },
  data() {
    return {
      loading: false,
      keyword: '',
      tree: [],
      currentNode: null,
      currentRef: '',
      currentPath: '',
      // 右键菜单
      menuVisible: false,
      menuX: 0,
      menuY: 0,
      menuNode: null,
      // 新建弹窗
      dialogVisible: false,
      dialogParentRef: null,
      dialogParentName: '',
      dialogName: '',
      dialogSaving: false,
      dialogCandidates: [],
      dialogCandidateLoading: false,
      dialogSelectedCivil: null,
      // 重命名弹窗
      renameVisible: false,
      renameId: null,
      renameName: '',
      // 分组授权弹窗
      aclVisible: false,
      aclScopeType: 'group',
      aclScopeId: null,
      aclTitle: ''
    }
  },
  watch: {
    keyword(val) {
      this.$refs.tree && this.$refs.tree.filter(val)
    }
  },
  created() {
    this.loadTree()
    document.addEventListener('click', this.hideMenu)
  },
  beforeDestroy() {
    document.removeEventListener('click', this.hideMenu)
  },
  methods: {
    async loadTree() {
      this.loading = true
      try {
        // 光猫页按 ONU 类型统计：光猫设备模块接入前计数恒为 0
        const res = await fetchGroupTree('ONU')
        this.tree = res.data || []
        this.syncCurrentNode()
      } finally {
        this.loading = false
      }
    },
    /** 树重新加载后，用 currentRef 在新树里找回当前节点（保持选中态） */
    syncCurrentNode() {
      if (!this.currentRef) {
        this.currentNode = null
        this.currentPath = ''
        return
      }
      const find = (nodes) => {
        for (const node of nodes) {
          if (node.nodeRef === this.currentRef) {
            return node
          }
          const hit = find(node.children || [])
          if (hit) {
            return hit
          }
        }
        return null
      }
      this.currentNode = find(this.tree)
      this.currentPath = this.pathFromTree(this.currentRef)
    },
    nodeIcon(data) {
      if (data.kind === 'root') {
        return 'el-icon-house'
      }
      if (data.kind === 'civil') {
        return data.level === 1 ? 'el-icon-location-information' : 'el-icon-location-outline'
      }
      return data.children && data.children.length ? 'el-icon-folder-opened' : 'el-icon-folder'
    },
    async onNodeClick(data) {
      this.currentNode = data
      this.currentRef = data.nodeRef
      this.currentPath = this.pathFromTree(data.nodeRef)
    },
    pathFromTree(ref) {
      const findPath = (nodes, trail) => {
        for (const node of nodes) {
          const next = [...trail, node.name]
          if (node.nodeRef === ref) {
            return next.join(' / ')
          }
          const hit = findPath(node.children || [], next)
          if (hit) {
            return hit
          }
        }
        return null
      }
      return findPath(this.tree, []) || ''
    },
    filterNode(value, data) {
      if (!value) {
        return true
      }
      return data.name.indexOf(value) !== -1
    },

    // ---------- 右键菜单 ----------
    onNodeContext(event, data) {
      this.menuX = event.clientX
      this.menuY = event.clientY
      this.menuNode = data
      this.menuVisible = true
    },
    hideMenu() {
      this.menuVisible = false
    },

    // ---------- 新建子分组 ----------
    async openCreate(parentNode) {
      this.dialogParentRef = parentNode ? parentNode.nodeRef : 'ROOT'
      this.dialogParentName = parentNode ? parentNode.name : '资源根组'
      this.dialogSelectedCivil = null
      this.dialogCandidates = []
      this.dialogVisible = true
      await this.loadCandidates(this.dialogParentRef)
    },
    async loadCandidates(parentRef) {
      this.dialogCandidateLoading = true
      try {
        const res = await fetchGroupCandidates(parentRef)
        this.dialogCandidates = res.data || []
      } catch (e) {
        this.dialogCandidates = []
      } finally {
        this.dialogCandidateLoading = false
      }
    },
    pickCandidate(option) {
      this.dialogSelectedCivil = option.code
      this.dialogName = option.name
    },
    resetDialog() {
      this.dialogName = ''
      this.dialogSelectedCivil = null
      this.dialogCandidates = []
    },
    async saveDialog() {
      if (!this.dialogSelectedCivil && !this.dialogName.trim()) {
        this.$message.warning('请选择区划或填写分组名称')
        return
      }
      this.dialogSaving = true
      try {
        await createGroup({
          parentRef: this.dialogParentRef || 'ROOT',
          civilCode: this.dialogSelectedCivil,
          name: this.dialogName.trim()
        })
        this.$message.success('分组已创建')
        this.dialogVisible = false
        await this.loadTree()
      } catch (e) {
        this.$message.error(e && e.message ? e.message : '创建失败')
      } finally {
        this.dialogSaving = false
      }
    },

    // ---------- 重命名 / 删除 ----------
    openRename(node) {
      this.renameId = node.id
      this.renameName = node.name
      this.renameVisible = true
      this.hideMenu()
    },
    async saveRename() {
      if (!this.renameName.trim()) {
        this.$message.warning('请填写分组名称')
        return
      }
      this.dialogSaving = true
      try {
        await renameGroup(this.renameId, this.renameName.trim())
        this.$message.success('已重命名')
        this.renameVisible = false
        await this.loadTree()
      } catch (e) {
        this.$message.error(e && e.message ? e.message : '保存失败')
      } finally {
        this.dialogSaving = false
      }
    },
    async removeGroup(node) {
      this.hideMenu()
      try {
        await this.$confirm(`确定删除分组「${node.name}」？`, '提示', { type: 'warning' })
      } catch (e) {
        return
      }
      try {
        await deleteGroup(node.nodeRef)
        this.$message.success('已删除')
        if (this.currentRef === node.nodeRef) {
          this.currentRef = ''
          this.currentPath = ''
          this.currentNode = null
        }
        await this.loadTree()
      } catch (e) {
        this.$message.error(e && e.message ? e.message : '删除失败')
      }
    },

    // ---------- 分组级授权 ----------
    openGroupAcl(node) {
      this.aclScopeType = 'group'
      this.aclScopeId = node.nodeRef
      this.aclTitle = node.name
      this.aclVisible = true
    }
  }
}
</script>

<style scoped>
/* 撑满可视区：navbar 50px + app-container 上下内边距 40px */
.onu-page {
  height: calc(100vh - 90px);
  display: flex;
  flex-direction: column;
}
.onu-row {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 16px;
}
.tree-col {
  flex: 0 0 400px;
  width: 400px;
  max-width: 400px;
  min-height: 0;
}
.onu-main-col {
  flex: 1;
  min-width: 0;
  min-height: 0;
}
.group-card {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.group-card ::v-deep .el-card__body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-weight: 600;
}
.tree-filter {
  flex: none;
  width: 100%;
  margin-bottom: 10px;
}
.tree-wrap {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
.tree-node {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.tree-count {
  margin-left: 4px;
}
.tree-count ::v-deep .el-badge__content {
  position: static;
  transform: none;
}
.tree-tip {
  flex: none;
  color: #909399;
  font-size: 12px;
  padding: 8px 4px 0;
  border-top: 1px dashed #ebeef5;
  margin-top: 8px;
}
.member-path {
  margin-left: 10px;
  color: #909399;
  font-size: 13px;
  font-weight: 400;
}
.empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #909399;
  font-size: 14px;
}
.empty-icon {
  font-size: 48px;
  color: #c0c4cc;
}
.empty-sub {
  font-size: 12px;
  max-width: 420px;
  text-align: center;
  line-height: 1.6;
}
/* 右键菜单 */
.ctx-menu {
  position: fixed;
  z-index: 3000;
  min-width: 130px;
  padding: 4px 0;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.12);
}
.ctx-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 14px;
  color: #606266;
  font-size: 13px;
  cursor: pointer;
}
.ctx-item:hover {
  background: #f5f7fa;
  color: #409eff;
}
.ctx-item.danger:hover {
  color: #f56c6c;
}
.cand-block {
  margin-bottom: 12px;
}
.cand-title {
  color: #606266;
  font-size: 13px;
  margin-bottom: 8px;
}
.cand-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 10px;
  min-height: 120px;
  max-height: 300px;
  overflow: auto;
}
.cand-item {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 14px 6px;
  font-size: 14px;
  color: #303133;
  background: #f5f7fa;
  border: 1px solid transparent;
  border-radius: 4px;
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cand-item i {
  color: #909399;
}
.cand-item:hover {
  background: #ecf5ff;
}
.cand-item.selected {
  background: #ecf5ff;
  border-color: #409eff;
  color: #409eff;
  font-weight: 600;
}
.cand-item.selected i {
  color: #409eff;
}
.cand-empty {
  color: #909399;
  font-size: 13px;
  padding: 8px 0 4px;
}
.cand-form {
  margin-top: 6px;
}
</style>
