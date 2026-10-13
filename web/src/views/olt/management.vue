<template>
  <div class="app-container">
    <!-- 顶部：OLT 资源信息（当前全部为模拟数据） -->
    <el-card shadow="never" class="olt-top-card">
      <div slot="header" class="olt-top-head">
        <span class="olt-title">OLT/ONU 管理</span>
        <el-button type="success" size="mini" icon="el-icon-refresh" @click="todoPending('全部刷新')">全部刷新</el-button>
      </div>

      <div class="olt-res-row">
        <span class="olt-label">OLT 资源信息：</span>
        <el-select v-model="oltName" size="small" style="width:260px" @change="onResourceChange">
          <el-option v-for="r in resources" :key="r.id" :label="r.name" :value="r.name" />
        </el-select>
        <el-input v-model="olt.ip" size="small" style="width:170px" class="olt-res-ip" />
        <el-button type="primary" size="small" icon="el-icon-refresh-right" @click="todoPending('同步')">同步</el-button>
        <el-button type="primary" size="small" icon="el-icon-check" @click="todoPending('保存')">保存</el-button>
        <el-button type="warning" size="small" icon="el-icon-cpu" @click="todoPending('诊断测试')">诊断测试</el-button>
        <el-button type="success" size="small" icon="el-icon-setting" @click="todoPending('手动配置')">手动配置</el-button>
      </div>

      <div class="olt-meta-row">
        <span>型号：<b>{{ olt.model }}</b></span>
        <span>OLT 软件版本：{{ olt.software }}</span>
        <span>认证SNMP：({{ olt.snmp }})</span>
        <span>发现时间：{{ olt.discoveredAt }}
          <el-button type="text" size="mini" icon="el-icon-edit" @click="todoPending('编辑资源')" />
        </span>
      </div>
    </el-card>

    <!-- 主体：选项卡 -->
    <el-card shadow="never" class="olt-main-card">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="OLT 规格" name="spec">
          <!-- 板卡信息表 -->
          <div class="olt-section-title">板卡信息</div>
          <el-table :data="olt.boards" border size="small" class="board-table">
            <el-table-column label="槽位" prop="no" width="70" align="center" />
            <el-table-column label="板卡类型" prop="type" min-width="130" />
            <el-table-column label="端口类型" prop="portMode" width="100" align="center" />
            <el-table-column label="端口数" prop="portCount" width="80" align="center" />
            <el-table-column label="在线状态" width="100" align="center">
              <template slot-scope="{ row }">
                <el-tag :type="row.status === '正常' ? 'success' : 'danger'" size="mini">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="温度" width="80" align="center">
              <template slot-scope="{ row }">{{ row.temp }}°C</template>
            </el-table-column>
            <el-table-column label="CPU 占用" prop="cpu" width="90" align="center" />
            <el-table-column label="内存占用" prop="mem" width="90" align="center" />
            <el-table-column label="备注" prop="remark" min-width="140" show-overflow-tooltip />
          </el-table>

          <!-- 连接概要（模拟） -->
          <div class="olt-summary">
            <div>OLT 连接池子上：<b>{{ olt.model }}</b>（{{ olt.chassis }}）；ONU 在线总数 <b class="olt-num">{{ olt.onuOnline }}</b> {{ olt.onuOnlineTime }}</div>
            <div>机架屏自动同步：{{ olt.lastSync }}（同步最近OLT：{{ olt.ip }}）</div>
            <div>{{ olt.syncSummary }}</div>
          </div>

          <!-- 槽位面板 -->
          <div class="slot-grid">
            <div v-for="slot in olt.slots" :key="slot.no" class="slot-card">
              <div class="slot-head">
                <div class="slot-row"><span class="k">槽位编号</span><b>{{ slot.no }}</b></div>
                <div class="slot-row"><span class="k">板卡类型</span><b>{{ slot.type }}</b></div>
                <div class="slot-row">
                  <span class="k">端口</span>
                  <el-radio-group v-model="slot.portMode" size="mini" @change="todoPending('端口类型切换')">
                    <el-radio label="EPON">EPON</el-radio>
                    <el-radio label="GPON">GPON</el-radio>
                  </el-radio-group>
                </div>
                <div class="slot-row"><span class="k">MAC类型</span><span>{{ slot.macType }}</span></div>
                <div class="slot-row"><span class="k">温度</span><b>{{ slot.temp }}°C</b></div>
                <div class="slot-row">
                  <span class="k">状态</span>
                  <el-tag :type="slot.status === '正常' ? 'success' : 'danger'" size="mini">{{ slot.status }}</el-tag>
                </div>
              </div>

              <!-- 端口面板：模拟机架图，鼠标悬停查看端口详情 -->
              <div class="panel-wrap">
                <div class="rack-panel">
                  <div
                    v-for="port in slot.ports"
                    :key="port.no"
                    class="port-block"
                    :class="port.online ? 'is-online' : 'is-offline'"
                    @click="todoPending('端口 ' + port.name + ' 详情')"
                  >
                    <span class="port-led" />
                    <span class="port-no">{{ port.name }}</span>
                  </div>
                </div>
                <div class="port-list">
                  <div v-for="port in slot.ports" :key="'txt' + port.no" class="port-info">
                    <span class="port-info-name" :class="port.online ? 'on' : 'off'">
                      <i class="port-dot" :class="port.online ? 'on' : 'off'" />{{ port.name }}
                    </span>
                    <span class="port-info-line">{{ port.online ? ('Tx: ' + port.tx + ' / Rx: ' + port.rx) : '未注册 ONU' }}</span>
                    <span v-if="port.online" class="port-info-line">{{ port.mac }} · {{ port.vlan }} · ONU {{ port.onus }}</span>
                    <span v-else class="port-info-line">端口未启用</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="业务板块" name="service">
          <div class="olt-placeholder">
            <p>业务板块（模拟占位）：上联口规划、VLAN 规划、业务通道配置。</p>
            <el-table :data="olt.services" border size="small" style="max-width:720px">
              <el-table-column label="业务板块" prop="name" min-width="140" />
              <el-table-column label="VLAN" prop="vlan" width="100" align="center" />
              <el-table-column label="上联口" prop="uplink" min-width="120" />
              <el-table-column label="状态" width="90" align="center">
                <template slot-scope="{ row }">
                  <el-tag :type="row.enabled ? 'success' : 'info'" size="mini">{{ row.enabled ? '启用' : '停用' }}</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-tab-pane>

        <el-tab-pane label="OLA" name="ola">
          <div class="olt-placeholder">OLA（光链路聚合，模拟占位）：待接入真实 OLT 采集数据后展示聚合组与成员口。</div>
        </el-tab-pane>

        <el-tab-pane label="ONU 后板编辑面板" name="onu">
          <div class="olt-placeholder">ONU 后板编辑面板（模拟占位）：用于按槽位/端口批量编辑 ONU 模板与授权，待后端接入。</div>
        </el-tab-pane>

        <el-tab-pane label="OLT 主板信息" name="board">
          <div class="olt-placeholder">
            <p>OLT 主板信息（模拟占位）</p>
            <div class="detail-list">
              <div class="detail-row"><span class="k">机框型号</span><span class="v">{{ olt.chassis }}</span></div>
              <div class="detail-row"><span class="k">槽位总数</span><span class="v">{{ olt.slotCount }} 槽</span></div>
              <div class="detail-row"><span class="k">主控板</span><span class="v">{{ olt.masterBoard }}</span></div>
              <div class="detail-row"><span class="k">电源模块</span><span class="v">{{ olt.power }}</span></div>
              <div class="detail-row"><span class="k">运行时长</span><span class="v">{{ olt.uptime }}</span></div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="OLT 升级" name="upgrade">
          <div class="olt-placeholder">OLT 升级（模拟占位）：固件版本管理与批量升级任务，待后端接入。</div>
        </el-tab-pane>

        <el-tab-pane label="主备" name="standby">
          <div class="olt-placeholder">
            <p>主备状态（模拟占位）</p>
            <div class="detail-list">
              <div class="detail-row"><span class="k">主用主控</span><span class="v">槽位 10 / SCUN</span></div>
              <div class="detail-row"><span class="k">备用主控</span><span class="v">槽位 11 / SCUN（热备）</span></div>
              <div class="detail-row"><span class="k">倒换状态</span><span class="v">正常</span></div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="保护" name="protect">
          <div class="olt-placeholder">保护（模拟占位）：Type B/C 保护组、环路检测等配置，待后端接入。</div>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script>
