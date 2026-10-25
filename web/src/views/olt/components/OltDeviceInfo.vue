<template>
  <div class="detail-list">
    <div class="detail-row"><span class="k">OLT 名称</span><span class="v">{{ device.name || '-' }}</span></div>
    <div class="detail-row"><span class="k">OLT 地址</span><span class="v">{{ device.host }}</span></div>
    <div class="detail-row"><span class="k">登录方式</span><span class="v">{{ device.protocol }} / {{ device.port }}</span></div>
    <div class="detail-row"><span class="k">登录账号</span><span class="v">{{ device.username || 'root（默认）' }}</span></div>
    <div class="detail-row"><span class="k">字符集编码</span><span class="v">{{ device.charset || 'AUTO' }}</span></div>
    <div class="detail-row"><span class="k">绑定出口 IP</span><span class="v">{{ device.bindLocalIp || '未绑定' }}</span></div>
    <div class="detail-row"><span class="k">Web 登录账号</span><span class="v">{{ webAccountText }}</span></div>
    <div class="detail-row"><span class="k">厂商 / 型号</span><span class="v">{{ device.vendorLabel || '未识别' }} / {{ device.model || '-' }}</span></div>
    <div class="detail-row"><span class="k">软件版本</span><span class="v">{{ device.softwareVersion || '-' }}</span></div>
    <div class="detail-row"><span class="k">补丁版本</span><span class="v">{{ device.patchVersion || '-' }}</span></div>
    <div class="detail-row"><span class="k">背板型号</span><span class="v">{{ device.backplane || '-' }}</span></div>
    <div class="detail-row"><span class="k">主控 MAC</span><span class="v">{{ device.macAddress || '-' }}</span></div>
    <div class="detail-row"><span class="k">运行时长</span><span class="v">{{ device.uptime || '-' }}</span></div>
    <div class="detail-row"><span class="k">固件版本</span><span class="v">{{ firmwareText }}</span></div>
    <div class="detail-row"><span class="k">板卡配置</span><span class="v">{{ boardSummary || '-' }}</span></div>
    <div class="detail-row"><span class="k">最近连接</span><span class="v">{{ device.lastConnectAt || '-' }}</span></div>
    <div class="detail-row"><span class="k">最近同步</span><span class="v">{{ device.lastSyncAt || '-' }}</span></div>
    <div class="detail-row"><span class="k">同步摘要</span><span class="v">{{ device.syncSummary || '-' }}</span></div>
    <div class="detail-row"><span class="k">备注</span><span class="v">{{ device.remark || '-' }}</span></div>
  </div>
</template>

<script>
/**
 * 配置信息：台账里的一切，加上登录后回填的厂商、型号、版本。
 *
 * 各厂商页都放这一个，省得改一处要同步三个地方。
 */
export default {
  name: 'OltDeviceInfo',
  props: {
    device: {
      type: Object,
      default: () => ({})
    },
    firmwareText: {
      type: String,
      default: '-'
    },
    boardSummary: {
      type: String,
      default: ''
    },
    webAccountText: {
      type: String,
      default: '不限制'
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';
</style>
