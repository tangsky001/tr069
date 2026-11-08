<template>
  <div class="app-container">
    <el-card shadow="never">
      <div slot="header" class="card-header">
        <span>在线更新</span>
      </div>
      <div class="placeholder">
        <i class="el-icon-upload" />
        <p>在线更新功能开发中，敬请期待</p>
      </div>
    </el-card>
  </div>
</template>

<script>
export default {
  name: 'SystemUpgrade'
}
</script>

<style scoped>
.card-header {
  font-weight: 600;
}
.placeholder {
  padding: 60px 0;
  text-align: center;
  color: #909399;
}
.placeholder i {
  font-size: 48px;
}
.placeholder p {
  margin-top: 12px;
  font-size: 14px;
}
</style>
