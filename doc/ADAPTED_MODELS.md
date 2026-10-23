# 光猫型号适配清单

[English](./ADAPTED_MODELS.en.md) | 简体中文

已适配表只记两列：**运营商** + **厂家型号**。适配成功一台就加一行，不写版本号、能力项这些会变的东西。
还没走完适配（采集到了能力树或正在排查）的型号记在「适配中」表里。

## 已适配

| 运营商 | 厂家型号 |
| --- | --- |
| CMCC（移动） | CIOT P22g-CIac |
| CMCC（移动） | CIOT ZNM802 |
| CMCC（移动） | MSG2200C-UP-4E4V |
| CT（电信） | B866-S1-4E1P3W1-Ethernet |

> `B866-S1-4E1P3W1` 硬件是 **XGPON** 上行；这台改成 **Ethernet 上行**后上报的 ProductClass
> 为 `B866-S1-4E1P3W1-Ethernet`，型号 XML 也是按这个 ProductClass 归档的
> （`b866-s1-4e1p3w1-ethernet_v5r022c01s300.xml`，565 对象 / 3018 参数）。
> 上行方式一改 DeviceInfo.ProductClass 就跟着变，等同于另一个型号，需另走一遍适配流程。

## 适配中

| 运营商 | 厂家型号 | 进度 |
| --- | --- | --- |
| CT（电信） | HS8145C5-GPON | 能力树已归档（360 对象 / 1947 参数），待申报保存 |
| CT（电信） | MSG2100E-UPON-8V-AC | GPN 固件不可用（一律 9002），改按已知路径取到 26 对象 / 12 参数，待申报保存；运营商待台账确认（Inform 上报了 `X_CT-COM_UserInfo`） |

---

## 说明

- 适配档案存在便携化 SQLite 模板库 `configuration/tr069_tpl.pack`（配置项 `acs.tr069tpl.db-path`），
  随仓库走，拷到部署机即识别为已适配。
- 库里 `device.status = ADAPTED` 才算已适配；`PENDING` 只是自动登记的待适配占位，不参与下发。
  所以「适配中」的型号在库里仍是 `PENDING`。
- 采集到的型号能力树存在 `configuration/models/<运营商>/<型号>_<版本>.xml`，
  归档成功只说明能力树拿到了，还要在机型适配页申报运营商/网口/WiFi/SIP 并保存，才算适配完成
  （保存后同目录下会多出一个 `<型号>_<版本>.yml`，里面 `adapted: true`）。
- 本表与库是同一件事的两面：入库成功一行，这里补一行；改成「已适配」时从「适配中」挪过去。

## 新增一台

设备列表 → 勾选设备 → 机型适配（申报运营商 / 网口数 / 网口速率 / SIP 数 / WiFi 规格）
→ 试采集 → 通过后保存。保存即写入模板库，然后在本表补一行。

## 频段识别的判据

申报 WiFi 规格时，「试采集」要把设备实际的射频能力判出来。各厂家写法差异很大，
按下面几条判据取并集（都在 `worklist/service/ModelAdaptationService`）：

| 判据 | 说明 | 例 |
| --- | --- | --- |
| 频段字段 | `OperatingFrequencyBand` / `SupportedFrequencyBands` 等报出 `2.4GHz`、`5GHz` | 移动 P22g 的 `X_CMCC_FrequencyBand` |
| 信道列表 | `PossibleChannels` / `SupportedChannels` / `ChannelsInUse`：信道 ≤ 14 判 2.4G，≥ 36 判 5.8G | `1-13` → 2.4G，`36 40 44 … 161` → 5.8G |
| 802.11 制式 | `Standard` 含 `ac`/`ax` 判 5.8G，`b`/`g` 判 2.4G；单独的 `n` 不表态（两个频段都能跑） | `bgn` → 2.4G，`anac` → 5.8G |
| SSID 字样 | SSID 里带 `2.4` / `5G` 时才作数 | `CMCC-xxxx-5G` → 5.8G |

两条兜底规则：

1. **只标 5G、不标 2.4G 时按双频认定** —— 这是行业惯例（`CMCC-xxxx-5G` 配一个什么都不标的
   `CMCC-xxxx`）。认出 5.8G 但还有射频没标出频段，没标的那个按 2.4G 认定。
   反过来不成立：只有一个射频且它标了 5G，就是纯 5G 设备，不补 2.4G。
2. **一个频段都认不出时按射频数推断** —— 单射频按 2.4G，双射频按双频。老固件既不报频段字段、
   SSID 也不带字样（`ChinaNet-xxxx`），不兜底的话申报 2.4G 永远过不了。
