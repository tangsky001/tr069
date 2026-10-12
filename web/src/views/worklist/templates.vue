<template>
  <div class="app-container">
    <el-card shadow="never" class="desc-card">
      <div class="desc">
        配置模板用于创建 ONU 上各个配置项的设置，比如局域网、广域网、WiFi、语音等。
        模板以文件形式存放在配置根目录，保存后立即热加载，无需重启服务。
      </div>
      <div class="create-bar">
        <span class="create-label">新增模板：</span>
        <el-button size="small" class="cat-btn cat-BIZ" @click="openCreate('BIZ')">业务模板</el-button>
        <el-button
          v-for="c in categories"
          :key="c.code"
          size="small"
          :class="['cat-btn', 'cat-' + c.code]"
          @click="openCreate(c.code)"
        >{{ c.label }}</el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" :model="query" size="small">
        <el-form-item label="业务分组">
          <el-select v-model="query.group" clearable placeholder="全部分组" style="width:150px">
            <el-option v-for="g in groups" :key="g" :label="g" :value="g" />
          </el-select>
        </el-form-item>
        <el-form-item label="模板类型">
          <el-select v-model="query.category" clearable placeholder="全部类型" style="width:150px">
            <el-option v-for="c in categories" :key="c.code" :label="c.label" :value="c.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="设备类型">
          <el-select v-model="query.deviceType" clearable filterable placeholder="全部" style="width:180px">
            <el-option v-for="d in options.deviceTypes" :key="d" :label="d" :value="d" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="模板名 / 说明" clearable style="width:180px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="handleSearch">搜索</el-button>
          <el-button icon="el-icon-refresh" @click="resetQuery">清除</el-button>
          <el-button icon="el-icon-refresh-right" @click="reload">重新加载配置</el-button>
        </el-form-item>
      </el-form>
      <div class="batch-bar">
        <span>共 {{ filtered.length }} 条，已选 {{ selection.length }} 条</span>
        <el-button
          type="danger"
          size="mini"
          icon="el-icon-delete"
          :disabled="selection.length === 0"
          @click="batchDelete"
        >批量删除</el-button>
      </div>
    </el-card>

    <el-table
      v-loading="loading"
      :data="pagedList"
      border
      fit
      size="small"
      highlight-current-row
      style="width:100%"
      @selection-change="onSelectionChange"
    >
      <el-table-column type="selection" width="45" />
      <el-table-column label="ID" width="60">
        <template slot-scope="{ row, $index }">
          <span>{{ (query.page - 1) * query.size + $index + 1 }}</span>
        </template>
      </el-table-column>
      <el-table-column label="名称 / 业务分组" min-width="200" show-overflow-tooltip>
        <template slot-scope="{ row }">
          <div class="cell-main">{{ row.name }}</div>
          <div class="cell-sub">{{ row.group || '-' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="模板类型" width="120">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="tagType(row.category)">{{ categoryLabel(row.category) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="内容" min-width="240" show-overflow-tooltip>
        <template slot-scope="{ row }">
          <span>{{ contentSummary(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="备注" min-width="180" show-overflow-tooltip>
        <template slot-scope="{ row }">
          <span>{{ firstLine(row.doc) || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="130">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="row.enabled ? 'success' : 'info'">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
          <el-tooltip v-if="!row.stepsMigrated" content="执行步骤未迁移，下发会被拒绝" placement="top">
            <el-tag size="mini" type="warning">未迁移</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="编辑" width="170" fixed="right">
        <template slot-scope="{ row }">
          <el-button type="text" size="mini" icon="el-icon-edit" @click="openEdit(row)">编辑</el-button>
          <el-button type="text" size="mini" icon="el-icon-copy-document" @click="openCopy(row)">复制</el-button>
          <el-button type="text" size="mini" icon="el-icon-delete" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      :current-page.sync="query.page"
      :page-size.sync="query.size"
      :page-sizes="[20, 50, 100, 200]"
      :total="filtered.length"
      layout="total, sizes, prev, pager, next, jumper"
      @size-change="handleSearch"
      @current-change="handleSearch"
    />

    <el-dialog
      :title="dialogTitle"
      :visible.sync="dialogVisible"
      width="1080px"
      top="4vh"
      :close-on-click-modal="false"
    >
      <div v-if="mode === 'create'" class="mode-bar">
        <el-radio-group v-model="wizardMode" size="mini">
          <el-radio-button :label="true">ITMS 向导（WAN）</el-radio-button>
          <el-radio-button :label="false">高级模式（参数 + 步骤）</el-radio-button>
        </el-radio-group>
        <span v-if="wizardMode" class="mode-tip">保存时自动生成参数定义与下发步骤，与手写 YAML 等价</span>
      </div>

      <!-- ==================== ITMS 向导模式 ==================== -->
      <el-form v-if="wizardMode" :model="wizard" label-width="150px" size="small">
        <el-form-item label="模版类型">
          <el-select v-model="wizard.category" style="width:220px" @change="onWizardTypeChange">
            <el-option
              v-for="t in wizardTypes"
              :key="t.code"
              :label="t.label"
              :value="t.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="模版名称" required>
          <el-input
            v-model="wizard.name"
            placeholder="汉字、字母、数字、下划线组成"
            style="width:320px"
            maxlength="64"
          />
        </el-form-item>
        <!-- ============ WAN 专属字段 ============ -->
        <template v-if="isWanWizard">
          <el-form-item label="IP 协议">
            <el-select v-model="wizard.ipProtocol" style="width:220px">
              <el-option label="IPv4（默认）" value="IPv4" />
              <el-option label="IPv6" value="IPv6" />
              <el-option label="IPv4 + IPv6" value="IPv4+IPv6" />
            </el-select>
          </el-form-item>
          <el-form-item label="WAN VLAN ID" required>
            <el-input v-model="wizard.vlanId" placeholder="如 201，为空表示无" style="width:220px" />
          </el-form-item>
          <el-form-item label="802.1p QoS 优先级">
            <el-input v-model="wizard.qos" placeholder="0-7，为空表示不下发" style="width:220px" />
          </el-form-item>
          <el-form-item label="业务模式">
            <el-select v-model="wizard.serviceMode" style="width:220px">
              <el-option label="Internet 上网" value="INTERNET" />
              <el-option label="语音（VOIP）" value="VOIP" />
              <el-option label="Internet/Other + 语音（VOIP）" value="INTERNET_VOIP" />
              <el-option label="其他/Other" value="OTHER" />
              <el-option label="电视（IPTV）" value="IPTV" />
              <el-option label="OTT" value="OTT" />
            </el-select>
          </el-form-item>
          <el-form-item label="绑定端口">
            <el-checkbox-group v-model="wizard.ports" class="port-group">
              <el-checkbox v-for="p in portOptions" :key="p.value" :label="p.value" class="port-item">
                <el-tag v-if="p.band === '2.4G'" size="mini" type="danger">2.4G</el-tag>
                <el-tag v-if="p.band === '5.8G'" size="mini" type="warning">5.8G</el-tag>
                {{ p.label }}
              </el-checkbox>
            </el-checkbox-group>
          </el-form-item>
          <el-form-item label="MTU（最大传输单元）">
            <el-input v-model="wizard.mtu" placeholder="1280-1500，为空不修改，PPPoE 最大 1492" style="width:320px" />
          </el-form-item>
          <el-form-item label="开启组播上行通道">
            <el-switch
              v-model="wizard.multicastEnable"
              active-text="是"
              inactive-text="否"
            />
          </el-form-item>
          <el-form-item v-if="wizard.multicastEnable" label="组播 VLAN ID">
            <el-input v-model="wizard.multicastVlan" placeholder="0 或为空表示不启用，可选" style="width:220px" />
          </el-form-item>
          <el-form-item label="增量下发 WAN 业务">
            <el-switch
              v-model="wizard.incremental"
              active-text="是"
              inactive-text="否"
            />
            <span class="mode-tip">是=在现有 WAN 基础上新增；否=下发前先清掉旧 WAN（需配合删除类步骤）</span>
          </el-form-item>
        </template>

        <!-- ============ WiFi 专属字段 ============ -->
        <template v-else-if="wizard.category.startsWith('WIFI')">
          <el-form-item label="启用无线 SSID">
            <el-switch v-model="wizard.wifiEnable" active-text="是" inactive-text="否" />
          </el-form-item>
          <el-form-item label="SSID 名称" required>
            <el-input v-model="wizard.wifiSsid" style="width:320px" maxlength="32" />
          </el-form-item>
          <el-form-item label="信道带宽">
            <el-select v-model="wizard.wifiBandwidth" style="width:220px">
              <el-option v-for="b in wifiBandwidths" :key="b" :label="b" :value="b" />
            </el-select>
          </el-form-item>
          <el-form-item :label="wizard.category === 'WIFI_24G' ? '2.4G WiFi 信道' : '5.8G WiFi 信道'">
            <el-select v-model="wizard.wifiChannel" style="width:220px">
              <el-option v-for="c in wifiChannels" :key="c" :label="c" :value="c" />
            </el-select>
          </el-form-item>
          <el-form-item label="隐藏 SSID">
            <el-switch v-model="wizard.wifiHidden" active-text="是" inactive-text="否" />
          </el-form-item>
          <el-form-item label="禁用 WPS 按键功能">
            <el-switch v-model="wizard.wifiWpsDisable" active-text="是" inactive-text="否" />
          </el-form-item>
          <el-form-item label="发射功率">
            <el-select v-model="wizard.wifiTxPower" style="width:220px">
              <el-option label="自动" value="自动" />
              <el-option label="高（100%）" value="1" />
              <el-option label="中（50%）" value="0.5" />
              <el-option label="低（25%）" value="0.25" />
            </el-select>
          </el-form-item>
          <el-form-item label="加密方式">
            <el-select v-model="wizard.wifiEncryption" style="width:220px" @change="onEncryptionChange">
              <el-option label="开放（无密码）" value="Basic" />
              <el-option label="WPA-PSK" value="WPA" />
              <el-option label="WPA2-PSK" value="11i" />
              <el-option label="WPA/WPA2 混合" value="WPAand11i" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="isWifiEncrypted" label="WPA 加密算法">
            <el-select v-model="wizard.wifiWpaAlgo" style="width:220px">
              <el-option label="AES+TKIP（默认）" value="AESEnabledandTKIPEnabled" />
              <el-option label="AES" value="AESEnabled" />
              <el-option label="TKIP" value="TKIPEncryptionEnabled" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="isWifiEncrypted" label="WPA 预共享密钥/WiFi 密码" required>
            <el-input v-model="wizard.wifiPassword" style="width:320px" maxlength="63" show-password />
          </el-form-item>
          <el-form-item label="无线硬件开关">
            <el-switch v-model="wizard.wifiHwSwitch" active-text="是" inactive-text="否" />
            <span class="mode-tip">厂商私有参数，仅存为占位参数，不发标准报文</span>
          </el-form-item>
        </template>

        <!-- ============ 业务模板专属字段 ============ -->
        <template v-else-if="wizard.category === 'BIZ'">
          <el-form-item label="配置模板成员" required>
            <div class="mode-tip">右侧候选按「分组 / 模板名」展示；选入左侧的成员自上而下按执行顺序下发</div>
            <el-transfer
              v-model="wizard.bizMembers"
              :data="bizCandidates"
              :titles="['当前成员', '候选模板']"
              :button-texts="['移除', '添加']"
              target-order="push"
              filterable
              filter-placeholder="输入关键字筛选"
            />
          </el-form-item>
        </template>

        <!-- ============ SIP 语音专属字段 ============ -->
        <template v-else>
          <el-form-item label="语音协议">
            <el-select v-model="wizard.sipProtocol" style="width:220px">
              <el-option label="自动（按 SIP 生成）" value="SIP" />
              <el-option label="SIP" value="SIP" />
              <el-option label="H.248（请用高级模式手写步骤）" value="H248" disabled />
            </el-select>
          </el-form-item>
          <el-form-item label="SIP 注册服务器地址" required>
            <el-input v-model="wizard.registrar" placeholder="IP 或域名" style="width:320px" />
          </el-form-item>
          <el-form-item label="SIP 注册服务器端口">
            <el-input v-model="wizard.registrarPort" style="width:220px" />
          </el-form-item>
          <el-form-item label="SIP 代理服务器地址">
            <el-input v-model="wizard.proxy" placeholder="可选，默认和 SIP 服务器相同" style="width:320px" />
          </el-form-item>
          <el-form-item label="SIP 代理服务器端口">
            <el-input v-model="wizard.proxyPort" style="width:220px" />
          </el-form-item>
          <el-form-item label="Outbound 服务器地址">
            <el-input v-model="wizard.outbound" placeholder="IP 或域名，可选" style="width:320px" />
          </el-form-item>
          <el-form-item label="Outbound 服务器端口">
            <el-input v-model="wizard.outboundPort" style="width:220px" />
          </el-form-item>
          <el-form-item label="备用 SIP 注册服务器地址">
            <el-input v-model="wizard.backupRegistrar" placeholder="IP 或域名，可选" style="width:320px" />
          </el-form-item>
          <el-form-item label="备用 SIP 注册服务器端口">
            <el-input v-model="wizard.backupRegistrarPort" style="width:220px" />
          </el-form-item>
          <el-form-item label="最小拨号长度">
            <el-input v-model="wizard.minDigits" placeholder="可选 1-3" style="width:220px" />
          </el-form-item>
          <el-form-item label="用户域名(UserAgentDomain)">
            <el-input v-model="wizard.userAgentDomain" placeholder="可选，一般为空，设为 - 表示清除" style="width:320px" />
          </el-form-item>
          <el-form-item label="数图（DigitMap）开关">
            <el-select v-model="wizard.digitMapMode" style="width:220px">
              <el-option label="== 忽略 ==" value="ignore" />
              <el-option label="下发" value="set" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="wizard.digitMapMode === 'set'" label="数图（DigitMap）规则">
            <el-input v-model="wizard.digitMapRule" placeholder="如 [2-8]xxxxxx" style="width:320px" />
          </el-form-item>
          <el-form-item label="禁用特殊号码拨号（1xx）">
            <el-switch v-model="wizard.disable1xx" active-text="是" inactive-text="否" />
          </el-form-item>
          <el-form-item label="SIP 用户信息">
            <el-input
              v-model="wizard.sipAccounts"
              type="textarea"
              :rows="3"
              placeholder="每行一条：电话号码 空格 密码，按行对应语音 Profile"
              style="width:420px"
            />
            <el-button type="text" size="mini" icon="el-icon-delete" @click="wizard.sipAccounts = ''">清空</el-button>
          </el-form-item>
          <el-form-item label="优先使用已存在的SIP账号信息">
            <el-switch v-model="wizard.preferExistingSip" active-text="是" inactive-text="否" />
            <span class="mode-tip">是=只下发服务器参数，保留设备上已有账号</span>
          </el-form-item>
        </template>
        <el-row :gutter="12">
          <el-col v-if="wizard.category !== 'BIZ'" :span="6">
            <el-form-item label="运营商">
              <el-select v-model="wizard.isp" filterable allow-create style="width:100%">
                <el-option v-for="i in options.isps" :key="i" :label="i" :value="i" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col v-if="wizard.category !== 'BIZ'" :span="6">
            <el-form-item label="设备类型">
              <el-select v-model="wizard.deviceType" filterable allow-create style="width:100%">
                <el-option v-for="d in options.deviceTypes" :key="d" :label="d" :value="d" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="分组">
              <el-select v-model="wizard.group" filterable allow-create clearable placeholder="== 选择分组 ==" style="width:100%">
                <el-option v-for="g in groups" :key="g" :label="g" :value="g" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="激活">
              <el-switch v-model="wizard.enabled" active-text="是" inactive-text="否" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="备注">
          <el-input v-model="wizard.doc" type="textarea" :rows="2" style="width:520px" />
        </el-form-item>
        <el-form-item v-if="wizard.category !== 'BIZ'" label="模板 key">
          <span class="key-text">{{ buildWizardKey() }}</span>
        </el-form-item>
      </el-form>

      <!-- ==================== 高级模式（原有参数 + 步骤编辑） ==================== -->
      <el-tabs v-else v-model="activeTab">
        <el-tab-pane label="基本信息" name="basic">
          <el-form :model="form" label-width="110px" size="small">
            <el-row :gutter="12">
              <el-col :span="8">
                <el-form-item label="模板名称" required>
                  <el-input v-model="form.name" placeholder="如 WAN_DHCP_41" />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="模板类型">
                  <el-select v-model="form.category" style="width:100%">
                    <el-option v-for="c in categories" :key="c.code" :label="c.label" :value="c.code" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="业务分组">
                  <el-input v-model="form.group" placeholder="可空" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="12">
              <el-col :span="6">
                <el-form-item label="运营商">
                  <el-select v-model="form.isp" filterable allow-create default-first-option style="width:100%">
                    <el-option v-for="i in options.isps" :key="i" :label="i" :value="i" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="6">
                <el-form-item label="规范版本">
                  <el-select v-model="form.version" filterable allow-create default-first-option style="width:100%">
                    <el-option v-for="v in options.versions" :key="v" :label="v" :value="v" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="6">
                <el-form-item label="业务域">
                  <el-select v-model="form.domain" filterable allow-create default-first-option style="width:100%">
                    <el-option v-for="d in options.domains" :key="d" :label="d" :value="d" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="6">
                <el-form-item label="设备类型">
                  <el-select v-model="form.deviceType" filterable allow-create default-first-option style="width:100%">
                    <el-option v-for="d in options.deviceTypes" :key="d" :label="d" :value="d" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="12">
              <el-col :span="6">
                <el-form-item label="文件形式">
                  <el-select v-model="form.format" style="width:100%">
                    <el-option label="YAML（推荐，支持步骤 options 与参数枚举）" value="yaml" />
                    <el-option label="XML（存量格式，不支持 options/枚举）" value="xml" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="4">
                <el-form-item label="启用">
                  <el-switch v-model="form.enabled" />
                </el-form-item>
              </el-col>
              <el-col :span="4">
                <el-form-item label="优先级">
                  <el-input-number v-model="form.priority" :min="1" :max="999" controls-position="right" style="width:100%" />
                </el-form-item>
              </el-col>
              <el-col :span="10">
                <el-form-item label="模板 key">
                  <span class="key-text">{{ buildKey(form) }}</span>
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item label="说明">
              <el-input v-model="form.doc" type="textarea" :rows="3" placeholder="用途说明，列表页备注列取首行" />
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="参数定义" name="args">
          <div class="tab-tip">
            参数在下发时作为 <code>${参数名}</code> 占位符使用，页面下发可填值，未填取这里的默认值。
          </div>
          <el-table :data="form.args" border size="small">
            <el-table-column label="参数名" width="180">
              <template slot-scope="{ row }">
                <el-input v-model="row.name" size="mini" placeholder="如 VLAN_ID" />
              </template>
            </el-table-column>
            <el-table-column label="默认值" width="160">
              <template slot-scope="{ row }">
                <el-input v-model="row.defaultValue" size="mini" />
              </template>
            </el-table-column>
            <el-table-column label="类型" width="120">
              <template slot-scope="{ row }">
                <el-select v-model="row.type" size="mini">
                  <el-option label="string" value="string" />
                  <el-option label="int" value="int" />
                  <el-option label="boolean" value="boolean" />
                  <el-option label="enum" value="enum" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="必填" width="70">
              <template slot-scope="{ row }">
                <el-checkbox v-model="row.required" />
              </template>
            </el-table-column>
            <el-table-column label="枚举值（逗号分隔，仅 enum）" min-width="180">
              <template slot-scope="{ row }">
                <el-input v-model="row.enumText" size="mini" placeholder="仅 enum 类型需要" />
              </template>
            </el-table-column>
            <el-table-column label="说明" min-width="180">
              <template slot-scope="{ row }">
                <el-input v-model="row.description" size="mini" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110">
              <template slot-scope="{ row, $index }">
                <el-button type="text" size="mini" :disabled="$index === 0" @click="moveArg($index, -1)">上移</el-button>
                <el-button type="text" size="mini" @click="removeArg($index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-button size="mini" icon="el-icon-plus" style="margin-top:8px" @click="addArg">添加参数</el-button>
        </el-tab-pane>

        <el-tab-pane label="执行步骤" name="steps">
          <div class="tab-tip">
            按顺序串行下发给设备。参数值支持 <code>${参数名}</code> 与 <code>${ctx.变量名}</code>（引用前面步骤 saveAs 的结果）。
            步骤为空的模板不允许下发。
          </div>
          <div v-for="(step, index) in form.steps" :key="index" class="step-item">
            <div class="step-head">
              <span class="step-index">步骤 {{ index + 1 }}</span>
              <el-button type="text" size="mini" :disabled="index === 0" @click="moveStep(index, -1)">上移</el-button>
              <el-button type="text" size="mini" :disabled="index === form.steps.length - 1" @click="moveStep(index, 1)">下移</el-button>
              <el-button type="text" size="mini" @click="copyStep(index)">复制</el-button>
              <el-button type="text" size="mini" class="danger-text" @click="removeStep(index)">删除</el-button>
            </div>
            <el-row :gutter="10">
              <el-col :span="7">
                <el-select v-model="step.rpc" size="mini" filterable placeholder="CWMP 方法" style="width:100%">
                  <el-option v-for="r in rpcMethods" :key="r" :label="r" :value="r" />
                </el-select>
              </el-col>
              <el-col :span="9">
                <el-input v-model="step.comment" size="mini" placeholder="步骤说明" />
              </el-col>
              <el-col :span="4">
                <el-input v-model="step.saveAs" size="mini" placeholder="结果存入变量" />
              </el-col>
              <el-col :span="4">
                <el-checkbox v-model="step.abortOnError" size="mini">失败中断</el-checkbox>
              </el-col>
            </el-row>
            <el-row :gutter="10" style="margin-top:6px">
              <el-col :span="10">
                <el-input v-model="step.objectName" size="mini" placeholder="objectName：AddObject/DeleteObject 的对象路径" />
              </el-col>
              <el-col :span="10">
                <el-input v-model="step.parameterPath" size="mini" placeholder="parameterPath：GetParameterNames 的参数路径" />
              </el-col>
              <el-col :span="4">
                <el-input-number
                  v-model="step.timeoutSeconds"
                  size="mini"
                  :min="0"
                  :max="600"
                  controls-position="right"
                  placeholder="超时秒"
                  style="width:100%"
                />
              </el-col>
            </el-row>
            <div class="step-sub">
              <div class="step-sub-head">
                <span>参数列表（SetParameterValues）</span>
                <el-button type="text" size="mini" @click="addStepParam(step)">添加</el-button>
              </div>
              <el-row v-for="(p, pi) in step.parameters" :key="'p' + pi" :gutter="8" class="step-sub-row">
                <el-col :span="11">
                  <el-input v-model="p.name" size="mini" placeholder="参数路径，如 InternetGatewayDevice.WANDevice.1..." />
                </el-col>
                <el-col :span="11">
                  <el-input v-model="p.value" size="mini" placeholder="值，如 ${VLAN_ID}" />
                </el-col>
                <el-col :span="2">
                  <el-button type="text" size="mini" class="danger-text" @click="removeStepParam(step, pi)">删除</el-button>
                </el-col>
              </el-row>
            </div>
            <div class="step-sub">
              <div class="step-sub-head">
                <span>options（Download/Reboot 等专用，写 YAML 才生效）</span>
                <el-button type="text" size="mini" @click="addStepOption(step)">添加</el-button>
              </div>
              <el-row v-for="(o, oi) in step.optionList" :key="'o' + oi" :gutter="8" class="step-sub-row">
                <el-col :span="8">
                  <el-input v-model="o.key" size="mini" placeholder="如 CommandKey / FileType / URL" />
                </el-col>
                <el-col :span="14">
                  <el-input v-model="o.value" size="mini" placeholder="值" />
                </el-col>
                <el-col :span="2">
                  <el-button type="text" size="mini" class="danger-text" @click="removeStepOption(step, oi)">删除</el-button>
                </el-col>
              </el-row>
            </div>
          </div>
          <el-button size="mini" icon="el-icon-plus" @click="addStep">添加步骤</el-button>
        </el-tab-pane>
      </el-tabs>

      <span slot="footer">
        <el-button @click="dialogVisible = false">{{ wizardMode ? '关闭' : '取消' }}</el-button>
        <el-button type="primary" :loading="saving" @click="submit">{{ wizardMode ? '保存设置' : '保存' }}</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import {
  fetchTemplates,
  fetchTemplateCategories,
  fetchTemplateOptions,
  fetchTemplateDetail,
  createTemplate,
  updateTemplate,
  copyTemplate,
  deleteTemplate,
  reloadConfig
} from '@/api/worklist'
import { saveBizTemplate } from '@/api/bizTemplate'

const RPC_METHODS = [
  'SetParameterValues',
  'GetParameterValues',
  'GetParameterNames',
  'SetParameterAttributes',
  'GetParameterAttributes',
  'AddObject',
  'DeleteObject',
  'Download',
  'Upload',
  'Reboot',
  'FactoryReset',
  'ScheduleInform',
  'ScheduleDownload',
  'CancelTransfer',
  'ChangeDUState',
  'GetRPCMethods',
  'GetQueuedTransfers',
  'GetAllQueuedTransfers',
  'SetVouchers',
  'GetOptions'
]

function emptyForm() {
  return {
    key: '',
    sourceKey: '',
    name: '',
    category: 'CUSTOM',
    group: '',
    isp: 'standard',
    version: 'v4.0',
    domain: 'business',
    deviceType: 'GPON_4+1',
    enabled: true,
    priority: 100,
    doc: '',
    format: 'yaml',
    args: [],
    steps: []
  }
}

// 向导支持的模版类型；code 里 WAN_BRIDGE 保存时映射为存量分类 WAN_ROUTE
const WIZARD_TYPES = [
  { code: 'WAN_DHCP', label: 'WAN DHCP' },
  { code: 'WAN_PPPOE', label: 'WAN PPPoE' },
  { code: 'WAN_STATIC', label: 'WAN 静态IP' },
  { code: 'WAN_BRIDGE', label: 'WAN 桥接' },
  { code: 'WIFI_24G', label: '2.4G WiFi' },
  { code: 'WIFI_58G', label: '5.8G WiFi' },
  { code: 'SIP', label: 'SIP 语音' },
  { code: 'BIZ', label: '业务模板' }
]

// 各频段的信道与带宽候选
const WIFI_CHANNELS = {
  WIFI_24G: ['自动', '1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12', '13'],
  WIFI_58G: ['自动', '36', '40', '44', '48', '149', '153', '157', '161', '165']
}
const WIFI_BANDWIDTH = {
  WIFI_24G: ['自动', '20MHz', '40MHz'],
  WIFI_58G: ['自动', '20MHz', '40MHz', '80MHz']
}

// 绑定端口候选：LAN 口 + 双频 SSID + PON 上联
const PORT_OPTIONS = [
  { value: 'LAN1', label: 'LAN1' }, { value: 'LAN2', label: 'LAN2' },
  { value: 'LAN3', label: 'LAN3' }, { value: 'LAN4', label: 'LAN4' },
  { value: 'LAN5', label: 'LAN5' }, { value: 'LAN6', label: 'LAN6' },
  { value: 'LAN7', label: 'LAN7' }, { value: 'LAN8', label: 'LAN8' },
  { value: 'SSID1', label: 'SSID1', band: '2.4G' }, { value: 'SSID2', label: 'SSID2', band: '2.4G' },
  { value: 'SSID3', label: 'SSID3', band: '2.4G' }, { value: 'SSID4', label: 'SSID4', band: '2.4G' },
  { value: 'SSID5', label: 'SSID5', band: '5.8G' }, { value: 'SSID6', label: 'SSID6', band: '5.8G' },
  { value: 'SSID7', label: 'SSID7', band: '5.8G' }, { value: 'SSID8', label: 'SSID8', band: '5.8G' },
  { value: 'PON', label: 'PON（FTTR 从光猫下行 PON 口）' }
]

function emptyWizard() {
  return {
    category: 'WAN_DHCP',
    name: '',
    ipProtocol: 'IPv4',
    vlanId: '',
    qos: '',
    serviceMode: 'INTERNET',
    ports: [],
    mtu: '',
    multicastEnable: false,
    multicastVlan: '',
    incremental: true,
    // SIP 语音专属字段
    sipProtocol: 'SIP',
    registrar: '',
    registrarPort: '5060',
    proxy: '',
    proxyPort: '5060',
    outbound: '',
    outboundPort: '',
    backupRegistrar: '',
    backupRegistrarPort: '',
    minDigits: '',
    userAgentDomain: '',
    digitMapMode: 'ignore',
    digitMapRule: '',
    disable1xx: false,
    sipAccounts: '',
    preferExistingSip: false,
    // WiFi 专属字段
    wifiEnable: true,
    wifiSsid: '',
    wifiBandwidth: '自动',
    wifiChannel: '自动',
    wifiHidden: false,
    wifiWpsDisable: false,
    wifiTxPower: '自动',
    wifiEncryption: 'Basic',
    wifiWpaAlgo: 'AESEnabledandTKIPEnabled',
    wifiPassword: '',
    wifiHwSwitch: false,
    // 业务模板成员（BIZ 向导用，存配置模板 key）
    bizMembers: [],
    group: '',
    doc: '',
    enabled: true,
    isp: 'standard',
    version: 'v4.0',
    domain: 'business',
    deviceType: 'GPON_4+1'
  }
}

export default {
  name: 'TemplateList',
  data() {
    return {
      loading: false,
      saving: false,
      list: [],
      selection: [],
      categories: [],
      options: { isps: [], versions: [], domains: [], deviceTypes: [] },
      query: { page: 1, size: 50, group: '', category: '', deviceType: '', keyword: '' },
      dialogVisible: false,
      dialogTitle: '新增配置模板',
      mode: 'create',
      wizardMode: true,
      activeTab: 'basic',
      rpcMethods: RPC_METHODS,
      wizardTypes: WIZARD_TYPES,
      portOptions: PORT_OPTIONS,
      bizCandidates: [],
      wizard: emptyWizard(),
      form: emptyForm()
    }
  },
  computed: {
    isWanWizard() {
      return this.wizard.category.startsWith('WAN')
    },
    wifiChannels() {
      return WIFI_CHANNELS[this.wizard.category] || ['自动']
    },
    wifiBandwidths() {
      return WIFI_BANDWIDTH[this.wizard.category] || ['自动']
    },
    isWifiEncrypted() {
      return this.wizard.wifiEncryption !== 'Basic'
    },
    groups() {
      const set = new Set()
      this.list.forEach(t => {
        if (t.group) set.add(t.group)
      })
      return Array.from(set)
    },
    filtered() {
      const keyword = this.query.keyword.trim().toLowerCase()
      return this.list.filter(t => {
        if (this.query.group && t.group !== this.query.group) return false
        if (this.query.deviceType && t.deviceType !== this.query.deviceType) return false
        if (this.query.category && t.category !== this.query.category) return false
        if (keyword) {
          const hay = (t.name + ' ' + (t.doc || '') + ' ' + t.key).toLowerCase()
          if (!hay.includes(keyword)) return false
        }
        return true
      })
    },
    pagedList() {
      const start = (this.query.page - 1) * this.query.size
      return this.filtered.slice(start, start + this.query.size)
    }
  },
  created() {
    this.loadMeta()
    this.load()
  },
  methods: {
    async loadMeta() {
      try {
        const [cat, opt] = await Promise.all([fetchTemplateCategories(), fetchTemplateOptions()])
        this.categories = cat.data || []
        this.options = opt.data || this.options
        const isps = this.options.isps || []
        const versions = this.options.versions || []
        const domains = this.options.domains || []
        const deviceTypes = this.options.deviceTypes || []
        this.form.isp = isps.includes('standard') ? 'standard' : (isps[0] || 'standard')
        this.form.version = versions[0] || 'v4.0'
        this.form.domain = domains[0] || 'business'
        this.form.deviceType = deviceTypes[0] || 'GPON_4+1'
      } catch (e) {
        this.categories = []
      }
    },
    async load() {
      this.loading = true
      try {
        const res = await fetchTemplates()
        this.list = res.data || []
      } catch (e) {
        this.list = []
      } finally {
        this.loading = false
      }
    },
    async reload() {
      await reloadConfig()
      this.$message.success('配置已重新加载')
      this.load()
    },
    handleSearch() {
      this.query.page = 1
    },
    resetQuery() {
      this.query = { page: 1, size: 50, group: '', category: '', deviceType: '', keyword: '' }
    },
    onSelectionChange(rows) {
      this.selection = rows
    },
    categoryLabel(code) {
      const hit = this.categories.find(c => c.code === code)
      return hit ? hit.label : code
    },
    tagType(code) {
      const map = {
        LAN: '', WAN_ROUTE: 'success', WAN_STATIC: 'success', WAN_DHCP: 'success',
        WAN_PPPOE: 'success', SIP: 'warning', WIFI_24G: 'danger', WIFI_58G: 'danger',
        FIRMWARE: 'info', OTHER: 'info', CUSTOM: 'info'
      }
      return map[code] || 'info'
    },
    contentSummary(row) {
      const args = row.args || {}
      const keys = Object.keys(args)
      if (keys.length === 0) return '-'
      return keys
        .slice(0, 3)
        .map(k => `${k}=${args[k] && args[k].defaultValue !== undefined ? args[k].defaultValue : ''}`)
        .join('  ')
    },
    firstLine(doc) {
      if (!doc) return ''
      const line = String(doc).split('\n').find(l => l.trim().length > 0)
      return line ? line.trim() : ''
    },
    buildKey(form) {
      return [form.isp, form.version, form.domain, form.deviceType, form.name].filter(Boolean).join('/')
    },

    // ---------------- 对话框 ----------------
    openCreate(category) {
      this.mode = 'create'
      this.dialogTitle = '新建模板'
      this.activeTab = 'basic'
      this.form = emptyForm()
      if (category) this.form.category = category
      // WAN/WiFi/SIP/业务模板走向导，其余分类（LAN/固件等）直接进高级模式
      const cat = String(category || '')
      this.wizardMode = !category || cat.startsWith('WAN') || cat.startsWith('WIFI') ||
        cat === 'SIP' || cat === 'BIZ'
      this.wizard = emptyWizard()
      if (cat === 'BIZ') {
        this.wizard.category = 'BIZ'
        this.loadBizCandidates()
      }
      const isps = this.options.isps || []
      const versions = this.options.versions || []
      const domains = this.options.domains || []
      const deviceTypes = this.options.deviceTypes || []
      const isp = isps.includes('standard') ? 'standard' : (isps[0] || 'standard')
      const version = versions[0] || 'v4.0'
      const domain = domains[0] || 'business'
      const deviceType = deviceTypes[0] || 'GPON_4+1'
      this.form.isp = isp
      this.form.version = version
      this.form.domain = domain
      this.form.deviceType = deviceType
      this.wizard.isp = isp
      this.wizard.version = version
      this.wizard.domain = domain
      this.wizard.deviceType = deviceType
      this.dialogVisible = true
    },
    async openEdit(row) {
      this.mode = 'edit'
      this.wizardMode = false
      this.dialogTitle = '编辑配置模板 - ' + row.name
      this.activeTab = 'basic'
      const res = await fetchTemplateDetail(row.key)
      const detail = res.data || row
      this.form = this.toForm(detail, 'edit')
      this.dialogVisible = true
    },
    async openCopy(row) {
      this.mode = 'copy'
      this.wizardMode = false
      this.dialogTitle = '复制模板 - ' + row.name
      this.activeTab = 'basic'
      const res = await fetchTemplateDetail(row.key)
      const detail = res.data || row
      this.form = this.toForm(detail, 'copy')
      this.form.name = detail.name + '_copy'
      this.dialogVisible = true
    },
    toForm(detail, mode) {
      const form = emptyForm()
      form.key = detail.key
      form.sourceKey = detail.key
      form.name = detail.name
      form.category = detail.category || 'OTHER'
      form.group = detail.group || ''
      form.isp = detail.isp
      form.version = detail.version
      form.domain = detail.domain
      form.deviceType = detail.deviceType
      form.enabled = detail.enabled !== false
      form.priority = detail.priority || 100
      form.doc = detail.doc || ''
      form.format = String(detail.sourceFile || '').toLowerCase().endsWith('.xml') ? 'xml' : 'yaml'
      if (mode === 'copy') {
        form.format = 'yaml'
      }

      const args = []
      const argMap = detail.args || {}
      Object.keys(argMap).forEach(name => {
        const a = argMap[name] || {}
        args.push({
          name,
          defaultValue: a.defaultValue === undefined || a.defaultValue === null ? '' : String(a.defaultValue),
          type: a.type || 'string',
          required: a.required !== false,
          enumText: (a.enumValues || []).join(','),
          description: a.description || ''
        })
      })
      form.args = args

      form.steps = (detail.steps || []).map(s => ({
        rpc: s.rpc || '',
        comment: s.comment || '',
        objectName: s.objectName || '',
        parameterPath: s.parameterPath || '',
        saveAs: s.saveAs || '',
        abortOnError: s.abortOnError !== false,
        nextLevel: !!s.nextLevel,
        timeoutSeconds: s.timeoutSeconds || 0,
        parameters: (s.parameters || []).map(p => ({ name: p.name || '', value: p.value || '' })),
        optionList: Object.keys(s.options || {}).map(k => ({ key: k, value: String(s.options[k]) }))
      }))
      return form
    },
    toPayload() {
      const args = {}
      this.form.args.forEach((a, index) => {
        if (!a.name) return
        const arg = {
          defaultValue: a.defaultValue,
          order: index + 1,
          required: !!a.required,
          type: a.type || 'string',
          description: a.description || ''
        }
        const enums = String(a.enumText || '')
          .split(',')
          .map(v => v.trim())
          .filter(Boolean)
        if (enums.length > 0) arg.enumValues = enums
        args[a.name] = arg
      })

      const steps = this.form.steps.map(s => {
        const step = {
          rpc: s.rpc,
          comment: s.comment || '',
          objectName: s.objectName || '',
          parameterPath: s.parameterPath || '',
          nextLevel: !!s.nextLevel,
          saveAs: s.saveAs || '',
          abortOnError: s.abortOnError !== false,
          timeoutSeconds: s.timeoutSeconds || 0
        }
        const parameters = (s.parameters || [])
          .filter(p => p.name || p.value)
          .map(p => ({ name: p.name || '', value: p.value || '' }))
        if (parameters.length > 0) step.parameters = parameters
        const options = {}
        ;(s.optionList || []).forEach(o => {
          if (o.key) options[o.key] = o.value
        })
        if (Object.keys(options).length > 0) step.options = options
        return step
      })

      return {
        name: this.form.name,
        category: this.form.category,
        group: this.form.group || '',
        isp: this.form.isp,
        version: this.form.version,
        domain: this.form.domain,
        deviceType: this.form.deviceType,
        enabled: this.form.enabled,
        priority: this.form.priority || 100,
        doc: this.form.doc || '',
        args,
        steps
      }
    },
    async submit() {
      if (this.wizardMode) {
        await this.submitWizard()
        return
      }
      if (!this.form.name) {
        this.$message.warning('模板名称不能为空')
        return
      }
      if (!this.form.steps.length) {
        await this.$confirm('该模板没有执行步骤，保存后不允许下发，仅能作为归档。是否继续？', '提示', {
          type: 'warning'
        }).catch(() => {
          throw new Error('cancel')
        })
      }
      this.saving = true
      try {
        const template = this.toPayload()
        if (this.mode === 'create') {
          await createTemplate({ template, format: this.form.format })
        } else if (this.mode === 'edit') {
          await updateTemplate({ key: this.form.key, template, format: this.form.format })
        } else {
          await copyTemplate({ sourceKey: this.form.sourceKey, template, format: this.form.format })
        }
        this.$message.success('已保存')
        this.dialogVisible = false
        this.load()
      } catch (e) {
        if (e && e.message !== 'cancel') {
          this.$message.error((e && e.message) || '保存失败')
        }
      } finally {
        this.saving = false
      }
    },

    // ---------------- 参数 / 步骤编辑 ----------------
    buildWizardKey() {
      const w = this.wizard
      return [w.isp, w.version, w.domain, w.deviceType, w.name].filter(Boolean).join('/')
    },
    onWizardTypeChange() {
      // 切到开放加密时密码字段自动隐藏，无需清值
    },
    onEncryptionChange() {
      // 占位：加密方式切换仅影响展示
    },

    /**
     * 把向导表单编译成与手写 YAML 等价的模板：业务字段变成带默认值的参数，
     * 步骤里的路径按 WAN_ADD.yaml 的占位符约定生成（${参数名} / ${ctx.wanInstance}）。
     */
    buildWizardTemplate() {
      if (this.wizard.category === 'SIP') {
        return this.buildSipTemplate()
      }
      if (this.wizard.category.startsWith('WIFI')) {
        return this.buildWifiTemplate()
      }
      const w = this.wizard
      const pppoe = w.category === 'WAN_PPPOE'
      const bridged = w.category === 'WAN_BRIDGE'
      const staticIp = w.category === 'WAN_STATIC'
      const objName = pppoe ? 'WANPPPConnection' : 'WANIPConnection'
      // 连接设备默认取实例 1，多连接设备下发时可在参数表单里改
      const WAN_DEV = 'InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1'
      const INST = '${WANConnectionDevice}.${ctx.wanInstance}.' + objName

      // ---------------- 参数 ----------------
      const args = {}
      let order = 1
      const addArg = (name, defaultValue, type, required, description, enumValues) => {
        const arg = { defaultValue, order: order++, required, type, description }
        if (enumValues) arg.enumValues = enumValues
        args[name] = arg
      }
      addArg('WANConnectionDevice', WAN_DEV, 'string', true,
        '连接设备路径，多 WAN 设备按实际改实例号')
      addArg('INCREMENTAL', w.incremental ? 'true' : 'false', 'boolean', true,
        '增量下发：true=保留现有 WAN 仅新增；false=先清理旧 WAN（需自行追加 DeleteObject 步骤）')
      addArg('SERVICE_MODE', w.serviceMode, 'enum', true, '业务列表',
        ['INTERNET', 'VOIP', 'INTERNET_VOIP', 'OTHER', 'IPTV', 'OTT'])
      addArg('IP_PROTOCOL', w.ipProtocol, 'enum', false, '地址族偏好',
        ['IPv4', 'IPv6', 'IPv4+IPv6'])
      if (w.vlanId !== '') {
        addArg('VLAN_ID', w.vlanId, 'string', true, 'WAN 侧 VLAN，空表示不带 VLAN')
      }
      if (w.qos !== '') {
        addArg('QOS_8021P', w.qos, 'int', false, '802.1p 优先级 0-7')
      }
      if (w.mtu !== '') {
        addArg('MTU', w.mtu, 'int', false, 'MTU，1280-1500，PPPoE 最大 1492')
      }
      if (pppoe) {
        addArg('PPPOE_USER', '', 'string', true, 'PPPoE 拨号账号')
        addArg('PPPOE_PASS', '', 'string', true, 'PPPoE 拨号口令')
      }
      if (staticIp) {
        addArg('STATIC_IP', '', 'string', true, '静态 IP 地址')
        addArg('STATIC_MASK', '255.255.255.0', 'string', true, '子网掩码')
        addArg('STATIC_GW', '', 'string', true, '默认网关')
      }
      const portPaths = (w.ports || []).map(this.portToPath).filter(Boolean)
      if (portPaths.length > 0) {
        addArg('BIND_PORTS', portPaths.join(','), 'string', false,
          '绑定端口完整路径，逗号分隔，可按设备实际增删')
      }
      if (w.multicastEnable) {
        addArg('MULTICAST_VLAN', w.multicastVlan === '' ? '0' : w.multicastVlan, 'int', false,
          '组播 VLAN，0 表示不启用')
      }
      addArg('Enable', 'true', 'boolean', true, '是否启用该连接')

      // ---------------- 步骤 ----------------
      const steps = []
      steps.push({
        comment: '创建 WAN 连接实例',
        rpc: 'AddObject',
        objectName: '${WANConnectionDevice}.' + objName + '.',
        saveAs: 'wanInstance',
        abortOnError: true,
        parameters: [],
        options: {}
      })

      const params = [
        { name: INST + '.Name', value: '${SERVICE_MODE}_R_VID_' + (w.vlanId === '' ? '' : '${VLAN_ID}') },
        { name: INST + '.ConnectionType', value: pppoe ? 'PPPoE' : (bridged ? 'IP_Bridged' : 'IP_Routed') }
      ]
      if (!pppoe && !bridged) {
        params.push({ name: INST + '.AddressingType', value: staticIp ? 'Static' : 'DHCP' })
      }
      params.push({ name: INST + '.X_CT-COM_ServiceList', value: '${SERVICE_MODE}' })
      params.push({ name: INST + '.X_CT-COM_IPProtocolVersionMode', value: '${IP_PROTOCOL}' })
      if (!bridged) {
        // 需要公网出口的业务才开 NAT：上网、上网+语音、OTT；纯语音/IPTV 走桥接式转发
        const needNat = ['INTERNET', 'INTERNET_VOIP', 'OTT'].includes(w.serviceMode) || pppoe
        params.push({ name: INST + '.NATEnabled', value: needNat ? 'true' : 'false' })
      }
      if (pppoe) {
        params.push({ name: INST + '.Username', value: '${PPPOE_USER}' })
        params.push({ name: INST + '.Password', value: '${PPPOE_PASS}' })
        params.push({ name: INST + '.ConnectionTrigger', value: 'AlwaysOn' })
      }
      if (staticIp) {
        params.push({ name: INST + '.ExternalIPAddress', value: '${STATIC_IP}' })
        params.push({ name: INST + '.SubnetMask', value: '${STATIC_MASK}' })
        params.push({ name: INST + '.DefaultGateway', value: '${STATIC_GW}' })
      }
      if (w.vlanId !== '') {
        params.push({ name: INST + '.VLANIDMark', value: '${VLAN_ID}' })
      }
      if (w.qos !== '') {
        params.push({ name: INST + '.X_CT-COM_8021pPriority', value: '${QOS_8021P}' })
      }
      if (w.mtu !== '') {
        params.push({ name: INST + '.MaxMTUSize', value: '${MTU}' })
      }
      if (portPaths.length > 0) {
        params.push({ name: INST + '.X_CT-COM_LanInterface', value: '${BIND_PORTS}' })
      }
      if (w.multicastEnable) {
        params.push({ name: INST + '.X_CT-COM_MulticastVlan', value: '${MULTICAST_VLAN}' })
      }
      params.push({ name: INST + '.Enable', value: '${Enable}' })
      steps.push({
        comment: '下发连接参数',
        rpc: 'SetParameterValues',
        objectName: '',
        parameterPath: '',
        saveAs: '',
        abortOnError: true,
        parameters: params,
        options: {}
      })

      return {
        name: w.name,
        category: w.category === 'WAN_BRIDGE' ? 'WAN_ROUTE' : w.category,
        group: w.group || '',
        isp: w.isp,
        version: w.version,
        domain: w.domain,
        deviceType: w.deviceType,
        enabled: w.enabled,
        priority: 100,
        doc: (w.doc || '') + '\n(ITMS 向导生成: ' + w.category + ')',
        args,
        steps
      }
    },
    // 绑定端口 -> TR-098 接口路径；PON 口由厂商私有参数决定，这里不下发
    portToPath(port) {
      if (port.startsWith('LAN')) {
        return 'InternetGatewayDevice.LANDevice.1.LANEthernetInterfaceConfig.' + port.slice(3)
      }
      if (port.startsWith('SSID')) {
        return 'InternetGatewayDevice.LANDevice.1.WLANConfiguration.' + port.slice(4)
      }
      return ''
    },

    /**
     * SIP 语音模板：服务器/账号参数下发到 VoiceProfile.N.SIP 对象。
     * Profile 数 = SIP 用户信息行数；只发服务器时假定设备上 Profile.1 已存在。
     */
    buildSipTemplate() {
      const w = this.wizard
      const VOICE = '${VOICE_SERVICE}.VoiceProfile.'
      const lines = this.parseSipAccounts(w.sipAccounts)

      const args = {}
      let order = 1
      const addArg = (name, defaultValue, type, required, description) => {
        args[name] = { defaultValue, order: order++, required, type, description }
      }
      addArg('VOICE_SERVICE', 'InternetGatewayDevice.Services.VoiceService.1', 'string', true,
        '语音服务路径，按设备实际改实例号')
      addArg('REGISTRAR', w.registrar, 'string', true, 'SIP 注册服务器地址')
      addArg('REGISTRAR_PORT', w.registrarPort || '5060', 'int', true, 'SIP 注册服务器端口')
      addArg('PROXY', w.proxy || w.registrar, 'string', false, 'SIP 代理服务器地址，默认同注册服务器')
      addArg('PROXY_PORT', w.proxyPort || '5060', 'int', false, 'SIP 代理服务器端口')
      if (w.outbound) {
        addArg('OUTBOUND_PROXY', w.outbound, 'string', false, 'Outbound 服务器地址')
        addArg('OUTBOUND_PORT', w.outboundPort || '5060', 'int', false, 'Outbound 服务器端口')
      }
      if (w.backupRegistrar) {
        addArg('BACKUP_REGISTRAR', w.backupRegistrar, 'string', false, '备用 SIP 注册服务器地址')
        addArg('BACKUP_PORT', w.backupRegistrarPort || '5060', 'int', false, '备用 SIP 注册服务器端口')
      }
      if (w.userAgentDomain) {
        addArg('USER_AGENT_DOMAIN', w.userAgentDomain, 'string', false,
          '用户域名，- 表示清除设备上的配置')
      }
      if (w.digitMapMode === 'set') {
        addArg('DIGITMAP', w.digitMapRule, 'string', false, '数图规则')
      }
      if (!w.preferExistingSip) {
        lines.forEach((acc, i) => {
          addArg('SIP_USER_' + (i + 1), acc.user, 'string', true,
            '第 ' + (i + 1) + ' 个语音 Profile 的 SIP 账号')
          addArg('SIP_PASS_' + (i + 1), acc.pass, 'string', true,
            '第 ' + (i + 1) + ' 个语音 Profile 的 SIP 口令')
        })
      }

      const params = [
        { name: VOICE + '1.SIP.RegistrarServer', value: '${REGISTRAR}' },
        { name: VOICE + '1.SIP.RegistrarServerPort', value: '${REGISTRAR_PORT}' },
        { name: VOICE + '1.SIP.ProxyServer', value: '${PROXY}' },
        { name: VOICE + '1.SIP.ProxyServerPort', value: '${PROXY_PORT}' }
      ]
      if (w.outbound) {
        params.push({ name: VOICE + '1.SIP.OutboundProxy', value: '${OUTBOUND_PROXY}' })
        params.push({ name: VOICE + '1.SIP.OutboundProxyPort', value: '${OUTBOUND_PORT}' })
      }
      if (w.backupRegistrar) {
        params.push({ name: VOICE + '1.SIP.X_CT-COM_BackupRegistrarServer', value: '${BACKUP_REGISTRAR}' })
        params.push({ name: VOICE + '1.SIP.X_CT-COM_BackupRegistrarServerPort', value: '${BACKUP_PORT}' })
      }
      if (w.userAgentDomain) {
        params.push({ name: VOICE + '1.SIP.UserAgentDomain', value: '${USER_AGENT_DOMAIN}' })
      }
      if (w.digitMapMode === 'set') {
        params.push({ name: VOICE + '1.SIP.X_CT-COM_DigitMap', value: '${DIGITMAP}' })
      }
      // 账号按行对应 Profile：第 1 行写 Profile.1，第 2 行写 Profile.2 …
      if (!w.preferExistingSip) {
        lines.forEach((acc, i) => {
          const n = i + 1
          params.push({ name: VOICE + n + '.SIP.AuthUserName', value: '${SIP_USER_' + n + '}' })
          params.push({ name: VOICE + n + '.SIP.AuthPassword', value: '${SIP_PASS_' + n + '}' })
          params.push({ name: VOICE + n + '.SIP.UserName', value: '${SIP_USER_' + n + '}' })
          params.push({ name: VOICE + n + '.SIP.Password', value: '${SIP_PASS_' + n + '}' })
        })
      }

      const steps = [{
        comment: '下发 SIP 服务器与账号参数',
        rpc: 'SetParameterValues',
        objectName: '',
        parameterPath: '',
        saveAs: '',
        abortOnError: true,
        parameters: params,
        options: {}
      }]

      return {
        name: w.name,
        category: 'SIP',
        group: w.group || '',
        isp: w.isp,
        version: w.version,
        domain: w.domain,
        deviceType: w.deviceType,
        enabled: w.enabled,
        priority: 100,
        doc: (w.doc || '') + '\n(ITMS 向导生成: SIP 语音)',
        args,
        steps
      }
    },
    // SIP 用户信息：每行 "号码 密码"（空格/逗号/冒号分隔都认）
    parseSipAccounts(text) {
      return String(text || '')
        .split('\n')
        .map(l => l.trim())
        .filter(Boolean)
        .map(line => {
          const parts = line.split(/[\s,;，；:：]+/)
          return { user: parts[0] || '', pass: parts[1] || '' }
        })
    },

    /**
     * WiFi 模板：下发 WLANConfiguration 实例的常规参数。
     * 标准参数（Enable/SSID/BeaconType/PreSharedKey...）+ CT 私有（隐藏/限功率/带宽）。
     */
    buildWifiTemplate() {
      const w = this.wizard
      const is24 = w.category === 'WIFI_24G'
      const WLAN = 'InternetGatewayDevice.LANDevice.1.WLANConfiguration.${WLAN_INDEX}.'
      const encrypted = w.wifiEncryption !== 'Basic'

      const args = {}
      let order = 1
      const addArg = (name, defaultValue, type, required, description) => {
        args[name] = { defaultValue, order: order++, required, type, description }
      }
      addArg('WLAN_INDEX', is24 ? '1' : '5', 'int', true,
        'WLANConfiguration 实例号，2.4G 一般 1-4，5.8G 一般 5-8')
      addArg('SSID_NAME', w.wifiSsid, 'string', true, 'SSID 名称')
      if (encrypted) {
        addArg('WIFI_PASS', w.wifiPassword, 'string', true, 'WPA 预共享密钥，至少 8 位')
      }

      const params = [
        { name: WLAN + 'Enable', value: w.wifiEnable ? 'true' : 'false' },
        { name: WLAN + 'SSID', value: '${SSID_NAME}' },
        { name: WLAN + 'AutoChannelEnabled', value: w.wifiChannel === '自动' ? 'true' : 'false' }
      ]
      if (w.wifiChannel !== '自动') {
        params.push({ name: WLAN + 'Channel', value: w.wifiChannel })
      }
      if (w.wifiBandwidth !== '自动') {
        params.push({ name: WLAN + 'X_CT-COM_ChannelBandwidth', value: w.wifiBandwidth })
      }
      params.push({ name: WLAN + 'X_CT-COM_HideSSID', value: w.wifiHidden ? '1' : '0' })
      params.push({ name: WLAN + 'X_CT-COM_WPSDisable', value: w.wifiWpsDisable ? '1' : '0' })
      if (w.wifiTxPower !== '自动') {
        params.push({ name: WLAN + 'X_CT-COM_TransmitPower', value: w.wifiTxPower })
      }
      params.push({ name: WLAN + 'BeaconType', value: w.wifiEncryption })
      if (encrypted) {
        params.push({ name: WLAN + 'WPAEncryptionModes', value: w.wifiWpaAlgo })
        params.push({ name: WLAN + 'PreSharedKey', value: '${WIFI_PASS}' })
      } else {
        params.push({ name: WLAN + 'BasicEncryptionModes', value: 'None' })
      }

      const steps = [{
        comment: '下发 WLAN 配置',
        rpc: 'SetParameterValues',
        objectName: '',
        parameterPath: '',
        saveAs: '',
        abortOnError: true,
        parameters: params,
        options: {}
      }]

      return {
        name: w.name,
        category: w.category,
        group: w.group || '',
        isp: w.isp,
        version: w.version,
        domain: w.domain,
        deviceType: w.deviceType,
        enabled: w.enabled,
        priority: 100,
        doc: (w.doc || '') + '\n(ITMS 向导生成: ' + (is24 ? '2.4G' : '5.8G') + ' WiFi)',
        args,
        steps
      }
    },

    // 业务模板：左列表成员按选择顺序即执行顺序
    async loadBizCandidates() {
      try {
        const res = await fetchTemplates()
        this.bizCandidates = (res.data || []).map(t => ({
          key: t.key,
          label: (t.group ? t.group + ' / ' : '') + t.name +
            '（' + this.categoryLabel(t.category) + '）'
        }))
      } catch (e) {
        this.bizCandidates = []
      }
    },
    async submitBizWizard() {
      const w = this.wizard
      if (!w.bizMembers.length) {
        this.$message.warning('至少选择一个配置模板成员')
        return
      }
      if (!w.group) {
        this.$message.warning('分组不能为空')
        return
      }
      this.saving = true
      try {
        await saveBizTemplate({
          template: {
            name: w.name,
            groupName: w.group || '',
            remark: w.doc || '',
            enabled: w.enabled,
            continueOnError: true
          },
          items: w.bizMembers.map((key, i) => ({ templateKey: key, itemOrder: i + 1 }))
        })
        this.$message.success('业务模板已保存')
        this.dialogVisible = false
      } catch (e) {
        this.$message.error((e && e.message) || '保存失败')
      } finally {
        this.saving = false
      }
    },
    async submitSipWizard() {
      const w = this.wizard
      if (!w.registrar) {
        this.$message.warning('SIP 注册服务器地址不能为空')
        return
      }
      const ports = [
        ['注册服务器端口', w.registrarPort], ['代理服务器端口', w.proxyPort],
        ['Outbound 端口', w.outboundPort], ['备用注册端口', w.backupRegistrarPort]
      ]
      for (const [label, value] of ports) {
        if (value !== '' && !/^\d{1,5}$/.test(value)) {
          this.$message.warning(label + ' 必须是数字')
          return
        }
      }
      if (w.minDigits !== '' && !/^[1-3]$/.test(w.minDigits)) {
        this.$message.warning('最小拨号长度取值 1-3，或留空')
        return
      }
      if (w.digitMapMode === 'set' && !w.digitMapRule) {
        this.$message.warning('数图开关选了下发，规则不能为空')
        return
      }
      this.saving = true
      try {
        await createTemplate({ template: this.buildSipTemplate(), format: 'yaml' })
        this.$message.success('模板已生成并保存')
        this.dialogVisible = false
        this.load()
      } catch (e) {
        this.$message.error((e && e.message) || '保存失败')
      } finally {
        this.saving = false
      }
    },
    async submitWizard() {
      const w = this.wizard
      if (!w.name) {
        this.$message.warning('模版名称不能为空')
        return
      }
      if (!/^[A-Za-z0-9_\u4e00-\u9fa5]+$/.test(w.name)) {
        this.$message.warning('模版名称只能由汉字、字母、数字、下划线组成')
        return
      }
      if (w.category === 'BIZ') {
        await this.submitBizWizard()
        return
      }
      if (w.category === 'SIP') {
        await this.submitSipWizard()
        return
      }
      if (w.category.startsWith('WIFI')) {
        if (!w.wifiSsid) {
          this.$message.warning('SSID 名称不能为空')
          return
        }
        if (w.wifiEncryption !== 'Basic' && (w.wifiPassword || '').length < 8) {
          this.$message.warning('WiFi 密码至少 8 位')
          return
        }
      }
      if (w.vlanId !== '' && !/^\d{1,4}$/.test(w.vlanId)) {
        this.$message.warning('WAN VLAN ID 必须是 1-4094 的数字，或留空')
        return
      }
      if (w.qos !== '' && !/^[0-7]$/.test(w.qos)) {
        this.$message.warning('802.1p 优先级取值 0-7，或留空不下发')
        return
      }
      if (w.mtu !== '' && !(+w.mtu >= 1280 && +w.mtu <= 1500)) {
        this.$message.warning('MTU 取值 1280-1500，或留空不修改')
        return
      }
      this.saving = true
      try {
        await createTemplate({ template: this.buildWizardTemplate(), format: 'yaml' })
        this.$message.success('模板已生成并保存')
        this.dialogVisible = false
        this.load()
      } catch (e) {
        this.$message.error((e && e.message) || '保存失败')
      } finally {
        this.saving = false
      }
    },
    addArg() {
      this.form.args.push({ name: '', defaultValue: '', type: 'string', required: true, enumText: '', description: '' })
    },
    removeArg(index) {
      this.form.args.splice(index, 1)
    },
    moveArg(index, offset) {
      const target = index + offset
      if (target < 0 || target >= this.form.args.length) return
      const item = this.form.args[index]
      this.form.args.splice(index, 1)
      this.form.args.splice(target, 0, item)
    },
    addStep() {
      this.form.steps.push({
        rpc: 'SetParameterValues',
        comment: '',
        objectName: '',
        parameterPath: '',
        saveAs: '',
        abortOnError: true,
        nextLevel: false,
        timeoutSeconds: 0,
        parameters: [{ name: '', value: '' }],
        optionList: []
      })
    },
    removeStep(index) {
      this.form.steps.splice(index, 1)
    },
    moveStep(index, offset) {
      const target = index + offset
      if (target < 0 || target >= this.form.steps.length) return
      const item = this.form.steps[index]
      this.form.steps.splice(index, 1)
      this.form.steps.splice(target, 0, item)
    },
    copyStep(index) {
      const copy = JSON.parse(JSON.stringify(this.form.steps[index]))
      this.form.steps.splice(index + 1, 0, copy)
    },
    addStepParam(step) {
      step.parameters.push({ name: '', value: '' })
    },
    removeStepParam(step, index) {
      step.parameters.splice(index, 1)
    },
    addStepOption(step) {
      step.optionList.push({ key: '', value: '' })
    },
    removeStepOption(step, index) {
      step.optionList.splice(index, 1)
    },

    // ---------------- 删除 ----------------
    async handleDelete(row) {
      await this.$confirm('确认删除配置模板 ' + row.name + '？对应文件会被移除。', '删除确认', {
        type: 'warning'
      }).catch(() => {
        throw new Error('cancel')
      })
      await deleteTemplate(row.key)
      this.$message.success('已删除')
      this.load()
    },
    async batchDelete() {
      await this.$confirm('确认删除选中的 ' + this.selection.length + ' 个配置模板？', '批量删除', {
        type: 'warning'
      }).catch(() => {
        throw new Error('cancel')
      })
      for (const row of this.selection) {
        await deleteTemplate(row.key)
      }
      this.$message.success('已删除')
      this.selection = []
      this.load()
    }
  }
}
</script>

<style scoped>
.desc-card {
  margin-bottom: 12px;
}
.desc {
  color: #606266;
  font-size: 13px;
  line-height: 1.8;
}
.create-bar {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
}
.create-label {
  font-size: 13px;
  color: #909399;
  margin-right: 8px;
}
.cat-btn {
  margin: 0 6px 6px 0;
}
.cat-LAN { background: #409eff; border-color: #409eff; color: #fff; }
.cat-WAN_ROUTE { background: #67c23a; border-color: #67c23a; color: #fff; }
.cat-WAN_STATIC { background: #5cb85c; border-color: #5cb85c; color: #fff; }
.cat-WAN_DHCP { background: #36cfc9; border-color: #36cfc9; color: #fff; }
.cat-WAN_PPPOE { background: #1890ff; border-color: #1890ff; color: #fff; }
.cat-SIP { background: #e6a23c; border-color: #e6a23c; color: #fff; }
.cat-WIFI_24G { background: #f78989; border-color: #f78989; color: #fff; }
.cat-WIFI_58G { background: #f56c6c; border-color: #f56c6c; color: #fff; }
.cat-FIRMWARE { background: #9254de; border-color: #9254de; color: #fff; }
.cat-OTHER { background: #909399; border-color: #909399; color: #fff; }
.cat-CUSTOM { background: #606266; border-color: #606266; color: #fff; }
.filter-card {
  margin-bottom: 12px;
}
.batch-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  color: #606266;
}
.cell-main {
  font-weight: 600;
  color: #303133;
}
.cell-sub {
  color: #909399;
  font-size: 12px;
}
.key-text {
  color: #909399;
  font-size: 12px;
  word-break: break-all;
}
.mode-bar {
  display: flex;
  align-items: center;
  margin-bottom: 14px;
}
.mode-tip {
  margin-left: 10px;
  color: #909399;
  font-size: 12px;
}
.port-group {
  max-width: 640px;
}
.port-item {
  width: 150px;
  margin-left: 0;
}
.pager {
  margin-top: 12px;
  text-align: right;
}
.tab-tip {
  color: #909399;
  font-size: 12px;
  margin-bottom: 8px;
  line-height: 1.6;
}
.step-item {
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 10px;
  margin-bottom: 10px;
  background: #fafafa;
}
.step-head {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
}
.step-index {
  font-weight: 600;
  font-size: 13px;
  margin-right: 12px;
  color: #303133;
}
.step-sub {
  margin-top: 8px;
  padding-left: 8px;
  border-left: 2px solid #e4e7ed;
}
.step-sub-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: #606266;
}
.step-sub-row {
  margin-top: 4px;
}
.danger-text {
  color: #f56c6c;
}
</style>
