<template>
  <div class="hsgq-faceplate">
    <div v-if="!board" class="hsgq-empty">{{ emptyText }}</div>
    <template v-else>
      <!-- 整机面板：按真机正面画，PON 口数与上联 GE/XGE、NMS/CONSOLE 由型号规格决定 -->
      <svg class="hsgq-svg" :viewBox="viewBox" role="img" :aria-label="ariaLabel">
        <defs>
          <linearGradient id="hsgq-body" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stop-color="#e6e6e6" />
            <stop offset="0.55" stop-color="#d3d3d3" />
            <stop offset="1" stop-color="#bcbcbc" />
          </linearGradient>
        </defs>

        <rect x="2" y="2" :width="width - 4" height="126" rx="9" class="fp-body" />

        <!-- 左上 HSGQ 红标，左下型号 -->
        <text class="fp-logo" x="30" y="48">HSGQ</text>
        <text class="fp-model" x="30" y="112">{{ model || 'HSGQ' }}</text>

        <!-- RST 复位孔 -->
        <text class="fp-rst" x="176" y="101">RST</text>
        <circle cx="206" cy="97" r="3.5" class="fp-rst-hole" />

        <!-- PON 组括弧 + PON 口：4 / 8 / 16 口按实际端口数等距排开，点口看该口 ONT -->
        <path :d="bracketPath" class="fp-bracket" />
        <text class="fp-group-label" :x="bracketCenter" y="32">PON</text>
        <g
          v-for="pon in ponPorts"
          :key="'pon-' + pon.no"
          class="fp-port"
          :class="{ 'fp-port-active': isActive(pon) }"
          @click="toggle(pon)"
        >
          <title>{{ ponTitle(pon) }}</title>
          <text class="fp-port-no" :class="{ 'fp-small': compact }" :x="pon.x" y="58">{{ pon.no }}</text>
          <rect :x="pon.x - pon.w / 2" y="63" :width="pon.w" height="30" rx="3" class="fp-sc" />
          <circle :cx="pon.x" cy="71" r="4" class="fp-sc-dot" />
          <circle :cx="pon.x + pon.w / 2 - 5" cy="78" r="3" :class="lampClass(pon)" />
          <text class="fp-count" :class="{ 'fp-small': compact }" :x="pon.x" y="112">
            {{ countText(pon) }}
          </text>
          <rect :x="pon.x - pon.step / 2" y="46" :width="pon.step" height="70" fill="transparent" />
        </g>

        <!-- 上联口：GE（RJ45 电口）+ XGE（SFP+ 光口），几个由型号规格决定；
             一个都没有时连括号也不画，免得留个空框 -->
        <g v-if="uplinkPorts.length" class="fp-uplink">
          <path :d="uplinkBracketPath" class="fp-bracket" />
          <text class="fp-group-label" :x="(uplinkX0 + uplinkLineEnd) / 2" y="32">UPLINK</text>
        </g>
        <g v-for="port in uplinkPorts" :key="port.key">
          <text class="fp-label" :x="port.x" y="60">{{ port.label }}</text>
          <rect v-if="port.kind === 'ge'" :x="port.x - 15" y="64" width="30" height="22" rx="2" class="fp-rj45" />
          <!-- SFP+ 光口：窄槽，和电口一眼能分开 -->
          <rect v-else :x="port.x - 12" y="64" width="24" height="22" rx="2" class="fp-sfp" />
        </g>

        <!-- 管理口：NMS / CONSOLE / USB，有才画 -->
        <rect v-if="mgmtPorts.length" :x="mgmtX0" y="18" :width="mgmtWidth" height="104" rx="4" class="fp-box" />
        <g v-for="port in mgmtPorts" :key="port.key">
          <text class="fp-label" :x="port.x" y="34">{{ port.label }}</text>
          <rect :x="port.x - 17" y="40" width="34" height="22" rx="2" class="fp-rj45" />
        </g>

        <!-- 右侧状态灯列：PWR/SYS 常绿，PON 跟 ONU 在线数走 -->
        <g v-for="led in leds" :key="'led-' + led.label">
          <text class="fp-led-label" :x="ledsX" :y="led.y">{{ led.label }}</text>
          <circle :cx="ledsX + 46" :cy="led.y - 2.5" r="2.5" :class="led.class" />
        </g>
      </svg>

      <!-- 点开的 PON 口：ONT 明细放到面板下方整行显示 -->
      <div v-for="item in openPanels" :key="item.key" class="hsgq-ont-panel">
        <div class="hsgq-ont-title">
          <span>{{ item.port.name }}（{{ item.port.iface }}）下的 ONT</span>
          <span class="hsgq-ont-close" @click="toggle(item.port)">收起</span>
        </div>
        <div class="hsgq-ont-list">
          <div v-for="ont in item.port.onts" :key="ont.id" class="hsgq-ont-row">
            <span class="hsgq-ont-id">#{{ ont.id }}</span>
            <span class="hsgq-ont-sn">{{ ont.sn }}</span>
            <span class="hsgq-ont-desc">{{ ont.desc }}</span>
            <span class="hsgq-ont-state" :class="ont.online ? 'hsgq-online' : 'hsgq-offline'">
              <i class="hsgq-dot" />{{ ont.stateText }}
            </span>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script>
