<template>
  <div v-loading="loading" class="app-container group-page">
    <div class="group-row">
      <!-- 左：分组树，固定 300px -->
      <div class="tree-col">
        <el-card shadow="never" class="group-card">
          <div slot="header" class="panel-header">
            <span>OLT分组树</span>
            <el-button
              type="primary"
              size="mini"
              icon="el-icon-plus"
              @click="openCreate(currentNode)"
            >新建</el-button>
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

      <!-- 右：组内成员 -->
      <div class="member-col">
        <el-card shadow="never" class="group-card">
          <div slot="header" class="panel-header">
            <span>
              组内成员
              <span v-if="currentPath" class="member-path">{{ currentPath }}</span>
            </span>
            <el-button
              type="primary"
              size="small"
              icon="el-icon-plus"
              :disabled="!currentRef"
              @click="openBind"
            >绑定 OLT</el-button>
          </div>

          <div v-if="!currentRef" class="empty">左侧选择一个分组节点，查看组内 OLT</div>
          <template v-else>
            <el-table :data="members" size="mini" border empty-text="该节点还没有绑定 OLT，点右上角「绑定 OLT」">
              <el-table-column prop="name" label="设备名称" min-width="130" show-overflow-tooltip />
              <el-table-column prop="host" label="地址" min-width="120" show-overflow-tooltip />
              <el-table-column prop="vendorText" label="厂商" width="80" align="center" />
              <el-table-column label="状态" width="80" align="center">
                <template slot-scope="{ row }">
                  <el-tag size="mini" :type="row.statusLevel">{{ row.statusText }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="绑定时间" width="150">
                <template slot-scope="{ row }">{{ formatTime(row.boundAt) }}</template>
              </el-table-column>
              <el-table-column prop="createdBy" label="操作人" width="90" show-overflow-tooltip />
              <el-table-column label="操作" width="70" align="center">
                <template slot-scope="{ row }">
                  <el-button type="text" size="mini" class="danger-text" @click="unbind(row)">解绑</el-button>
                </template>
              </el-table-column>
            </el-table>
          </template>
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

    <!-- 绑定 OLT 弹窗 -->
    <el-dialog title="绑定 OLT 到分组" :visible.sync="bindVisible" width="560px">
      <div v-if="currentPath" class="bind-path">目标节点：{{ currentPath }}</div>
      <el-input
        v-model="bindKeyword"
        size="small"
        prefix-icon="el-icon-search"
        placeholder="搜索 OLT 名称 / 地址"
        clearable
        class="bind-filter"
      />
      <div v-loading="bindLoading" class="bind-list">
        <div
          v-for="option in bindOptions"
          :key="option.id"
          class="bind-row"
          :class="{ selected: bindOltId === option.id }"
          @click="selectOlt(option)"
        >
          <div class="bind-main">
            <span class="bind-name">{{ option.name }}</span>
            <span class="bind-host">{{ option.host }}</span>
            <span class="bind-vendor">{{ option.vendorText }}</span>
          </div>
          <div class="bind-state">
            <el-tag v-if="option.boundCode" size="mini" type="warning">已绑定：{{ option.boundText }}</el-tag>
            <el-tag v-else size="mini" type="info">在根节点</el-tag>
          </div>
        </div>
        <div v-if="bindOptions.length === 0 && !bindLoading" class="empty">没有匹配的 OLT，去「OLT 管理」添加设备</div>
      </div>
      <div slot="footer">
        <el-button size="small" @click="bindVisible = false">取消</el-button>
        <el-button
          type="primary"
          size="small"
          :disabled="!bindOltId"
          :loading="binding"
          @click="bind"
        >绑定</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import {
  fetchGroupTree,
  fetchGroupCandidates,
  createGroup,
  renameGroup,
  deleteGroup,
  fetchGroupMembers,
  fetchBindableOlts,
  bindGroupMember,
  unbindGroupMember
} from '@/api/group'

/**
 * 分组管理：默认只有固定根节点「资源根组」（未绑定设备的默认归属）。
 * 右键节点新建子分组：弹窗按 civilCode.csv 列出该节点下级区划候选
 * （根→34 省，省→市，市→区县），选中确定才落库；区县级可手输名称建自定义组。
 * 右侧组内成员绑定/解绑 OLT；展示文案全部由后端算好，前端只渲染。
 */
export default {
  name: 'GroupManage',
  data() {
    return {
      loading: false,
      keyword: '',
      tree: [],
      currentNode: null,
      currentRef: '',
      currentPath: '',
      members: [],
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
      // 绑定弹窗
      bindVisible: false,
      bindKeyword: '',
      bindOptions: [],
      bindOltId: null,
      bindLoading: false,
      binding: false
    }
  },
  watch: {
    keyword(val) {
      this.$refs.tree && this.$refs.tree.filter(val)
    },
    bindKeyword() {
      this.loadBindOptions()
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
        const res = await fetchGroupTree()
        this.tree = res.data || []
      } finally {
        this.loading = false
      }
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
      this.currentPath = ''
      await this.loadMembers()
    },
    async loadMembers() {
      if (!this.currentRef) {
        return
      }
      this.loading = true
      try {
        const res = await fetchGroupMembers(this.currentRef)
        this.members = res.data || []
        // 路径文案以成员行的 groupText 为准（后端算好）；空节点从树里拼
        if (this.members.length > 0) {
          this.currentPath = this.members[0].groupText
        } else {
          this.currentPath = this.pathFromTree(this.currentRef)
        }
      } finally {
        this.loading = false
      }
    },
    pathFromTree(ref) {
      const findPath = (nodes, trail) => {
        for (const node of nodes) {
          const next = [...trail, node.name]
          if (node.nodeRef === ref) {
            return next
          }
          const hit = findPath(node.children || [], next)
          if (hit) {
            return hit
          }
        }
        return null
      }
      const path = findPath(this.tree, [])
      return path ? path.join(' / ') : ''
    },
    filterNode(value, data) {
      if (!value) {
        return true
      }
      return data.name.indexOf(value) !== -1
    },

    // ---------- 右键菜单 ----------
    onNodeContext(event, data) {
      event.preventDefault()
      // 根节点也允许新建子分组（弹窗列省份），仅不提供改名/删除
      this.menuNode = data
      this.menuX = event.clientX
      this.menuY = event.clientY
      this.menuVisible = true
    },
    hideMenu() {
      this.menuVisible = false
    },

    // ---------- 新建 / 重命名 / 删除 ----------
    async openCreate(parentNode) {
      this.dialogParentRef = parentNode ? parentNode.nodeRef : 'ROOT'
      this.dialogParentName = parentNode ? parentNode.name : '资源根组'
      this.dialogName = ''
      this.dialogSelectedCivil = null
      this.dialogCandidates = []
      this.dialogVisible = true
      this.hideMenu()
      // 拉取父节点下一级区划候选：根→省份，省→市，市→区县
      this.dialogCandidateLoading = true
      try {
        const res = await fetchGroupCandidates(this.dialogParentRef)
        this.dialogCandidates = res.data || []
      } catch (e) {
        this.$message.error('区划候选加载失败，请确认后端已重新打包启动')
        this.dialogCandidates = []
      } finally {
        this.dialogCandidateLoading = false
      }
    },
    resetDialog() {
      this.dialogParentRef = null
      this.dialogParentName = ''
      this.dialogName = ''
      this.dialogSelectedCivil = null
      this.dialogCandidates = []
    },
    pickCandidate(option) {
      this.dialogSelectedCivil = option.code
      // 区划名自动带出，可再改
      this.dialogName = option.name
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
        if (this.currentRef) {
          await this.loadMembers()
        }
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
          this.members = []
          this.currentPath = ''
        }
        await this.loadTree()
      } catch (e) {
        this.$message.error(e && e.message ? e.message : '删除失败')
      }
    },

    // ---------- 绑定 / 解绑 ----------
    openBind() {
      this.bindVisible = true
      this.bindOltId = null
      this.loadBindOptions()
    },
    async loadBindOptions() {
      this.bindLoading = true
      try {
        const res = await fetchBindableOlts(this.bindKeyword)
        this.bindOptions = res.data || []
        this.bindOptions.forEach(option => {
          if (this.currentRef === option.boundCode) {
            option.boundText = '当前节点'
          }
        })
      } finally {
        this.bindLoading = false
      }
    },
    selectOlt(option) {
      // 根节点 = 默认归属：选任何设备都合法（绑定会把已绑定的解绑回根）
      if (option.boundCode && this.currentRef !== option.boundCode && this.currentRef !== 'ROOT') {
        this.$message.warning(`该 OLT 已绑定在「${option.boundText}」，先去解绑再换`)
        return
      }
      this.bindOltId = option.id
    },
    async bind() {
      this.binding = true
      try {
        await bindGroupMember(this.currentRef, this.bindOltId)
        this.$message.success('已绑定')
        this.bindVisible = false
        await Promise.all([this.loadMembers(), this.loadTree()])
      } catch (e) {
        this.$message.error(e && e.message ? e.message : '绑定失败')
      } finally {
        this.binding = false
      }
    },
    async unbind(row) {
      try {
        await this.$confirm(`确定把「${row.name}」从分组解绑？`, '提示', { type: 'warning' })
      } catch (e) {
        return
      }
      try {
        await unbindGroupMember(row.id)
        this.$message.success('已解绑')
        await Promise.all([this.loadMembers(), this.loadTree()])
      } catch (e) {
        this.$message.error(e && e.message ? e.message : '解绑失败')
      }
    },
    formatTime(value) {
      return (value || '').replace('T', ' ').slice(0, 19) || '-'
    }
  }
}
</script>

