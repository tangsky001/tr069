<template>
  <span class="help-label">
    {{ text }}
    <el-tooltip
      v-if="tip"
      placement="right"
      effect="dark"
      :open-delay="80"
      popper-class="olt-help-popper"
    >
      <div slot="content" class="olt-help-content">
        <div class="olt-help-title">{{ text }}</div>
        <div class="olt-help-body">{{ tip }}</div>
      </div>
      <i class="el-icon-question help-icon" />
    </el-tooltip>
  </span>
</template>

<script>
/** 带问号气泡的表单项标题，占用 el-form-item 的 label 插槽：文字 + 蓝色问号，悬停出提示 */
export default {
  name: 'OltHelpLabel',
  props: {
    text: {
      type: String,
      required: true
    },
    tip: {
      type: String,
      default: ''
    }
  }
}
</script>

<style lang="scss" scoped>
.help-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.help-icon {
  color: #409eff;
  font-size: 14px;
  cursor: pointer;

  &:hover {
    color: #66b1ff;
  }
}
</style>

<style lang="scss">
/* 气泡挂在 body 上，scoped 管不到，用 popper-class 定位 */
.olt-help-popper {
  max-width: 320px;

  .olt-help-content {
    max-width: 300px;
  }

  .olt-help-title {
    margin-bottom: 4px;
    font-size: 13px;
    font-weight: 600;
  }

  .olt-help-body {
    font-size: 12px;
    line-height: 1.6;
    white-space: normal;
    word-break: break-word;
  }
}
</style>
