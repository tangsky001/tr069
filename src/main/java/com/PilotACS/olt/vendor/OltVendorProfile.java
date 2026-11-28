/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.785
 *
 */

package com.PilotACS.olt.vendor;

import com.PilotACS.olt.entity.OltBoard;
import com.PilotACS.olt.model.OltAction;
import com.PilotACS.olt.model.OltTerminalUserRequest;
import com.PilotACS.olt.telnet.OltSnapshot;
import com.PilotACS.olt.telnet.OltVendor;

import java.util.List;
import java.util.Map;

/** 一家 OLT 厂商（或一条产品线）的全部差异 */
public interface OltVendorProfile {

    /** 对应的厂商枚举值 */
    OltVendor vendor();

    /** 命令风格 */
    OltVendor.CliStyle cliStyle();

    /** 按回显关键字识别厂商 */
    boolean matches(String output);

    /** 关分页命令 */
    List<String> pagingCommands();

    /** 版本命令 */
    List<String> versionCommands();

    /** 板卡命令 */
    List<String> boardCommands();

    /** 从回显里提取型号，取不到返回 null */
    String extractModel(String text);

    /** 是否一体化整机 */
    default boolean wholeDevice() {
        return false;
    }

    /** 按型号生成整机卡片 */
    default OltBoard buildWholeDevice(String model) {
        return null;
    }

    /** 进配置视图 */
    default boolean enterConfigView(OltCli cli, int timeoutMs) {
        if (cli == null) {
            return false;
        }
        cli.run("enable", timeoutMs);
        String out = cli.run("config", timeoutMs);
        return out != null && out.contains("(config)#");
    }

    /** 厂商专属的深度采集 */
    default void collectDetails(OltCli cli, OltSnapshot snapshot, List<OltBoard> boards,
                                List<String> notes, int timeoutMs) {
    }

    /** 是否支持页面新建 / 删除 VLAN */
    default boolean supportsVlanConfig() {
        return false;
    }

    /** 可做 VLAN 成员的端口类型 */
    default java.util.Set<String> vlanMemberPortTypes() {
        return java.util.Set.of();
    }

    /** 新建 VLAN 的命令 */
    default List<String> createVlanCommands(int startVlanId, int endVlanId) {
        return List.of();
    }

    /** 新建 VLAN 并把端口加入 */
    default List<String> createVlanCommands(int startVlanId, int endVlanId,
                                            List<String> tagged, List<String> untagged) {
        return createVlanCommands(startVlanId, endVlanId);
    }

    /** 删除 VLAN 的命令 */
    default List<String> deleteVlanCommands(List<Integer> vlanIds) {
        return List.of();
    }

    /** 上行口放通 VLAN 的命令 */
    default List<String> addPortVlanCommands(String iface, List<Integer> vlanIds, boolean nativeVlan) {
        return List.of();
    }

    /** 上行口解除 VLAN 绑定的命令 */
    default List<String> removePortVlanCommands(String iface, List<Integer> vlanIds) {
        return List.of();
    }

    /** 页面可下发的命令动作列表 */
    default List<OltAction> actions() {
        return List.of();
    }

    /** 是否支持页面读写下发 TR-069 模板 */
    default boolean supportsTr069Profile() {
        return false;
    }

    /** 查询 TR-069 模板的命令 */
    default List<String> tr069ProfileQueryCommands() {
        return List.of();
    }

    /** 新增 TR-069 模板的命令 */
    default List<String> createTr069ProfileCommands(com.PilotACS.olt.model.OltTr069ProfileRequest request) {
        return List.of();
    }

    /** 解析 TR-069 模板列表 */
    default List<com.PilotACS.olt.model.OltTr069Profile> parseTr069Profiles(String text) {
        return List.of();
    }

    /**
     * 查询单个 TR-069 模板详情的命令（display all 只给 ID/名称时逐条补 ACS 地址与账号）。
     * 返回 null 表示设备没有这条命令。
     */
    default String tr069ProfileDetailCommand(int profileId) {
        return null;
    }

    /** 新建 ONT 线路模板的命令序列（建模板 + 绑 DBA + VLAN 映射 + TR069 管理） */
    default List<String> createLineProfileCommands(com.PilotACS.olt.model.OltLineProfileCreateRequest request) {
        return List.of();
    }

    /**
     * 删除 ONT 线路模板的命令序列：GPON 进模板视图清掉 GEM 映射，commit 后退出视图再删模板；
     * EPON 没有 GEM/T-CONT，直接删模板。
     * ponType / 模板内的现有配置由快照给出，返回 List.of() 表示设备还没接入。
     */
    default List<String> deleteLineProfileCommands(String ponType, int profileId,
                                                   com.PilotACS.olt.model.OltLineProfile line) {
        return List.of();
    }

    /** 是否支持采集待注册 ONT */
    default boolean supportsOntAutofind() {
        return false;
    }

    /** 查询待注册 ONT 的命令 */
    default List<String> ontAutofindCommands() {
        return List.of();
    }

    /** 解析待注册 ONT 列表 */
    default List<com.PilotACS.olt.model.OltOntAutofind> parseOntAutofind(String text) {
        return List.of();
    }

    /** 是否支持查/开 PON 口自动发现 */
    default boolean supportsOntAutoFind() {
        return false;
    }

    /** 进 PON 板视图的命令 */
    default String ponViewCommand(int frame, int slot) {
        return null;
    }

    /** 进 PON 板视图的命令（按端口类型） */
    default String ponViewCommand(int frame, int slot, String ponType) {
        return ponViewCommand(frame, slot);
    }

    /** 查单个 PON 口自动发现开关的命令 */
    default String ontAutoFindQueryCommand(int port) {
        return null;
    }

