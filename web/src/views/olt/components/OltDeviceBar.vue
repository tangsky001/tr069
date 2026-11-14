<template>
  <el-card v-if="device.id" shadow="never" class="olt-top-card">
    <div slot="header" class="olt-top-head">
      <span class="olt-title">{{ device.name || device.host }}</span>
      <div>
        <el-button size="mini" icon="el-icon-back" @click="$emit('back')">返回列表</el-button>
        <el-button size="mini" icon="el-icon-edit" @click="$emit('edit')">编辑</el-button>
        <el-button size="mini" type="primary" icon="el-icon-refresh-right" :loading="syncing" @click="$emit('sync')">同步</el-button>
        <el-button size="mini" type="warning" icon="el-icon-cpu" :loading="testing" @click="$emit('test')">诊断测试</el-button>
        <el-button size="mini" type="danger" icon="el-icon-delete" @click="$emit('delete')">删除</el-button>
        <span class="olt-auto">
          <el-switch :value="autoRefresh" @input="$emit('update:autoRefresh', $event)" />
          <span class="olt-auto-label">自动刷新</span>
          <el-select
            :value="pollSeconds"
            size="small"
            style="width:86px;margin-left:4px"
            :disabled="!autoRefresh"
            @input="$emit('update:pollSeconds', $event)"
          >
            <el-option :value="5" label="5 秒" />
            <el-option :value="10" label="10 秒" />
            <el-option :value="30" label="30 秒" />
            <el-option :value="60" label="60 秒" />
          </el-select>
        </span>
      </div>
    </div>

    <div class="olt-meta-row">
      <span>地址：<b>{{ device.host || '-' }}</b></span>
      <span>厂商：{{ device.vendorLabel || '未识别' }}</span>
      <span>型号：<b>{{ device.model || '-' }}</b></span>
      <span>背板型号：{{ device.backplane || '-' }}</span>
      <span>主控 MAC：{{ device.macAddress || '-' }}</span>
      <span>固件版本：{{ firmwareText }}</span>
      <span>运行时长：{{ device.uptime || '-' }}</span>
      <span>
        状态：
        <el-tag :type="statusTag" size="mini">{{ statusText }}</el-tag>
      </span>
      <span>
        最近同步：{{ device.lastSyncAt || '未同步' }}
        <span class="olt-auto-tip">（启动即采一次，之后每 5 分钟自动采集）</span>
      </span>
    </div>

    <div v-if="device.lastError" class="olt-error">最近一次失败：{{ device.lastError }}</div>
  </el-card>
</template>

<script>
/**
 * 详情页顶部设备条：设备概况 + 编辑 / 同步 / 诊断 / 删除 / 自动刷新。
 *
 * 三个厂商页的头部完全一样，抽出来后各页只关心自己的标签页。
 * 操作一律冒泡给页面处理——弹窗确认、跑接口都由页面与 mixin 负责。
 */
export default {
  name: 'OltDeviceBar',
  props: {
    device: {
      type: Object,
      default: () => ({})
    },
    statusText: {
      type: String,
      default: '未探测'
    },
    statusTag: {
      type: String,
      default: 'info'
    },
    firmwareText: {
      type: String,
      default: '-'
    },
    syncing: {
      type: Boolean,
      default: false
    },
    testing: {
      type: Boolean,
      default: false
    },
    autoRefresh: {
      type: Boolean,
      default: true
    },
    pollSeconds: {
      type: Number,
      default: 10
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';
</style>
