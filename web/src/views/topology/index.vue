<template>
  <div class="app-container">
    <el-card shadow="never" class="topo-panel">
      <div slot="header" class="panel-header">
        <span><i class="el-icon-share" /> 资源拓扑</span>
        <el-tag size="mini" type="warning">建设中</el-tag>
      </div>

      <div class="topo-intro">
        全光组网资源链路可视化：OLT → 板卡 / PON 口 → 光猫（ONT），
        后续在这里按局端到终端的层级展示设备分布、在线状态与业务开通情况。
      </div>

      <!-- 层级示意：数据接入前用占位结构示意最终形态 -->
      <div class="topo-demo">
        <div class="topo-node root">
          <i class="el-icon-s-platform" />
          <div>OLT 局端</div>
          <div class="topo-sub">机框 / 板卡 / 上联口</div>
        </div>
        <div class="topo-link">
          <span class="topo-line" />
          <span class="topo-arrow">▼</span>
        </div>
        <div class="topo-node">
          <i class="el-icon-connection" />
          <div>PON 口</div>
          <div class="topo-sub">分光比 / 光模块状态</div>
        </div>
        <div class="topo-link">
          <span class="topo-line" />
          <span class="topo-arrow">▼</span>
        </div>
        <div class="topo-node leaf">
          <i class="el-icon-cpu" />
          <div>光猫 ONT</div>
          <div class="topo-sub">注册信息 / TR069 业务下发</div>
        </div>
      </div>

      <div class="topo-note">
        规划内容：与「首页概览」共用 OLT 采集数据与 WebSocket 状态推送，
        节点按在线/离线着色，点击节点直达对应管理面板。
      </div>
    </el-card>
  </div>
</template>

<script>
/**
 * 资源拓扑占位页：一级菜单先上，展示规划形态；
 * 拓扑图数据接入（OLT/PON/ONT 层级树）后续单独实现。
 */
export default {
  name: 'Topology'
}
</script>

<style scoped>
.topo-panel {
  max-width: 860px;
}
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}
.topo-intro {
  color: #606266;
  font-size: 14px;
  line-height: 1.8;
  margin-bottom: 24px;
}
.topo-demo {
  display: flex;
  align-items: stretch;
  justify-content: center;
  padding: 8px 0 16px;
}
.topo-node {
  width: 180px;
  padding: 16px 12px;
  text-align: center;
  color: #303133;
  font-weight: 600;
  background: #f4f8ff;
  border: 1px solid #c6dbff;
  border-radius: 6px;
}
.topo-node i {
  font-size: 28px;
  color: #409eff;
}
.topo-node.root {
  background: #eef4ff;
}
.topo-node.leaf {
  background: #f0fff4;
  border-color: #b7ebc8;
}
.topo-node.leaf i {
  color: #67c23a;
}
.topo-sub {
  margin-top: 6px;
  color: #909399;
  font-size: 12px;
  font-weight: 400;
}
.topo-link {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  width: 70px;
  color: #c0c4cc;
}
.topo-line {
  width: 100%;
  height: 1px;
  background: #dcdfe6;
  margin-bottom: 4px;
}
.topo-arrow {
  font-size: 12px;
}
.topo-note {
  color: #909399;
  font-size: 13px;
  line-height: 1.8;
  padding-top: 12px;
  border-top: 1px dashed #ebeef5;
}
</style>