/** 上联口每个占的宽度（含间隔） */
const UPLINK_STEP = 56

/**
 * 鸿升光整机面板（第三方界面的板卡区专用）。
 *
 * 鸿升光是一体机，没有"机框插板卡"的结构，硬套机框视图会很怪（一个槽位围一圈空槽）。
 * 这里按真机正面画一条横向面板：PON1-4 用采集到的真实数据（在线/总数、点口展开 ONT），
 * GE / CONSOLE / RST 是外观件，只画样子不带数据。
 *
 * 端口数据由 mixin 折算好（slots[0]，端口带 onuOnline/onuTotal/onts），交互（展开、悬停）
 * 冒泡给页面，和机框视图同一套状态，页面代码不用区分厂商。
 */
export default {
  name: 'OltHsgqFaceplate',
  props: {
    /** 整机那块"板"（mixin slots 的第一项），端口与 ONU 数据都在上面 */
    board: {
      type: Object,
      default: null
    },
    /** 设备型号，印在面板左下角 */
    model: {
      type: String,
      default: ''
    },
    /** 已展开看 ONT 的端口，key 同 hoverPort（「槽位:端口」） */
    expandedOnts: {
      type: Object,
      default: () => ({})
    },
    hoverPort: {
      type: String,
      default: ''
    },
    emptyText: {
      type: String,
      default: '尚无板卡数据，点上面的「同步」采集。'
    }
  },
  computed: {
    /** 整机规格：几个 PON 口、几个 GE / XGE、有没有 NMS / CONSOLE，由后端按型号给 */
    frontPanel() {
      return (this.board && this.board.frontPanel) || null
    },
    /** PON 区在面板上的横向范围（左边是 HSGQ 标与 RST，右边是上联口） */
    ponAreaStart() {
      return 240
    },
    /** PON 区宽度跟着口数走：4 口 4×46，16 口就宽一倍 */
    ponAreaWidth() {
      const count = (this.board.ports || []).length || 4
      return Math.max(4, count) * 46
    },
    /** 上联口起点：PON 区右边留一段 */
    uplinkX0() {
      return this.ponAreaStart + this.ponAreaWidth + 30
    },
    uplinkLineEnd() {
      return this.uplinkX0 + Math.max(1, this.uplinkPorts.length) * UPLINK_STEP - 12
    },
    uplinkBracketPath() {
      return `M${this.uplinkX0} 44 v-6 h${this.uplinkLineEnd - this.uplinkX0} v6`
    },
    /** GE 在前、XGE 在后，和真机上印的顺序一致 */
    uplinkPorts() {
      const panel = this.frontPanel
      if (!panel) return []
      const list = []
      for (let i = 0; i < (panel.geCount || 0); i++) {
        list.push({ key: 'ge-' + i, kind: 'ge', label: 'GE' + (i + 1), x: this.uplinkX0 + UPLINK_STEP * (i + 0.5) })
      }
      for (let i = 0; i < (panel.xgeCount || 0); i++) {
        list.push({
          key: 'xge-' + i,
          kind: 'xge',
          label: 'XGE' + (i + 1),
          x: this.uplinkX0 + UPLINK_STEP * (panel.geCount + i + 0.5)
        })
      }
      return list
    },
    mgmtX0() {
      return this.uplinkLineEnd + 18
    },
    /** 管理口：NMS / CONSOLE / USB（真机上这三样常并排画在一个框里） */
    mgmtPorts() {
      const panel = this.frontPanel
      if (!panel) return []
      const keys = []
      if (panel.hasNms) keys.push({ key: 'nms', label: 'NMS' })
      if (panel.hasConsole) keys.push({ key: 'console', label: 'CONSOLE' })
      if (panel.hasUsb) keys.push({ key: 'usb', label: 'USB' })
      return keys.map((item, index) => ({ ...item, x: this.mgmtX0 + 6 + index * 56 + 22 }))
    },
    mgmtWidth() {
      return Math.max(0, this.mgmtPorts.length * 56 + 12)
    },
    ledsX() {
      return (this.mgmtPorts.length ? this.mgmtX0 + this.mgmtWidth : this.uplinkLineEnd + 16) + 14
    },
    /** 面板总宽：内容到哪画到哪，右侧给状态灯留 70 */
    width() {
      return Math.max(760, this.ledsX + 74)
    },
    viewBox() {
      return '0 0 ' + this.width + ' 130'
    },
    ariaLabel() {
      return this.model + (this.frontPanel ? ' 整机前面板：' + this.frontPanel.summary : ' 整机前面板')
    },
    /** 每个 PON 口占的宽度：4 口 95、8 口 47.5、16 口 23.75 */
    ponStep() {
      const count = (this.board.ports || []).length || 4
      return this.ponAreaWidth / count
    },
    /** 口子多了字要缩小，16 口时 9.5px 会糊成一片 */
    compact() {
      return this.ponStep < 32
    },
    bracketPath() {
      return `M${this.ponAreaStart} 44 v-6 h${this.ponAreaWidth} v6`
    },
    bracketCenter() {
      return this.ponAreaStart + this.ponAreaWidth / 2
    },
    /** PON 口摆位：按实际端口数（4/8/16）等距排开，口宽随间距收窄 */
    ponPorts() {
      const step = this.ponStep
      const width = Math.max(12, Math.min(34, step - 10))
      return (this.board.ports || []).map((port, index) => ({
        ...port,
        x: this.ponAreaStart + step * (index + 0.5),
        step,
        w: width
      }))
    },
    /** 右侧状态灯：PWR/SYS 常绿，PON 各口看 ONU 在线，ETH 常灰；口太多时只留 PWR/SYS/ETH 免得挤出面板 */
    leds() {
      const rows = [
        { label: 'PWR', class: 'fp-led-on' },
        { label: 'SYS', class: 'fp-led-on' }
      ]
      const ports = this.board.ports || []
      if (ports.length <= 6) {
        ports.forEach(pon => {
          rows.push({ label: 'PON' + pon.no, class: this.lampClass(pon) })
        })
      }
      rows.push({ label: 'ETH', class: 'fp-led-idle' })
      const gap = rows.length > 8 ? 9 : 12.5
      return rows.map((row, index) => ({ ...row, y: 32 + index * gap }))
    },
    /** 已展开的 PON 口（带着端口引用，下面直接渲染 ONT 明细） */
    openPanels() {
      const list = []
      ;(this.board.ports || []).forEach(port => {
        if (port.onts.length && this.expandedOnts[this.board.no + ':' + port.no]) {
          list.push({ key: String(port.no), port })
        }
      })
      return list
    }
  },
  methods: {
    isActive(pon) {
      return !!this.hoverPort && this.hoverPort === this.board.no + ':' + pon.no
    },
    countText(pon) {
      return pon.onuTotal ? pon.onuOnline + '/' + pon.onuTotal : '-'
    },
    ponTitle(pon) {
      const head = pon.name + (pon.iface ? '（' + pon.iface + '）' : '')
      return head + '：' + (pon.onuText || '未采集到 ONU 数据')
    },
    /** 口灯：有 ONU 在线绿灯，配了 ONU 全离线红灯，空口灰 */
    lampClass(pon) {
      if (pon.onuOnline > 0) return 'fp-led-on'
      return pon.onuTotal > 0 ? 'fp-led-alarm' : 'fp-led-idle'
    },
    toggle(pon) {
      this.$emit('toggle', { slotNo: this.board.no, portNo: pon.no, count: 1 })
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';

.hsgq-faceplate {
  margin-top: 4px;
}
.hsgq-svg {
  display: block;
  width: 100%;
  max-width: 860px;
}
.hsgq-empty {
  padding: 24px 0;
  color: #5f6a78;
  font-size: 12px;
}

/* 面板本体与丝印 */
.fp-body {
  fill: url(#hsgq-body);
  stroke: #878d93;
  stroke-width: 1.5;
}
.fp-logo {
  fill: #d0021b;
  font-size: 30px;
  font-weight: 800;
  letter-spacing: 2px;
}
.fp-model {
  fill: #4a4a4a;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 1px;
}
.fp-rst {
  fill: #5a5a5a;
  font-size: 10px;
}
.fp-rst-hole {
  fill: #3a3a3a;
}
.fp-bracket {
  fill: none;
  stroke: #6b6b6b;
  stroke-width: 1;
}
.fp-group-label {
  fill: #4a4a4a;
  font-size: 11px;
  text-anchor: middle;
}
.fp-port {
  cursor: pointer;
}
.fp-port-active .fp-sc {
  stroke: #3f6f9f;
  stroke-width: 2;
}
.fp-port-no,
.fp-label {
  fill: #4a4a4a;
  font-size: 9.5px;
  text-anchor: middle;
}
/* 8/16 口机型：口间距小，字号跟着收一档 */
.fp-small {
  font-size: 8px;
}
.fp-sc {
  fill: #17191c;
  stroke: #6f6f6f;
  stroke-width: 1;
}
.fp-sc-dot {
  fill: #8a8a8a;
}
.fp-count {
  fill: #5a5a5a;
  font-size: 10px;
  font-weight: 600;
  text-anchor: middle;
}
.fp-box {
  fill: #ececec;
  stroke: #a9a9a9;
}
.fp-rj45 {
  fill: #17191c;
  stroke: #6f6f6f;
}
/* SFP+ 光口：比电口窄，中间一道亮槽，和 GE 一眼能分开 */
.fp-sfp {
  fill: #17191c;
  stroke: #8a8a8a;
}
.fp-led-label {
  fill: #5a5a5a;
  font-size: 7px;
}
.fp-led-on {
  fill: #35c24f;
}
.fp-led-alarm {
  fill: #d9534f;
}
.fp-led-idle {
  fill: #9aa0a6;
}

/* 展开的 ONT 明细：和机框视图下方的明细同一套排版 */
.hsgq-ont-panel {
  margin-top: 8px;
  border: 1px solid #2c333d;
  border-radius: 4px;
  background: #14181e;
}
.hsgq-ont-title {
  display: flex;
  justify-content: space-between;
  padding: 6px 10px;
  border-bottom: 1px solid #2c333d;
  color: #dfe6f0;
  font-size: 12px;
}
.hsgq-ont-close {
  color: #4a9eff;
  cursor: pointer;
}
.hsgq-ont-list {
  padding: 4px 10px 8px;
}
.hsgq-ont-row {
  display: flex;
  gap: 12px;
  padding: 3px 0;
  color: #aeb7c4;
  font-size: 12px;
}
.hsgq-ont-id {
  min-width: 34px;
  color: #7f8a99;
}
.hsgq-ont-sn {
  min-width: 150px;
  font-family: monospace;
}
.hsgq-ont-state {
  margin-left: auto;
}
.hsgq-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 4px;
  border-radius: 50%;
}
.hsgq-online {
  color: #58c26a;
  .hsgq-dot {
    background: #35c24f;
  }
}
.hsgq-offline {
  color: #8a93a0;
  .hsgq-dot {
    background: #4a525c;
  }
}
</style>