    /** 解析 PON 口自动发现开关状态 */
    default Boolean parseOntAutoFindState(String output) {
        return null;
    }

    /** 开启指定 PON 口自动发现的命令块 */
    default List<String> ontAutoFindEnableCommands(List<String> fspList) {
        return List.of();
    }

    /** 是否支持一键开局 */
    default boolean supportsQuickSetup() {
        return false;
    }

    /** 一键开局的命令序列 */
    default List<String> quickSetupCommands(com.PilotACS.olt.model.OltQuickSetupRequest request) {
        return List.of();
    }

    /** 解析机框槽位数 */
    default Integer slotCount(String boardText, List<OltBoard> boards) {
        if (boardText == null || boardText.isBlank()) {
            return null;
        }
        return com.PilotACS.olt.telnet.OltInfoParser.parseSlotCount(boardText);
    }

    /** 是否支持 ont confirm */
    default boolean supportsOntConfirm() {
        return false;
    }

    /** 确认一台待注册 ONT 的命令 */
    default String ontConfirmCommand(int port, String authMode, String authValue,
                                     Integer lineProfileId, Integer srvProfileId) {
        return null;
    }

    /** 确认所有待确认板卡的命令 */
    default String boardConfirmCommand() {
        return null;
    }

    /** 是否支持改 ONT 描述 */
    default boolean supportsOntDescription() {
        return false;
    }

    /** 改已注册 ONT 描述的命令 */
    default String ontDescriptionCommand(int port, int ontId, String description) {
        return null;
    }

    /** 是否支持给 ONT 下发 TR-069 服务器模板 */
    default boolean supportsTr069ServerConfig() {
        return false;
    }

    /** 给 ONT 绑定 TR-069 服务器模板的命令 */
    default String tr069ServerConfigCommand(int port, int ontId, int tr069ProfileId) {
        return null;
    }

    /** 是否支持给 ONT 以太口配 native-vlan */
    default boolean supportsOntPortNativeVlan() {
        return false;
    }

    /** 给 ONT 以太口配 native-vlan 的命令 */
    default String ontPortNativeVlanCommand(int port, int ontId, int eth, int userVlan) {
        return null;
    }

    /** 查线路模板详情的命令 */
    default String lineProfileDetailCommand(String ponType, int profileId) {
        return null;
    }

    /** 线路模板里加 T-CONT 的命令 */
    default List<String> lineTcontAddCommands(String ponType, int profileId, int tcontId, int dbaProfileId) {
        return List.of();
    }

    /** 线路模板里加 GEM Port 的命令 */
    default List<String> lineGemportAddCommands(String ponType, int profileId, int gemportId, int tcontId) {
        return List.of();
    }

    /** 给 GEM Port 加映射的命令 */
    default List<String> lineGemMappingAddCommands(String ponType, int profileId, int gemportId,
                                                   List<int[]> mappings) {
        return List.of();
    }

    /** 是否支持给已注册 ONT 建业务虚口 */
    default boolean supportsServicePorts() {
        return false;
    }

    /** 给已注册 ONT 建业务虚口的命令 */
    default String servicePortCommand(int index, int vlan, int frame, int slot, int port,
                                      int ontId, int gemPort) {
        return null;
    }

    /** 建内外 VLAN 翻译业务虚口的命令 */
    default String servicePortTranslateCommand(int vlan, int frame, int slot, int port,
                                               int ontId, int gemPort, int userVlan) {
        return null;
    }

    /** 删除已注册 ONT 的命令 */
    default String ontDeleteCommand(int port, int ontId) {
        return null;
    }

    /** 复位单个 ONT 的命令 */
    default String ontResetCommand(int port, int ontId) {
        return null;
    }

    /** 复位整条 PON 口全部 ONT 的命令 */
    default String ontResetPortCommand(int port) {
        return null;
    }

    /** 查 ONT 名下业务虚口的命令 */
    default String servicePortListCommand(int frame, int slot, int port, Integer ontId) {
        return null;
    }

    /** 删业务虚口的命令 */
    default String servicePortDeleteCommand(int index) {
        return null;
    }

    /** 按端口定位删业务虚口的命令 */
    default String servicePortDeleteByPortCommand(int frame, int slot, int port,
                                                  int ontId, int gemport, int userVlan) {
        return null;
    }

    /** 单块板的详情命令 */
    default String boardDetailCommand(int frame, int slot) {
        return null;
    }

    /** 清空一个 PON 口下全部 ONT 的命令 */
    default String ontDeleteAllCommand(int port) {
        return null;
    }

    /** 整块板全部 PON 口的 ONT 明细命令 */
    default String ontListCommand(int frame, int slot) {
        return null;
    }

    /** 解析 ONT 明细回显 */
    default Map<Integer, List<com.PilotACS.olt.model.OltOnt>> parseOntList(String text) {
        return Map.of();
    }

    /** 存盘命令 */
    default String saveCommand() {
        return "save";
    }

    /** 整机重启命令 */
    default String rebootCommand() {
        return "reboot";
    }

    /** 链路聚合查询命令 */
    default String linkAggregationQueryCommand(String target) {
        return null;
    }

    /** 创建链路聚合组命令 */
    default String linkAggregationCreateCommand(String masterFs, String ports,
                                                String direction, String workmode) {
        return null;
    }

    /** 删除链路聚合组命令 */
    default String linkAggregationDeleteCommand(String masterFs) {
        return null;
    }

    /** 是否支持建 OLT 本地用户 */
    default boolean supportsTerminalUser() {
        return false;
    }

    /** 建本地用户的命令序列 */
    default List<String> terminalUserAddCommands(OltTerminalUserRequest request) {
        return List.of();
    }
}
