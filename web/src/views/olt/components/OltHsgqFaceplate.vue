<template>
  <div class="hsgq-faceplate">
    <div v-if="!board" class="hsgq-empty">{{ emptyText }}</div>
    <template v-else>
      <!-- 整机面板：按真机正面画（HSGQ 红标 / PON1-4 / GE1-4 / CONSOLE+ETH），不用机框插槽那套 -->
      <svg class="hsgq-svg" viewBox="0 0 760 130" role="img" :aria-label="model">
        <defs>
          <linearGradient id="hsgq-body" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stop-color="#e6e6e6" />
            <stop offset="0.55" stop-color="#d3d3d3" />
            <stop offset="1" stop-color="#bcbcbc" />
          </linearGradient>
        </defs>

        <rect x="2" y="2" width="756" height="126" rx="9" class="fp-body" />

        <!-- 左上 HSGQ 红标，左下型号 -->
        <text class="fp-logo" x="30" y="48">HSGQ</text>
        <text class="fp-model" x="30" y="112">{{ model || 'HSGQ' }}</text>

        <!-- RST 复位孔 -->
        <text class="fp-rst" x="176" y="101">RST</text>
        <circle cx="206" cy="97" r="3.5" class="fp-rst-hole" />

        <!-- PON 组括弧 + 4 个 SC 口，点口看该口 ONT -->
        <path d="M245 44 v-6 h184 v6" class="fp-bracket" />
        <text class="fp-group-label" x="337" y="32">PON</text>
        <g
          v-for="pon in ponPorts"
          :key="'pon-' + pon.no"
          class="fp-port"
          :class="{ 'fp-port-active': isActive(pon) }"
          @click="toggle(pon)"
        >
          <title>{{ ponTitle(pon) }}</title>
          <text class="fp-port-no" :x="pon.x" y="58">{{ pon.no }}</text>
          <rect :x="pon.x - 17" y="63" width="34" height="30" rx="3" class="fp-sc" />
          <circle :cx="pon.x" cy="71" r="4" class="fp-sc-dot" />
          <circle :cx="pon.x + 24" cy="78" r="3" :class="lampClass(pon)" />
          <text class="fp-count" :x="pon.x" y="112">{{ countText(pon) }}</text>
          <rect :x="pon.x - 25" y="46" width="50" height="70" fill="transparent" />
        </g>

        <!-- GE1/GE2 上下叠一排，GE3、GE4 各一个 -->
        <rect x="444" y="40" width="54" height="84" rx="4" class="fp-box" />
        <text class="fp-label" x="471" y="51">GE1</text>
        <rect x="456" y="55" width="30" height="20" rx="2" class="fp-rj45" />
        <text class="fp-label" x="471" y="100">GE2</text>
        <rect x="456" y="104" width="30" height="20" rx="2" class="fp-rj45" />
        <text class="fp-label" x="527" y="60">GE3</text>
        <rect x="512" y="64" width="30" height="22" rx="2" class="fp-rj45" />
        <text class="fp-label" x="582" y="60">GE4</text>
        <rect x="567" y="64" width="30" height="22" rx="2" class="fp-rj45" />

        <!-- CONSOLE + ETH -->
        <rect x="625" y="18" width="58" height="104" rx="4" class="fp-box" />
        <text class="fp-label" x="654" y="30">CONSOLE</text>
        <rect x="637" y="34" width="34" height="22" rx="2" class="fp-rj45" />
        <text class="fp-label" x="654" y="86">ETH</text>
        <rect x="637" y="90" width="34" height="22" rx="2" class="fp-rj45" />

        <!-- 右侧状态灯列：PWR/SYS 常绿，PON 跟 ONU 在线数走 -->
        <g v-for="led in leds" :key="'led-' + led.label">
          <text class="fp-led-label" x="696" :y="led.y">{{ led.label }}</text>
          <circle cx="742" :cy="led.y - 2.5" r="2.5" :class="led.class" />
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
    /** PON 口摆位：4 个口等距排开，超出的口忽略（E04 固定 4 口） */
    ponPorts() {
      return (this.board.ports || []).slice(0, 4).map((port, index) => ({
        ...port,
        x: 262 + index * 50
      }))
    },
    /** 右侧状态灯：PWR/SYS 常绿，PON 各口看 ONU 在线，ETH 常灰 */
    leds() {
      const rows = [
        { label: 'PWR', class: 'fp-led-on' },
        { label: 'SYS', class: 'fp-led-on' }
      ]
      for (let no = 1; no <= 4; no++) {
        const pon = this.ponPorts.find(port => port.no === no)
        rows.push({ label: 'PON' + no, class: this.lampClass(pon || { onuTotal: 0, onuOnline: 0 }) })
      }
      rows.push({ label: 'ETH', class: 'fp-led-idle' })
      return rows.map((row, index) => ({ ...row, y: 32 + index * 12.5 }))
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
