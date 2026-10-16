<template>
  <div class="app-container">
    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" :model="query" size="small">
        <el-form-item label="序列号">
          <el-input v-model="query.sn" placeholder="支持模糊匹配" clearable style="width:180px" />
        </el-form-item>
        <el-form-item label="运营商">
          <el-select v-model="query.isp" clearable placeholder="全部" style="width:140px">
            <el-option label="电信 CT" value="CT" />
            <el-option label="联通 CU" value="CU" />
            <el-option label="移动 CMCC" value="CMCC" />
            <el-option label="通用 standard" value="standard" />
          </el-select>
        </el-form-item>
        <el-form-item label="设备类型">
          <el-input v-model="query.deviceType" placeholder="如 ADSL_4+1" clearable style="width:160px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="handleSearch">查询</el-button>
          <el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
      <div class="online-bar">
        当前在线会话：<b>{{ onlineCount }}</b> 台
        <el-button type="text" icon="el-icon-refresh" @click="loadOnline">刷新</el-button>
        <el-button type="text" @click="onlineVisible = true">查看明细</el-button>
      </div>
    </el-card>

    <el-table
      v-loading="loading"
      :data="displayList"
      border
      fit
      highlight-current-row
      style="width:100%"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="42" align="center" />
      <!-- 设备标识：SN 与在线状态，房间号 / 备注点「修改」弹窗编辑 -->
      <el-table-column label="设备标识" min-width="270">
        <template slot-scope="{ row }">
          <div class="cell-main">
            <el-link type="primary" @click="openDetail(row)">{{ row.sn }}</el-link>
            <el-tag :type="isOnline(row) ? 'success' : 'info'" size="mini">
              {{ isOnline(row) ? '在线' : '离线' }}
            </el-tag>
          </div>
          <div class="cell-sub">{{ row.oui || '-' }} · {{ row.isp || '-' }} {{ row.ispVersion || '' }}</div>
          <div class="cell-remark">
            <span class="remark-label">房间号</span>
            <span class="remark-value">{{ row.roomNo || '-' }}</span>
            <span class="remark-label">备注</span>
            <span class="remark-value remark-value-text">{{ row.remark || '-' }}</span>
            <el-link type="primary" class="remark-edit" @click="openRemark(row)">修改</el-link>
          </div>
        </template>
      </el-table-column>

      <!-- 终端信息：厂家 / 型号 / 硬件固件版本 / PON 类型 + 光功率，独立组件承载 -->
      <el-table-column label="终端信息" min-width="200">
        <device-identity-cell
          slot="header"
          mode="header"
          :sort-key="sortKey"
          :sort-order="sortOrder"
          :optic-filter="opticFilter"
          @sort="sortBy"
          @update:opticFilter="v => opticFilter = v"
        />
        <template slot-scope="{ row }">
          <device-identity-cell :row="row" :biz="bizOf(row.sn)" />
        </template>
      </el-table-column>

      <el-table-column label="业务状态" align="center">
        <el-table-column label="WAN 连接" min-width="130">
          <template slot-scope="{ row }">
            <el-link
              v-if="wanList(row.sn).length"
              type="primary"
              @click="openWanParams(row.sn)"
            >
              <el-tag type="primary" size="mini" class="wan-count">{{ wanList(row.sn).length }}</el-tag>
              <span>条 WAN 连接</span>
            </el-link>
            <span v-else class="cell-empty">-</span>
            <!-- 机型适配入口：未适配的给适配入口，已适配的给绿标 -->
            <div v-if="!adaptOf(row.sn).adapted" class="adapt-line">
              <el-button type="text" size="mini" class="adapt-btn" @click="openAdapt(row)">
                <i class="el-icon-cpu" /> 适配
              </el-button>
            </div>
            <div v-else class="adapt-line">
              <span class="adapt-ok"><i class="el-icon-circle-check" /> 已适配</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="WIFI 信息" min-width="140">
          <template slot-scope="{ row }">
            <template v-if="wifiList(row.sn).length">
              <el-tag
                v-for="item in wifiList(row.sn)"
                :key="item.path"
                size="mini"
                class="wifi-tag"
              >{{ item.params.SSID || '-' }}</el-tag>
            </template>
            <span v-else class="cell-empty">-</span>
          </template>
        </el-table-column>
        <el-table-column label="SIP 信息" min-width="150">
          <template slot-scope="{ row }">
            <template v-if="sipList(row.sn).length">
              <div v-for="item in sipList(row.sn)" :key="item.path" class="biz-row">
                <el-tag size="mini" :type="sipRegistered(item.params) ? 'success' : 'danger'">
                  {{ sipStatusText(item.params) }}
                </el-tag>
                <span class="biz-name">{{ item.params.UserName || '-' }}</span>
              </div>
            </template>
            <span v-else class="cell-empty">-</span>
          </template>
        </el-table-column>
        <el-table-column label="设备状态" width="80" align="center">
          <template slot-scope="{ row }">
            <el-tag :type="isOnline(row) ? 'success' : 'danger'" size="mini">
              {{ isOnline(row) ? '在线' : '离线' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="上报的IP" min-width="120">
          <template slot-scope="{ row }">
            <span>{{ wanIp(row.sn) }}</span>
          </template>
        </el-table-column>
      </el-table-column>

      <el-table-column label="注册信息" align="center">
        <el-table-column label="注册时间" width="150">
          <template slot-scope="{ row }">
            <span>{{ formatTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最近上报" width="150">
          <template slot-scope="{ row }">
            <span>{{ formatTime(row.lastContactTime) }}</span>
          </template>
        </el-table-column>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template slot-scope="{ row }">
          <el-dropdown trigger="click" size="mini" @command="cmd => handleDeviceCommand(cmd, row)">
            <el-button type="text" size="mini">
              业务/操作<i class="el-icon-arrow-down el-icon--right" />
            </el-button>
            <el-dropdown-menu slot="dropdown">
              <el-dropdown-item command="business" icon="el-icon-connection">业务信息</el-dropdown-item>
              <el-dropdown-item command="probe" icon="el-icon-search">诊断(GetRPCMethods)</el-dropdown-item>
              <el-dropdown-item command="reboot" icon="el-icon-refresh-right" divided>重启设备</el-dropdown-item>
              <el-dropdown-item command="factoryReset" icon="el-icon-delete">恢复出厂</el-dropdown-item>
            </el-dropdown-menu>
          </el-dropdown>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      :current-page.sync="query.page"
      :page-size.sync="query.size"
      :page-sizes="[10, 20, 50, 100]"
      :total="total"
      layout="total, sizes, prev, pager, next, jumper"
      @size-change="handleSearch"
      @current-change="load"
    />

    <!-- 右下角批量操作工具条 -->
    <div class="batch-bar">
      <span class="batch-label">模版关键字</span>
      <el-select
        v-model="selectedTemplate"
        filterable
        clearable
        placeholder="== 业务模版 =="
        size="small"
        style="width:160px"
      >
        <el-option-group label="== 配置模板 ==">
          <el-option
            v-for="t in templates"
            :key="t.id"
            :label="t.name"
            :value="t.id"
          >
            <span>{{ t.name }}</span>
            <span class="member-hint">{{ t.categoryLabel || t.category }}</span>
          </el-option>
        </el-option-group>
      </el-select>
      <el-button size="small" type="primary" plain @click="batchDispatchTemplate">批量下发模版</el-button>

      <el-dropdown trigger="click" @command="cmd => handleBatchCommand(cmd)">
        <el-button size="small">一键工单<i class="el-icon-caret-top el-icon--right" /></el-button>
        <el-dropdown-menu slot="dropdown">
          <el-dropdown-item command="worklist-record" icon="el-icon-tickets">工单记录</el-dropdown-item>
          <el-dropdown-item command="worklist-batch" icon="el-icon-s-order" divided>批量开通工单</el-dropdown-item>
          <el-dropdown-item command="worklist-repair" icon="el-icon-s-tools">批量检修工单</el-dropdown-item>
        </el-dropdown-menu>
      </el-dropdown>

      <el-dropdown trigger="click" @command="cmd => handleBatchCommand(cmd)">
        <el-button size="small">更多...<i class="el-icon-caret-top el-icon--right" /></el-button>
        <el-dropdown-menu slot="dropdown">
          <el-dropdown-item command="query-admin-pwd">查询超管密码</el-dropdown-item>
          <el-dropdown-item command="set-admin-pwd">设置超管密码</el-dropdown-item>
          <el-dropdown-item command="query-telnet" divided>开启/查询 Telnet</el-dropdown-item>
          <el-dropdown-item command="close-telnet">关闭 Telnet</el-dropdown-item>
          <el-dropdown-item command="open-wan-access" divided>开启 WAN 侧访问</el-dropdown-item>
          <el-dropdown-item command="close-wan-access">关闭 WAN 侧访问</el-dropdown-item>
          <el-dropdown-item command="toggle-wifi">一键开启/关闭 WiFi</el-dropdown-item>
          <el-dropdown-item command="disable-sip">禁用 SIP 电话</el-dropdown-item>
          <el-dropdown-item command="del-wans">删除所有WAN连接(TR069除外)</el-dropdown-item>
          <el-dropdown-item command="unlock-net">解除上网限制(将重启光猫)</el-dropdown-item>
          <el-dropdown-item command="diagnose">诊断测试</el-dropdown-item>
          <el-dropdown-item command="clear-cache">删除设备缓存</el-dropdown-item>
          <el-dropdown-item command="mark-swap">标记并置换光猫</el-dropdown-item>
          <el-dropdown-item command="factory-clear" divided>恢复出厂并清零（不再接受汇报）</el-dropdown-item>
          <el-dropdown-item command="factory-reset">恢复出厂配置</el-dropdown-item>
          <el-dropdown-item command="deep-factory-reset">深度恢复出厂</el-dropdown-item>
          <el-dropdown-item command="deep-factory-clear">深度恢复出厂并清零（不再接受汇报）</el-dropdown-item>
        </el-dropdown-menu>
      </el-dropdown>

      <el-dropdown trigger="click" @command="cmd => handleAdvancedCommand(cmd)">
        <el-button size="small">高级...<i class="el-icon-caret-top el-icon--right" /></el-button>
        <el-dropdown-menu slot="dropdown">
          <el-dropdown-item command="export">导出设备</el-dropdown-item>
          <el-dropdown-item command="custom-param">下发自定义 TR069 参数值</el-dropdown-item>
          <el-dropdown-item command="debug">调试指令</el-dropdown-item>
          <el-dropdown-item command="telnet">执行 Telnet 命令</el-dropdown-item>
          <el-dropdown-item command="strip-ssid">去除 WiFi SSID 前缀</el-dropdown-item>
          <el-dropdown-item command="fix-sip">修复 SIP 注册错误</el-dropdown-item>
          <el-dropdown-item command="fix-bridge">修复 Internet 桥接/DHCP 透传</el-dropdown-item>
          <el-dropdown-item command="export-config">导出配置文件</el-dropdown-item>
          <el-dropdown-item command="clone-config">克隆配置文件</el-dropdown-item>
          <el-dropdown-item command="force-report">强制上报/上线</el-dropdown-item>
          <el-dropdown-item command="refresh">刷新状态</el-dropdown-item>
        </el-dropdown-menu>
      </el-dropdown>

      <el-dropdown trigger="click" @command="cmd => handleOltCommand(cmd)">
        <el-button size="small">OLT融合管理<i class="el-icon-caret-top el-icon--right" /></el-button>
        <el-dropdown-menu slot="dropdown">
          <el-dropdown-item command="olt-mgr" icon="el-icon-s-platform">OLT/ONU 管理</el-dropdown-item>
          <el-dropdown-item command="olt-topo" icon="el-icon-share" divided>OLT 拓扑（开发中）</el-dropdown-item>
        </el-dropdown-menu>
      </el-dropdown>

      <el-button size="small" type="warning" plain @click="batchReboot">重启</el-button>
    </div>

    <!-- 下发自定义 TR069 参数弹窗 -->
    <el-dialog title="下发自定义 TR069 参数值" :visible.sync="customParamVisible" width="640px">
      <el-form label-width="100px" size="small">
        <el-form-item label="目标设备">
          <span>{{ customParamScope }}</span>
        </el-form-item>
        <el-form-item v-for="(row, idx) in customParamRows" :key="idx">
          <div class="custom-param-row">
            <el-input v-model="row.name" placeholder="参数路径，如 InternetGatewayDevice.DeviceInfo.X_..." style="width:56%" />
            <el-input v-model="row.value" placeholder="值" style="width:22%" />
            <el-select v-model="row.type" style="width:20%">
              <el-option label="xsd:string" value="xsd:string" />
              <el-option label="xsd:boolean" value="xsd:boolean" />
              <el-option label="xsd:int" value="xsd:int" />
              <el-option label="xsd:unsignedInt" value="xsd:unsignedInt" />
            </el-select>
            <el-button type="text" icon="el-icon-delete" @click="customParamRows.splice(idx, 1)" />
          </div>
        </el-form-item>
        <el-form-item>
          <el-button size="mini" icon="el-icon-plus" @click="customParamRows.push({ name: '', value: '', type: 'xsd:string' })">加一行</el-button>
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button type="primary" @click="submitCustomParam">下发</el-button>
        <el-button @click="customParamVisible = false">取消</el-button>
      </span>
    </el-dialog>

    <!-- 调试指令弹窗 -->
    <el-dialog title="调试指令" :visible.sync="debugVisible" width="620px">
      <el-form label-width="100px" size="small">
        <el-form-item label="目标设备">
          <span>{{ customParamScope }}</span>
        </el-form-item>
        <el-form-item label="RPC 方法">
          <el-select v-model="debugForm.rpc" filterable allow-create style="width:100%">
            <el-option v-for="m in rpcMethods" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="参数 JSON">
          <el-input v-model="debugForm.args" type="textarea" :rows="5" placeholder="{&quot;parameterNames&quot;:[&quot;InternetGatewayDevice.&quot;]}" />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button type="primary" @click="submitDebug">下发</el-button>
        <el-button @click="debugVisible = false">取消</el-button>
      </span>
    </el-dialog>

    <el-dialog title="在线会话明细" :visible.sync="onlineVisible" width="900px">
      <el-table :data="onlineList" border size="small" max-height="460">
        <el-table-column label="序列号" prop="sn" min-width="180" show-overflow-tooltip />
        <el-table-column label="OUI" prop="oui" width="90" />
        <el-table-column label="状态" prop="state" width="110" />
        <el-table-column label="软件版本" prop="softwareVersion" width="130" show-overflow-tooltip />
        <el-table-column label="待发消息" prop="pendingCount" width="90" />
        <el-table-column label="最近上报" min-width="170">
          <template slot-scope="{ row }">
            <span>{{ formatTime(row.lastContactTime) }}</span>
          </template>
        </el-table-column>
      </el-table>
      <span slot="footer">
        <el-button @click="onlineVisible = false">关闭</el-button>
      </span>
    </el-dialog>

    <el-dialog
      :title="'设备业务信息 - ' + businessSn"
      :visible.sync="businessVisible"
      width="960px"
    >
      <device-business-panel v-if="businessVisible" :sn="businessSn" />
    </el-dialog>

    <el-dialog
      :title="'WAN 连接参数 - ' + wanDialogSn"
      :visible.sync="wanVisible"
      width="760px"
      top="5vh"
    >
      <div v-if="!wanList(wanDialogSn).length" class="cell-empty">该设备暂无 WAN 连接数据，请先在业务信息里重新采集</div>
      <el-card
        v-for="item in wanList(wanDialogSn)"
        :key="item.path"
        shadow="never"
        class="wan-param-card"
      >
        <div slot="header" class="wan-param-head">
          <span class="wan-param-name">WAN - {{ item.params.Name || wanShort(item.path) }}</span>
          <el-tag size="mini" :type="wanTagType(item.params.ConnectionStatus)">
            {{ wanText(item.params.ConnectionStatus) }}
          </el-tag>
          <span v-if="uptimeText(item.params.Uptime)" class="wan-uptime">{{ uptimeText(item.params.Uptime) }}</span>
        </div>
        <div class="wan-param-row">
          <span class="k">连接模式/地址类型:</span>
          <span class="v">{{ connMode(item.params) }}</span>
        </div>
        <div class="wan-param-row">
          <span class="k">业务模式:</span>
          <span class="v v-strong">{{ serviceMode(item) }}</span>
        </div>
        <div class="wan-param-row">
          <span class="k">MAC地址:</span>
          <span class="v">{{ showVal(item.params.MACAddress) }}</span>
        </div>
        <div class="wan-param-row">
          <span class="k">VLAN ID:</span>
          <span class="v v-strong">{{ showVal(item.params.VLANIDMark) }}</span>
        </div>
        <div class="wan-param-row">
          <span class="k">IP地址/子网掩码:</span>
          <span class="v">
            <span class="v-ip">{{ showVal(item.params.ExternalIPAddress) }}</span>
            / {{ showVal(item.params.SubnetMask) }}
          </span>
        </div>
        <div class="wan-param-row">
          <span class="k">网关:</span>
          <span class="v">{{ showVal(item.params.DefaultGateway) }}</span>
        </div>
        <div v-if="item.params.UserName" class="wan-param-row">
          <span class="k">拨号账号:</span>
          <span class="v">{{ item.params.UserName }}</span>
        </div>
        <el-collapse class="wan-more">
          <el-collapse-item title="全部参数">
            <div v-for="(value, name) in item.params" :key="name" class="wan-all-row">
              <span class="k">{{ name }}</span>
              <span class="v">{{ showVal(value) }}</span>
            </div>
          </el-collapse-item>
        </el-collapse>
      </el-card>
      <span slot="footer">
        <el-button @click="wanVisible = false">关闭</el-button>
      </span>
    </el-dialog>

    <el-dialog
      :title="'机型适配 - ' + (adaptRow ? (adaptRow.productClass || adaptRow.deviceType || '') : '')"
      :visible.sync="adaptVisible"
      width="600px"
      top="6vh"
    >
      <div v-if="adaptRow" class="adapt-device-line">
        <span class="k">样机：</span>
        <span>{{ adaptRow.sn }}</span>
        <span class="adapt-meta">
          {{ adaptRow.manufacturer || '-' }} / {{ adaptRow.productClass || adaptRow.deviceType || '-' }}
          / {{ adaptRow.softwareVersion || '-' }}
        </span>
      </div>
      <el-form label-width="150px" size="small" class="adapt-form">
        <el-form-item label="运营商">
          <el-select v-model="adaptForm.isp" style="width:220px">
            <el-option v-for="o in ispOptions" :key="o.v" :label="o.t" :value="o.v" />
          </el-select>
        </el-form-item>
        <el-form-item label="网口数量">
          <el-select v-model="adaptForm.lanPorts" style="width:220px">
            <el-option v-for="o in portOptions" :key="o" :label="o" :value="o" />
          </el-select>
        </el-form-item>
        <el-form-item label="网口速率">
          <el-select v-model="adaptForm.lanSpeed" style="width:220px">
            <el-option v-for="o in speedOptions" :key="o" :label="o" :value="o" />
          </el-select>
        </el-form-item>
        <el-form-item label="SIP 数量">
          <el-select v-model="adaptForm.sipCount" style="width:220px">
            <el-option v-for="o in portOptions" :key="o" :label="o" :value="o" />
          </el-select>
        </el-form-item>
        <el-form-item label="WIFI 规格">
          <el-select v-model="adaptForm.wifiSpec" style="width:220px">
            <el-option v-for="o in wifiOptions" :key="o" :label="o" :value="o" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="adaptResult" label="测试结果">
          <div class="adapt-result" :class="adaptResult.supported ? 'is-ok' : 'is-bad'">
            <div class="adapt-result-head">
              <i :class="adaptResult.supported ? 'el-icon-success' : 'el-icon-error'" />
              {{ adaptResult.supported ? '能力全部支持，可保存到库' : '存在不满足项' }}
              <el-tag size="mini" type="info">采集：{{ adaptResult.collectStatus }}</el-tag>
            </div>
            <div class="adapt-result-row">
              实测能力：网口 {{ adaptResult.detected.lanPortCount }} 个
              <span v-if="adaptResult.detected.lanSpeedMbps">
                / 最高 {{ adaptResult.detected.lanSpeedMbps }}Mbps
              </span>
              / SIP {{ adaptResult.detected.sipCount }} 路
              / WIFI {{ adaptResult.detected.wifiBands.length ? adaptResult.detected.wifiBands.join('+') : '无' }}
            </div>
            <!-- 真下发到光猫的试探记录：写入的是参数当前值，不改变配置 -->
            <el-table
              v-if="adaptResult.probes && adaptResult.probes.length"
              :data="adaptResult.probes"
              size="mini"
              border
              class="adapt-probe-table"
            >
              <el-table-column label="能力" prop="capability" width="90" />
              <el-table-column label="下发参数路径" prop="path" min-width="220" show-overflow-tooltip />
              <el-table-column label="写入值" prop="value" width="90" show-overflow-tooltip />
              <el-table-column label="光猫返回" min-width="200" show-overflow-tooltip>
                <template slot-scope="{ row }">
                  <el-tag size="mini" :type="row.failed ? 'danger' : 'success'">
                    {{ row.failed ? '失败' : '通过' }}
                  </el-tag>
                  <span class="adapt-probe-status">{{ row.status }}</span>
                </template>
              </el-table-column>
            </el-table>
            <div v-for="(r, i) in adaptResult.reasons" :key="i" class="adapt-reason">{{ r }}</div>
          </div>
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button type="warning" plain :loading="adaptTesting" @click="runAdaptTest">测试</el-button>
        <el-button
          type="primary"
          :loading="adaptSaving"
          :disabled="!(adaptResult && adaptResult.supported)"
          @click="submitAdaptSave"
        >保存到库</el-button>
        <el-button @click="adaptVisible = false">关闭</el-button>
      </span>
    </el-dialog>

    <el-dialog
      :title="detailTitle"
      :visible.sync="detailVisible"
      width="620px"
      top="6vh"
    >
      <div class="detail-list">
        <div v-for="row in detailRows(detailRow)" :key="row.label" class="detail-row">
          <span class="k">{{ row.label }}:</span>
          <span class="v" :class="{ 'v-strong': row.strong }">{{ row.value }}</span>
        </div>
      </div>
      <span slot="footer">
        <el-button type="primary" @click="goDetail(detailRow && detailRow.sn)">完整详情</el-button>
        <el-button @click="detailVisible = false">关闭</el-button>
      </span>
    </el-dialog>

    <!-- 房间号 / 备注：房间号用于下发时替换模板里的 %room% -->
    <el-dialog title="修改房间号与备注" :visible.sync="remarkVisible" width="460px" append-to-body>
      <el-form label-width="72px">
        <el-form-item label="序列号">
          <span class="remark-sn">{{ remarkRow ? remarkRow.sn : '-' }}</span>
        </el-form-item>
        <el-form-item label="房间号">
          <el-input
            v-model="remarkForm.roomNo"
            placeholder="如 0001，下发时替换 %room%"
            maxlength="32"
            clearable
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="remarkForm.remark"
            type="textarea"
            :rows="3"
            placeholder="设备说明，可留空"
            maxlength="255"
          />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="remarkVisible = false">取消</el-button>
        <el-button type="primary" :loading="remarkSaving" @click="submitRemark">保存</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import {
  fetchDevicePage, fetchOnlineDevices, dispatchRpc, fetchDeviceBusinessBatch,
  fetchAdaptStatus, testAdaptation, saveAdaptation, updateDeviceRemark
} from '@/api/device'
import { fetchRpcResult } from '@/api/rpc'
import { fetchConfigTemplates } from '@/api/configTemplate'
import { RPC_METHODS } from '@/api/device'
import DeviceBusinessPanel from '@/components/DeviceBusinessPanel'
import DeviceIdentityCell from '@/components/DeviceIdentityCell.vue'
import { ponTypeOf, opticStateOf } from '@/utils/deviceOptic'

export default {
  name: 'DeviceList',
  components: { DeviceBusinessPanel, DeviceIdentityCell },
  data() {
    return {
      loading: false,
      list: [],
      total: 0,
      bizMap: {},
      // 机型适配状态：sn -> { adapted, configPath, ... }
      adaptMap: {},
      adaptVisible: false,
      adaptRow: null,
      adaptTesting: false,
      adaptSaving: false,
      adaptResult: null,
      adaptForm: { isp: 'standard', lanPorts: '1', lanSpeed: '自动', sipCount: '无', wifiSpec: '2.4G' },
      ispOptions: [
        { v: 'CT', t: '电信' }, { v: 'CU', t: '联通' },
        { v: 'CMCC', t: '移动' }, { v: 'standard', t: '标准/通用' }
      ],
      portOptions: ['无', '1', '2', '3', '4', '8'],
      speedOptions: ['无', '自动', '100M', '1000M', '2500M'],
      wifiOptions: ['无', '2.4G', '2.4G+5.8G'],
      wanVisible: false,
      wanDialogSn: '',
      detailVisible: false,
      detailRow: null,
      sortKey: '',
      sortOrder: '',
      opticFilter: '',
      onlineCount: 0,
      onlineList: [],
      onlineVisible: false,
      businessVisible: false,
      businessSn: '',
      // 批量操作工具条
      selection: [],
      templates: [],
      selectedTemplate: '',
      customParamVisible: false,
      customParamRows: [{ name: '', value: '', type: 'xsd:string' }],
      debugVisible: false,
      debugForm: { rpc: 'GetParameterValues', args: '{}' },
      rpcMethods: RPC_METHODS,
      query: {
        page: 1,
        size: 20,
        sn: '',
        isp: '',
        deviceType: ''
      },
      // 房间号 / 备注弹窗
      remarkVisible: false,
      remarkRow: null,
      remarkForm: { roomNo: '', remark: '' },
      remarkSaving: false
    }
  },
  computed: {
    // 自定义参数/调试指令弹窗里的目标设备描述
    customParamScope() {
      const sns = this.selectedSns()
      if (!sns.length) return '（未勾选设备，请先在列表勾选）'
      return sns.length === 1 ? sns[0] : '已勾选 ' + sns.length + ' 台设备'
    },
    detailTitle() {
      const row = this.detailRow
      if (!row) return '设备详情'
      return (row.oui ? row.oui + '-' : '') + row.sn + ' 详情'
    },
    // 光模块筛选 + 表头字段排序，只作用于当前页数据
    displayList() {
      let rows = this.list
      if (this.opticFilter) {
        rows = rows.filter(r => opticStateOf(this.bizOf(r.sn)) === this.opticFilter)
      }
      if (this.sortKey) {
        const key = this.sortKey
        const dir = this.sortOrder === 'asc' ? 1 : -1
        rows = [...rows].sort((a, b) =>
          this.sortVal(a, key).localeCompare(this.sortVal(b, key), 'zh') * dir)
      }
      return rows
    }
  },
  created() {
    this.load()
    this.loadOnline()
    this.loadTemplates()
  },
  methods: {
    async load() {
      this.loading = true
      try {
        const res = await fetchDevicePage(this.query)
        const data = res.data || {}
        this.list = data.items || []
        this.total = data.total || 0
        this.loadBiz()
        this.loadAdaptStatus()
      } catch (e) {
        this.list = []
        this.total = 0
      } finally {
        this.loading = false
      }
    },
    // 房间号 / 备注：弹窗编辑，保存成功后同步回列表行
    openRemark(row) {
      this.remarkRow = row
      this.remarkForm = { roomNo: row.roomNo || '', remark: row.remark || '' }
      this.remarkVisible = true
    },
    async submitRemark() {
      const row = this.remarkRow
      if (!row || !row.sn) {
        return
      }
      const payload = {
        roomNo: this.remarkForm.roomNo || '',
        remark: this.remarkForm.remark || ''
      }
      this.remarkSaving = true
      try {
        await updateDeviceRemark(row.sn, payload)
        row.roomNo = payload.roomNo
        row.remark = payload.remark
        this.$message.success('已保存')
        this.remarkVisible = false
      } catch (e) {
        this.$message.error('保存失败: ' + ((e && e.message) || '未知错误'))
      } finally {
        this.remarkSaving = false
      }
    },
    // 整页设备的业务概要一次拉回，列表直接铺开 WAN/WIFI/IP，不再逐行请求
    async loadBiz() {
      const sns = this.list.map(d => d.sn)
      if (!sns.length) {
        this.bizMap = {}
        return
      }
      try {
        const res = await fetchDeviceBusinessBatch(sns)
        this.bizMap = res.data || {}
      } catch (e) {
        this.bizMap = {}
      }
    },
    bizOf(sn) {
      return this.bizMap[sn] || {}
    },
    // 整页设备的机型适配状态一次拉回：有型号能力树 XML = 已适配
    async loadAdaptStatus() {
      const sns = this.list.map(d => d.sn)
      if (!sns.length) {
        this.adaptMap = {}
        return
      }
      try {
        const res = await fetchAdaptStatus(sns)
        this.adaptMap = res.data || {}
      } catch (e) {
        this.adaptMap = {}
      }
    },
    adaptOf(sn) {
      return this.adaptMap[sn] || {}
    },
    openAdapt(row) {
      this.adaptRow = row
      this.adaptResult = null
      this.adaptForm.isp = row.isp || 'standard'
      this.adaptVisible = true
    },
    async runAdaptTest() {
      this.adaptTesting = true
      this.adaptResult = null
      try {
        const res = await testAdaptation(this.adaptRow.sn, this.adaptForm)
        this.adaptResult = res.data || {}
        if (!this.adaptResult.detected) this.adaptResult.detected = { wifiBands: [] }
        if (!this.adaptResult.probes) this.adaptResult.probes = []
      } catch (e) {
        this.$message.error('测试失败：' + (e.message || '请确认设备在线'))
      } finally {
        this.adaptTesting = false
      }
    },
    async submitAdaptSave() {
      if (!this.adaptRow) return
      this.adaptSaving = true
      try {
        await saveAdaptation(this.adaptRow.sn, this.adaptForm)
        this.$message.success('机型适配已入库，同型号设备下次上线直接调库')
        this.adaptVisible = false
        this.loadAdaptStatus()
      } catch (e) {
        this.$message.error(e.message || '保存失败')
      } finally {
        this.adaptSaving = false
      }
    },
    wanList(sn) {
      return this.bizOf(sn).wan || []
    },
    wifiList(sn) {
      return this.bizOf(sn).wifi || []
    },
    sipList(sn) {
      return this.bizOf(sn).sip || []
    },
    sipRegistered(params) {
      const s = String(params.SIPRegisterStatus || '').toLowerCase()
      return s === 'registered' || s === 'ok' || s === 'up' || s === '成功'
    },
    sipStatusText(params) {
      const raw = params.SIPRegisterStatus
      if (!raw) return '未配置'
      return this.sipRegistered(params) ? '已注册' : '未注册'
    },
    // 管理地址：由后端从 WAN_TR069 通道算出（业务快照的 ip 字段），前端不做判定
    wanIp(sn) {
      return this.bizOf(sn).ip || '-'
    },
    isOnline(row) {
      const status = String(row.status || '').toLowerCase()
      return status.includes('online') || status.includes('在线') || row.online === true
    },
    // ...WANPPPConnection.1. -> PPP 1，设备没上报 Name 时的兜底叫法
    wanShort(path) {
      const matched = path.match(/WAN(PPP|IP)Connection\.(\d+)\.$/)
      return matched ? 'WAN ' + matched[1] + ' ' + matched[2] : 'WAN'
    },
    wanTagType(status) {
      const s = String(status || '').toLowerCase()
      if (s === 'connected' || s === 'up') return 'success'
      if (s === 'disconnected' || s === 'down') return 'danger'
      return 'info'
    },
    wanText(status) {
      const s = String(status || '').toLowerCase()
      if (s === 'connected' || s === 'up') return '已连接'
      if (s === 'disconnected' || s === 'down') return '未连接'
      return status || '-'
    },

    // ---------------- WAN 连接参数弹窗 ----------------
    openWanParams(sn) {
      this.wanDialogSn = sn
      this.wanVisible = true
    },

    // ---------------- 设备详情弹窗（设备标识列点 SN 打开） ----------------
    openDetail(row) {
      this.detailRow = row
      this.detailVisible = true
    },
    detailRows(row) {
      if (!row) return []
      const biz = this.bizOf(row.sn)
      const adapt = this.adaptOf(row.sn) || {}
      const mac = this.paramValue(biz.device || [], 'MACAddress')
      // 目录名与原始申报值都映射：适配档目录用归一化的 CM/CT/CU，
      // yml profile 里的 isp 是申报时的原始值（如 CMCC）
      const ispNames = {
        CMCC: '中国移动', CM: '中国移动',
        CT: '中国电信', CU: '中国联通',
        standard: '标准/通用'
      }
      // 运营商单独一行：以设备台账登记值为准（适配时选定的运营商会写回台账），
      // 台账还没写回时退回适配档里的申报值
      const adaptIsp = row.isp || (adapt.profile && adapt.profile.isp) || adapt.adaptedIsp || adapt.isp || ''
      const ispText = ispNames[adaptIsp] || adaptIsp
      const rows = [
        { label: 'OUI', value: row.oui },
        { label: '设备 SN', value: row.sn },
        { label: 'MAC 地址', value: mac },
        { label: '运营商', value: (ispText + (row.ispVersion ? ' ' + row.ispVersion : '')).trim() },
        {
          label: '设备能力',
          value: adapt.adapted
            ? this.adaptedCapabilityText(adapt)
            : this.capabilityText(biz)
        },
        { label: '硬件版本', value: row.hardwareVersion },
        { label: '固件版本', value: row.softwareVersion },
        { label: '设备型号', value: row.productClass || row.deviceType },
        { label: '管理 IP（TR069）', value: this.wanIp(row.sn) },
        { label: '远程管理地址', value: row.connectionRequestUrl },
        { label: 'CPE USER', value: row.cpeToAcsName },
        { label: 'CWMP 版本', value: row.cwmpVersion },
        { label: 'WiFi SSID', value: (biz.wifi || []).map(w => w.params.SSID).filter(Boolean).join(' / ') },
        { label: '注册时间', value: this.formatTime(row.createdAt) },
        { label: '最近上报', value: this.formatTime(row.lastContactTime) }
      ]
      return rows.filter(r => r.value && r.value !== '-' && r.value !== '从未采集')
    },
    // 从业务快照拼设备能力：WiFi 频段 + SIP 电话
    capabilityText(biz) {
      const parts = []
      const wifi = biz.wifi || []
      if (wifi.some(w => this.wifiBand(w.params) === '2.4G')) parts.push('2.4G WiFi')
      if (wifi.some(w => this.wifiBand(w.params) === '5.8G')) parts.push('5.8G WiFi')
      if ((biz.sip || []).length) parts.push('1电话')
      return parts.join('、') || null
    },
    // 已适配设备用适配档能力展示：网口数量/速率、SIP 数量、WIFI 规格、样机来源。
    // 运营商不在这里带——详情里它是独立一行，混进能力串会让两处口径打架
    adaptedCapabilityText(adapt) {
      const p = adapt.profile || adapt
      const parts = []
      if (p.lan_ports && p.lan_ports !== '无') {
        parts.push('网口 ' + p.lan_ports + (p.lan_speed && p.lan_speed !== '无' && p.lan_speed !== '自动' ? '×' + p.lan_speed : ''))
      } else if (p.lan_ports === '无') {
        parts.push('无网口')
      }
      if (p.sip_count && p.sip_count !== '无') parts.push('SIP ' + p.sip_count + '路')
      else if (p.sip_count === '无') parts.push('无SIP')
      if (p.wifi_spec && p.wifi_spec !== '无') parts.push('WiFi ' + p.wifi_spec)
      else if (p.wifi_spec === '无') parts.push('无WiFi')
      const tail = p.sample_sn ? '（ ' + p.sample_sn + '）' : ''
      return parts.join('、') + tail
    },
    paramValue(list, name) {
      const item = (list || []).find(x => x.params && x.params[name] !== undefined)
      return item ? item.params[name] : undefined
    },
    connMode(params) {
      const type = String(params.ConnectionType || '')
      const mode = type.toLowerCase().includes('bridged') ? '桥接' : '路由'
      const addr = params.AddressingType || (type.toLowerCase().includes('ppp') ? 'PPPoE' : '-')
      return mode + ' / ' + addr
    },
    // 业务类型由后端判定（键名含 ServiceList，不区分厂商前缀）
    serviceMode(item) {
      return item.service || '-'
    },
    uptimeText(seconds) {
      const n = parseInt(seconds, 10)
      if (!n || n < 0) return ''
      const d = Math.floor(n / 86400)
      const h = Math.floor((n % 86400) / 3600)
      const m = Math.floor((n % 3600) / 60)
      return (d ? d + '天' : '') + h + '小时' + m + '分'
    },
    showVal(value) {
      return value === undefined || value === null || value === '' ? '-' : value
    },
    wifiBand(params) {
      const std = String(params.Standard || '').toLowerCase()
      return /a|ac|ax/.test(std) ? '5.8G' : '2.4G'
    },

    sortVal(row, key) {
      if (key === 'pon') return ponTypeOf(row, this.bizOf(row.sn))
      return String(row[key] || '')
    },
    // 表头字段排序，由终端信息列组件抛出
    sortBy(key) {
      if (this.sortKey !== key) {
        this.sortKey = key
        this.sortOrder = 'asc'
      } else if (this.sortOrder === 'asc') {
        this.sortOrder = 'desc'
      } else {
        this.sortKey = ''
        this.sortOrder = ''
      }
    },
    async loadOnline() {
      try {
        const res = await fetchOnlineDevices()
        this.onlineList = res.data || []
        this.onlineCount = this.onlineList.length
      } catch (e) {
        this.onlineList = []
        this.onlineCount = 0
      }
    },
    handleSearch() {
      this.query.page = 1
      this.load()
    },
    resetQuery() {
      this.query = { page: 1, size: 20, sn: '', isp: '', deviceType: '' }
      this.load()
    },
    // 完整详情：跳原来的设备详情页（能力树采集、RPC、参数树在那里）
    goDetail(sn) {
      if (!sn) return
      this.detailVisible = false
      this.$router.push({ name: 'DeviceDetail', params: { sn }})
    },

    // ---------------- 业务信息与快捷操作 ----------------
    handleDeviceCommand(cmd, row) {
      if (cmd === 'business') {
        this.openBusiness(row.sn)
        return
      }
      if (cmd === 'probe') {
        this.quickRpc(row.sn, 'GetRPCMethods', {}, false)
        return
      }
      if (cmd === 'reboot') {
        this.$confirm('确认重启设备 ' + row.sn + '？', '重启设备', { type: 'warning' })
          .then(() => this.quickRpc(row.sn, 'Reboot', {}, true))
          .catch(() => {})
        return
      }
      if (cmd === 'factoryReset') {
        this.$confirm('恢复出厂会清空设备全部配置，确认继续？', '恢复出厂', { type: 'warning' })
          .then(() => this.quickRpc(row.sn, 'FactoryReset', {}, true))
          .catch(() => {})
      }
    },
    openBusiness(sn) {
      this.businessSn = sn
      this.businessVisible = true
    },
    /**
     * 下发一条简单 RPC 并轮询到出结果为止。
     * 列表页不做复杂展示，成功/失败提示即可，任务号留给需要追查的场景。
     */
    async quickRpc(sn, rpcName, args, withTaskId) {
      try {
        const res = await dispatchRpc(sn, rpcName, args)
        const data = res.data || {}
        if (!data.queued) {
          this.$message.error('指令未受理（设备离线或队列已满）')
          return
        }
        this.$message.success('指令已受理' + (withTaskId ? '，任务号 ' + data.taskId : ''))
        this.pollQuickRpc(data.taskId)
      } catch (e) {
        this.$message.error('下发失败：' + (e.message || ''))
      }
    },
    pollQuickRpc(taskId) {
      let count = 0
      const timer = setInterval(async() => {
        count += 1
        try {
          const res = await fetchRpcResult(taskId)
          const data = res.data
          if (data && data.status === 'success') {
            clearInterval(timer)
            this.$message.success('执行成功（任务号 ' + taskId + '）')
          } else if (data && data.status === 'fail') {
            clearInterval(timer)
            this.$message.warning('执行失败：' + (data.faultString || data.faultCode || ''))
          }
        } catch (e) { /* 单次失败不打断 */ }
        if (count >= 60) {
          clearInterval(timer)
        }
      }, 3000)
    },

    formatTime(value) {
      if (!value) return '-'
      return String(value).replace('T', ' ')
    },

    // ---------------- 右下角批量操作工具条 ----------------
    handleSelectionChange(rows) {
      this.selection = rows
    },
    selectedSns() {
      return this.selection.map(r => r.sn)
    },
    // 批量操作前校验勾选，返回 SN 列表；未勾选提示并返回 null
    requireSelection() {
      const sns = this.selectedSns()
      if (!sns.length) {
        this.$message.warning('请先勾选要操作的设备')
        return null
      }
      return sns
    },
    loadTemplates() {
      fetchConfigTemplates({}).then(res => {
        this.templates = res.data || []
      }).catch(() => {
        this.templates = []
      })
    },
    batchDispatchTemplate() {
      const sns = this.requireSelection()
      if (!sns) return
      if (!this.selectedTemplate) {
        this.$message.warning('请先选择模版关键字')
        return
      }
      // 下发逻辑：按模板意图 + 型号映射生成真实参数，待后端实现后接入
      this.todoPending('批量下发模版（下发逻辑待接入）')
    },
    handleBatchCommand(cmd) {
      if (cmd === 'worklist-record') {
        this.$router.push({ name: 'WorklistInstances' })
        return
      }
      // 恢复出厂类命令走现有 FactoryReset RPC，其余待后端接入
      if (['factory-clear', 'factory-reset', 'deep-factory-reset', 'deep-factory-clear'].includes(cmd)) {
        const sns = this.requireSelection()
        if (!sns) return
        this.$confirm('恢复出厂会清空设备全部配置，对已勾选 ' + sns.length + ' 台设备执行？', '恢复出厂', { type: 'warning' })
          .then(() => sns.forEach(sn => this.quickRpc(sn, 'FactoryReset', {}, true)))
          .catch(() => {})
        return
      }
      this.todoPending('批量操作：' + cmd)
    },
    handleOltCommand(cmd) {
      if (cmd === 'olt-mgr') {
        this.$router.push({ name: 'OltManagement' })
        return
      }
      this.todoPending('OLT 融合管理')
    },
    handleAdvancedCommand(cmd) {
      const sns = this.requireSelection()
      if (!sns) return
      switch (cmd) {
        case 'export':
          this.exportDevices(sns)
          break
        case 'custom-param':
          this.customParamVisible = true
          break
        case 'debug':
          this.debugVisible = true
          break
        case 'refresh':
          this.refreshStatus()
          break
        default:
          this.todoPending('高级操作：' + cmd)
      }
    },
    submitCustomParam() {
      const rows = this.customParamRows.filter(r => r.name)
      if (!rows.length) {
        this.$message.warning('请至少填写一条参数')
        return
      }
      const sns = this.requireSelection()
      if (!sns) return
      const values = rows.map(r => [r.name, r.value, r.type])
      sns.forEach(sn => {
        dispatchRpc(sn, 'SetParameterValues', { parameterValues: values })
          .catch(() => {})
      })
      this.$message.success('已向 ' + sns.length + ' 台设备下发 SetParameterValues')
    },
    submitDebug() {
      const sns = this.requireSelection()
      if (!sns) return
      let args = {}
      try {
        args = JSON.parse(this.debugForm.args || '{}')
      } catch (e) {
        this.$message.error('参数 JSON 格式不正确')
        return
      }
      sns.forEach(sn => this.quickRpc(sn, this.debugForm.rpc, args, true))
      this.debugVisible = false
    },
    exportDevices(sns) {
      const rows = sns.length ? this.list.filter(r => sns.includes(r.sn)) : this.displayList
      const header = ['SN', 'OUI', '运营商', '型号', '硬件版本', '固件版本', '状态', '注册时间', '最近上报']
      const lines = rows.map(r => [
        r.sn, r.oui, r.isp, r.productClass || r.deviceType, r.hardwareVersion,
        r.softwareVersion, this.isOnline(r) ? '在线' : '离线',
        this.formatTime(r.createdAt), this.formatTime(r.lastContactTime)
      ])
      const csv = [header].concat(lines).map(l => l.join(',')).join('\n')
      const blob = new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8' })
      const link = document.createElement('a')
      link.href = URL.createObjectURL(blob)
      link.download = 'devices.csv'
      link.click()
      URL.revokeObjectURL(link.href)
    },
    refreshStatus() {
      this.load()
      this.loadOnline()
      this.$message.success('已刷新列表与在线状态')
    },
    // 批量重启：勾选设备逐台下发 Reboot
    batchReboot() {
      const sns = this.requireSelection()
      if (!sns) return
      this.$confirm('确认重启已勾选的 ' + sns.length + ' 台设备？', '批量重启', { type: 'warning' })
        .then(() => sns.forEach(sn => this.quickRpc(sn, 'Reboot', {}, true)))
        .catch(() => {})
    },
    todoPending(text) {
      this.$message.info(text + '：功能开发中，后端接口接入后启用')
    }
  }
}
</script>

<style scoped>
.filter-card {
  margin-bottom: 16px;
}
.online-bar {
  color: #606266;
  font-size: 13px;
}
.pager {
  margin-top: 16px;
  text-align: right;
}
/* 右下角批量操作工具条 */
.batch-bar {
  margin-top: 10px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}
.batch-bar .batch-label {
  color: #909399;
  font-size: 12px;
  margin-right: 2px;
}
.batch-bar .el-button + .el-button {
  margin-left: 0;
}
.custom-param-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.sn-text {
  color: #303133;
  font-weight: 600;
}
.member-hint {
  float: right;
  color: #8492a6;
  font-size: 12px;
}
.biz-progress {
  border-top: 1px solid #ebeef5;
  padding-top: 10px;
  font-size: 13px;
  color: #606266;
}
/* 设备标识列：主行 + 两行补充信息 */
.cell-main {
  font-weight: 600;
  color: #303133;
}
.cell-sub {
  color: #909399;
  font-size: 12px;
  margin-top: 2px;
}
/* 房间号 / 备注：就地编辑，失焦即保存 */
.cell-remark {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 4px;
}
.remark-label {
  color: #909399;
  font-size: 12px;
  flex-shrink: 0;
}
.remark-value {
  color: #303133;
  font-size: 12px;
  flex-shrink: 0;
}
/* 备注可能较长，超出省略，完整内容在弹窗里看 */
.remark-value-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 96px;
}
.remark-edit {
  margin-left: auto;
  font-size: 12px;
  flex-shrink: 0;
}
.remark-sn {
  color: #606266;
  word-break: break-all;
}
.cell-empty {
  color: #c0c4cc;
}
.mode-tip {
  color: #909399;
  font-size: 12px;
  line-height: 1.4;
  margin-top: 2px;
}
.wan-count {
  margin-right: 4px;
}
/* 机型适配标识与弹窗 */
.adapt-line {
  margin-top: 4px;
}
.adapt-tag {
  cursor: pointer;
}
.adapt-btn {
  color: #ff0000;
  font-size: 16px;
  font-weight: bold;
  padding: 0;
}
.adapt-ok {
  color: #67c23a;
  font-size: 13px;
  font-weight: bold;
}
.adapt-device-line {
  margin-bottom: 12px;
  font-size: 13px;
}
.adapt-device-line .k {
  color: #909399;
}
.adapt-meta {
  margin-left: 8px;
  color: #909399;
}
.adapt-result {
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 8px 10px;
  font-size: 12px;
  line-height: 20px;
}
.adapt-result.is-ok {
  border-color: #67c23a;
  background: #f0f9eb;
}
.adapt-result.is-bad {
  border-color: #f56c6c;
  background: #fef0f0;
}
.adapt-result-head i {
  margin-right: 4px;
}
.is-ok .adapt-result-head i {
  color: #67c23a;
}
.is-bad .adapt-result-head i {
  color: #f56c6c;
}
.adapt-result-row {
  color: #606266;
}
.adapt-reason {
  color: #f56c6c;
}
.adapt-probe-table {
  margin-top: 6px;
}
.adapt-probe-status {
  margin-left: 6px;
  color: #606266;
}
.biz-row {
  display: flex;
  align-items: center;
  margin-bottom: 3px;
}
.biz-name {
  margin-left: 6px;
  font-size: 12px;
  color: #606266;
}
.wifi-tag {
  margin: 0 4px 4px 0;
}
/* ---- WAN 连接参数弹窗 ---- */
.wan-param-card {
  margin-bottom: 10px;
}
.wan-param-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.wan-param-name {
  font-weight: 600;
  color: #2f7d32;
}
.wan-uptime {
  color: #909399;
  font-size: 12px;
}
.wan-param-row {
  display: flex;
  padding: 5px 0;
  border-bottom: 1px solid #f2f6fc;
  font-size: 13px;
  align-items: center;
}
.wan-param-row .k {
  width: 150px;
  flex-shrink: 0;
  color: #67a04e;
}
.wan-param-row .v {
  color: #303133;
  word-break: break-all;
}
.v-strong {
  font-weight: 600;
}
.v-ip {
  color: #2f7d32;
  font-weight: 600;
}
.wan-more {
  border-top: 1px solid #ebeef5;
  margin-top: 6px;
}
.wan-all-row {
  display: flex;
  padding: 2px 0;
  font-size: 12px;
  border-bottom: 1px dashed #ebeef5;
}
.wan-all-row .k {
  width: 260px;
  color: #909399;
  flex-shrink: 0;
}
.wan-all-row .v {
  color: #303133;
  word-break: break-all;
}
/* ---- 设备详情弹窗 ---- */
.detail-row {
  display: flex;
  padding: 6px 0;
  border-bottom: 1px dashed #ebeef5;
  font-size: 13px;
}
.detail-row .k {
  width: 130px;
  flex-shrink: 0;
  color: #909399;
}
.detail-row .v {
  color: #303133;
  word-break: break-all;
}
.detail-row .v-strong {
  font-weight: 600;
}
</style>
