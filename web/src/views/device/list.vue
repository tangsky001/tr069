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
            <el-option label="广电 CBN" value="CBN" />
            <el-option label="通用 standard" value="standard" />
          </el-select>
        </el-form-item>
        <el-form-item label="设备类型">
          <el-input v-model="query.deviceType" placeholder="如 ADSL_4+1" clearable style="width:160px" />
        </el-form-item>
        <el-form-item label="房间号">
          <el-input v-model="query.roomNo" placeholder="支持模糊匹配" clearable style="width:140px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="handleSearch">查询</el-button>
          <el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
      <div class="online-bar">
        设备总数：<b>{{ deviceStat.total }}</b> 台
        <span class="stat-online">在线 <b>{{ deviceStat.online }}</b></span>
        <span class="stat-offline">离线 <b>{{ deviceStat.offline }}</b></span>
        <span class="online-bar-sep">|</span>
        当前在线会话：<b>{{ onlineCount }}</b> 台
        <el-button type="text" icon="el-icon-refresh" @click="refreshOnlineBar">刷新</el-button>
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
        <el-table-column label="WAN 连接" min-width="165">
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
            <!-- LAN 网口：设备申报几个口就列几个，插线绿色、未插灰色，紧跟在 WAN 连接下面 -->
            <div v-if="lanList(row.sn).length" class="lan-list-bar">
              <div
                v-for="item in lanList(row.sn)"
                :key="item.path"
                class="lan-list-chip"
                :class="{ 'lan-list-up': item.connected }"
                :title="lanPortTip(item)"
              >
                <svg class="lan-list-icon" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                  <rect x="3" y="8" width="18" height="11" rx="1.5" fill="none" stroke="currentColor" stroke-width="1.8" />
                  <line x1="7" y1="8" x2="7" y2="4" stroke="currentColor" stroke-width="1.5" />
                  <line x1="12" y1="8" x2="12" y2="4" stroke="currentColor" stroke-width="1.5" />
                  <line x1="17" y1="8" x2="17" y2="4" stroke="currentColor" stroke-width="1.5" />
                </svg>
                <span class="lan-list-label">LAN{{ item.instance }}</span>
              </div>
            </div>
            <!-- 机型适配入口：未适配的给适配入口，已适配的给绿标 -->
            <div v-if="!adaptOf(row.sn).adapted" class="adapt-line">
              <el-button v-if="canModelManage" type="text" size="mini" class="adapt-btn" @click="openAdapt(row)">
                <i class="el-icon-cpu" /> 适配
              </el-button>
            </div>
            <div v-else class="adapt-line">
              <span class="adapt-ok"><i class="el-icon-circle-check" /> 已适配</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="WIFI 信息" min-width="160">
          <template slot-scope="{ row }">
            <template v-if="enabledWifiList(row.sn).length">
              <div class="wifi-list-band-row">
                <span class="wifi-list-band-label">2.4G:</span>
                <template v-if="enabledWifi24(row.sn).length">
                  <el-tag
                    v-for="item in enabledWifi24(row.sn)"
                    :key="item.path"
                    size="mini"
                    class="wifi-tag wifi-tag-editable"
                    @click.native="openWifiEdit(row, item)"
                  >{{ item.params.SSID || '-' }}</el-tag>
                </template>
                <span v-else class="cell-empty">-</span>
              </div>
              <div class="wifi-list-band-row">
                <span class="wifi-list-band-label">5.8G:</span>
                <template v-if="enabledWifi58(row.sn).length">
                  <el-tag
                    v-for="item in enabledWifi58(row.sn)"
                    :key="item.path"
                    size="mini"
                    class="wifi-tag wifi-tag-editable"
                    @click.native="openWifiEdit(row, item)"
                  >{{ item.params.SSID || '-' }}</el-tag>
                </template>
                <span v-else class="cell-empty">-</span>
              </div>
            </template>
            <span v-else class="cell-empty">-</span>
          </template>
        </el-table-column>
        <el-table-column label="SIP 信息" min-width="150">
          <template slot-scope="{ row }">
            <template v-if="sipList(row.sn).length">
              <div v-for="item in sipList(row.sn)" :key="item.path" class="biz-row">
                <el-tag size="mini" :type="sipTagType(item)">
                  {{ item.statusText }}
                </el-tag>
                <span class="biz-name">{{ item.number }}</span>
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
        <!-- 运行信息：运行时长 / 加入时间 / 最后汇报时间 / 汇报IP，取代原先独立的「上报的IP」列 -->
        <el-table-column label="运行信息" min-width="180">
          <device-runtime-cell
            slot="header"
            mode="header"
            :sort-key="sortKey"
            :sort-order="sortOrder"
            @sort="sortBy"
          />
          <template slot-scope="{ row }">
            <device-runtime-cell :row="row" :biz="bizOf(row.sn)" />
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
              <!-- 查业务信息是读；往下三条都会真的动设备，没有写权限就别摆出来 -->
              <el-dropdown-item command="business" icon="el-icon-connection">业务信息</el-dropdown-item>
              <el-dropdown-item v-if="canDeviceManage" command="probe" icon="el-icon-search">诊断(GetRPCMethods)</el-dropdown-item>
              <el-dropdown-item v-if="canDeviceManage" command="reboot" icon="el-icon-refresh-right" divided>重启设备</el-dropdown-item>
              <el-dropdown-item v-if="canDeviceManage" command="factoryReset" icon="el-icon-delete">恢复出厂</el-dropdown-item>
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
        <el-option-group v-if="bizTemplates.length" label="== 业务模板 ==">
          <el-option
            v-for="b in bizTemplates"
            :key="'biz-' + b.template.id"
            :label="b.template.name"
            :value="'biz-' + b.template.id"
          >
            <span>{{ b.template.name }}</span>
            <span class="member-hint">{{ (b.items || []).length }} 个成员</span>
          </el-option>
        </el-option-group>
        <el-option-group label="== 配置模板 ==">
          <el-option
            v-for="t in templates"
            :key="'cfg-' + t.id"
            :label="t.name"
            :value="'cfg-' + t.id"
          >
            <span>{{ t.name }}</span>
            <span class="member-hint">{{ t.categoryLabel || t.category }}</span>
          </el-option>
        </el-option-group>
      </el-select>
      <!-- 批量操作条上每个按钮各管各的权限：能看台账不等于能重启设备 -->
      <el-button
        v-if="canDeviceManage"
        size="small"
        type="primary"
        plain
        @click="batchDispatchTemplate"
      >批量下发模版</el-button>

      <el-dropdown v-if="canWorklistManage" trigger="click" @command="cmd => handleBatchCommand(cmd)">
        <el-button size="small">一键工单<i class="el-icon-caret-top el-icon--right" /></el-button>
        <el-dropdown-menu slot="dropdown">
          <el-dropdown-item command="worklist-record" icon="el-icon-tickets">工单记录</el-dropdown-item>
          <el-dropdown-item command="worklist-batch" icon="el-icon-s-order" divided>批量开通工单</el-dropdown-item>
          <el-dropdown-item command="worklist-repair" icon="el-icon-s-tools">批量检修工单</el-dropdown-item>
        </el-dropdown-menu>
      </el-dropdown>

      <el-dropdown v-if="canDeviceManage" trigger="click" @command="cmd => handleBatchCommand(cmd)">
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

      <el-dropdown v-if="canDeviceManage" trigger="click" @command="cmd => handleAdvancedCommand(cmd)">
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

      <el-dropdown v-if="canOltManage" trigger="click" @command="cmd => handleOltCommand(cmd)">
        <el-button size="small">OLT融合管理<i class="el-icon-caret-top el-icon--right" /></el-button>
        <el-dropdown-menu slot="dropdown">
          <el-dropdown-item command="olt-mgr" icon="el-icon-s-platform">OLT/ONU 管理</el-dropdown-item>
          <el-dropdown-item command="olt-topo" icon="el-icon-share" divided>OLT 拓扑（开发中）</el-dropdown-item>
        </el-dropdown-menu>
      </el-dropdown>

      <el-button v-if="canDeviceManage" size="small" type="warning" plain @click="batchReboot">重启</el-button>
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

    <!-- 查询超管账号密码弹窗：每台设备一行，多路账号展开看 -->
    <el-dialog title="超级管理员账号密码" :visible.sync="adminPwdVisible" width="860px">
      <el-table v-loading="adminPwdLoading" :data="adminPwdRows" border size="small" max-height="480">
        <el-table-column type="expand">
          <template slot-scope="{ row }">
            <el-table v-if="row.accounts && row.accounts.length" :data="row.accounts" border size="mini" style="width:100%">
              <el-table-column label="实例" prop="instance" width="70" />
              <el-table-column label="账号" prop="username" min-width="160" show-overflow-tooltip />
              <el-table-column label="密码" prop="password" min-width="200" show-overflow-tooltip />
              <el-table-column label="参数路径" prop="path" min-width="300" show-overflow-tooltip />
            </el-table>
            <div v-else class="admin-pwd-empty">{{ row.message || '未查询到账号' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="序列号" prop="sn" min-width="190" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template slot-scope="{ row }">
            <el-tag size="mini" :type="adminPwdTagType(row)">{{ adminPwdStatusText(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="账号" min-width="150">
          <template slot-scope="{ row }">{{ adminFirstAccount(row).username || '-' }}</template>
        </el-table-column>
        <el-table-column label="密码" min-width="190">
          <template slot-scope="{ row }">{{ adminFirstAccount(row).password || '-' }}</template>
        </el-table-column>
      </el-table>
      <div class="admin-pwd-tip">账号与口令是从设备上实时读到的明文，可直接用于登录光猫后台。</div>
      <span slot="footer">
        <el-button type="primary" :loading="adminPwdLoading" @click="reloadAdminPwd">重新查询</el-button>
        <el-button @click="adminPwdVisible = false">关闭</el-button>
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
          <span class="wan-param-name">WAN - {{ item.title }}</span>
          <el-tag size="mini" :type="wanTagType(item)">
            {{ item.statusText }}
          </el-tag>
          <span v-if="item.uptimeText" class="wan-uptime">{{ item.uptimeText }}</span>
        </div>
        <div class="wan-param-row">
          <span class="k">连接模式/地址类型:</span>
          <span class="v">{{ item.connMode }}</span>
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
      title="编辑 WLAN"
      :visible.sync="wifiEditVisible"
      width="480px"
      top="8vh"
    >
      <div v-if="wifiEditForm.band" class="wifi-edit-head">
        <el-tag size="mini" type="info">{{ wifiEditForm.band }}</el-tag>
        <span class="wifi-edit-path">{{ wifiEditForm.path }}</span>
      </div>
      <el-form label-width="100px" size="small">
        <el-form-item label="SSID 名称">
          <el-input v-model="wifiEditForm.ssid" maxlength="32" show-word-limit />
        </el-form-item>
        <el-form-item label="加密方式">
          <el-select
            v-model="wifiEditForm.encryption"
            style="width:100%"
            allow-create
            filterable
            default-first-option
            placeholder="选一种或输入设备实际值"
          >
            <el-option label="不加密" value="None" />
            <el-option label="WEP" value="WEPEncryption" />
            <el-option label="TKIP" value="TKIPEncryption" />
            <el-option label="AES" value="AESEncryption" />
            <el-option label="TKIP+AES 混合" value="TKIPandAESEncryption" />
          </el-select>
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="wifiEditForm.password" type="password" show-password placeholder="为空表示不修改密码" />
        </el-form-item>
        <el-form-item label="启用状态">
          <el-switch
            v-model="wifiEditForm.enabled"
            active-text="启用"
            inactive-text="禁用"
          />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="wifiEditVisible = false">取消</el-button>
        <el-button type="primary" :loading="wifiSubmitting" @click="submitWifiEdit">提交下发</el-button>
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
  fetchAdaptStatus, testAdaptation, saveAdaptation, updateDeviceRemark,
  fetchAdminAccounts, fetchDeviceStat, fetchCapabilityOptions, setDeviceParams,
  RPC_METHODS
} from '@/api/device'
import { fetchRpcResult } from '@/api/rpc'
import { fetchConfigTemplates } from '@/api/configTemplate'
import { fetchBizTemplates, executeBizTemplate } from '@/api/bizTemplate'
import { createWorklist } from '@/api/worklist'
import DeviceBusinessPanel from '@/components/DeviceBusinessPanel'
import DeviceIdentityCell from '@/components/DeviceIdentityCell.vue'
import DeviceRuntimeCell from '@/components/DeviceRuntimeCell.vue'

// 业务概要自动回读间隔：与后端 LAN 网口状态自动刷新（5 分钟）同频
const BIZ_AUTO_REFRESH_MS = 300000

// 设备列表自动刷新间隔：在线状态、顶栏统计都靠它跟上，不用手动点刷新
const LIST_AUTO_REFRESH_MS = 15000

export default {
  name: 'DeviceList',
  components: { DeviceBusinessPanel, DeviceIdentityCell, DeviceRuntimeCell },
  data() {
    return {
      loading: false,
      list: [],
      total: 0,
      bizMap: {},
      bizTimer: null,
      listTimer: null,
      // 机型适配状态：sn -> { adapted, configPath, ... }
      adaptMap: {},
      adaptVisible: false,
      adaptRow: null,
      adaptTesting: false,
      adaptSaving: false,
      adaptResult: null,
      adaptForm: { isp: 'standard', lanPorts: '1', lanSpeed: '自动', sipCount: '无', wifiSpec: '2.4G' },
      // 候选值由后端给：后端逐项比对时按这些取值解析，页面下拉里多一个
      // 后端不认的值会按「不申报」处理，等于白填
      ispOptions: [],
      portOptions: [],
      speedOptions: [],
      wifiOptions: [],
      wanVisible: false,
      wanDialogSn: '',
      wifiEditVisible: false,
      wifiSubmitting: false,
      wifiEditForm: {
        sn: '',
        band: '',
        path: '',
        instance: 1,
        ssid: '',
        encryption: '',
        password: '',
        enabled: true
      },
      detailVisible: false,
      detailRow: null,
      sortKey: '',
      sortOrder: '',
      opticFilter: '',
      // 台账统计：总数 / 在线 / 离线，口径与列表行上的在线标签一致
      deviceStat: { total: 0, online: 0, offline: 0 },
      onlineCount: 0,
      onlineList: [],
      onlineVisible: false,
      businessVisible: false,
      businessSn: '',
      // 批量操作工具条
      selection: [],
      templates: [],
      bizTemplates: [],
      selectedTemplate: '',
      customParamVisible: false,
      customParamRows: [{ name: '', value: '', type: 'xsd:string' }],
      debugVisible: false,
      debugForm: { rpc: 'GetParameterValues', args: '{}' },
      // 查询超管密码弹窗：每台设备一行，账号列表展开看
      adminPwdVisible: false,
      adminPwdLoading: false,
      adminPwdRows: [],
      rpcMethods: RPC_METHODS,
      query: {
        page: 1,
        size: 20,
        sn: '',
        isp: '',
        deviceType: '',
        // 房间号：台账上登记的分群依据，下发时替换模板 %room% 的取值来源
        roomNo: ''
      },
      // 房间号 / 备注弹窗
      remarkVisible: false,
      remarkRow: null,
      remarkForm: { roomNo: '', remark: '' },
      remarkSaving: false
    }
  },
  computed: {
    // 页面上的写操作按模块分权限摆，系统管理员这些全是真
    canDeviceManage() {
      return this.$has('device:manage')
    },
    canModelManage() {
      return this.$has('model:manage')
    },
    canWorklistManage() {
      return this.$has('worklist:manage')
    },
    canOltManage() {
      return this.$has('olt:manage')
    },
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
        // 有没有光功率数据由后端判定：它才知道哪些参数名算 power、换算不出来算没有
        rows = rows.filter(r => this.bizOf(r.sn).opticState === this.opticFilter)
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
    this.loadStat()
    this.loadTemplates()
    this.loadCapabilityOptions()
    // 后端每 5 分钟自动刷新一次 LAN 网口状态，这里同频回读当前页的业务概要，
    // 网口图标不用手动刷新就能跟上插拔变化
    this.bizTimer = setInterval(() => {
      if (!document.hidden) {
        this.reloadBiz()
      }
    }, BIZ_AUTO_REFRESH_MS)
    // 列表每 15 秒自动刷新：在线状态、顶栏总数/在线/离线、业务概要一起跟上，
    // 不用手动点刷新。弹窗开着或勾了行时不刷，免得顶掉正在填的表单和选中的设备
    this.listTimer = setInterval(() => {
      if (!document.hidden && !this.hasBlockingDialog()) {
        this.load(true)
        this.loadStat()
        this.loadOnline()
      }
    }, LIST_AUTO_REFRESH_MS)
  },
  beforeDestroy() {
    if (this.bizTimer) {
      clearInterval(this.bizTimer)
      this.bizTimer = null
    }
    if (this.listTimer) {
      clearInterval(this.listTimer)
      this.listTimer = null
    }
  },
  /**
   * 有没有正在进行的交互：有就先不自动刷新。
   * 弹窗里的表单、勾好的设备行，都会被一次后台刷新冲掉。
   */
  hasBlockingDialog() {
    return !!(this.adaptVisible || this.wanVisible || this.wifiEditVisible ||
      this.detailVisible || this.onlineVisible || this.businessVisible ||
      this.customParamVisible || this.debugVisible || this.adminPwdVisible ||
      this.remarkVisible || this.selection.length)
  },
  methods: {
    /**
     * 拉设备分页数据。
     *
     * @param silent 后台自动刷新时传 true：不转 loading（否则每 15 秒整页闪一下），
     *               请求失败也不清空列表（网络抖一下不该把列表清空）
     */
    async load(silent) {
      if (!silent) {
        this.loading = true
      }
      try {
        const res = await fetchDevicePage(this.query)
        const data = res.data || {}
        this.list = data.items || []
        this.total = data.total || 0
        this.loadBiz()
        this.loadAdaptStatus()
      } catch (e) {
        if (!silent) {
          this.list = []
          this.total = 0
        }
      } finally {
        if (!silent) {
          this.loading = false
        }
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
    // 定时回读业务概要：只在成功时覆盖。整页业务列突然变空比显示旧值更糟
    async reloadBiz() {
      const sns = this.list.map(d => d.sn)
      if (!sns.length) {
        return
      }
      try {
        const res = await fetchDeviceBusinessBatch(sns)
        if (res && res.data) {
          this.bizMap = res.data
        }
      } catch (e) {
        // 静默失败：定时刷新不该打扰正在操作列表的人
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
    // 开关与频段都由后端判定，前端只按结果过滤：
    // 频段那套优先级（RFBand → 频段参数 → 制式列表 → SSID 名 → 实例号）只有后端一份
    enabledWifiList(sn) {
      return this.wifiList(sn).filter(item => item.enabled)
    },
    enabledWifi24(sn) {
      return this.enabledWifiList(sn).filter(item => item.band === '2.4G')
    },
    enabledWifi58(sn) {
      return this.enabledWifiList(sn).filter(item => item.band === '5.8G')
    },
    lanList(sn) {
      return this.bizOf(sn).lan || []
    },
    // 网口小图标的悬停提示：LAN1 已连接（MAC: xx:xx...）
    lanPortTip(item) {
      const state = item.connected ? '已连接' : '未连接'
      const mac = item.mac ? ' MAC: ' + item.mac : ''
      return 'LAN' + (item.instance || '') + ' ' + state + mac
    },

    openWifiEdit(row, item) {
      const params = item && item.params || {}
      // 必须读 IEEE11iEncryptionModes：下发写的是这个参数，
      // 拿 BeaconType 的值（Basic / 11i / WPAand11i）填进去设备会回 9003
      const encryption = params.IEEE11iEncryptionModes ||
        params.BasicEncryptionModes || params.WPAEncryptionModes || ''
      const password = params.KeyPassphrase || params.PreSharedKey || params.WPA_PSK || ''
      this.wifiEditForm = {
        sn: row.sn,
        // 频段、实例号、开关都取后端判好的，前端不再自己解析路径与猜状态
        band: item.band,
        path: item.path || '',
        instance: item.instance,
        ssid: item.ssid || '',
        encryption: String(encryption).replace(/None/i, 'None'),
        password: String(password),
        enabled: item.enabled
      }
      this.wifiEditVisible = true
    },

    async submitWifiEdit() {
      const form = this.wifiEditForm
      if (!form.ssid) {
        this.$message.warning('SSID 名称不能为空')
        return
      }
      this.wifiSubmitting = true
      try {
        // 直接提交业务字段：业务类型（WIFI_24G / WIFI_58G）与意图键
        // （wpa_mode / wpa_key / enable）都由后端翻译，页面不必知道下发引擎认哪些键
        await createWorklist({
          sn: form.sn,
          wifi: {
            instance: form.instance,
            ssid: form.ssid,
            encryption: form.encryption,
            password: form.password,
            enabled: form.enabled,
            band: form.band
          },
          username: this.$store.getters.name || 'admin'
        })
        this.$message.success('WLAN 修改工单已提交')
        this.wifiEditVisible = false
      } catch (e) {
        this.$message.error('提交失败：' + ((e && e.message) || ''))
      } finally {
        this.wifiSubmitting = false
      }
    },

    sipList(sn) {
      return this.bizOf(sn).sip || []
    },
    // 注册判定与号码截取都在后端（URI 带 @ 时截前面，避免显示成 2026@192.168.x.x），
    // 前端只剩标签颜色：已注册绿、未配置灰、其余黄
    sipTagType(item) {
      if (item.registered) return 'success'
      if (item.statusText === '未配置') return 'info'
      return 'warning'
    },
    // 管理地址：由后端从 WAN_TR069 通道算出（业务快照的 ip 字段），前端不做判定
    wanIp(sn) {
      return this.bizOf(sn).ip || '-'
    },
    isOnline(row) {
      const status = String(row.status || '').toLowerCase()
      return status.includes('online') || status.includes('在线') || row.online === true
    },
    // 连接名与状态文案都由后端给（没报 Name 时用「WAN PPP 1」这类兜底叫法），
    // 前端只决定标签颜色
    wanTagType(item) {
      if (item.up) return 'success'
      if (item.statusText === '-') return 'info'
      return 'danger'
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
      const mac = biz.mac
      // 目录名与原始申报值都映射：适配档目录用归一化的 CM/CT/CU，
      // yml profile 里的 isp 是申报时的原始值（如 CMCC）
      const ispNames = {
        CMCC: '中国移动', CM: '中国移动',
        CT: '中国电信', CU: '中国联通', CBN: '中国广电',
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
      if (wifi.some(w => w.band === '2.4G')) parts.push('2.4G WiFi')
      if (wifi.some(w => w.band === '5.8G')) parts.push('5.8G WiFi')
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
    // 业务类型由后端判定（键名含 ServiceList，不区分厂商前缀）
    serviceMode(item) {
      return item.service || '-'
    },
    showVal(value) {
      return value === undefined || value === null || value === '' ? '-' : value
    },

    sortVal(row, key) {
      if (key === 'pon') return this.bizOf(row.sn).ponType || 'GE'
      // 排序比的是秒数，格式化后的「1天2小时」是字符串，没法比大小
      if (key === 'upTime') return this.bizOf(row.sn).uptimeSeconds || ''
      if (key === 'ip') return String(this.bizOf(row.sn).ip || '')
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
    /** 台账统计：总数 / 在线 / 离线。与列表行标签同一口径（cpe_device.status）。 */
    async loadStat() {
      try {
        const res = await fetchDeviceStat()
        const data = res.data || {}
        this.deviceStat = {
          total: data.total || 0,
          online: data.online || 0,
          offline: data.offline || 0
        }
      } catch (e) {
        this.deviceStat = { total: 0, online: 0, offline: 0 }
      }
    },
    // 顶栏「刷新」：设备数、在线会话一起拉，避免两个数字不同步
    refreshOnlineBar() {
      this.loadStat()
      this.loadOnline()
    },
    handleSearch() {
      this.query.page = 1
      this.load()
    },
    resetQuery() {
      this.query = { page: 1, size: 20, sn: '', isp: '', deviceType: '', roomNo: '' }
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
      // 业务模板与配置模板是两个库：下拉要都能选到，否则业务模板配好了却无从下发
      fetchBizTemplates().then(res => {
        this.bizTemplates = (res.data || []).filter(b => b && b.template)
      }).catch(() => {
        this.bizTemplates = []
      })
    },
    batchDispatchTemplate() {
      const sns = this.requireSelection()
      if (!sns) return
      if (!this.selectedTemplate) {
        this.$message.warning('请先选择模版关键字')
        return
      }
      // 下拉里两类模板的 id 会撞，选型值带前缀区分
      const picked = String(this.selectedTemplate)
      const id = Number(picked.slice(4))
      if (picked.startsWith('biz-')) {
        this.batchCreateBizWorklist(sns, id)
        return
      }
      const tpl = this.templates.find(t => t.id === id) || {}
      // 模板的 category 即业务类型，落到工单的业务类型上；取不到就留空由下发侧推导
      this.batchCreateWorklist(sns, (tpl.category || '').toUpperCase(), tpl.name || '模版下发')
    },

    /**
     * 业务模板整包下发：交给后端按成员编排顺序串行执行，逐台各起一条执行记录。
     *
     * <p>成员依赖是有先后顺序的（先建连接再挂业务），所以不在这里一次性全排进队列，
     * 而是让执行器等前一个成员结束再发下一个；失败即停由模板的「出错继续」决定。</p>
     */
    batchCreateBizWorklist(sns, templateId) {
      const biz = this.bizTemplates.find(b => b.template && b.template.id === templateId)
      if (!biz) {
        this.$message.warning('业务模板不存在，请刷新后重试')
        return
      }
      const items = (biz.items || []).filter(i => i.category)
      if (!items.length) {
        this.$message.warning('业务模板「' + biz.template.name + '」没有可下发的成员')
        return
      }
      this.$confirm(
        `将对已勾选的 ${sns.length} 台设备整包下发业务模板「${biz.template.name}」，` +
        `${items.length} 个成员按编排顺序串行执行，确认提交？`,
        '批量下发',
        { type: 'warning' }
      ).then(() => {
        const username = this.$store.getters.name || 'admin'
        const tasks = sns.map(sn => {
          return executeBizTemplate({
            templateId,
            sn,
            params: {},
            username
          }).then(() => null).catch(err => ({ sn, err: (err && err.message) || '提交失败' }))
        })
        Promise.all(tasks).then(results => this.reportBatchResult(results, sns.length))
      }).catch(() => {})
    },

    /**
     * 给多台设备逐台创建下发工单。
     *
     * <p>每台一个独立流水号，互不影响；单台失败记录下来但继续其余设备，
     * 最后汇总失败清单——不能因为某一台有问题就把整批吞掉。</p>
     */
    batchCreateWorklist(sns, category, label) {
      this.$confirm(
        `将对已勾选的 ${sns.length} 台设备创建「${label || '下发'}」工单，确认提交？`,
        '批量下发',
        { type: 'warning' }
      ).then(() => {
        const username = this.$store.getters.name || 'admin'
        const tasks = sns.map(sn => {
          // 流水号不传：后端发号自带自增段与随机段，多台的工单不会互相被幂等掉
          return createWorklist({
            sn,
            category: category || '',
            values: {},
            username
          }).then(() => null).catch(err => ({ sn, err: (err && err.message) || '创建失败' }))
        })
        Promise.all(tasks).then(results => this.reportBatchResult(results, sns.length))
      }).catch(() => {})
    },

    /** 机型适配申报的候选值，由后端维护，页面只负责选。 */
    loadCapabilityOptions() {
      fetchCapabilityOptions()
        .then(res => {
          const data = (res && res.data) || {}
          this.ispOptions = data.isp || []
          this.portOptions = data.ports || []
          this.speedOptions = data.speeds || []
          this.wifiOptions = data.wifiSpecs || []
        })
        .catch(() => {})
    },

    /** 批量提交的汇总提示：全成功给个轻提示，有失败则列出每台的原因。 */
    reportBatchResult(results, total) {
      const failed = results.filter(r => r)
      if (!failed.length) {
        this.$message.success(`已提交 ${total} 条下发请求`)
        this.$emit('refresh')
        return
      }
      this.$notify.warning({
        title: `${failed.length}/${total} 条提交失败`,
        message: failed.map(f => `${f.sn}：${f.err}`).join('<br>'),
        dangerouslyUseHTMLString: true,
        duration: 0
      })
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
      // 开通 / 检修：按勾选设备逐台建单，业务类型留空由下发侧推导
      if (cmd === 'worklist-batch') {
        const sns = this.requireSelection()
        if (!sns) return
        this.batchCreateWorklist(sns, '', '开通工单')
        return
      }
      if (cmd === 'worklist-repair') {
        const sns = this.requireSelection()
        if (!sns) return
        this.batchCreateWorklist(sns, '', '检修工单')
        return
      }
      // 查询超管密码：对勾选设备逐台实时读，结果在弹窗里明文展示
      if (cmd === 'query-admin-pwd') {
        this.openAdminPwd()
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
      // 走专用接口：SetParameterValues 的报文结构由后端拼。
      // 原先这里自己拼 { parameterValues: [[...]] } 发给通用 RPC 通道，
      // 而那个键名后端根本不认——报文里的 ParameterList 是空的，
      // 设备收到的是一条啥参数都没有的 SetParameterValues，页面却提示下发成功
      const items = rows.map(r => ({ name: r.name, value: r.value || '', type: r.type }))
      sns.forEach(sn => {
        setDeviceParams(sn, items).catch(() => {})
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
    // ===== 查询超管账号密码 =====
    /**
     * 打开弹窗并对勾选设备逐台查询。
     *
     * 每台设备独立一行、独立请求：单台超时/离线不影响其它台，
     * 行内 loading 结束就出结果，不用等全部完成。
     */
    openAdminPwd() {
      const sns = this.requireSelection()
      if (!sns) return
      this.adminPwdVisible = true
      this.adminPwdLoading = true
      this.adminPwdRows = sns.map(sn => ({
        sn, loading: true, status: '', message: '', accounts: []
      }))
      const settle = () => {
        this.adminPwdLoading = this.adminPwdRows.some(r => r.loading)
      }
      sns.forEach((sn, idx) => {
        fetchAdminAccounts(sn)
          .then((res) => {
            const d = (res && res.data) || {}
            const row = this.adminPwdRows[idx]
            row.status = d.status || ''
            row.message = d.message || ''
            row.accounts = d.accounts || []
            row.loading = false
          })
          .catch((err) => {
            const row = this.adminPwdRows[idx]
            row.status = 'fail'
            row.message = (err && err.message) || '查询失败'
            row.loading = false
          })
          .finally(settle)
      })
    },
    reloadAdminPwd() {
      this.openAdminPwd()
    },
    adminFirstAccount(row) {
      return (row.accounts && row.accounts.length) ? row.accounts[0] : {}
    },
    adminPwdStatusText(row) {
      if (row.loading) return '查询中'
      const map = { success: '已获取', empty: '无账号', timeout: '超时', fail: '失败', offline: '离线' }
      return map[row.status] || '未知'
    },
    adminPwdTagType(row) {
      if (row.loading) return 'info'
      const map = { success: 'success', empty: 'info', timeout: 'warning', fail: 'danger', offline: 'info' }
      return map[row.status] || 'info'
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
.online-bar b {
  padding: 0 2px;
}
.stat-online {
  margin-left: 14px;
  color: #67c23a;
}
.stat-offline {
  margin-left: 14px;
  color: #909399;
}
.online-bar-sep {
  margin: 0 10px;
  color: #dcdfe6;
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
.wifi-tag-editable {
  cursor: pointer;
}
.wifi-tag-editable:hover {
  background-color: #ecf5ff;
  border-color: #409eff;
  color: #409eff;
}
/* ---- 列表页 WiFi 信息按频段上下显示 ---- */
.wifi-list-band-row {
  display: flex;
  align-items: flex-start;
  line-height: 22px;
}
.wifi-list-band-row:not(:last-child) {
  margin-bottom: 2px;
}
.wifi-list-band-label {
  flex-shrink: 0;
  width: 36px;
  color: #67c23a;
  font-size: 12px;
  font-weight: 600;
}
/* ---- WiFi 编辑弹窗 ---- */
.wifi-edit-head {
  margin-bottom: 18px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.wifi-edit-path {
  font-size: 12px;
  color: #909399;
  word-break: break-all;
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
/* ---- 查询超管账号密码弹窗 ---- */
.admin-pwd-empty {
  padding: 8px 12px;
  font-size: 12px;
  color: #909399;
}
.admin-pwd-tip {
  margin-top: 8px;
  font-size: 12px;
  color: #e6a23c;
}
/* ---- WAN 单元格内的 LAN 网口小图标：4 个口要并排一行，不换行 ---- */
.lan-list-bar {
  display: flex;
  flex-wrap: nowrap;
  gap: 3px;
  margin-top: 4px;
}
.lan-list-chip {
  display: inline-flex;
  flex: 0 0 30px;
  width: 30px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 2px 0;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background: #f5f7fa;
  color: #909399;
}
.lan-list-chip.lan-list-up {
  border-color: #67c23a;
  background: #f0f9eb;
  color: #67c23a;
}
.lan-list-icon {
  width: 14px;
  height: 14px;
}
.lan-list-label {
  font-size: 9px;
  line-height: 12px;
  white-space: nowrap;
  transform: scale(0.8);
}
</style>
