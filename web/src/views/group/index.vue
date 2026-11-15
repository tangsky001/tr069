<template>
  <div v-loading="loading" class="app-container group-page">
    <div class="group-row">
      <!-- OLT分组树：固定 400px 宽，高度自适应撑满可视区 -->
      <div class="tree-col">
        <el-card shadow="never" class="group-card">
          <div slot="header" class="panel-header">
            <span>OLT分组树</span>
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

      <!-- 右：组内设备（添加 OLT 即归属当前分组） -->
      <div class="member-col">
        <el-card shadow="never" class="group-card member-card">
          <div slot="header" class="panel-header">
            <span>
              组内设备
              <span v-if="currentPath" class="member-path">{{ currentPath }}</span>
            </span>
            <el-button
              type="primary"
              size="small"
              icon="el-icon-plus"
              :disabled="!currentRef"
              @click="openAddOlt"
            >添加 OLT</el-button>
          </div>

          <div v-if="!currentRef" class="empty">左侧选择一个分组节点，查看组内 OLT</div>
          <template v-else>
            <el-table :data="members" size="mini" border empty-text="该节点还没有设备，点左侧「添加 OLT」，添加即归属此分组">
              <el-table-column prop="name" label="设备名称" min-width="130" show-overflow-tooltip />
              <el-table-column prop="host" label="地址" min-width="120" show-overflow-tooltip />
              <el-table-column prop="vendorText" label="厂商" width="80" align="center" />
              <el-table-column label="状态" width="80" align="center">
                <template slot-scope="{ row }">
                  <el-tag size="mini" :type="row.statusLevel">{{ row.statusText }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="添加时间" width="150">
                <template slot-scope="{ row }">{{ formatTime(row.boundAt) }}</template>
              </el-table-column>
              <el-table-column prop="createdBy" label="操作人" width="90" show-overflow-tooltip />
              <el-table-column label="操作" width="200" align="center">
                <template slot-scope="{ row }">
                  <el-button type="text" size="mini" @click="openEditOlt(row)">编辑</el-button>
                  <el-button type="text" size="mini" @click="openMove(row)">移动</el-button>
                  <el-button v-if="$has('acl:manage')" type="text" size="mini" @click="openDeviceAcl(row)">权限</el-button>
                  <el-button type="text" size="mini" @click="jumpToOlt(row)">管理</el-button>
                  <el-button type="text" size="mini" class="danger-text" @click="removeOlt(row)">删除</el-button>
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

    <!-- 移动设备到其他分组弹窗：树上点选目标节点，ROOT=移回根节点 -->
    <el-dialog
      :title="'移动「' + (moveDeviceName || '') + '」到其他分组'"
      :visible.sync="moveVisible"
      width="420px"
      top="8vh"
    >
      <div class="move-tip">点击选择目标分组（选「资源根组」= 移回根节点）：</div>
      <el-tree
        :data="tree"
        :props="{ label: 'name', children: 'children' }"
        node-key="nodeRef"
        highlight-current
        default-expand-all
        @node-click="onMovePick"
      >
        <span slot-scope="{ data }" class="move-node">
          <i :class="nodeIcon(data)" />
          <span>{{ data.name }}</span>
        </span>
      </el-tree>
      <div slot="footer">
        <el-button size="small" @click="moveVisible = false">取消</el-button>
        <el-button
          type="primary"
          size="small"
          :disabled="!moveTarget"
          :loading="moving"
          @click="doMove"
        >确定移动</el-button>
      </div>
    </el-dialog>

    <!-- 设备/分组级授权弹窗（OLT 表域） -->
    <acl-dialog
      :visible.sync="aclVisible"
      :scope-type="aclScopeType"
      :scope-id="aclScopeId"
      :title="aclTitle"
      member-type="OLT"
    />

    <!-- 添加 / 编辑 OLT：添加即归属当前选中分组（groupRef 为空 = 根节点） -->
    <olt-form-dialog
      :visible.sync="addVisible"
      :olt="editingOlt"
      :group-ref="currentRef"
      :group-name="currentPath"
      @saved="onOltSaved"
    />
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
  moveGroupMember
} from '@/api/group'
import OltFormDialog from '@/views/olt/components/OltFormDialog'
import AclDialog from './components/AclDialog'
import { deleteOlt, fetchOltDetail } from '@/api/olt'

/**
 * 分组管理（OLT）：默认只有固定根节点「资源根组」（未绑定设备的默认归属）。
 * 右键节点新建子分组：弹窗按 civilCode.csv 列出该节点下级区划候选
 * （根→34 省，省→市，市→区县），选中确定才落库；区县级可手输名称建自定义组。
 * 点「添加 OLT」把设备直接建在当前选中分组。
 */
