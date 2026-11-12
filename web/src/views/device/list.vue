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
      <!-- 设备标识：终端唯一标识（OUI-SN）为主，下面是 SN、房间号备注、
           下发进度，铭牌 MAC 压在最后一行 -->
      <el-table-column label="设备标识" min-width="280">
        <template slot-scope="{ row }">
          <div class="cell-main">
            <el-link type="primary" @click="openDetail(row)">{{ terminalId(row) }}</el-link>
            <el-tag :type="isOnline(row) ? 'success' : 'info'" size="mini">
              {{ isOnline(row) ? '在线' : '离线' }}
            </el-tag>
          </div>
          <div class="cell-sub">
            <span class="remark-label">SN</span>
            <span class="cell-sn">{{ row.sn }}</span>
          </div>
          <!-- 下发进度：意图下发任务、本机提交的重启/恢复出厂、队列里压着的指令，都在这一行 -->
          <div v-if="progressLines(row.sn).length" class="cell-tasks">
            <div v-for="line in progressLines(row.sn)" :key="line.key" class="cell-task">
              <i :class="line.icon" />
              <span>{{ line.text }}</span>
              <el-tag v-if="line.tag" size="mini" :type="line.status === 'running' ? 'warning' : 'info'">
                {{ line.tag }}
              </el-tag>
            </div>
          </div>
          <div class="cell-remark">
            <span class="remark-label">房间号</span>
            <span class="remark-value">{{ row.roomNo || '-' }}</span>
            <span class="remark-label">备注</span>
            <span class="remark-value remark-value-text">{{ row.remark || '-' }}</span>
            <el-link type="primary" class="remark-edit" @click="openRemark(row)">修改</el-link>
          </div>
          <!-- 铭牌 MAC：设备标签上印的那个，取 LAN 口地址（WAN 的每条连接各有一份，会随通道变） -->
          <div v-if="macOf(row.sn)" class="cell-mac">
            <i class="el-icon-postcard" /> {{ macOf(row.sn) }}
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
              <!-- 每一路 SIP 都能点开看全部参数：只读本地快照，不发任何 RPC，
                   不会唤醒设备（以前这一列是死的，点什么都没反应） -->
              <div
                v-for="item in sipList(row.sn)"
                :key="item.path"
                class="biz-row sip-row"
                title="点击查看该路 SIP 的全部参数"
                @click="openSipParams(row.sn, item)"
              >
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
        <el-button size="small">一键下发<i class="el-icon-caret-top el-icon--right" /></el-button>
        <el-dropdown-menu slot="dropdown">
          <el-dropdown-item command="worklist-record" icon="el-icon-tickets">工单记录</el-dropdown-item>
          <el-dropdown-item command="worklist-batch" icon="el-icon-s-order" divided>批量开通业务</el-dropdown-item>
          <el-dropdown-item command="worklist-repair" icon="el-icon-s-tools">批量检修任务</el-dropdown-item>
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
    <el-dialog title="超级管理员账号密码" :visible.sync="adminPwdVisible" width="960px">
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
        <el-table-column label="序列号" prop="sn" min-width="175" show-overflow-tooltip />
        <el-table-column label="状态" width="95">
          <template slot-scope="{ row }">
            <el-tag size="mini" :type="adminPwdTagType(row)">{{ adminPwdStatusText(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="账号" min-width="140" show-overflow-tooltip>
          <template slot-scope="{ row }">{{ adminFirstAccount(row).username || '-' }}</template>
        </el-table-column>
        <el-table-column label="密码" min-width="170" show-overflow-tooltip>
          <template slot-scope="{ row }">{{ adminFirstAccount(row).password || '-' }}</template>
        </el-table-column>
        <el-table-column label="说明" min-width="240" show-overflow-tooltip>
          <template slot-scope="{ row }">{{ adminPwdHint(row) }}</template>
        </el-table-column>
      </el-table>
      <div class="admin-pwd-tip" :class="{ 'admin-pwd-tip-warn': adminPwdHasNoAccount }">{{ adminPwdTip }}</div>
      <span slot="footer">
        <el-button type="primary" :loading="adminPwdLoading" @click="reloadAdminPwd">重新查询</el-button>
        <el-button @click="adminPwdVisible = false">关闭</el-button>
      </span>
    </el-dialog>

    <!-- Telnet 状态与账号密码弹窗：未开启会自动开启并读取默认账号密码 -->
    <el-dialog title="Telnet 状态与账号密码" :visible.sync="telnetVisible" width="1040px">
      <el-table v-loading="telnetLoading" :data="telnetRows" border size="small" max-height="480">
        <el-table-column label="序列号" prop="sn" min-width="175" show-overflow-tooltip />
        <el-table-column label="状态" width="95">
          <template slot-scope="{ row }">
            <el-tag size="mini" :type="telnetTagType(row)">{{ telnetStatusText(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Telnet" width="90" align="center">
          <template slot-scope="{ row }">
            <el-tag v-if="row.enabled === true" size="mini" type="success">已开启</el-tag>
            <el-tag v-else-if="row.enabled === false" size="mini" type="info">已关闭</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="账号" prop="username" min-width="120" show-overflow-tooltip>
          <template slot-scope="{ row }">{{ row.username || '-' }}</template>
        </el-table-column>
        <el-table-column label="密码" min-width="180" show-overflow-tooltip>
          <template slot-scope="{ row }">
            <span v-if="row.password">{{ telnetPwdShow ? row.password : '••••••' }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="端口" prop="port" width="70">
          <template slot-scope="{ row }">{{ row.port || '-' }}</template>
        </el-table-column>
        <el-table-column label="参数路径" min-width="240" show-overflow-tooltip>
          <template slot-scope="{ row }">{{ row.path || '-' }}</template>
        </el-table-column>
        <el-table-column label="说明" min-width="200" show-overflow-tooltip>
          <template slot-scope="{ row }">{{ telnetHint(row) }}</template>
        </el-table-column>
      </el-table>
      <div class="admin-pwd-tip" :class="{ 'admin-pwd-tip-warn': telnetHasNoResult }">{{ telnetTip }}</div>
      <span slot="footer">
        <el-button type="text" @click="telnetPwdShow = !telnetPwdShow">{{ telnetPwdShow ? '隐藏密码' : '显示密码' }}</el-button>
        <el-button type="success" plain :loading="telnetLoading" :disabled="!telnetCanEnable" @click="batchTelnetSwitch(true)">全部开启</el-button>
        <el-button type="danger" plain :loading="telnetLoading" :disabled="!telnetCanDisable" @click="batchTelnetSwitch(false)">全部关闭</el-button>
        <el-button type="primary" :loading="telnetLoading" @click="reloadTelnet">重新查询</el-button>
        <el-button @click="telnetVisible = false">关闭</el-button>
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
          <span class="v">
            {{ item.connMode }}
            <el-tag v-if="item.ipProtocol" size="mini" :type="ipTagType(item.ipProtocol)">
              {{ item.ipProtocol }}
            </el-tag>
            <el-tag v-if="item.mtu" size="mini" type="info">MTU/{{ item.mtu }}</el-tag>
          </span>
        </div>
        <div class="wan-param-row">
          <span class="k">业务模式:</span>
          <span class="v v-strong">{{ serviceMode(item) }}</span>
        </div>
        <div class="wan-param-row">
          <span class="k">端口绑定:</span>
          <span class="v">{{ item.bindPorts || '未绑定（默认全部口）' }}</span>
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
        <span class="snapshot-tip">以上为本地快照，未唤醒设备</span>
        <el-button @click="wanVisible = false">关闭</el-button>
      </span>
    </el-dialog>

    <!-- SIP 语音：与「编辑 WLAN」同款——点开就是表单，点下发 = 提交一张 SIP 工单 -->
    <el-dialog
      :title="'SIP 语音 - ' + sipEditForm.sn"
      :visible.sync="sipVisible"
      width="520px"
      top="8vh"
    >
      <div class="snapshot-tip-block">打开此窗口不会唤醒设备，点「下发到设备」才会写入</div>
      <el-form label-width="118px" size="small">
        <el-form-item label="下发线路">
          <!-- 设备上只有一路语音时不再摆勾选框：选来选去还是那一路，只会多一步操作 -->
          <div v-if="sipLineOptions(sipEditForm.sn).length > 1" class="sip-line-pick">
            <el-checkbox-group v-model="sipEditForm.lines">
              <el-checkbox v-for="opt in sipLineOptions(sipEditForm.sn)" :key="opt.line" :label="opt.line">
                第 {{ opt.line }} 路
                <span class="sip-line-num">{{ opt.number || '未配置' }}</span>
              </el-checkbox>
            </el-checkbox-group>
            <div class="sip-line-tip">勾选的每一路按同一套号码 / 密码下发</div>
          </div>
          <span v-else class="sip-edit-path">
            第 {{ (sipEditForm.lines[0] || 1) }} 路 · {{ sipEditForm.path }}
          </span>
        </el-form-item>
        <el-form-item label="电话号码 / URI">
          <el-input v-model="sipEditForm.number" placeholder="如 8888 或 88880001@ims.domain" />
        </el-form-item>
        <el-form-item label="认证用户名">
          <!-- 设备上叫 AuthUserName，注册时送出去的就是它；真机上多数情况与号码同号 -->
          <el-input v-model="sipEditForm.authUser" placeholder="AuthUserName" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="sipEditForm.password"
            :type="sipShowPwd ? 'text' : 'password'"
            placeholder="AuthPassword"
          >
            <i
              slot="suffix"
              :class="sipShowPwd ? 'el-icon-view' : 'el-icon-hide'"
              class="pwd-eye"
              @click="sipShowPwd = !sipShowPwd"
            />
          </el-input>
        </el-form-item>
        <!-- 服务器地址整档共用一份（RegistrarServer / ProxyServer 挂在 VoiceProfile 上，
             不是某个话机口的私有值），是局点侧的地址，不该在这张单里改，所以只读。
             设备没上报时才放开手填，否则下发会缺 %sip_registrar% 变量直接渲染失败 -->
        <el-form-item label="注册服务器">
          <el-input
            v-model="sipEditForm.registrar"
            :disabled="!!sipEditForm.registrar"
            placeholder="设备未上报"
          />
        </el-form-item>
        <el-form-item label="注册服务器端口">
          <el-input
            v-model="sipEditForm.registrarPort"
            :disabled="!!sipEditForm.registrarPort"
            placeholder="设备未上报"
          />
        </el-form-item>
        <el-form-item label="代理服务器">
          <el-input
            v-model="sipEditForm.proxy"
            :disabled="!!sipEditForm.proxy"
            placeholder="设备未上报"
          />
        </el-form-item>
        <el-form-item label="代理服务器端口">
          <el-input
            v-model="sipEditForm.proxyPort"
            :disabled="!!sipEditForm.proxyPort"
            placeholder="设备未上报"
          />
        </el-form-item>
        <div class="sip-readonly-tip">
          <i class="el-icon-info" /> 四项取自设备当前配置（整档共用），不随本次下发改动
        </div>
      </el-form>
      <el-collapse v-if="sipDialogItems.length" class="wan-more">
        <el-collapse-item title="全部参数（当前快照）">
          <div v-for="(value, name) in sipDialogItems[0].params" :key="name" class="wan-all-row">
            <span class="k">{{ name }}</span>
            <span class="v">{{ showVal(value) }}</span>
          </div>
        </el-collapse-item>
      </el-collapse>
      <span slot="footer">
        <span class="snapshot-tip">以上为本地快照，未唤醒设备</span>
        <el-button @click="sipVisible = false">取消</el-button>
        <el-button type="primary" :loading="sipSubmitting" @click="submitSipEdit">下发到设备</el-button>
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
      <!-- 打开弹窗只读快照，不碰设备；只有点「提交下发」才真正写入 -->
      <div class="snapshot-tip-block">打开此窗口不会唤醒设备，保存并下发时才会向设备写入</div>
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
          <div class="adapt-result" :class="adaptResult.supported ? 'is-ok' : (adaptResult.collectInterrupted ? 'is-warn' : 'is-bad')">
            <div class="adapt-result-head">
              <i :class="adaptResult.supported ? 'el-icon-success' : (adaptResult.collectInterrupted ? 'el-icon-warning' : 'el-icon-error')" />
              {{ adaptResult.supported ? '能力全部支持，可保存到库'
                : (adaptResult.collectInterrupted ? '采集被会话中断，请重新测试' : '存在不满足项') }}
              <el-tag size="mini" :type="adaptResult.collectStatus === 'running' ? 'warning' : 'info'">
                采集：{{ adaptResult.collectStatus }}
              </el-tag>
            </div>
            <!-- 逐项比对结论：√ 满足 / ✗ 不满足 / — 未申报不比对 -->
            <div v-if="adaptResult.checks && adaptResult.checks.length" class="adapt-checks">
              <div v-for="c in adaptResult.checks" :key="c.label" class="adapt-check">
                <el-tag
                  size="mini"
                  :type="!c.checked ? 'info' : (c.pass ? 'success' : 'danger')"
                >{{ !c.checked ? '—' : (c.pass ? '✓' : '✗') }}</el-tag>
                <span class="adapt-check-label">{{ c.label }}</span>
                <span class="adapt-check-detail">{{ c.detail }}</span>
              </div>
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
            <div
              v-for="(r, i) in adaptResult.reasons"
              :key="i"
              :class="adaptResult.collectInterrupted ? 'adapt-reason-warn' : 'adapt-reason'"
            >{{ r }}</div>
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
  fetchDeviceActiveTasks, fetchRpcMethods, fetchQuickCommands,
  fetchSipLines, submitSipEdit, dispatchDevices, exportDevices as exportDevicesApi,
  fetchPollConfig, fetchRpcProgressLabels,
  setTelnetEnabled,
  fetchTelnetCredentials
} from '@/api/device'
import { fetchRpcResult } from '@/api/rpc'
import { fetchConfigTemplates } from '@/api/configTemplate'
import { fetchBizTemplates, executeBizTemplate } from '@/api/bizTemplate'
import DeviceBusinessPanel from '@/components/DeviceBusinessPanel'
import DeviceIdentityCell from '@/components/DeviceIdentityCell.vue'
import DeviceRuntimeCell from '@/components/DeviceRuntimeCell.vue'

// 轮询节奏不在页面写死：created 时从后端 /device/poll-config 取（见 loadPollConfig），
// 现场要调快调慢只改后端配置。

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
      // 下发进度：sn -> { tasks: [{ label, phase, ... }], rpcPending: n }，只含还在跑的任务
      taskMap: {},
      taskTimer: null,
      // 本机提交的单条 RPC（重启 / 恢复出厂）不进任务表，这里按 sn 记住它在干什么
      rpcProgress: {},
      // RPC 行内措辞：后端 /device/rpc/progress-labels 下发（running / success / fail）
      rpcLabels: {},
      // 轮询节奏：后端 /device/poll-config 下发，页面不再写死几秒
      pollConfig: { listSeconds: 15, taskSeconds: 5, bizSeconds: 300, rpcSeconds: 3, rpcMaxAttempts: 60 },
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
      sipVisible: false,
      sipDialogSn: '',
      sipDialogPath: '',
      sipShowPwd: false,
      sipSubmitting: false,
      /** 可编辑的语音线路清单：路数、当前号码、服务器全部由后端算 */
      sipLines: [],
      sipEditForm: {
        sn: '',
        path: '',
        lines: [],
        number: '',
        authUser: '',
        password: '',
        registrar: '',
        registrarPort: '5060',
        proxy: '',
        proxyPort: '5060'
      },
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
      // Telnet 状态弹窗：每台设备一行，可逐台/批量开启或关闭
      telnetVisible: false,
      telnetLoading: false,
      telnetRows: [],
      // Telnet 密码默认掩码显示，点「显示密码」才明文
      telnetPwdShow: false,
      // 可下发方法清单，以后端 /device/rpc/methods 为准；空了就是后端没给，前端不兜底
      rpcMethods: [],
      // 快捷操作的「命令 → RPC」映射，以后端 /device/rpc/quick-commands 为准
      quickCommandRpc: {},
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
      return this.$has('device:update')
    },
    canModelManage() {
      return this.$has('model:update')
    },
    canWorklistManage() {
      return this.$has('worklist:update')
    },
    canOltManage() {
      return this.$has('olt:update')
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
    // 排序与光模块筛选都在后端做（/device/page 的 sortKey / sortOrder / optic），
    // 页面拿到什么就显示什么——原来这里只排当前页 20 条，翻页就重新洗牌
    displayList() {
      return this.list
    },
    // 一行账号都没读到时，底部提示不能还写着「可直接用于登录后台」
    adminPwdHasNoAccount() {
      return !this.adminPwdLoading && !this.adminPwdRows.some(r => r.accounts && r.accounts.length)
    },
    adminPwdTip() {
      if (this.adminPwdLoading) return '正在向设备实时读取账号与口令（离线设备会先发连接请求唤醒），单台最长等约 70 秒…'
      if (!this.adminPwdHasNoAccount) return '账号与口令是从设备上实时读到的明文，可直接用于登录光猫后台。'
      return '没读到账号与口令：原因见上方“说明”列，展开行可看每台设备读到的参数路径。'
    },
    telnetHasNoResult() {
      return !this.telnetLoading && !this.telnetRows.some(r => r.enabled === true || r.enabled === false)
    },
    telnetTip() {
      if (this.telnetLoading) return '正在向设备读取 Telnet 状态与账号密码（未开启的会自动下发开启指令），单台最长等约 120 秒…'
      if (!this.telnetHasNoResult) return '账号密码为实时读取结果；未开启的设备已自动开启。点击「全部开启/关闭」可批量切换。'
      return '没读到 Telnet 状态：原因见上方“说明”列，常见情况是设备不支持该参数或当前离线。'
    },
    telnetCanEnable() {
      return !this.telnetLoading && this.telnetRows.some(r => r.enabled !== true)
    },
    telnetCanDisable() {
      return !this.telnetLoading && this.telnetRows.some(r => r.enabled !== false)
    }
  },
  watch: {
    // 光模块状态筛选由后端查（opticState 只有后端判得出来），条件一变就重新请求
    opticFilter() {
      this.query.page = 1
      this.load()
    }
  },
  created() {
    this.load()
    this.loadOnline()
    this.loadStat()
    this.loadTemplates()
    this.loadCapabilityOptions()
    this.loadRpcMeta()
    this.loadPollConfig()
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
    if (this.taskTimer) {
      clearInterval(this.taskTimer)
      this.taskTimer = null
    }
  },
  methods: {
    /**
     * 有没有正在进行的交互：有就先不自动刷新。
     * 弹窗里的表单、勾好的设备行，都会被一次后台刷新冲掉。
     */
    hasBlockingDialog() {
      return !!(this.adaptVisible || this.wanVisible || this.wifiEditVisible ||
        this.detailVisible || this.onlineVisible || this.businessVisible ||
        this.customParamVisible || this.debugVisible || this.adminPwdVisible ||
        this.telnetVisible || this.remarkVisible || this.selection.length)
    },
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
        const res = await fetchDevicePage({
          ...this.query,
          sortKey: this.sortKey || undefined,
          sortOrder: this.sortOrder || undefined,
          optic: this.opticFilter || undefined
        })
        const data = res.data || {}
        this.list = data.items || []
        this.total = data.total || 0
        this.loadBiz()
        this.loadAdaptStatus()
        this.loadTasks()
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
    // 终端唯一标识「OUI-序列号」由后端拼（设备不一定报 ManufacturerOUI，
    // 台账里那份一定有），前端不再自己切片拼字符串
    terminalId(row) {
      return this.bizOf(row.sn).title || row.sn
    },
    // 铭牌 MAC：后端从 LAN 网口取，WAN 的 MAC 每条连接各有一份会随通道变，不能拿来认设备
    macOf(sn) {
      return this.bizOf(sn).mac || ''
    },

    // ---------------- 下发进度 ----------------
    /**
     * 回读每台设备正在进行的下发任务。
     *
     * <p>接口只返回还在跑的（PENDING / RUNNING），跑完自然从这一份里消失，
     * 行上的进度条也就自己没了——不用额外去摘。</p>
     */
    async loadTasks() {
      const sns = this.list.map(d => d.sn)
      if (!sns.length) {
        this.taskMap = {}
        return
      }
      try {
        const res = await fetchDeviceActiveTasks(sns)
        if (res && res.data) {
          this.taskMap = res.data
        }
      } catch (e) {
        // 静默失败：进度是附加信息，拉不到不该把整列掏空
      }
    },
    /**
     * 这一行要显示的动态提示，按「正在做什么」排：
     * 意图下发任务 → 本机提交的重启/恢复出厂 → 队列里压着的指令。
     */
    progressLines(sn) {
      const lines = []
      const row = this.taskMap[sn] || {}
      const tasks = row.tasks || []
      const task = tasks.length ? tasks[0] : null
      if (task) {
        // 文案（正在下发 xxx / 阶段名 / 排队条数）与状态键都由后端给，页面只上图标和颜色
        lines.push({
          key: 'task-' + (task.id || task.taskNo),
          icon: 'el-icon-loading',
          text: task.label,
          tag: task.phase,
          status: task.phaseKey || 'running'
        })
        if (task.queuedText) {
          lines.push({
            key: 'task-queued',
            icon: 'el-icon-time',
            text: task.queuedText,
            status: 'queued'
          })
        }
      }
      const rpc = this.rpcProgress[sn]
      if (rpc) {
        lines.push({
          key: 'rpc',
          icon: rpc.status === 'running' ? 'el-icon-loading' : 'el-icon-circle-check',
          text: rpc.label,
          status: rpc.status
        })
      }
      return lines
    },
    // 整页设备的机型适配状态一次拉回：以 SQLite 模板库里的适配结果为准，
    // 型号能力树 XML 只是归档产物，有没有不影响「已适配」的判定
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
      // 加密/密码真正可写的参数名与当前值由后端判定下发（不同固件参数名不同），
      // 前端不再自己猜协议参数
      this.wifiEditForm = {
        sn: row.sn,
        // 频段、实例号、开关都取后端判好的，前端不再自己解析路径与猜状态
        band: item.band,
        path: item.path || '',
        instance: item.instance,
        ssid: item.ssid || '',
        encryption: String(item.encryptionValue || ''),
        password: String(item.keyValue || ''),
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
        // 直接下发：业务类型（WIFI_24G / WIFI_58G）与意图键（wpa_mode / wpa_key / enable）
        // 都由后端翻译，页面不必知道下发引擎认哪些键；不建工单，受理即发
        const res = await dispatchDevices({
          sns: [form.sn],
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
        const row = ((res.data || {}).rows || [])[0] || {}
        if (row.errorMsg) {
          this.$message.error('下发失败：' + row.errorMsg)
        } else {
          this.$message.success('WLAN 修改任务已提交')
          this.wifiEditVisible = false
        }
      } catch (e) {
        this.$message.error('提交失败：' + ((e && e.message) || ''))
      } finally {
        this.wifiSubmitting = false
      }
    },

    sipList(sn) {
      return this.bizOf(sn).sip || []
    },
    // SIP 弹窗要展示的那一路：点哪条看哪条，路径对不上就退回整组
    sipDialogItems() {
      const list = this.sipList(this.sipDialogSn)
      if (!this.sipDialogPath) return list
      const picked = list.filter(item => item.path === this.sipDialogPath)
      return picked.length ? picked : list
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
    // 在线标记后端算好（/device/page 的 online 字段），与台账统计同一口径
    isOnline(row) {
      return row && row.online === true
    },
    // 连接名与状态文案都由后端给（没报 Name 时用「WAN PPP 1」这类兜底叫法），
    // 前端只决定标签颜色
    wanTagType(item) {
      if (item.up) return 'success'
      if (item.statusText === '-') return 'info'
      return 'danger'
    },
    // 地址协议徽标配色：单栈 IPv4 绿色，IPv6-only 橙色（少见，提醒留意），双栈灰色
    ipTagType(protocol) {
      if (protocol === 'IPv6') return 'warning'
      if (protocol === 'IPv4+IPv6') return 'info'
      return 'success'
    },

    // ---------------- WAN 连接参数弹窗 ----------------
    openWanParams(sn) {
      this.wanDialogSn = sn
      this.wanVisible = true
    },

    // SIP 语音参数：同样只看本地快照。这里刻意不调 collectDeviceBusiness——
    // 点一下就把设备叫醒（连接请求 + 一轮 GPV）既慢又会打扰正在用的用户，
    // 要最新值请走详情面板的「重新采集」
    /**
     * 这台设备能下发的语音线路清单：路数、每路当前号码全由后端算
     * （申报能力与实际采到的路数取大值、VoiceProfile 级服务器参数已补进每一路）。
     */
    sipLineOptions(sn) {
      return this.sipLines || []
    },

    /** 点开的这一行属于第几路：后端在每条 SIP 行上给了 line，取不到算第 1 路。 */
    sipLineOf(item) {
      return item && item.line ? Number(item.line) : 1
    },

    /** 拉这台的语音线路清单（只读库里的快照，不唤醒设备） */
    loadSipLines(sn) {
      if (!sn) return Promise.resolve()
      return fetchSipLines(sn)
        .then(res => {
          this.sipLines = res.data || []
        })
        .catch(() => {
          this.sipLines = []
        })
    },

    async openSipParams(sn, item) {
      const line = this.sipLineOf(item)
      this.sipDialogSn = sn
      this.sipDialogPath = (item && item.path) || ''
      this.sipShowPwd = false
      this.sipLines = []
      this.sipEditForm = {
        sn,
        path: (item && item.path) || '',
        // 默认就是点开的那一路，避免在多路设备上忘了勾却把第 1 路覆盖掉
        lines: [line],
        number: '',
        authUser: '',
        password: '',
        registrar: '',
        registrarPort: '',
        proxy: '',
        proxyPort: ''
      }
      this.sipVisible = true
      await this.loadSipLines(sn)
      const option = this.sipLines.find(row => row.line === line) || this.sipLines[0]
      if (!option) return
      this.sipEditForm = {
        ...this.sipEditForm,
        number: option.number || '',
        authUser: option.authUser || option.number || '',
        password: option.password || '',
        registrar: option.registrar || '',
        registrarPort: option.registrarPort || '',
        proxy: option.proxy || '',
        proxyPort: option.proxyPort || ''
      }
    },

    /**
     * 点「下发到设备」：只提交业务字段，按路拆单、占位符翻译、幂等键都由后端做。
     */
    async submitSipEdit() {
      const form = this.sipEditForm
      this.sipSubmitting = true
      try {
        const res = await submitSipEdit(form.sn, {
          lines: form.lines,
          number: form.number,
          authUser: form.authUser,
          password: form.password,
          registrar: form.registrar,
          registrarPort: form.registrarPort,
          proxy: form.proxy,
          proxyPort: form.proxyPort,
          username: this.$store.getters.name || 'admin'
        })
        const data = res.data || {}
        if (data.submitted) {
          this.$message.success('SIP 语音下发任务已提交 ' + data.submitted + ' 张（第 ' +
            (data.lines || []).join('、') + ' 路）')
        }
        const failures = data.failures || []
        failures.forEach(row => {
          this.$message.error('第 ' + row.line + ' 路提交失败：' + (row.message || ''))
        })
        if (!data.failed) {
          this.sipVisible = false
        }
      } catch (e) {
        this.$message.error('提交失败：' + ((e && e.message) || ''))
      } finally {
        this.sipSubmitting = false
      }
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
      // 运营商中文名与能力串都由后端给（适配状态接口），页面不再维护码表
      const ispText = adapt.ispText || row.isp || ''
      const rows = [
        { label: 'OUI', value: row.oui },
        { label: '设备 SN', value: row.sn },
        { label: 'MAC 地址', value: mac },
        { label: '运营商', value: (ispText + (row.ispVersion ? ' ' + row.ispVersion : '')).trim() },
        {
          label: '设备能力',
          value: adapt.adapted ? (adapt.capabilityText || '') : (biz.capabilityText || '')
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
    // 能力串（未适配看业务快照、已适配看适配申报）都由后端拼好，页面只显示
    // 业务类型由后端判定（键名含 ServiceList，不区分厂商前缀）
    serviceMode(item) {
      return item.service || '-'
    },
    showVal(value) {
      return value === undefined || value === null || value === '' ? '-' : value
    },

    /**
     * 表头字段排序，由终端信息 / 运行信息列组件抛出。
     *
     * <p>排序键交给后端（台账列落 SQL，PON / 运行时长 / 管理 IP 走业务快照），
     * 换一次排序就重新查一次：原来只排当前页，翻到第 2 页顺序就变了。</p>
     */
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
      this.query.page = 1
      this.load()
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
    /**
     * 快捷操作的命令 → RPC 映射：只认后端 /device/rpc/quick-commands 下发的结果，
     * 缺失即后端漏配，明确报错，前端不做任何兜底映射。
     */
    rpcOf(cmd) {
      const rpc = this.quickCommandRpc[cmd]
      if (!rpc) {
        this.$message.error('命令未在后端配置映射（/device/rpc/quick-commands）：' + cmd)
        return null
      }
      return rpc
    },
    async loadRpcMeta() {
      try {
        const [methods, commands, labels] = await Promise.all([
          fetchRpcMethods(), fetchQuickCommands(), fetchRpcProgressLabels()
        ])
        if (Array.isArray(methods.data) && methods.data.length) {
          this.rpcMethods = methods.data
        }
        if (commands.data && typeof commands.data === 'object') {
          this.quickCommandRpc = commands.data
        }
        if (labels.data && typeof labels.data === 'object') {
          this.rpcLabels = labels.data
        }
      } catch (e) {
        // 请求层已提示错误；清单留空暴露后端问题，前端不做兜底
      }
    },
    /** 轮询节奏由后端给，拿到再建定时器；接口挂了就用默认值，页面不能因此不刷新 */
    async loadPollConfig() {
      try {
        const res = await fetchPollConfig()
        if (res.data && typeof res.data === 'object') {
          this.pollConfig = { ...this.pollConfig, ...res.data }
        }
      } catch (e) {
        // 用默认节奏，不阻塞页面
      }
      this.startTimers()
    },
    /**
     * 建三个定时器：列表、任务进度、业务概要。
     *
     * <p>间隔来自 {@link #loadPollConfig}（后端 /device/poll-config）：
     * 原来写死在页面顶部，现场想调慢就得发版。</p>
     */
    startTimers() {
      const config = this.pollConfig
      this.bizTimer = setInterval(() => {
        if (!document.hidden) {
          this.reloadBiz()
        }
      }, Math.max(1, config.bizSeconds) * 1000)
      this.taskTimer = setInterval(() => {
        if (!document.hidden) {
          this.loadTasks()
        }
      }, Math.max(1, config.taskSeconds) * 1000)
      // 列表自动刷新：在线状态、顶栏总数一起跟上；弹窗开着或勾了行时不刷，
      // 免得顶掉正在填的表单和选中的设备
      this.listTimer = setInterval(() => {
        if (!document.hidden && !this.hasBlockingDialog()) {
          this.load(true)
          this.loadStat()
          this.loadOnline()
        }
      }, Math.max(1, config.listSeconds) * 1000)
    },
    handleDeviceCommand(cmd, row) {
      if (cmd === 'business') {
        this.openBusiness(row.sn)
        return
      }
      const rpc = this.rpcOf(cmd)
      if (!rpc) return
      if (cmd === 'probe') {
        this.quickRpc(row.sn, rpc, {}, false)
        return
      }
      if (cmd === 'reboot') {
        this.$confirm('确认重启设备 ' + row.sn + '？', '重启设备', { type: 'warning' })
          .then(() => this.quickRpc(row.sn, rpc, {}, true))
          .catch(() => {})
        return
      }
      if (cmd === 'factoryReset') {
        this.$confirm('恢复出厂会清空设备全部配置，确认继续？', '恢复出厂', { type: 'warning' })
          .then(() => this.quickRpc(row.sn, rpc, {}, true))
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
        // 单条 RPC 不进下发任务表，行上的「正在重启」得由提交方自己记：
        // 别的页面轮询任务表是看不到这次操作的
        this.$set(this.rpcProgress, sn, {
          // 记住方法名：出结果时按同一份后端措辞换成「完成 / 失败」
          rpcName,
          label: this.rpcProgressLabel(rpcName),
          status: 'running'
        })
        this.pollQuickRpc(sn, data.taskId)
      } catch (e) {
        this.$message.error('下发失败：' + (e.message || ''))
      }
    },
    pollQuickRpc(sn, taskId) {
      const config = this.pollConfig || {}
      const maxAttempts = Math.max(1, config.rpcMaxAttempts || 60)
      const intervalMs = Math.max(1, config.rpcSeconds || 3) * 1000
      let count = 0
      const timer = setInterval(async() => {
        count += 1
        try {
          const res = await fetchRpcResult(taskId)
          const data = res.data
          if (data && data.status === 'success') {
            clearInterval(timer)
            this.$message.success('执行成功（任务号 ' + taskId + '）')
            this.markRpcDone(sn, 'success')
          } else if (data && data.status === 'fail') {
            clearInterval(timer)
            this.$message.warning('执行失败：' + (data.faultString || data.faultCode || ''))
            this.markRpcDone(sn, 'fail')
          }
        } catch (e) { /* 单次失败不打断 */ }
        if (count >= maxAttempts) {
          clearInterval(timer)
          this.clearRpc(sn)
        }
      }, intervalMs)
    },
    /**
     * RPC 行内措辞：正在做什么、做完/失败怎么说，全由后端
     * {@code /device/rpc/progress-labels} 给，页面不再做「去掉正在再拼完成」这种字符串手术。
     */
    rpcLabelsOf(rpcName) {
      return this.rpcLabels[rpcName] || this.rpcLabels.default || {}
    },
    rpcProgressLabel(rpcName) {
      const labels = this.rpcLabelsOf(rpcName)
      return labels.running || ('正在下发 ' + rpcName)
    },
    /**
     * 出结果后把行上的提示换成完成时/失败时的说法，留一会儿让人看到，
     * 再自己消失——指令跑完一行字立刻不见，看的人会以为没执行。
     */
    markRpcDone(sn, result) {
      const cur = this.rpcProgress[sn]
      if (!cur) return
      const labels = this.rpcLabelsOf(cur.rpcName)
      // 后端总会带 default 兜底；这里只留一个最短的保底词，不再拿「正在重启」去拼「已完成」
      this.$set(this.rpcProgress, sn, {
        label: labels[result] || (result === 'success' ? '已完成' : '执行失败'),
        status: result
      })
      setTimeout(() => this.clearRpc(sn), 6000)
    },
    clearRpc(sn) {
      this.$delete(this.rpcProgress, sn)
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
      // 模板的 category 即业务类型，直接下发；取不到就留空由下发侧推导
      this.batchDispatch(sns, (tpl.category || '').toUpperCase(), tpl.name || '模版下发', id)
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
     * 给多台设备逐台直接下发：不建工单，受理即发，进度看下发任务本身。
     *
     * <p>单台失败记录下来但继续其余设备，最后汇总失败清单——
     * 不能因为某一台有问题就把整批吞掉。</p>
     */
    batchDispatch(sns, category, label, templateId) {
      this.$confirm(
        `将对已勾选的 ${sns.length} 台设备下发「${label || '业务'}」，确认提交？`,
        '批量下发',
        { type: 'warning' }
      ).then(async() => {
        try {
          const res = await dispatchDevices({
            sns,
            category: category || '',
            templateId: templateId || null,
            values: {},
            username: this.$store.getters.name || 'admin'
          })
          const rows = ((res.data || {}).rows) || []
          // 失败行转成 { sn, err } 复用统一的批量结果汇总
          const results = rows.map(r => (r.errorMsg ? { sn: r.sn, err: r.errorMsg } : null))
          this.reportBatchResult(results, sns.length)
        } catch (e) {
          /* 拦截器已提示 */
        }
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
      // 恢复出厂类命令走后端 quick-commands 映射的 RPC，其余待后端接入
      if (['factory-clear', 'factory-reset', 'deep-factory-reset', 'deep-factory-clear'].includes(cmd)) {
        const rpc = this.rpcOf(cmd)
        if (!rpc) return
        const sns = this.requireSelection()
        if (!sns) return
        this.$confirm('恢复出厂会清空设备全部配置，对已勾选 ' + sns.length + ' 台设备执行？', '恢复出厂', { type: 'warning' })
          .then(() => sns.forEach(sn => this.quickRpc(sn, rpc, {}, true)))
          .catch(() => {})
        return
      }
      // 开通 / 检修：按勾选设备逐台建单，业务类型留空由下发侧推导
      if (cmd === 'worklist-batch') {
        const sns = this.requireSelection()
        if (!sns) return
        this.batchDispatch(sns, '', '开通业务')
        return
      }
      if (cmd === 'worklist-repair') {
        const sns = this.requireSelection()
        if (!sns) return
        this.batchDispatch(sns, '', '检修任务')
        return
      }
      // 查询超管密码：对勾选设备逐台实时读，结果在弹窗里明文展示
      if (cmd === 'query-admin-pwd') {
        this.openAdminPwd()
        return
      }
      // 开启/查询 Telnet：打开弹窗并实时读取当前状态
      if (cmd === 'query-telnet') {
        this.openTelnet()
        return
      }
      // 关闭 Telnet：直接对勾选设备下发关闭指令
      if (cmd === 'close-telnet') {
        const sns = this.requireSelection()
        if (!sns) return
        this.$confirm('确认关闭已勾选 ' + sns.length + ' 台设备的 Telnet？', '关闭 Telnet', { type: 'warning' })
          .then(() => {
            let done = 0
            let fail = 0
            sns.forEach(sn => {
              setTelnetEnabled(sn, false)
                .then(() => { done++ })
                .catch(() => { fail++ })
                .finally(() => {
                  if (done + fail === sns.length) {
                    this.$message.success('已提交关闭 Telnet 指令：成功 ' + done + ' 台' + (fail ? '，失败 ' + fail + ' 台' : ''))
                  }
                })
            })
          })
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
    /**
     * 导出台账：列、状态文案、时间格式都由后端生成，页面只把文本存成文件。
     * 勾选了就导出勾选的，没勾选按当前筛选条件导出（后端最多 5000 条）。
     */
    async exportDevices(sns) {
      try {
        const params = sns.length ? { sns: sns.join(',') } : { ...this.query }
        delete params.page
        delete params.size
        const res = await exportDevicesApi(params)
        const data = res.data || {}
        const blob = new Blob(['\ufeff' + (data.content || '')], { type: 'text/csv;charset=utf-8' })
        const link = document.createElement('a')
        link.href = URL.createObjectURL(blob)
        link.download = data.fileName || 'devices.csv'
        link.click()
        URL.revokeObjectURL(link.href)
      } catch (e) {
        this.$message.error('导出失败：' + ((e && e.message) || ''))
      }
    },
    refreshStatus() {
      this.load()
      this.loadOnline()
      this.$message.success('已刷新列表与在线状态')
    },
    // 批量重启：勾选设备逐台下发（RPC 由后端 quick-commands 映射决定）
    batchReboot() {
      const rpc = this.rpcOf('batch-reboot')
      if (!rpc) return
      const sns = this.requireSelection()
      if (!sns) return
      this.$confirm('确认重启已勾选的 ' + sns.length + ' 台设备？', '批量重启', { type: 'warning' })
        .then(() => sns.forEach(sn => this.quickRpc(sn, rpc, {}, true)))
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
      // 查询中是本页的临时状态；结果文案（已获取 / 无账号 / 超时 / 失败 / 离线）由后端给
      if (row.loading) return '查询中'
      return row.statusText || '未知'
    },
    // 状态标签只说明结果类型，具体原因（设备回 9005 / 未回应 / 离线）在这列讲清楚，
    // 否则在线设备上两个横杠，看不出到底是设备不支持还是查询本身出了问题
    adminPwdHint(row) {
      if (row.loading) return '正在向设备读取账号与口令…'
      if (row.message) return row.message
      if (row.accounts && row.accounts.length > 1) {
        return '共 ' + row.accounts.length + ' 组账号，展开行可看全部'
      }
      return '-'
    },
    adminPwdTagType(row) {
      if (row.loading) return 'info'
      return row.statusLevel || 'info'
    },
    // ===== Telnet 查询与开关 =====
    // 走 credentials 接口：未开启的设备后端会自动下发开启并读取默认账号密码
    openTelnet() {
      const sns = this.requireSelection()
      if (!sns) return
      this.telnetVisible = true
      this.telnetLoading = true
      this.telnetPwdShow = false
      this.telnetRows = sns.map(sn => ({
        sn, loading: true, status: '', enabled: null, path: '', message: '',
        username: '', password: '', port: ''
      }))
      const settle = () => {
        this.telnetLoading = this.telnetRows.some(r => r.loading)
      }
      sns.forEach((sn, idx) => {
        fetchTelnetCredentials(sn)
          .then((res) => {
            const d = (res && res.data) || {}
            const row = this.telnetRows[idx]
            row.status = d.status || ''
            row.statusText = d.statusText || ''
            row.statusLevel = d.statusLevel || ''
            row.enabled = d.enabled
            row.path = d.path || ''
            row.message = d.message || ''
            row.username = d.username || ''
            row.password = d.password || ''
            row.port = d.port || ''
            // 未开启但已自动下发开启成功的，说明里带上提示
            if (d.enablePath && d.enabled === true) {
              row.message = row.message || 'Telnet 原未开启，已自动开启并读取账号密码'
            }
            row.loading = false
          })
          .catch((err) => {
            const row = this.telnetRows[idx]
            row.status = 'fail'
            row.statusText = '失败'
            row.statusLevel = 'danger'
            row.enabled = null
            row.message = (err && err.message) || '查询失败'
            row.loading = false
          })
          .finally(settle)
      })
    },
    reloadTelnet() {
      this.openTelnet()
    },
    telnetStatusText(row) {
      if (row.loading) return '查询中'
      return row.statusText || row.status || '未知'
    },
    telnetTagType(row) {
      if (row.loading) return 'info'
      return row.statusLevel || 'info'
    },
    telnetHint(row) {
      if (row.loading) return '正在向设备读取 Telnet 使能状态…'
      if (row.message) return row.message
      return '-'
    },
    batchTelnetSwitch(enabled, sns) {
      const targetSns = sns || this.requireSelection()
      if (!targetSns) return
      const actionText = enabled ? '开启' : '关闭'
      // 弹窗内点击时更新对应行的临时状态
      targetSns.forEach(sn => {
        const row = this.telnetRows.find(r => r.sn === sn)
        if (row) {
          row.loading = true
          row.status = ''
          row.statusText = ''
          row.message = ''
        }
      })
      this.telnetLoading = true
      const settle = () => {
        this.telnetLoading = this.telnetRows.some(r => r.loading)
      }
      targetSns.forEach((sn) => {
        setTelnetEnabled(sn, enabled)
          .then((res) => {
            const d = (res && res.data) || {}
            const row = this.telnetRows.find(r => r.sn === sn)
            if (row) {
              row.status = d.status || ''
              row.statusText = d.statusText || ''
              row.statusLevel = d.statusLevel || ''
              row.message = d.message || ''
              // 下发成功但不立即刷新 enabled，因为设备可能还没应用；任务ID给提示
              if (d.taskId) {
                row.message = (row.message || '已下发') + ' (任务' + d.taskId + ')'
              }
              row.loading = false
            }
          })
          .catch((err) => {
            const row = this.telnetRows.find(r => r.sn === sn)
            if (row) {
              row.status = 'fail'
              row.statusText = '失败'
              row.statusLevel = 'danger'
              row.message = (err && err.message) || actionText + '失败'
              row.loading = false
            }
          })
          .finally(settle)
      })
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
.cell-sn {
  color: #606266;
  font-family: Consolas, Monaco, monospace;
}
/* 下发进度：整行淡底浅色高亮，别压过重——它只是暂时的状态，不是台账内容 */
.cell-tasks {
  margin-top: 4px;
  padding: 3px 6px;
  border-radius: 3px;
  background: #fdf6ec;
}
.cell-task {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #e6a23c;
  font-size: 12px;
  line-height: 18px;
}
.cell-task .el-tag {
  margin-left: auto;
}
/* 铭牌 MAC：设备标签上印的，单独一行压在最下面 */
.cell-mac {
  margin-top: 4px;
  color: #909399;
  font-size: 12px;
  font-family: Consolas, Monaco, monospace;
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
.adapt-result.is-warn {
  border-color: #e6a23c;
  background: #fdf6ec;
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
.adapt-reason-warn {
  color: #e6a23c;
}
.adapt-checks {
  margin: 6px 0;
}
.adapt-check {
  display: flex;
  align-items: center;
  line-height: 22px;
}
.adapt-check-label {
  margin-left: 6px;
  font-weight: 600;
}
.adapt-check-detail {
  margin-left: 8px;
  color: #606266;
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
/* SIP 行可点开：给个手型与悬停底色，否则用户不知道这一列能点 */
.sip-row {
  cursor: pointer;
  border-radius: 3px;
  padding: 1px 3px;
  margin-left: -3px;
}
.sip-row:hover {
  background: #f0f7ff;
}
.snapshot-tip {
  float: left;
  font-size: 12px;
  color: #909399;
  line-height: 32px;
}
.pwd-eye {
  cursor: pointer;
  color: #909399;
  margin-right: 6px;
}
.sip-readonly-tip {
  margin: -4px 0 8px 118px;
  font-size: 12px;
  color: #c0c4cc;
}
.sip-line-pick {
  padding: 6px 10px;
  background: #f5f7fa;
  border-radius: 3px;
}
.sip-line-pick .el-checkbox {
  margin-right: 14px;
}
.sip-line-num {
  margin-left: 4px;
  font-size: 12px;
  color: #909399;
}
.sip-line-tip {
  margin-top: 4px;
  font-size: 12px;
  color: #c0c4cc;
}
.snapshot-tip-block {
  margin-bottom: 10px;
  padding: 6px 10px;
  font-size: 12px;
  color: #909399;
  background: #f5f7fa;
  border-radius: 3px;
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
.sip-edit-path,
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
/* 协议 / MTU 徽标跟在同一行文字后面，留一点间距 */
.wan-param-row .v .el-tag {
  margin-left: 6px;
  vertical-align: middle;
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
.admin-pwd-tip-warn {
  color: #f56c6c;
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