/**
 * OLT/ONU 管理页 —— 当前整页使用前端模拟数据。
 * 后续接入真实 OLT（SNMP/Telnet）采集后，将 olt / resources 替换为接口返回即可，
 * 页面结构不需要改动。
 */
export default {
  name: 'OltManagement',
  data() {
    return {
      activeTab: 'spec',
      todoMsg: '',
      // OLT 资源列表（模拟）
      resources: [
        { id: 1, name: '淮安SAS2108-核心机房03A', ip: '10.10.19.44' },
        { id: 2, name: '城区汇聚OLT-01', ip: '10.10.19.45' },
        { id: 3, name: '乡镇接入OLT-02', ip: '10.10.19.46' }
      ],
      oltName: '淮安SAS2108-核心机房03A',
      // OLT 概要与槽位数据（模拟）
      olt: {
        ip: '10.10.19.44',
        model: 'KOTMM26000B1(BIGSO)',
        chassis: 'EPEC C9',
        software: 'RP0213R0100-R01(3.3.22-160407.02)',
        snmp: 'SNMP v2c',
        discoveredAt: '2026-08-14 10:52:22',
        onuOnline: 4072,
        onuOnlineTime: '2026/10/22 07:28:38',
        lastSync: '2026-08-14 10:52:22',
        syncSummary: '已同步 4 槽位 / 4 光模块 / 0 交换机 / 4 分光器 / 12 PON口 / 0 上联口',
        slotCount: 14,
        masterBoard: 'SCUN x2（槽位 10/11，主备）',
        power: 'PRAM x2（双路输入）',
        uptime: '68 天 4 小时',
        boards: [
          { no: 1, type: 'HSC7Q100', portMode: 'EPON', portCount: 8, status: '正常', temp: 48, cpu: '12%', mem: '35%', remark: '8 口 EPON 业务板' },
          { no: 4, type: 'HSC2G8A', portMode: 'EPON', portCount: 4, status: '正常', temp: 50, cpu: '9%', mem: '30%', remark: '4 口 EPON + 2 GE 上联' },
          { no: 9, type: 'HSC7ZCS', portMode: 'GPON', portCount: 2, status: '正常', temp: 37, cpu: '6%', mem: '28%', remark: '2 口 GPON 业务板' },
          { no: 10, type: 'SCUN', portMode: '主控', portCount: 0, status: '正常', temp: 42, cpu: '15%', mem: '41%', remark: '主用主控交换板' },
          { no: 11, type: 'SCUN', portMode: '主控', portCount: 0, status: '正常', temp: 43, cpu: '8%', mem: '38%', remark: '备用主控交换板' }
        ],
        services: [
          { name: '上网业务（INTERNET）', vlan: 921, uplink: '上联口 GE 1/1', enabled: true },
          { name: '语音业务（VOIP）', vlan: 925, uplink: '上联口 GE 1/2', enabled: true },
          { name: 'IPTV 业务', vlan: 930, uplink: '上联口 GE 1/2', enabled: false }
        ],
        slots: [
          {
            no: 1, type: 'HSC7Q100', portMode: 'EPON',
            macType: 'RCC509（learn mode: HSC7Q256/1024）', temp: 48, status: '正常',
            ports: [
              { no: 1, name: 'PON 1/1', online: true, tx: '3.10 dBm', rx: '-34.72 dBm', mac: 'MAC Pass', vlan: 'VLAN 921', onus: 63 },
              { no: 2, name: 'PON 1/2', online: true, tx: '2.98 dBm', rx: '-30.88 dBm', mac: 'MAC Pass', vlan: 'VLAN 921', onus: 58 },
              { no: 3, name: 'PON 1/3', online: true, tx: '3.05 dBm', rx: '-27.41 dBm', mac: 'MAC Pass', vlan: 'VLAN 921', onus: 61 },
              { no: 4, name: 'PON 1/4', online: true, tx: '2.88 dBm', rx: '-31.02 dBm', mac: 'MAC Pass', vlan: 'VLAN 925', onus: 47 },
              { no: 5, name: 'PON 1/5', online: true, tx: '3.12 dBm', rx: '-29.55 dBm', mac: 'MAC Pass', vlan: 'VLAN 921', onus: 55 },
              { no: 6, name: 'PON 1/6', online: false, tx: '', rx: '', mac: '', vlan: '', onus: 0 },
              { no: 7, name: 'PON 1/7', online: true, tx: '2.95 dBm', rx: '-33.19 dBm', mac: 'MAC Pass', vlan: 'VLAN 921', onus: 52 },
              { no: 8, name: 'PON 1/8', online: true, tx: '3.02 dBm', rx: '-28.90 dBm', mac: 'MAC Pass', vlan: 'VLAN 925', onus: 49 }
            ]
          },
          {
            no: 4, type: 'HSC2G8A', portMode: 'EPON',
            macType: 'HSC7Q100（learn mode: HSC7Q256/1024）', temp: 50, status: '正常',
            ports: [
              { no: 1, name: 'PON 4/1', online: true, tx: '2.76 dBm', rx: '-30.11 dBm', mac: 'MAC Pass', vlan: 'VLAN 921', onus: 44 },
              { no: 2, name: 'PON 4/2', online: true, tx: '2.81 dBm', rx: '-32.67 dBm', mac: 'MAC Pass', vlan: 'VLAN 921', onus: 39 },
              { no: 3, name: 'PON 4/3', online: false, tx: '', rx: '', mac: '', vlan: '', onus: 0 },
              { no: 4, name: 'PON 4/4', online: false, tx: '', rx: '', mac: '', vlan: '', onus: 0 }
            ]
          },
          {
            no: 9, type: 'HSC7ZCS', portMode: 'GPON',
            macType: 'HSC7ZCS（learn mode: HSC7Q256/1024）', temp: 37, status: '正常',
            ports: [
              { no: 1, name: 'GPON 9/1', online: true, tx: '4.99 dBm', rx: '-25.36 dBm', mac: 'MAC Pass', vlan: 'VLAN 921', onus: 71 },
              { no: 2, name: 'GPON 9/2', online: true, tx: '4.85 dBm', rx: '-26.74 dBm', mac: 'MAC Pass', vlan: 'VLAN 925', onus: 66 }
            ]
          }
        ]
      }
    }
  },
  methods: {
    // 切换 OLT 资源：模拟场景下只同步 IP 显示
    onResourceChange(name) {
      const found = this.resources.find(r => r.name === name)
      if (found) this.olt.ip = found.ip
    },
    todoPending(text) {
      this.$message.info(text + '：当前为模拟数据，后端 OLT 采集接口接入后启用')
    }
  }
}
</script>