export default {
  name: 'GroupManage',
  components: { OltFormDialog, AclDialog },
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
      // 添加 / 编辑 OLT 弹窗（添加即归属当前分组）
      addVisible: false,
      editingOlt: null,
      // 设备/分组授权弹窗
      aclVisible: false,
      aclScopeType: 'device',
      aclScopeId: null,
      aclTitle: '',
      // 移动分组弹窗
      moveVisible: false,
      moveOltId: null,
      moveDeviceName: '',
      moveTarget: null,
      moveTargetName: '',
      moving: false
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
        const res = await fetchGroupTree()
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
      this.loadMembers()
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
        const res = await fetchGroupCandidates(this.dialogParentRef, 'OLT')
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
          name: this.dialogName.trim(),
          memberType: 'OLT'
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
        await renameGroup(this.renameId, this.renameName.trim(), 'OLT')
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
        await deleteGroup(node.nodeRef, 'OLT')
        this.$message.success('已删除')
        if (this.currentRef === node.nodeRef) {
          this.currentRef = ''
          this.currentPath = ''
          this.currentNode = null
          this.members = []
        }
        await this.loadTree()
      } catch (e) {
        this.$message.error(e && e.message ? e.message : '删除失败')
      }
    },

    // ---------- 添加 OLT：添加即归属当前选中分组 ----------
    openAddOlt() {
      if (!this.currentRef) {
        this.$message.warning('请先在左侧分组树选择一个分组节点')
        return
      }
      this.addVisible = true
    },
    async openEditOlt(row) {
      // 拉全量台账（含账号/端口/字符集等），表单回显才完整
      try {
        const res = await fetchOltDetail(row.oltId)
        this.editingOlt = (res.data && res.data.device) || null
        this.addVisible = true
      } catch (e) {
        this.$message.error('设备信息加载失败')
      }
    },
    async removeOlt(row) {
      try {
        await this.$confirm(`确定删除设备「${row.name}」？删除后该设备的采集数据同时清理。`, '提示', { type: 'warning' })
      } catch (e) {
        return
      }
      try {
        await deleteOlt(row.oltId)
        this.$message.success('设备已删除')
        await Promise.all([this.loadMembers(), this.loadTree()])
      } catch (e) {
        this.$message.error(e && e.message ? e.message : '删除失败')
      }
    },
    onOltSaved() {
      this.$message.success(this.editingOlt ? '设备已更新' : '设备已添加，归属当前分组')
      this.editingOlt = null
      this.loadTree()
    },
    /** 跳到 OLT 管理页并直接选中这台设备进行登录/管理 */
    jumpToOlt(row) {
      this.$router.push({ path: '/olt/management', query: { deviceId: row.oltId }})
    },

    // ---------- 设备/分组级授权 ----------
    openDeviceAcl(row) {
      this.aclScopeType = 'device'
      this.aclScopeId = String(row.oltId)
      this.aclTitle = row.name
      this.aclVisible = true
    },
    openGroupAcl(node) {
      this.aclScopeType = 'group'
      this.aclScopeId = node.nodeRef
      this.aclTitle = node.name
      this.aclVisible = true
    },

    // ---------- 移动设备到其他分组 ----------
    openMove(row) {
      this.moveOltId = row.oltId
      this.moveDeviceName = row.name
      this.moveTarget = null
      this.moveTargetName = ''
      this.moveVisible = true
    },
    onMovePick(data) {
      this.moveTarget = data.nodeRef
      this.moveTargetName = data.name
    },
    async doMove() {
      if (!this.moveTarget || !this.moveOltId) {
        return
      }
      this.moving = true
      try {
        await moveGroupMember(this.moveTarget, this.moveOltId)
        this.$message.success('「' + this.moveDeviceName + '」已移动到「' + this.moveTargetName + '」')
        this.moveVisible = false
        await Promise.all([this.loadMembers(), this.loadTree()])
      } catch (e) {
        this.$message.error(e && e.message ? e.message : '移动失败')
      } finally {
        this.moving = false
      }
    },
    formatTime(value) {
      return (value || '').replace('T', ' ').slice(0, 19) || '-'
    }
  }
}
</script>

<style scoped>
/* 撑满可视区：navbar 50px + app-container 上下内边距 40px */
.group-page {
  height: calc(100vh - 90px);
  display: flex;
  flex-direction: column;
}
.group-row {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 16px;
}
.tree-col {
  flex: 0 0 300px;
  width: 300px;
  max-width: 300px;
  min-height: 0;
}
.member-col {
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
.member-card ::v-deep .el-card__body {
  overflow: auto;
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

.move-tip {
  color: #606266;
  font-size: 13px;
  margin-bottom: 8px;
}
.move-node {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
</style>
