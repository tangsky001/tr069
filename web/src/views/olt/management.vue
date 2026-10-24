<template>
  <div class="app-container">
    <!-- 顶部：OLT 资源信息 -->
    <el-card shadow="never" class="olt-top-card">
      <div slot="header" class="olt-top-head">
        <span class="olt-title">OLT/ONU 管理</span>
        <div>
          <el-button type="primary" size="mini" icon="el-icon-plus" @click="openCreate">添加 OLT</el-button>
          <el-button type="success" size="mini" icon="el-icon-refresh" :loading="loading" @click="loadList()">刷新</el-button>
        </div>
      </div>

      <div v-if="resources.length === 0" class="olt-empty">
        尚未添加 OLT。点击右上角「添加 OLT」填写地址与登录账号，保存后可「诊断测试」验证连通性，再「同步」采集板卡。
      </div>

      <template v-else>
        <div class="olt-res-row">
          <span class="olt-label">OLT 资源信息：</span>
          <el-select
            v-model="selectedId"
            size="small"
            style="width:260px"
            filterable
            placeholder="请选择 OLT"
            @change="loadDetail"
          >
            <el-option v-for="r in resources" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
          <el-input :value="device.host" size="small" style="width:170px" disabled />
          <el-button type="primary" size="small" icon="el-icon-edit" :disabled="!selectedId" @click="openEdit">编辑</el-button>
          <el-button
            type="primary"
            size="small"
            icon="el-icon-refresh-right"
            :loading="syncing"
            :disabled="!selectedId"
            @click="handleSync"
          >同步</el-button>
          <el-button
            type="warning"
            size="small"
            icon="el-icon-cpu"
            :loading="testing"
            :disabled="!selectedId"
            @click="handleTest"
          >诊断测试</el-button>
          <el-button type="danger" size="small" icon="el-icon-delete" :disabled="!selectedId" @click="handleDelete">删除</el-button>
          <span class="olt-auto">
            <el-switch v-model="autoRefresh" />
            <span class="olt-auto-label">自动刷新</span>
            <el-select v-model="pollSeconds" size="small" style="width:86px;margin-left:4px" :disabled="!autoRefresh">
              <el-option :value="5" label="5 秒" />
              <el-option :value="10" label="10 秒" />
              <el-option :value="30" label="30 秒" />
              <el-option :value="60" label="60 秒" />
            </el-select>
          </span>
        </div>

        <div class="olt-meta-row">
          <span>名称：<b>{{ device.name || '-' }}</b></span>
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
            <span class="olt-auto-tip">（启动即采一次，之后每 5 分钟自动采集，页面自动刷新）</span>
          </span>
        </div>

        <div v-if="device.lastError" class="olt-error">最近一次失败：{{ device.lastError }}</div>
      </template>
    </el-card>

    <!-- 主体：选项卡 -->
    <el-card v-if="hasDevice" shadow="never" class="olt-main-card">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="OLT 状态" name="spec">
          <!-- 连接概要：放在板卡信息上面，先看设备概况，再看下面每块板卡 -->
          <div class="olt-summary">
            <div>
              厂商：<b>{{ device.vendorLabel || '未识别' }}</b>；型号：<b>{{ device.model || '-' }}</b>；
              背板：{{ device.backplane || '-' }}；软件版本：{{ firmwareText }}
            </div>
            <div>最近连接：{{ device.lastConnectAt || '从未成功连接' }}；最近同步：{{ device.lastSyncAt || '未同步' }}</div>
            <div>{{ device.syncSummary || '尚未同步，点击「同步」采集板卡信息' }}</div>
          </div>

          <div class="olt-section-title">
            板卡信息
            <span v-if="boardSummary" class="board-summary">{{ boardSummary }}</span>
          </div>

          <div v-if="boards.length === 0" class="olt-empty">
            尚无板卡数据。点击「同步」登录设备采集；若同步成功仍为空，说明厂商命令与解析规则不匹配，
            可到「采集记录」查看原始回显。
          </div>

          <!-- 板卡卡片：插槽 / 型号 / 状态 / 描述 / 温度 / 示意图，按现场设备的展示习惯排 -->
          <div v-else class="board-grid">
            <div v-for="slot in slots" :key="slot.no" class="board-card">
              <div class="board-row"><span class="k">板卡插槽</span><span class="v">{{ slot.no }}</span></div>
              <div class="board-row"><span class="k">板卡型号</span><span class="v">{{ slot.type }}</span></div>
              <div class="board-row">
                <span class="k">运行状态</span>
                <span class="v" :class="'board-status-' + slot.statusType">
                  <i :class="slot.statusIcon" />{{ slot.statusText }}
                </span>
              </div>
              <div class="board-row"><span class="k">描述</span><span class="v">{{ slot.description || '-' }}</span></div>
              <div class="board-row"><span class="k">温度</span><span class="v">{{ slot.temperature == null ? '-' : slot.temperature + '°C' }}</span></div>
              <template v-if="slot.ports.length">
                <div class="board-row"><span class="k">端口 / ONT</span><span class="v">{{ slot.onuSummary }}</span></div>
              </template>
              <div class="board-row no-border"><span class="k">示意图</span><span class="v" /></div>
              <div class="board-photo-box">
                <div
                  v-if="slot.image && !imageError(slot)"
                  class="board-photo-wrap"
                  :class="{ 'board-photo-rows': slot.markers.length }"
                >
                  <img
                    :src="slot.image"
                    :alt="slot.type"
                    class="board-img"
                    title="点击查看板卡大图"
                    @error="markImageMissing(slot.type)"
                    @click="openBoardImage(slot)"
                  >
                  <!-- 面板布局固定的型号：端口行就钉在图上对应物理口的高度，一行对一个箭头 -->
                  <template v-if="slot.markers.length">
                    <div
                      v-for="port in slot.ports"
                      :key="'overlay-' + port.no"
                      class="port-row port-overlay"
                      :class="{
                        'port-row-clickable': port.onts.length,
                        'port-row-active': isPortActive(slot.no, port.no)
                      }"
                      :style="{ top: markerTop(slot, port.no) + '%' }"
                      @click="toggleOnts(slot.no, port.no, port.onts.length)"
                      @mouseenter="hoverOn(slot.no, port.no)"
                      @mouseleave="hoverOff()"
                    >
                      <span class="port-caret">{{ port.onts.length ? (isOntsOpen(slot.no, port.no) ? '▾' : '▸') : '' }}</span>
                      <span class="port-name">{{ port.name }}</span>
                      <span class="port-iface">{{ port.iface }}</span>
                      <span v-if="port.onuText" class="port-ont">{{ port.onuText }}</span>
                    </div>
                  </template>
                </div>
                <div v-else class="board-no-image">暂无该型号示意图</div>

                <!-- 没有面板布局（或没图）的型号：端口行按普通列表排在示意图下面 -->
                <div v-if="!slot.markers.length && slot.ports.length" class="port-list">
                  <div
                    v-for="port in slot.ports"
                    :key="'row-' + port.no"
                    class="port-row"
                    :class="{
                      'port-row-clickable': port.onts.length,
                      'port-row-active': isPortActive(slot.no, port.no)
                    }"
                    @click="toggleOnts(slot.no, port.no, port.onts.length)"
                    @mouseenter="hoverOn(slot.no, port.no)"
                    @mouseleave="hoverOff()"
                  >
                    <span class="port-caret">{{ port.onts.length ? (isOntsOpen(slot.no, port.no) ? '▾' : '▸') : '' }}</span>
                    <span class="port-name">{{ port.name }}</span>
                    <span class="port-iface">{{ port.iface }}</span>
                    <!-- 光模块状态没采到时（有的版本没有 display port state）不画灯，免得被当成「不在位」 -->
                    <i v-if="port.hasOptic" class="port-lamp" :class="'lamp-' + port.lamp" :title="port.lampText" />
                    <span v-if="port.hasOptic" class="port-rx">{{ port.rxPower || '--' }}</span>
                    <span v-if="port.onuText" class="port-ont">{{ port.onuText }}</span>
                  </div>
                </div>

                <!-- 点开端口：这个口下每个 ONT 的 ID / SN / 在线状态，放在示意图下面 -->
                <template v-for="port in slot.ports">
                  <div v-if="isOntsOpen(slot.no, port.no) && port.onts.length" :key="'onts-' + port.no" class="ont-panel">
                    <div class="ont-panel-title">
                      {{ port.name }} · {{ port.iface }} · {{ port.onuText }}
                      <span class="ont-panel-close" @click.stop="toggleOnts(slot.no, port.no, 1)">收起</span>
                    </div>
                    <div class="ont-list">
                      <div v-for="ont in port.onts" :key="ont.id" class="ont-row">
                        <span class="ont-id">#{{ ont.id }}</span>
                        <span class="ont-sn">{{ ont.sn }}</span>
                        <span class="ont-desc">{{ ont.desc }}</span>
                        <span class="ont-state" :class="ont.online ? 'ont-online' : 'ont-offline'">
                          <i class="ont-dot" />{{ ont.stateText }}
                        </span>
                      </div>
                    </div>
                  </div>
                </template>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="配置信息" name="config">
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
            <div class="detail-row"><span class="k">板卡配置</span><span class="v">{{ boardSummary || '-' }}</span></div>
            <div class="detail-row"><span class="k">最近连接</span><span class="v">{{ device.lastConnectAt || '-' }}</span></div>
            <div class="detail-row"><span class="k">最近同步</span><span class="v">{{ device.lastSyncAt || '-' }}</span></div>
            <div class="detail-row"><span class="k">同步摘要</span><span class="v">{{ device.syncSummary || '-' }}</span></div>
            <div class="detail-row"><span class="k">备注</span><span class="v">{{ device.remark || '-' }}</span></div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="VLAN" name="vlan">
          <div class="olt-summary">
            <div>
              VLAN <b>{{ vlanList.length }}</b> 个，上行口 <b>{{ portVlans.length }}</b> 个，
              三层接口 <b>{{ vlanIpInterfaces.length }}</b> 个，静态路由 <b>{{ staticRoutes.length }}</b> 条
              <span v-if="vlanInfo && vlanInfo.collectedAt">；快照时间 {{ vlanInfo.collectedAt }}</span>
            </div>
          </div>

          <div v-if="!vlanList.length" class="olt-empty">
            尚未采集到 VLAN 信息。后台启动后会自动采一轮、之后每 5 分钟一次（<code>display vlan all</code>），
            也可以点上面的「同步」立即刷新。
          </div>

          <template v-else>
            <!-- 1. 三层 VLAN 接口：vlanif 对应的 IP 和掩码 -->
            <div class="olt-section-title">三层 VLAN 接口</div>
            <el-table v-if="vlanIpInterfaces.length" :data="vlanIpInterfaces" size="small" border>
              <el-table-column label="VLAN ID" prop="vlanId" width="100" align="center" />
              <el-table-column label="VLAN IP 地址" prop="ipAddress" width="180" />
              <el-table-column label="子网掩码" prop="mask" width="180" />
              <el-table-column label="接口" prop="name" width="140" />
              <el-table-column label="状态">
                <template slot-scope="{ row }">
                  {{ row.physical }} / {{ row.protocol }}
                </template>
              </el-table-column>
            </el-table>
            <div v-else class="vlan-empty-row">该 OLT 没有配置三层 VLAN 接口（vlanif）</div>

            <!-- 2. 上行 VLAN 透传：每个上行口放通了哪些 VLAN -->
            <div class="olt-section-title">上行 VLAN 透传</div>
            <el-table :data="portVlans" size="small" border>
              <el-table-column label="机框/板卡/端口" prop="iface" width="180" />
              <el-table-column label="透传 VLAN">
                <template slot-scope="{ row }">
                  <span v-if="row.vlans.length" class="vlan-text">{{ compressVlans(row.vlans) }}</span>
                  <span v-else class="vlan-tip-inline">无</span>
                </template>
              </el-table-column>
              <el-table-column label="Native VLAN" width="130" align="center">
                <template slot-scope="{ row }">{{ row.nativeVlan == null ? '-' : row.nativeVlan }}</template>
              </el-table-column>
            </el-table>

            <!-- 3. VLAN / 二层互通：按类型合并，最后一排是互通 VLAN -->
            <div class="olt-section-title">VLAN / 二层互通</div>
            <el-table :data="vlanGroupRows" size="small" border>
              <el-table-column label="VLAN 类型" prop="label" width="280" />
              <el-table-column label="VLAN ID">
                <template slot-scope="{ row }">
                  <span class="vlan-text">{{ row.text }}</span>
                </template>
              </el-table-column>
              <el-table-column label="标准口数" width="110" align="center">
                <template slot-scope="{ row }">{{ row.std == null ? '-' : row.std }}</template>
              </el-table-column>
              <el-table-column label="业务流数" width="110" align="center">
                <template slot-scope="{ row }">{{ row.serv == null ? '-' : row.serv }}</template>
              </el-table-column>
            </el-table>

            <!-- 4. 静态路由 -->
            <div class="olt-section-title">静态路由</div>
            <el-table v-if="staticRoutes.length" :data="staticRoutes" size="small" border>
              <el-table-column label="目的网络" prop="destination" width="200" />
              <el-table-column label="下一跳网关" prop="nextHop" width="200" />
              <el-table-column label="出接口" prop="iface" width="160" />
              <el-table-column label="优先级" width="100" align="center">
                <template slot-scope="{ row }">{{ row.preference == null ? '-' : row.preference }}</template>
              </el-table-column>
            </el-table>
            <div v-else class="vlan-empty-row">没有静态路由</div>
          </template>
        </el-tab-pane>

        <el-tab-pane label="DBA/线路模板" name="dba">
          <div class="olt-placeholder">
            DBA / 线路模板（占位）：待接入 <code>display dba-profile all</code> 与
            <code>display ont-lineprofile gpon all</code> 采集后展示，用于查看每条线路模板绑定的
            T-CONT / Gemport 与 DBA 带宽档位。
          </div>
        </el-tab-pane>

        <el-tab-pane label="业务/TR069/流量模板" name="service">
          <div class="olt-placeholder">
            业务 / TR069 / 流量模板（占位）：待接入 <code>display service-port all</code>、
            <code>display ont-srvprofile gpon all</code> 采集后展示业务流（VLAN + Gemport + 端口映射）、
            ONT 能力模板与 TR069 管理参数。
          </div>
        </el-tab-pane>

        <el-tab-pane label="ONT 配置/状态" name="ont">
          <div class="olt-summary">
            <div>
              业务板 <b>{{ ontBoards.length }}</b> 块，端口 <b>{{ ontPortTotal }}</b> 个，
              ONT <b>{{ ontSummary.total }}</b> 个（在线 {{ ontSummary.online }} / 离线 {{ ontSummary.offline }}）。
            </div>
            <div>
              数据来自每块业务板一条 <code>display ont info 0 &lt;槽位&gt; all</code>；展开端口可看该口下
              每一条 ONT 的 ID、SN（EPON 是 MAC）和在线状态。
            </div>
          </div>

          <div v-if="!ontBoards.length" class="olt-empty">
            尚未采集到端口/ONT 数据。后台启动后会自动采一轮、之后每 5 分钟一次，也可以点上面的「同步」立即刷新。
          </div>

          <div v-for="slot in ontBoards" :key="'ont-' + slot.no" class="ont-board">
            <div class="ont-board-title">
              槽位 {{ slot.no }} · {{ slot.type }}
              <span class="ont-board-sum">{{ slot.onuSummary }}</span>
            </div>
            <el-table :data="slot.ports" size="small" border>
              <el-table-column type="expand">
                <template slot-scope="{ row }">
                  <el-table v-if="row.onts.length" :data="row.onts" size="mini" border class="ont-sub-table">
                    <el-table-column label="ONT ID" prop="id" width="80" align="center" />
                    <el-table-column label="SN / MAC" prop="sn" width="210" />
                    <el-table-column label="型号" prop="desc" />
                    <el-table-column label="状态" width="90" align="center">
                      <template slot-scope="scope">
                        <span :class="scope.row.online ? 'ont-online' : 'ont-offline'">
                          <i class="ont-dot" />{{ scope.row.stateText }}
                        </span>
                      </template>
                    </el-table-column>
                  </el-table>
                  <div v-else class="ont-sub-empty">该端口下没有 ONT</div>
                </template>
              </el-table-column>
              <el-table-column label="端口" prop="name" width="80" />
              <el-table-column label="接口（框/槽/口）" prop="iface" width="140" />
              <el-table-column label="ONT 总数" prop="onuTotal" width="100" align="center" />
              <el-table-column label="在线" prop="onuOnline" width="80" align="center" />
              <el-table-column label="离线" prop="offline" width="80" align="center" />
              <el-table-column label="光模块收光" width="120" align="center">
                <template slot-scope="{ row }">
                  <span v-if="row.hasOptic">{{ row.rxPower || '--' }}</span>
                  <span v-else class="ont-sub-empty">未采集</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-tab-pane>

        <el-tab-pane label="ONT 注册" name="ontreg">
          <div class="olt-placeholder">
            ONT 注册（占位）：待接入 <code>display ont autofind all</code> 采集后展示待注册 ONT
            （SN / MAC、发现端口、时间），并支持按 SN 授权到指定端口、绑定线路模板与业务模板。
          </div>
        </el-tab-pane>

        <el-tab-pane label="OLA" name="ola">
          <div class="olt-placeholder">OLA（光链路聚合，占位）：待接入真实 OLT 采集数据后展示聚合组与成员口。</div>
        </el-tab-pane>

        <el-tab-pane label="ONU 后板编辑面板" name="onu">
          <div class="olt-placeholder">ONU 后板编辑面板（占位）：用于按槽位/端口批量编辑 ONU 模板与授权，待后端接入。</div>
        </el-tab-pane>

        <el-tab-pane label="采集记录" name="records">
          <div class="record-head">
            <span>最近 20 次连接与同步记录，展开可看设备原始回显。</span>
            <el-button size="mini" icon="el-icon-refresh" @click="loadRecords">刷新</el-button>
          </div>
          <el-table :data="records" border size="small">
            <el-table-column type="expand">
              <template slot-scope="{ row }">
                <pre class="raw-output">{{ row.rawPreview || '本次无回显（连接阶段即失败）' }}</pre>
              </template>
            </el-table-column>
            <el-table-column label="时间" prop="createdAt" width="170" />
            <el-table-column label="动作" width="100" align="center">
              <template slot-scope="{ row }">
                <el-tag size="mini" :type="row.action === 'SYNC' ? 'success' : 'info'">
                  {{ row.action === 'SYNC' ? '同步' : '诊断' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="结果" width="90" align="center">
              <template slot-scope="{ row }">
                <el-tag size="mini" :type="row.success ? 'success' : 'danger'">{{ row.success ? '成功' : '失败' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="说明" prop="message" min-width="320" show-overflow-tooltip />
            <el-table-column label="耗时" width="100" align="center">
              <template slot-scope="{ row }">{{ row.costMs == null ? '-' : row.costMs + ' ms' }}</template>
            </el-table-column>
            <el-table-column label="操作人" width="120">
              <template slot-scope="{ row }">{{ row.operator || '-' }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="OLT 升级" name="upgrade">
          <div class="olt-placeholder">OLT 升级（占位）：固件版本管理与批量升级任务，待后端接入。</div>
        </el-tab-pane>

        <el-tab-pane label="主备" name="standby">
          <div class="olt-placeholder">主备（占位）：主控板主备状态、倒换记录，待采集主控板信息后展示。</div>
        </el-tab-pane>

        <el-tab-pane label="保护" name="protect">
          <div class="olt-placeholder">保护（占位）：Type B/C 保护组、环路检测等配置，待后端接入。</div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 添加 / 编辑 OLT -->
    <olt-form-dialog
      :visible.sync="formVisible"
      :olt="editing"
      @saved="handleSaved"
    />

    <!-- 板卡示意图大图 -->
    <el-dialog
      :title="boardPreview ? boardPreview.label : '板卡示意图'"
      :visible.sync="boardPreviewVisible"
      width="360px"
      append-to-body
    >
      <div v-if="boardPreview" class="board-preview-wrap">
        <img :src="boardPreview.image" :alt="boardPreview.label" class="board-preview-img">
        <div class="board-preview-tip">示意图仅用于区分板卡外观，端口状态以表格数据为准</div>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { fetchOltList, fetchOltDetail, fetchOltRecords, testOltById, syncOlt, deleteOlt } from '@/api/olt'
import OltFormDialog from './components/OltFormDialog'
import { matchBoardProfile } from './boardProfiles'

/**
 * 板卡示意图上 PON 口的位置（占图片高度的百分比），下标就是端口号。
 * 只给面板布局固定的型号配，宁可没有也别乱标：
 * H807GPBD 上排 4 口（P0-P3）、中间一条激光警告贴、下排 4 口（P4-P7）。
 * 数值按图片像素量的（端口块中心 y ÷ 图高 719）。
 */
const PORT_MARKERS = {
  H807GPBD: [30, 36, 42, 47.5, 66.5, 72.5, 78.5, 84]
}

/**
 * OLT/ONU 管理页。
 *
 * 台账（地址、账号、端口、字符集）来自 olt_device；板卡来自 Telnet 同步结果 olt_board；
 * 「诊断测试」只验证能否登录，不覆盖已有板卡；「同步」才刷新板卡。
 */
export default {
  name: 'OltManagement',
  components: { OltFormDialog },
  data() {
    return {
      activeTab: 'spec',
      loading: false,
      syncing: false,
      testing: false,
      resources: [],
      selectedId: null,
      detail: { device: null, boards: [], records: [], vlan: null },
      formVisible: false,
      editing: null,
      boardPreview: null,
      boardPreviewVisible: false,
      // 图片按型号约定命名，缺图时记下来退回占位，避免一堆 404
      missingImages: [],
      // 页面自动刷新：后台每 5 分钟自己去采，这里只按间隔把最新数据拉到页面上，不碰设备
      autoRefresh: true,
      pollSeconds: 10,
      pollTimer: null,
      // 展开看 ONT 明细的端口，key 是「槽位:端口」
      expandedOnts: {},
      // 鼠标停在端口行/示意图标注上时，两边一起高亮
      hoverPort: ''
    }
  },
  computed: {
    device() {
      return this.detail.device || {}
    },
    boards() {
      return this.detail.boards || []
    },
    records() {
      return this.detail.records || []
    },
    hasDevice() {
      return !!this.detail.device
    },
    slots() {
      return this.boards.map(board => {
        // 板卡型号能对上档案时补上示意图
        const profile = matchBoardProfile(board.boardType)
        const type = board.boardType || '-'
        const ports = this.buildPorts(board)
        return {
          no: board.slotNo,
          type,
          portMode: board.portMode || (profile && profile.portMode),
          portCount: board.portCount,
          status: board.status,
          statusText: this.boardStatusText(board.status),
          statusType: this.boardStatusType(board.status),
          statusIcon: this.boardStatusIcon(board.status),
          description: board.description || (profile ? profile.label : ''),
          temperature: board.temperature,
          // 有档案用档案，没有就按约定 /olt/olt_<型号>.png 找图，缺图由 @error 退回占位
          image: profile ? profile.image : this.conventionImage(board.boardType),
          profile,
          ports,
          onuSummary: this.buildOnuSummary(board),
          markers: this.buildPortMarkers(type, ports)
        }
      })
    },
    /** 「ONT 配置/状态」页只列有端口的板卡（业务板），主控/上联板没有 ONT */
    ontBoards() {
      return this.slots.filter(slot => slot.ports.length)
    },
    ontPortTotal() {
      return this.ontBoards.reduce((sum, slot) => sum + slot.ports.length, 0)
    },
    /** 全局 ONT 汇总：各板各口加起来，和每行「23 ONT / 22 在线」能对上 */
    ontSummary() {
      let total = 0
      let online = 0
      this.ontBoards.forEach(slot => {
        slot.ports.forEach(port => {
          total += port.onuTotal || 0
          online += port.onuOnline || 0
        })
      })
      return { total, online, offline: Math.max(0, total - online) }
    },
    vlanInfo() {
      return this.detail.vlan || null
    },
    vlanList() {
      return (this.vlanInfo && this.vlanInfo.vlans) || []
    },
    portVlans() {
      return (this.vlanInfo && this.vlanInfo.portVlans) || []
    },
    vlanIpInterfaces() {
      return (this.vlanInfo && this.vlanInfo.ipInterfaces) || []
    },
    staticRoutes() {
      return (this.vlanInfo && this.vlanInfo.staticRoutes) || []
    },
    /** 按 VLAN 类型分组，和网管上「单层智能 VLAN (Smart VLAN)：41、45-46」的写法一致 */
    vlanTypeGroups() {
      const labels = {
        smart: '单层智能 VLAN (Smart VLAN)',
        mux: '汇聚 VLAN (Mux VLAN)',
        standard: '标准 VLAN (Standard VLAN)',
        super: '超级 VLAN (Super VLAN)'
      }
      const groups = {}
      this.vlanList.forEach(vlan => {
        const type = (vlan.type || 'other').toLowerCase()
        if (!groups[type]) {
          groups[type] = { ids: [], std: 0, serv: 0 }
        }
        groups[type].ids.push(vlan.vlanId)
        groups[type].std += vlan.stdPortNum || 0
        groups[type].serv += vlan.servPortNum || 0
      })
      return Object.keys(groups).map(type => ({
        type,
        label: labels[type] || type,
        text: this.compressVlans(groups[type].ids),
        std: groups[type].std,
        serv: groups[type].serv
      }))
    },
    /** VLAN 类型分组 + 最后一排「二层互通 VLAN」（VLAN-Con 不为空的那些） */
    vlanGroupRows() {
      const rows = this.vlanTypeGroups.slice()
      const connectIds = this.vlanList
        .filter(vlan => vlan.vlanConNum != null)
        .map(vlan => vlan.vlanId)
      rows.push({
        type: 'connect',
        label: '二层互通 VLAN',
        text: connectIds.length ? this.compressVlans(connectIds) : '无',
        std: null,
        serv: null
      })
      return rows
    },
    /** 固件版本按「版本 / 补丁」拼，和现场 OLT 的写法一致 */
    firmwareText() {
      const parts = [this.device.softwareVersion, this.device.patchVersion].filter(item => !!item)
      return parts.length ? parts.join(' / ') : '-'
    },
    /** 板卡类别统计，形如：共 7 块板卡（业务板 2 / 主控板 2 / 上联板 2 / 电源板 1） */
    boardSummary() {
      if (this.boards.length === 0) return ''
      const labels = {
        SERVICE: '业务板',
        CONTROL: '主控板',
        UPLINK: '上联板',
        POWER: '电源板',
        INTERFACE: '通用接口板',
        UNKNOWN: '未知'
      }
      const counts = {}
      this.boards.forEach(board => {
        const kind = board.boardKind || 'UNKNOWN'
        counts[kind] = (counts[kind] || 0) + 1
      })
      const detail = Object.keys(counts).map(kind => (labels[kind] || kind) + ' ' + counts[kind]).join(' / ')
      return '共 ' + this.boards.length + ' 块板卡（' + detail + '）'
    },
    statusText() {
      const map = { ONLINE: '在线', OFFLINE: '离线', UNKNOWN: '未探测' }
      return map[this.device.status] || '未探测'
    },
    statusTag() {
      if (this.device.status === 'ONLINE') return 'success'
      if (this.device.status === 'OFFLINE') return 'danger'
      return 'info'
    },
    webAccountText() {
      const accounts = this.device.webAccounts || []
      return accounts.length === 0 ? '不限制' : accounts.join('、')
    }
  },
  watch: {
    // 改了间隔或开关就重启定时器，不用刷新页面
    pollSeconds() {
      this.startPolling()
    },
    autoRefresh(value) {
      if (value) {
        this.startPolling()
      } else {
        this.stopPolling()
      }
    }
  },
  created() {
    this.loadList()
    this.startPolling()
  },
  beforeDestroy() {
    this.stopPolling()
  },
  methods: {
    /**
     * 自动刷新：按间隔拉详情，后台定时任务采集到的数据不用等手点同步就能看到。
     * 只在页面空闲（没有正在进行的加载/同步）时拉，避免和用户操作抢。
     */
    startPolling() {
      this.stopPolling()
      if (!this.autoRefresh) return
      this.pollTimer = setInterval(() => {
        if (this.selectedId && !this.syncing && !this.loading) {
          this.loadDetailSilently(this.selectedId)
        }
      }, this.pollSeconds * 1000)
    },
    stopPolling() {
      if (this.pollTimer) {
        clearInterval(this.pollTimer)
        this.pollTimer = null
      }
    },
    loadDetailSilently(id) {
      if (!id) return
      return fetchOltDetail(id)
        .then(res => {
          const data = res.data || {}
          this.detail = {
            device: data.device || null,
            boards: data.boards || [],
            records: data.records || [],
            vlan: data.vlan || null
          }
        })
        .catch(() => {})
    },
    loadList(preferId) {
      this.loading = true
      return fetchOltList()
        .then(res => {
          this.resources = res.data || []
          const wanted = preferId || this.selectedId
          const exists = this.resources.some(item => item.id === wanted)
          this.selectedId = exists ? wanted : (this.resources[0] ? this.resources[0].id : null)
          if (this.selectedId) {
            return this.loadDetail(this.selectedId)
          }
          this.detail = { device: null, boards: [], records: [] }
        })
        .catch(() => {})
        .finally(() => {
          this.loading = false
        })
    },
    loadDetail(id) {
      if (!id) return Promise.resolve()
      this.loading = true
      return fetchOltDetail(id)
        .then(res => {
          const data = res.data || {}
          this.detail = {
            device: data.device || null,
            boards: data.boards || [],
            records: data.records || [],
            vlan: data.vlan || null
          }
        })
        .catch(() => {})
        .finally(() => {
          this.loading = false
        })
    },
    loadRecords() {
      if (!this.selectedId) return
      fetchOltRecords(this.selectedId, 20)
        .then(res => {
          this.detail = { ...this.detail, records: res.data || [] }
        })
        .catch(() => {})
    },
    openCreate() {
      this.editing = null
      this.formVisible = true
    },
    openEdit() {
      this.editing = this.device
      this.formVisible = true
    },
    handleSaved(saved) {
      const id = saved && saved.id ? saved.id : this.selectedId
      this.loadList(id)
    },
    handleTest() {
      if (!this.selectedId) return
      this.testing = true
      testOltById(this.selectedId)
        .then(res => {
          const data = res.data || {}
          this.$alert(data.message || (data.success ? '连接成功' : '连接失败'), data.success ? '诊断测试通过' : '诊断测试失败', {
            type: data.success ? 'success' : 'error'
          })
          this.loadDetail(this.selectedId)
        })
        .catch(() => {})
        .finally(() => {
          this.testing = false
        })
    },
    handleSync(silent) {
      if (!this.selectedId) return
      this.syncing = true
      return syncOlt(this.selectedId)
        .then(res => {
          const data = res.data || {}
          this.detail = {
            device: data.device || null,
            boards: data.boards || [],
            records: data.records || [],
            vlan: data.vlan || null
          }
          if (!silent) {
            this.$message.success('同步完成：' + ((data.device && data.device.syncSummary) || '已刷新板卡信息'))
          }
          this.loadList(this.selectedId)
        })
        .catch(() => {})
        .finally(() => {
          this.syncing = false
        })
    },
    handleDelete() {
      if (!this.selectedId) return
      const name = this.device.name || this.device.host
      this.$confirm('删除后该 OLT 的板卡与采集记录将一并清理，是否继续？', '删除 OLT：' + name, {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning'
      })
        .then(() => deleteOlt(this.selectedId))
        .then(() => {
          this.$message.success('已删除')
          this.selectedId = null
          return this.loadList()
        })
        .catch(() => {})
    },
    /**
     * 端口光模块指示灯：在位亮绿灯，不在位灰灯，故障/类型不匹配黄灯。
     * 没采到状态就整段不渲染，避免一排灰灯看着像「全部不在位」。
     */
    buildPorts(board) {
      const ports = board.ports || []
      return ports
        .map(port => {
          const status = (port.opticStatus || '').toLowerCase()
          // opticalPresent 为 null 说明这台设备没有端口光模块命令，不画灯
          const hasOptic = port.opticalPresent != null
          let lamp = 'unknown'
          let lampText = '光模块状态未采集'
          if (port.opticalPresent === true) {
            lamp = 'on'
            lampText = '光模块在位'
          } else if (port.opticalPresent === false) {
            lamp = 'off'
            lampText = '光模块不在位'
          }
          if (/abnormal|mismatch|fault/.test(status)) {
            lamp = 'warn'
            lampText = '光模块异常（' + port.opticStatus + '）'
          }
          let onuText = ''
          if (port.onuTotal != null) {
            onuText = port.onuTotal + ' ONT / ' + (port.onuOnline == null ? '-' : port.onuOnline) + ' 在线'
          }
          // 接口标识和 OLT 命令行一致：框/槽/口，例如 0/1/0
          const frameNo = port.frameNo == null ? 0 : port.frameNo
          const slotNo = port.slotNo == null ? board.slotNo : port.slotNo
          const iface = slotNo == null ? '' : frameNo + '/' + slotNo + '/' + port.portNo
          // 每个 ONT 一条：页面点开端口后逐个显示，在线和不在线都列
          const onts = (port.onts || []).map(ont => ({
            id: ont.ontId,
            sn: ont.sn || '-',
            desc: ont.description || '',
            online: ont.online === true,
            stateText: ont.online === true ? '在线' : '离线'
          }))
          return {
            no: port.portNo,
            name: port.portName || ('P' + port.portNo),
            iface,
            onts,
            onuTotal: port.onuTotal == null ? 0 : port.onuTotal,
            onuOnline: port.onuOnline == null ? 0 : port.onuOnline,
            offline: port.onuTotal == null ? 0 : Math.max(0, port.onuTotal - (port.onuOnline || 0)),
            rxPower: port.rxPower,
            hasOptic,
            onuText,
            lamp,
            lampText
          }
        })
        .sort((a, b) => (a.no || 0) - (b.no || 0))
    },
    /** [1,41,45,46] → 1、41、45-46：连续段压成区间，和网管上的写法一致 */
    compressVlans(vlans) {
      const nums = (vlans || []).slice().sort((a, b) => a - b)
      const parts = []
      let start = null
      let prev = null
      nums.forEach(num => {
        if (start === null) {
          start = num
          prev = num
          return
        }
        if (num === prev + 1) {
          prev = num
          return
        }
        parts.push(start === prev ? String(start) : start + '-' + prev)
        start = num
        prev = num
      })
      if (start !== null) {
        parts.push(start === prev ? String(start) : start + '-' + prev)
      }
      return parts.join('、')
    },
    /** 「槽位:端口」作为展开 ONT 明细的 key */
    ontKey(slotNo, portNo) {
      return slotNo + ':' + portNo
    },
    isOntsOpen(slotNo, portNo) {
      return !!this.expandedOnts[this.ontKey(slotNo, portNo)]
    },
    toggleOnts(slotNo, portNo, count) {
      if (!count) return
      const key = this.ontKey(slotNo, portNo)
      const next = { ...this.expandedOnts }
      if (next[key]) {
        delete next[key]
      } else {
        next[key] = true
      }
      this.expandedOnts = next
    },
    /**
     * 示意图上的端口位置：只有面板布局固定、端口数也对得上的型号才返回。
     * 端口多过布局（比如 16 口板用了 8 口的图）宁可不钉，也不能把两行叠在一起。
     */
    buildPortMarkers(type, ports) {
      const tops = PORT_MARKERS[String(type || '').toUpperCase()]
      if (!tops || !ports.length || ports.length > tops.length) return []
      return ports
        .filter(port => port.no != null && port.no < tops.length)
        .map(port => ({ no: port.no, top: tops[port.no] }))
    },
    /** 端口行要钉的高度：取不到就退回 0（不会出现 NaN） */
    markerTop(slot, portNo) {
      const marker = slot.markers.find(item => item.no === portNo)
      return marker ? marker.top : 0
    },
    /** 端口行和示意图标注共用同一个 key，hover 时两边一起亮 */
    isPortActive(slotNo, portNo) {
      return this.hoverPort === this.ontKey(slotNo, portNo)
    },
    hoverOn(slotNo, portNo) {
      this.hoverPort = this.ontKey(slotNo, portNo)
    },
    hoverOff() {
      this.hoverPort = ''
    },
    /** 板卡下所有端口的 ONT 合计，放在「端口 / ONT」这一行的右侧 */
    buildOnuSummary(board) {
      const ports = board.ports || []
      let total = 0
      let online = 0
      let known = 0
      ports.forEach(port => {
        if (port.onuTotal != null) {
          total += port.onuTotal
          online += port.onuOnline || 0
          known++
        }
      })
      return known ? (total + ' ONT / ' + online + ' 在线') : ''
    },
    /** 板卡示意图约定路径：public/olt/olt_<板卡型号>.png，新增型号只要按名字放图即可 */
    conventionImage(boardType) {
      if (!boardType) return null
      return '/olt/olt_' + String(boardType).toUpperCase() + '.png'
    },
    imageError(slot) {
      return this.missingImages.indexOf(slot.type) >= 0
    },
    markImageMissing(boardType) {
      if (this.missingImages.indexOf(boardType) < 0) {
        this.missingImages.push(boardType)
      }
    },
    openBoardImage(slot) {
      this.boardPreview = {
        image: slot.image,
        label: (slot.profile ? slot.profile.label : slot.type) + '（槽位 ' + slot.no + '）'
      }
      this.boardPreviewVisible = true
    },
    /**
     * 把设备原始状态翻成页面上的说法：
     * Normal -> 正常，Active_normal -> 激活，Standby_normal -> 备用。
     */
    boardStatusText(status) {
      const text = (status || '').toLowerCase().replace(/[_-]+/g, ' ').trim()
      if (!text) return '未知'
      if (/auto find/.test(text)) return '自动发现'
      if (/not installed/.test(text)) return '未插板'
      if (/active/.test(text)) return '激活'
      if (/standby/.test(text)) return '备用'
      if (/fail|fault/.test(text)) return '故障'
      if (/abnormal/.test(text)) return '异常'
      if (/offline/.test(text)) return '离线'
      if (/config/.test(text)) return '配置中'
      if (/inservice|in service|in position|normal|working|online|up/.test(text)) return '正常'
      return status
    },
    boardStatusType(status) {
      const text = (status || '').toLowerCase()
      if (!text) return 'info'
      if (/fail|fault|abnormal|offline|not installed|out of service/.test(text)) return 'danger'
      if (/auto find|auto_find|standby|config/.test(text)) return 'warning'
      if (/normal|active|inservice|in service|in position|working|online|up/.test(text)) return 'success'
      return 'info'
    },
    boardStatusIcon(status) {
      const type = this.boardStatusType(status)
      if (type === 'success') return 'el-icon-success'
      if (type === 'danger') return 'el-icon-error'
      if (type === 'warning') return 'el-icon-warning-outline'
      return 'el-icon-info'
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
.olt-empty {
  color: #909399;
  font-size: 13px;
  line-height: 1.9;
  padding: 6px 0;
}
.olt-error {
  margin-top: 10px;
  padding: 8px 12px;
  background: #fef0f0;
  border-radius: 4px;
  color: #f56c6c;
  font-size: 12px;
  word-break: break-all;
}
.olt-res-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.olt-auto {
  display: inline-flex;
  align-items: center;
  margin-left: 6px;
}
.olt-auto-label {
  margin-left: 4px;
  font-size: 13px;
  color: #606266;
}
.olt-auto-tip {
  font-size: 12px;
  color: #909399;
}
.olt-label {
  color: #606266;
  font-size: 13px;
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
  margin: 16px 0 8px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.board-summary {
  font-weight: 400;
  color: #909399;
  font-size: 12px;
}
.olt-summary {
  background: #f5f7fa;
  border-radius: 4px;
  padding: 10px 14px;
  color: #606266;
  font-size: 13px;
  line-height: 1.9;
  margin-bottom: 16px;
}
/* ---- 板卡卡片：插槽 / 型号 / 状态 / 描述 / 温度 / 示意图 ---- */
.board-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
  margin-top: 12px;
}
.board-card {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 12px 14px;
  background: #fff;
}
.board-row {
  display: flex;
  align-items: center;
  padding: 5px 0;
  font-size: 13px;
  color: #303133;
  border-bottom: 1px dashed #f0f2f5;
}
.board-row.no-border {
  border-bottom: none;
}
.board-row .k {
  width: 76px;
  flex-shrink: 0;
  color: #909399;
}
.board-row .v {
  word-break: break-all;
}
.board-status-success {
  color: #67c23a;
}
.board-status-danger {
  color: #f56c6c;
}
.board-status-warning {
  color: #e6a23c;
}
.board-status-info {
  color: #909399;
}
.board-status-success i,
.board-status-danger i,
.board-status-warning i,
.board-status-info i {
  margin-right: 4px;
}
/* ---- 端口光模块指示灯 ---- */
.port-list {
  display: flex;
  flex-direction: column;
  padding: 2px 0 6px;
}
.port-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 0;
  font-size: 12px;
  color: #606266;
}
.port-name {
  width: 28px;
  color: #303133;
}
/* 接口标识 0/1/0：和设备命令行里的 F/S/P 一致，一眼能对上物理口 */
.port-iface {
  color: #909399;
  font-family: Consolas, Monaco, monospace;
}
.port-lamp {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #dcdfe6;
  flex-shrink: 0;
}
/* 在位：亮绿灯 + 一圈光晕，一眼能看出亮了 */
.port-lamp.lamp-on {
  background: #67c23a;
  box-shadow: 0 0 6px rgba(103, 194, 58, 0.85);
}
/* 不在位：灰灯 */
.port-lamp.lamp-off {
  background: #c0c4cc;
}
.port-lamp.lamp-warn {
  background: #e6a23c;
  box-shadow: 0 0 6px rgba(230, 162, 60, 0.85);
}
.port-lamp.lamp-unknown {
  background: #f5f7fa;
  border: 1px solid #dcdfe6;
}
.port-rx {
  width: 62px;
  text-align: right;
  color: #909399;
  font-family: Consolas, Monaco, monospace;
}
/* ONT 数量：没有光模块数据时它就是这一行唯一的信息，所以靠右对齐 */
.port-ont {
  margin-left: auto;
  text-align: right;
  color: #606266;
}
/* ---- 展开后逐个显示 ONT（ONU）---- */
.port-row-clickable {
  cursor: pointer;
}
.port-row-clickable:hover {
  background: #f5f7fa;
}
.port-caret {
  width: 10px;
  color: #c0c4cc;
}
.ont-list {
  padding: 2px 0 6px 18px;
}
.ont-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 0;
  font-size: 12px;
  color: #606266;
}
.ont-id {
  width: 34px;
  color: #303133;
}
.ont-sn {
  font-family: Consolas, Monaco, monospace;
  color: #909399;
}
.ont-desc {
  color: #909399;
}
.ont-state {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.ont-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #dcdfe6;
}
.ont-online .ont-dot {
  background: #67c23a;
}
.ont-offline {
  color: #909399;
}
.ont-offline .ont-dot {
  background: #c0c4cc;
}
/* ---- VLAN：透传 VLAN 按「41、45-46」的写法显示，和命令行回显一致 ---- */
.vlan-text {
  color: #303133;
  word-break: break-all;
}
.vlan-empty-row {
  padding: 10px 12px;
  font-size: 12px;
  color: #909399;
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}
.vlan-tip-inline {
  font-size: 12px;
  color: #c0c4cc;
}
/* ---- ONT 配置/状态：按板卡分表，展开端口看每条 ONT ---- */
.ont-board {
  margin-bottom: 18px;
}
.ont-board-title {
  display: flex;
  align-items: center;
  margin: 0 0 8px;
  padding-left: 8px;
  border-left: 3px solid #409eff;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}
.ont-board-sum {
  margin-left: 10px;
  font-weight: 400;
  font-size: 12px;
  color: #909399;
}
.ont-sub-table {
  margin: 4px 0 4px 24px;
  width: calc(100% - 48px);
}
.ont-sub-empty {
  padding: 6px 0 6px 24px;
  font-size: 12px;
  color: #c0c4cc;
}
.board-photo-box {
  padding: 8px 0 2px;
  text-align: center;
}
.board-photo-wrap {
  position: relative;
  display: inline-block;
}
/* 端口行钉在图的右侧，整块往左让出位置（连接线 12 + 行宽 210） */
.board-photo-rows {
  margin-right: 240px;
}
.port-overlay {
  position: absolute;
  left: calc(100% + 12px);
  width: 210px;
  transform: translateY(-50%);
  gap: 6px;
  padding: 1px 6px;
  border-radius: 4px;
  background: #fff;
  z-index: 2;
  white-space: nowrap;
}
/* 连到物理口的那条小横线 */
.port-overlay::before {
  content: '';
  position: absolute;
  right: 100%;
  top: 50%;
  width: 12px;
  height: 1px;
  background: #dcdfe6;
}
.port-overlay.port-row-active::before {
  background: #409eff;
}
.port-row-active {
  background: #ecf5ff;
}
/* ---- 点开端口后的 ONT 明细面板 ---- */
.ont-panel {
  margin-top: 10px;
  padding: 6px 8px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  background: #fafcff;
  text-align: left;
}
.ont-panel-title {
  display: flex;
  align-items: center;
  font-size: 12px;
  color: #303133;
  margin-bottom: 4px;
}
.ont-panel-close {
  margin-left: auto;
  color: #409eff;
  cursor: pointer;
}
.ont-panel .ont-list {
  padding-left: 0;
  max-height: 220px;
  overflow: auto;
}
/* 示意图是窄长条（约 42x719），按高度缩放，宽度交给原图比例；换成高清图后自动变宽 */
.board-img {
  height: 400px;
  width: auto;
  max-width: 92px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  background: #fafafa;
  padding: 4px 3px;
  cursor: zoom-in;
  transition: box-shadow 0.2s;
}
.board-img:hover {
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.16);
}
.board-no-image {
  height: 120px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px dashed #dcdfe6;
  border-radius: 4px;
  color: #c0c4cc;
  font-size: 12px;
}
.board-preview-wrap {
  text-align: center;
}
.board-preview-img {
  height: 68vh;
  width: auto;
  max-width: 100%;
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 6px 4px;
}
.board-preview-tip {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
}
.olt-placeholder {
  color: #909399;
  font-size: 13px;
  line-height: 2;
  padding: 8px 0;
}
/* ---- 详情 ---- */
.detail-row {
  display: flex;
  padding: 6px 0;
  border-bottom: 1px dashed #ebeef5;
  font-size: 13px;
  max-width: 720px;
}
.detail-row .k {
  width: 140px;
  flex-shrink: 0;
  color: #909399;
}
.detail-row .v {
  color: #303133;
  word-break: break-all;
}
/* ---- 采集记录 ---- */
.record-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  color: #606266;
  margin-bottom: 10px;
}
.raw-output {
  margin: 0;
  padding: 10px 14px;
  background: #1e1e1e;
  color: #d4d4d4;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 360px;
  overflow: auto;
}
</style>
