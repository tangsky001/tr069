<template>
  <el-card shadow="never" class="group-tree-card">
    <div slot="header" class="gt-head">
      <span>按分组筛选</span>
      <el-button
        v-if="selectedRef"
        type="text"
        size="mini"
        icon="el-icon-close"
        class="gt-clear"
        @click="clearSelect"
      >全部</el-button>
    </div>

    <el-input
      v-model="keyword"
      size="mini"
      prefix-icon="el-icon-search"
      placeholder="搜索分组"
      clearable
      class="gt-filter"
    />

    <el-tree
      ref="tree"
      :data="displayTree"
      :props="{ label: 'name', children: 'children' }"
      :filter-node-method="filterNode"
      node-key="nodeRef"
      highlight-current
      :current-node-key="selectedRef"
      default-expand-all
      @node-click="onNodeClick"
    >
      <span slot-scope="{ data }" class="gt-node">
        <!-- 设备叶子：SVG 图标 + 设备名，无子级指示 -->
        <template v-if="data.kind === 'device'">
          <svg class="gt-svg" viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
            <rect x="1" y="4" width="14" height="8" rx="1.5" fill="#e8eef7" stroke="#7a8ba6" />
            <circle cx="3.6" cy="8" r="1.1" fill="#4a89dc" />
            <circle cx="6.4" cy="8" r="1.1" fill="#4a89dc" />
            <rect x="9.2" y="6.6" width="4.6" height="2.8" rx="0.6" fill="#ffffff" stroke="#7a8ba6" />
          </svg>
          <span class="gt-name" :title="data.name">{{ data.name }}</span>
        </template>
        <!-- 分组节点：文件夹图标 + 计数角标 -->
        <template v-else>
          <i :class="nodeIcon(data)" />
          <span class="gt-name" :title="data.name">{{ data.name }}</span>
          <el-badge :value="data.memberTotal" type="info" class="gt-count" />
        </template>
      </span>
    </el-tree>

    <div v-if="!tree.length && !loading" class="gt-empty">
      还没有分组，去「分组管理」创建
    </div>
  </el-card>
</template>

<script>
import { fetchGroupTree } from '@/api/group'

/**
 * OLT 管理页左侧的分组树筛选栏（只读复用分组管理的树接口）：
 * 分组节点下面直接列出该组的设备（后端 members 字段，SVG 图标标识）；
 * 点分组节点 → 向外抛 node-select（含 nodeRef），管理页拉组内成员过滤 OLT 下拉；
 * 点设备叶子 → 抛 device-select（含设备 id），管理页直接选中该设备。
 * 只做展示与选择，不改任何分组数据，也不侵入厂商面板。
 */
export default {
  name: 'OltGroupTree',
  data() {
    return {
      loading: false,
      keyword: '',
      tree: [],
      selectedRef: null
    }
  },
  computed: {
    /** 展示树：分组节点下追加直属设备叶子（设备排在子分组后面） */
    displayTree() {
      const decorate = nodes => (nodes || []).map(node => ({
        ...node,
        children: [
          ...decorate(node.children),
          ...(node.members || []).map(m => ({
            nodeRef: 'D:' + m.id,
            name: m.name,
            kind: 'device',
            deviceId: m.id,
            children: []
          }))
        ]
      }))
      return decorate(this.tree)
    }
  },
  watch: {
    keyword(val) {
      this.$refs.tree && this.$refs.tree.filter(val)
    }
  },
  created() {
    this.loadTree()
  },
  methods: {
    loadTree() {
      this.loading = true
      return fetchGroupTree()
        .then(res => {
          this.tree = res.data || []
        })
        .catch(() => {})
        .finally(() => {
          this.loading = false
        })
    },
    onNodeClick(data) {
      // 设备叶子：只做设备定位，不改分组筛选
      if (data.kind === 'device') {
        this.$emit('device-select', data)
        return
      }
      // 再点一次已选中的节点 = 取消筛选，恢复全部
      if (this.selectedRef === data.nodeRef) {
        this.clearSelect()
        return
      }
      this.selectedRef = data.nodeRef
      this.$emit('node-select', data)
    },
    clearSelect() {
      this.selectedRef = null
      this.$emit('node-select', null)
    },
    nodeIcon(data) {
      if (data.kind === 'root') return 'el-icon-house'
      return data.children && data.children.length ? 'el-icon-folder-opened' : 'el-icon-folder'
    },
    filterNode(value, data) {
      if (!value) return true
      return data.name.indexOf(value) !== -1
    }
  }
}
</script>

<style scoped>
.group-tree-card {
  width: 260px;
  flex: none;
  display: flex;
  flex-direction: column;
}
.group-tree-card ::v-deep .el-card__body {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
.gt-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}
.gt-clear {
  padding: 0;
  color: #409eff;
}
.gt-filter {
  margin-bottom: 8px;
}
.gt-node {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}
.gt-svg {
  flex: none;
}
.gt-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.gt-count ::v-deep .el-badge__content {
  position: static;
  transform: none;
}
/* 标准树形引导连接线 */
.group-tree-card ::v-deep .el-tree > .el-tree-node {
  padding-left: 0;
}
.group-tree-card ::v-deep .el-tree-node {
  position: relative;
  padding-left: 16px;
}
.group-tree-card ::v-deep .el-tree-node__children {
  padding-left: 0;
}
/* Element 的缩进是行内样式，必须清零，缩进统一由节点自身的 padding-left 逐级提供 */
.group-tree-card ::v-deep .el-tree-node__content {
  padding-left: 0 !important;
}
.group-tree-card ::v-deep .el-tree-node::before {
  content: '';
  position: absolute;
  left: 7px;
  top: 0;
  bottom: 0;
  width: 1px;
  background: #e4e7ed;
}
.group-tree-card ::v-deep .el-tree-node::after {
  content: '';
  position: absolute;
  left: 7px;
  top: 13px;
  width: 9px;
  height: 1px;
  background: #e4e7ed;
}
.group-tree-card ::v-deep .el-tree-node:last-child::before {
  bottom: auto;
  height: 13px;
}
.group-tree-card ::v-deep .el-tree > .el-tree-node::before,
.group-tree-card ::v-deep .el-tree > .el-tree-node::after {
  display: none;
}
.gt-empty {
  color: #909399;
  font-size: 12px;
  padding: 8px 0;
  text-align: center;
}
</style>