<style scoped>
.group-row {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}
.tree-col {
  flex: 0 0 300px;
  width: 300px;
  max-width: 300px;
}
.member-col {
  flex: 1;
  min-width: 0;
}
.group-card {
  margin-bottom: 16px;
}
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-weight: 600;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.tree-filter {
  width: 100%;
  margin-bottom: 10px;
}
.tree-wrap {
  min-height: 200px;
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
  color: #909399;
  font-size: 13px;
  padding: 24px 0;
  text-align: center;
}
.danger-text {
  color: #f56c6c;
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
  max-height: 420px;
  overflow: auto;
  padding: 6px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  min-height: 120px;
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
.bind-path {
  margin-bottom: 10px;
  color: #606266;
  font-size: 13px;
}
.bind-filter {
  margin-bottom: 10px;
}
.bind-list {
  max-height: 320px;
  overflow: auto;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}
.bind-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 12px;
  cursor: pointer;
  border-bottom: 1px dashed #ebeef5;
}
.bind-row:last-child {
  border-bottom: none;
}
.bind-row:hover {
  background: #f5f7fa;
}
.bind-row.selected {
  background: #ecf5ff;
}
.bind-main {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.bind-name {
  color: #303133;
  font-weight: 600;
}
.bind-host {
  color: #909399;
  font-size: 12px;
}
.bind-vendor {
  color: #909399;
  font-size: 12px;
}
</style>