<style scoped>
.olt-top-card {
  margin-bottom: 16px;
}
.olt-top-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.olt-title {
  font-weight: 600;
  color: #303133;
}
.olt-res-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.olt-label {
  color: #606266;
  font-size: 13px;
}
.olt-res-ip {
  margin-left: 4px;
}
.olt-meta-row {
  margin-top: 12px;
  display: flex;
  gap: 28px;
  flex-wrap: wrap;
  color: #606266;
  font-size: 13px;
}
.olt-main-card {
  margin-bottom: 24px;
}
.olt-section-title {
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
}
.board-table {
  margin-bottom: 14px;
}
.olt-summary {
  background: #f5f7fa;
  border-radius: 4px;
  padding: 10px 14px;
  color: #606266;
  font-size: 13px;
  line-height: 1.9;
}
.olt-num {
  color: #2f7d32;
}
/* ---- 槽位面板 ---- */
.slot-grid {
  margin-top: 18px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}
.slot-card {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 12px;
  background: #fff;
}
.slot-head {
  margin-bottom: 10px;
}
.slot-row {
  display: flex;
  align-items: center;
  padding: 4px 0;
  font-size: 13px;
  color: #303133;
}
.slot-row .k {
  width: 72px;
  flex-shrink: 0;
  color: #909399;
}
.panel-wrap {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}
/* 模拟机架面板 */
.rack-panel {
  width: 92px;
  background: linear-gradient(180deg, #4a5568, #2d3748);
  border-radius: 4px;
  padding: 8px 6px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-height: 120px;
}
.port-block {
  background: #1a202c;
  border: 1px solid #4a5568;
  border-radius: 3px;
  padding: 4px 6px;
  display: flex;
  align-items: center;
  gap: 5px;
  cursor: pointer;
}
.port-block.is-online {
  border-color: #48bb78;
}
.port-no {
  color: #cbd5e0;
  font-size: 10px;
  white-space: nowrap;
}
.port-led {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}
.port-block.is-online .port-led {
  background: #48bb78;
  box-shadow: 0 0 4px #48bb78;
}
.port-block.is-offline .port-led {
  background: #4a5568;
}
/* 端口信息列表 */
.port-list {
  flex: 1;
  min-width: 0;
}
.port-info {
  display: flex;
  flex-direction: column;
  padding: 4px 0;
  border-bottom: 1px dashed #ebeef5;
}
.port-info-name {
  font-size: 12px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 4px;
}
.port-info-name.on {
  color: #2f7d32;
}
.port-info-name.off {
  color: #909399;
}
.port-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.port-dot.on {
  background: #48bb78;
}
.port-dot.off {
  background: #c0c4cc;
}
.port-info-line {
  font-size: 12px;
  color: #606266;
  padding-left: 10px;
}
.olt-placeholder {
  color: #909399;
  font-size: 13px;
  line-height: 2;
  padding: 8px 0;
}
/* ---- 主板信息等占位详情 ---- */
.detail-row {
  display: flex;
  padding: 6px 0;
  border-bottom: 1px dashed #ebeef5;
  font-size: 13px;
  max-width: 560px;
}
.detail-row .k {
  width: 120px;
  flex-shrink: 0;
  color: #909399;
}
.detail-row .v {
  color: #303133;
}
</style>
