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
/**
 * 带说明气泡的表单项标题：文字 + 蓝色问号，鼠标移上去出提示。
 *
 * <p>用在开局表单这类「填错代价大、但字段名说不清」的地方。用法是占住
 * {@code el-form-item} 的 label 插槽：</p>
 *
 * <pre>
 * &lt;el-form-item&gt;
 *   &lt;olt-help-label slot="label" text="管理 VLAN" tip="..." /&gt;
 *   &lt;el-input v-model="form.manageVlan" /&gt;
 * &lt;/el-form-item&gt;
 * </pre>
 */
export default {
  name: 'OltHelpLabel',
  props: {
    /** 字段名，同时作为气泡的标题 */
    text: {
      type: String,
      required: true
    },
    /** 说明正文；为空就不显示问号 */
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
/* 气泡挂在 body 上，scoped 管不到，用 popper-class 框住 */
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
