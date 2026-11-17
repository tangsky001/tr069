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

      <!-- 右：光猫设备台账（ACS 真实台账 + 归属分组 + 资源组 ACL 过滤，后端一次算好） -->
      <div class="onu-main-col">
        <el-card ref="deviceCard" shadow="never" class="group-card">
          <div slot="header" class="panel-header">
            <span>
              光猫设备
              <span v-if="currentPath" class="member-path">{{ currentPath }}</span>
            </span>
            <div class="head-actions">
              <el-tag
                v-if="currentRef"
                size="mini"
                type="warning"
                closable
                @close="clearNode"
              >分组：{{ currentNode ? currentNode.name : currentRef }}</el-tag>
              <el-button size="mini" icon="el-icon-refresh" :loading="membersLoading" @click="loadMembers">刷新</el-button>
            </div>
          </div>

          <el-table
            ref="deviceTable"
            v-loading="membersLoading"
            :data="members"
            :height="tableHeight"
            :empty-text="memberEmptyText"
            size="small"
            border
            fit
            highlight-current-row
            style="width:100%"
          >
            <!-- 设备标识：光猫 SN 为主，副行归属时间 -->
            <el-table-column label="设备标识(SN)" min-width="200">
              <template slot-scope="{ row }">
                <div class="cell-main">{{ row.memberRef || '—' }}</div>
                <div class="cell-sub">
                  <span v-if="row.boundAt" class="cell-time">归属 {{ fmtTime(row.boundAt) }}</span>
                </div>
              </template>
            </el-table-column>

            <el-table-column label="终端型号" min-width="150">
              <template slot-scope="{ row }">{{ row.name || '—' }}</template>
            </el-table-column>

            <el-table-column label="厂商" width="80">
              <template slot-scope="{ row }">{{ row.vendorText || '—' }}</template>
            </el-table-column>

            <el-table-column label="软件版本" width="110">
              <template slot-scope="{ row }">{{ row.softwareVersion || '—' }}</template>
            </el-table-column>

            <el-table-column label="运营商" width="70">
              <template slot-scope="{ row }">{{ row.isp || '—' }}</template>
            </el-table-column>

            <el-table-column label="IP" width="100">
              <template slot-scope="{ row }">{{ row.ip || '—' }}</template>
            </el-table-column>

            <el-table-column label="在线状态" width="70">
              <template slot-scope="{ row }">
                <el-tag :type="row.statusLevel || 'info'" size="mini">{{ row.statusText || '未采集' }}</el-tag>
              </template>
            </el-table-column>

            <!-- 实际归属：后端按归属记录算好的分组全路径，根视角下看真实落组 -->
            <el-table-column label="设备归属" min-width="140">
              <template slot-scope="{ row }">{{ row.actualGroupText || '—' }}</template>
            </el-table-column>

            <el-table-column label="最近上报" width="150">
              <template slot-scope="{ row }">{{ row.lastInformText || '—' }}</template>
            </el-table-column>

            <el-table-column label="备注" min-width="120" show-overflow-tooltip>
              <template slot-scope="{ row }">{{ row.remark || '—' }}</template>
            </el-table-column>

            <!-- 操作：进详情做运维操作（重启/批量下发等），备注直接改（同设备管理弹窗/API），移动改归属分组 -->
            <el-table-column label="操作" width="150" fixed="right">
              <template slot-scope="{ row }">
                <el-button
                  type="text"
                  size="mini"
                  icon="el-icon-view"
                  @click="goDetail(row)"
                >详情</el-button>
                <el-button v-if="canMove" type="text" size="mini" icon="el-icon-edit-outline" @click="openRemark(row)">备注</el-button>
                <el-button v-if="canMove" type="text" size="mini" icon="el-icon-position" @click="openMove(row)">移动</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </div>
    </div>

    <!-- 移动光猫到别的分组：点选目标节点，ROOT=移回资源根组 -->
    <el-dialog
      :title="'移动光猫：' + (moveRow ? (moveRow.memberRef || moveRow.name || '') : '')"
      :visible.sync="moveVisible"
      width="420px"
      top="8vh"
      @closed="resetMove"
    >
      <div class="move-tip">点选目标分组；选「资源根组」= 移回根节点（不归属任何分组）：</div>
      <el-tree
        v-loading="moveLoading"
        :data="moveTree"
        :props="{ label: 'name', children: 'children' }"
        node-key="nodeRef"
        highlight-current
        default-expand-all
        @node-click="onMovePick"
      >
        <span slot-scope="{ data }" class="move-node">
          <i :class="data.kind === 'root' ? 'el-icon-house' : 'el-icon-folder'" />
          <span>{{ data.name }}</span>
        </span>
      </el-tree>
      <div slot="footer">
        <el-button size="small" @click="moveVisible = false">取消</el-button>
        <el-button
          type="primary"
          size="small"
          :disabled="!moveTargetRef"
          :loading="moveSaving"
          @click="submitMove"
        >确定</el-button>
      </div>
    </el-dialog>

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

    <!-- 分组级授权弹窗（光猫表域 onu_res_acl） -->
    <acl-dialog
      :visible.sync="aclVisible"
      :scope-type="aclScopeType"
      :scope-id="aclScopeId"
      :title="aclTitle"
      member-type="ONU"
    />

    <!-- 房间号 / 备注：与设备管理页同一弹窗同一 API（POST /device/{sn}/remark），
         房间号用于下发时替换模板里的 %room% -->
    <el-dialog title="修改房间号与备注" :visible.sync="remarkVisible" width="460px" append-to-body>
      <el-form label-width="72px">
        <el-form-item label="序列号">
          <span class="remark-sn">{{ remarkRow ? remarkRow.memberRef : '-' }}</span>
        </el-form-item>
        <el-form-item label="房间号">
          <el-input
            v-model="remarkForm.roomNo"
            placeholder="如 0001，下发时替换 %room%"
            maxlength="32"
            clearable
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="remarkForm.remark"
            type="textarea"
            :rows="3"
            placeholder="设备说明，可留空"
            maxlength="255"
          />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="remarkVisible = false">取消</el-button>
        <el-button type="primary" :loading="remarkSaving" @click="submitRemark">保存</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import {
  fetchGroupTree,
  fetchGroupCandidates,
  fetchGroupMembers,
  createGroup,
  renameGroup,
  deleteGroup,
  moveGroupMemberByRef
} from '@/api/group'
import { updateDeviceRemark } from '@/api/device'
import AclDialog from './components/AclDialog'

/**
 * 设备根域 - 光猫设备（分配树）：全量光猫的主入口。
 * 左侧资源分组树（memberType=ONU 独立成树，权限独立），右侧 ACS 真实台账：
 * 后端按 SN 关联 cpe_device 出在线状态/版本/IP，并按光猫资源组 ACL 过滤可见性
 * （归属分组 → 上级分组 → 资源根组，第一个有授权的层级决定；未归属=资源根组）。
 * 归属/移动分组、分组授权在这里完成；改备注/重启/批量下发等运维操作进设备详情。
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
      // 光猫设备台账：设备模块未接入，先按分组归属（memberType=ONU）出一行一台
      members: [],
      membersLoading: false,
      // 右侧表格高度自适应
      tableHeight: null,
      // 移动光猫到别的分组（调 /group/move-member，已有归属直接改）
      moveVisible: false,
      moveRow: null,
      moveTree: [],
      moveLoading: false,
      moveTargetRef: null,
      moveTargetName: '',
      moveSaving: false,
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
      aclTitle: '',
      // 房间号 / 备注弹窗（同设备管理页）
      remarkVisible: false,
      remarkRow: null,
      remarkForm: { roomNo: '', remark: '' },
      remarkSaving: false
    }
  },
  computed: {
    /** 表格空态：区分「选中分组下没有」与「整体还没有上报的光猫」 */
    memberEmptyText() {
      if (this.currentRef) {
        return '当前分组下还没有光猫；点「资源根组」可看全部光猫，点「移动」调整归属'
      }
      return '还没有 ACS 上报的光猫（设备配置 ACS 地址并上报后自动出现在这里）'
    },
    /** 移动光猫归属走设备管理权限，与「设备管理-归属分组」一致 */
    canMove() {
      return this.$has('device:update')
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
  mounted() {
    this.calcTableHeight()
    window.addEventListener('resize', this.calcTableHeight)
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.calcTableHeight)
    document.removeEventListener('click', this.hideMenu)
  },
  methods: {
    async loadTree() {
      this.loading = true
      try {
        // 光猫页按 ONU 类型统计：计数来自 ACS 真实台账并按权限过滤
        const res = await fetchGroupTree('ONU')
        this.tree = res.data || []
        this.syncCurrentNode()
        await this.loadMembers()
      } finally {
        this.loading = false
      }
    },
    /** 拉当前节点（未选节点=根节点）直属光猫：后端关联真实台账并按资源组权限过滤 */
    async loadMembers() {
      this.membersLoading = true
      try {
        const res = await fetchGroupMembers(this.currentRef || 'ROOT', 'ONU')
        this.members = res.data || []
      } catch (e) {
        this.members = []
      } finally {
        this.membersLoading = false
        this.calcTableHeight()
      }
    },
    /** 进光猫详情页：改备注、重启、下发等主要操作都在设备详情/设备管理里做 */
    goDetail(row) {
      if (!row.memberRef) {
        return
      }
      this.$router.push('/device-detail/' + row.memberRef)
    },
    // ---------- 房间号 / 备注（与设备管理页同一 API） ----------
    openRemark(row) {
      this.remarkRow = row
      this.remarkForm = { roomNo: row.roomNo || '', remark: row.remark || '' }
      this.remarkVisible = true
    },
    async submitRemark() {
      const row = this.remarkRow
      if (!row || !row.memberRef) {
        return
      }
      const payload = {
        roomNo: this.remarkForm.roomNo || '',
        remark: this.remarkForm.remark || ''
      }
      this.remarkSaving = true
      try {
        await updateDeviceRemark(row.memberRef, payload)
        row.roomNo = payload.roomNo
        row.remark = payload.remark
        this.$message.success('已保存')
        this.remarkVisible = false
      } catch (e) {
        this.$message.error('保存失败: ' + ((e && e.message) || '未知错误'))
      } finally {
        this.remarkSaving = false
      }
    },
    /** 取消节点选中：回到根节点视角 */
    clearNode() {
      this.currentRef = ''
      this.currentPath = ''
      this.currentNode = null
      this.$refs.tree && this.$refs.tree.setCurrentKey(null)
      this.loadMembers()
    },
    // ---------- 移动光猫到别的分组 ----------
    /** 打开移动弹窗：拉光猫分组树点选目标节点 */
    openMove(row) {
      this.moveRow = row
      this.moveTargetRef = null
      this.moveTargetName = ''
      this.moveVisible = true
      this.moveLoading = true
      fetchGroupTree('ONU')
        .then(res => {
          this.moveTree = res.data || []
        })
        .catch(() => {
          this.moveTree = []
        })
        .finally(() => {
          this.moveLoading = false
        })
    },
    onMovePick(data) {
      this.moveTargetRef = data.nodeRef
      this.moveTargetName = data.name
    },
    resetMove() {
      this.moveRow = null
      this.moveTargetRef = null
      this.moveTargetName = ''
      this.moveTree = []
    },
    async submitMove() {
      if (!this.moveRow || !this.moveTargetRef) {
        return
      }
      const ref = this.moveRow.memberRef || this.moveRow.name
      this.moveSaving = true
      try {
        await moveGroupMemberByRef({
          groupRef: this.moveTargetRef,
          memberType: 'ONU',
          memberRef: ref,
          memberLabel: this.moveRow.name
        })
        this.$message.success('「' + ref + '」已移动到「' + this.moveTargetName + '」')
        this.moveVisible = false
        // 左树计数与右侧列表一起刷
        this.loadTree()
      } catch (e) {
        this.$message.error(e && e.message ? e.message : '移动失败')
      } finally {
        this.moveSaving = false
      }
    },

    /** 时间统一 yyyy-MM-dd HH:mm:ss（后端给的是 ISO，带 T） */
    fmtTime(value) {
      if (!value) {
        return '—'
      }
      return String(value).replace('T', ' ').slice(0, 19)
    },
    /** 表格高度 = 卡片可用高度 - 卡片头 - body 上下内边距，窗口缩放自动跟随 */
    calcTableHeight() {
      this.$nextTick(() => {
        const card = this.$refs.deviceCard
        if (!card) {
          return
        }
        const el = card.$el
        const header = el.querySelector('.el-card__header')
        const headerH = header ? header.offsetHeight : 0
        // el-card__body 上下各 20px 内边距
        this.tableHeight = Math.max(el.clientHeight - headerH - 40, 160)
      })
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
      await this.loadMembers()
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
        const res = await fetchGroupCandidates(parentRef, 'ONU')
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
          name: this.dialogName.trim(),
          memberType: 'ONU'
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
        await renameGroup(this.renameId, this.renameName.trim(), 'ONU')
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
        await deleteGroup(node.nodeRef, 'ONU')
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
.remark-sn {
  color: #606266;
  word-break: break-all;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
/* 移动弹窗 */
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
/* 设备标识列：主行 SN，副行名称/归属时间 */
.cell-main {
  color: #303133;
  font-weight: 500;
}
.cell-sub {
  display: flex;
  gap: 10px;
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
}
.cell-time {
  color: #b0b3b8;
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
