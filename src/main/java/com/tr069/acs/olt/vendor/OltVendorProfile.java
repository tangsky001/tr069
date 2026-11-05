package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.entity.OltBoard;
import com.tr069.acs.olt.model.OltAction;
import com.tr069.acs.olt.model.OltTerminalUserRequest;
import com.tr069.acs.olt.telnet.OltSnapshot;
import com.tr069.acs.olt.telnet.OltVendor;

import java.util.List;
import java.util.Map;

/** 一家 OLT 厂商（或一条产品线）的全部差异 */
public interface OltVendorProfile {

    /** 这个 profile 对应哪个厂商枚举值 */
    OltVendor vendor();

    /** 命令风格：华为 {@code display}，第三方 {@code show} */
    OltVendor.CliStyle cliStyle();

    /** 回显里能不能认出这家厂商——只看厂商关键字，命令风格不参与判断 */
    boolean matches(String output);

    /** 关分页命令；空表表示这家设备不用关（回显都很短，或奔不出来分页提示符） */
    List<String> pagingCommands();

    /** 版本命令，用于探测厂商与取型号 */
    List<String> versionCommands();

    /** 板卡命令，取不到时可多条依次试 */
    List<String> boardCommands();

    /** 从版本 / 版本或板卡回显里抠型号，抠不出返回 null */
    String extractModel(String text);

    /** 一体化整机：没有板卡插框的概念，整台设备按型号生成一张卡片 */
    default boolean wholeDevice() {
        return false;
    }

    /** 整机设备按型号生成整机卡片（{@link #wholeDevice()} 为真时必须实现） */
    default OltBoard buildWholeDevice(String model) {
        return null;
    }

    /** 进配置视图（DBA 模板增删、临时查询这类写操作要在里面做） */
    default boolean enterConfigView(OltCli cli, int timeoutMs) {
        if (cli == null) {
            return false;
        }
        cli.run("enable", timeoutMs);
        String out = cli.run("config", timeoutMs);
        return out != null && out.contains("(config)#");
    }

    /** 厂商专属的深度采集：配置视图里要问的那一堆数据 */
    default void collectDetails(OltCli cli, OltSnapshot snapshot, List<OltBoard> boards,
                                List<String> notes, int timeoutMs) {
        // 默认没有厂商专属采集
    }

    /** 页面能不能对这台设备新建 / 删除 VLAN */
    default boolean supportsVlanConfig() {
        return false;
    }

    /** 这台设备的哪些端口能做 VLAN 成员（页面「创建 VLAN」弹窗按这个类型给勾选项） */
    default java.util.Set<String> vlanMemberPortTypes() {
        return java.util.Set.of();
    }

    /** 新建 VLAN 的命令：单个 ID 或一段连续 ID（{@code start == end} 表示单个） */
    default List<String> createVlanCommands(int startVlanId, int endVlanId) {
        return List.of();
    }

    /** 新建 VLAN 并顺手把端口加进去 */
    default List<String> createVlanCommands(int startVlanId, int endVlanId,
                                            List<String> tagged, List<String> untagged) {
        return createVlanCommands(startVlanId, endVlanId);
    }

    /** 删除 VLAN 的命令：勾选的 ID 可能不连续，实现自己决定合并成一段还是逐条删 */
    default List<String> deleteVlanCommands(List<Integer> vlanIds) {
        return List.of();
    }

    /** 上行口放通 VLAN 的命令（华为例：{@code port vlan 41,45-46 0/7 0}） */
    default List<String> addPortVlanCommands(String iface, List<Integer> vlanIds, boolean nativeVlan) {
        return List.of();
    }

    /** 上行口解除 VLAN 绑定的命令（华为例：{@code no port vlan 41,45-46 0/7 0}） */
    default List<String> removePortVlanCommands(String iface, List<Integer> vlanIds) {
        return List.of();
    }

    /** 这台设备支持在页面上直接下发的命令动作（对应手册里一个个「功能 / 命令」格子） */
    default List<OltAction> actions() {
        return List.of();
    }

    /** 这台设备能不能在页面上读写下发 TR-069（ACS）模板 */
    default boolean supportsTr069Profile() {
        return false;
    }

    /** 查询 TR-069 模板的命令（config 视图），结果交给 {@link #parseTr069Profiles} */
    default List<String> tr069ProfileQueryCommands() {
        return List.of();
    }

    /** 新增 TR-069 模板的命令 */
    default List<String> createTr069ProfileCommands(com.tr069.acs.olt.model.OltTr069ProfileRequest request) {
        return List.of();
    }

    /** 解析查询回显里的模板列表 */
    default List<com.tr069.acs.olt.model.OltTr069Profile> parseTr069Profiles(String text) {
        return List.of();
    }

    /** 这台设备能不能采集「待注册 ONT」（还没被授权的那批，光猫报上 SN 但没正式上线） */
    default boolean supportsOntAutofind() {
        return false;
    }

    /** 查询待注册 ONT 的命令；不支持时返回空表 */
    default List<String> ontAutofindCommands() {
        return List.of();
    }

    /** 解析 {@link #ontAutofindCommands()} 的回显，得到待注册 ONT 列表 */
    default List<com.tr069.acs.olt.model.OltOntAutofind> parseOntAutofind(String text) {
        return List.of();
    }

    // ------------------------------------------------------------ PON 口自动发现开关

    /** 能不能查/开 PON 口的自动发现（{@code ont-auto-find}） */
    default boolean supportsOntAutoFind() {
        return false;
    }

    /** 进某块 PON 板视图的命令，如 {@code interface gpon 0/1} */
    default String ponViewCommand(int frame, int slot) {
        return null;
    }

    /** 进某块 PON 板视图的命令，端口类型不同写法不同（{@code interface epon 0/1}） */
    default String ponViewCommand(int frame, int slot, String ponType) {
        return ponViewCommand(frame, slot);
    }

    /** 查单个 PON 口开关的命令（在上面那个视图里执行） */
    default String ontAutoFindQueryCommand(int port) {
        return null;
    }

    /** 从查询回显里认这个口开着没；认不出返回 null，别瞎猜 */
    default Boolean parseOntAutoFindState(String output) {
        return null;
    }

    /** 开启指定 PON 口的命令块（含进视图、退视图、保存），fsp 形如 0/1/3 */
    default List<String> ontAutoFindEnableCommands(List<String> fspList) {
        return List.of();
    }

    /** 这台设备能不能用「一键开局」一次性下发端口模式、业务 VLAN 和 TR-069 模板 */
    default boolean supportsQuickSetup() {
        return false;
    }

    /** 一键开局的命令序列 */
    default List<String> quickSetupCommands(com.tr069.acs.olt.model.OltQuickSetupRequest request) {
        return List.of();
    }

    /** 机框槽位数。默认解析板卡命令回显里的槽位表；整机设备没有槽位表，按卡片数给 */
    default Integer slotCount(String boardText, List<OltBoard> boards) {
        if (boardText == null || boardText.isBlank()) {
            return null;
        }
        return com.tr069.acs.olt.telnet.OltInfoParser.parseSlotCount(boardText);
    }

    // ------------------------------------------------------------ ONT 注册（ont confirm）

    /** 这台设备能不能把「自动发现到的 ONT」确认成正式 ONT（{@code ont confirm}） */
    default boolean supportsOntConfirm() {
        return false;
    }

    /** 确认一台待注册 ONT 的命令（在 {@link #ponViewCommand} 进的 PON 板视图里执行） */
    default String ontConfirmCommand(int port, String authMode, String authValue,
                                     Integer lineProfileId, Integer srvProfileId) {
        return null;
    }

    /** 确认所有待确认板卡的命令（config 视图下执行） */
    default String boardConfirmCommand() {
        return null;
    }

    /** 能不能改 ONT 描述（页面上的「备注 / 型号」就是设备侧的 desc） */
    default boolean supportsOntDescription() {
        return false;
    }

    /** 改一台已注册 ONT 描述的命令（在 {@link #ponViewCommand} 进的 PON 板视图里执行） */
    default String ontDescriptionCommand(int port, int ontId, String description) {
        return null;
    }

    /** 查一条 ONT 线路模板详情的命令（config 视图下执行），用来读它 GEM 映射了哪些 VLAN */
    default String lineProfileDetailCommand(String ponType, int profileId) {
        return null;
    }

    /** 线路模板里加一条 T-CONT（华为例：{@code tcont 1 dba-profile-id 1000}） */
    default List<String> lineTcontAddCommands(String ponType, int profileId, int tcontId, int dbaProfileId) {
        return List.of();
    }

    /** 线路模板里加一个 GEM Port（华为例：{@code gem add 1 eth tcont 1 encrypt on}） */
    default List<String> lineGemportAddCommands(String ponType, int profileId, int gemportId, int tcontId) {
        return List.of();
    }

    /** 给线路模板里的一个 GEM Port 加映射（华为例：{@code gem mapping 1 0 vlan 46}） */
    default List<String> lineGemMappingAddCommands(String ponType, int profileId, int gemportId,
                                                   List<int[]> mappings) {
        return List.of();
    }

    /** 这台设备支不支持给已注册的 ONT 建业务虚口 */
    default boolean supportsServicePorts() {
        return false;
    }

    /** 给一台已注册的 ONT 建一条业务虚口的命令（config 视图下执行） */
    default String servicePortCommand(int index, int vlan, int frame, int slot, int port,
                                      int ontId, int gemPort) {
        return null;
    }

    /** 删掉一个已注册 ONT 的命令（PON 板视图里执行）：{@code ont delete  } */
    default String ontDeleteCommand(int port, int ontId) {
        return null;
    }

    /**
     * 查一个 ONT（或整个端口，ontId 传 null）名下业务虚口的命令（config 视图执行）。
     * 删 ONT 前必须先把它的业务虚口拆干净，否则 ont delete 会被引用顶回来。
     */
    default String servicePortListCommand(int frame, int slot, int port, Integer ontId) {
        return null;
    }

    /** 删一条业务虚口的命令（config 视图执行）：华为例 {@code undo service-port 100} */
    default String servicePortDeleteCommand(int index) {
        return null;
    }

    /** 单块板的详情命令（华为例：{@code display board 0/2}）：板卡型号、每个 PON 口 */
    default String boardDetailCommand(int frame, int slot) {
        return null;
    }

    /** 清空一个 PON 口下全部 ONT 的命令（华为例：{@code ont delete 0 all}） */
    default String ontDeleteAllCommand(int port) {
        return null;
    }

    /** 整块板所有 PON 口的 ONT 明细命令（config 视图执行），用于判断 SN 是不是已经注册过 */
    default String ontListCommand(int frame, int slot) {
        return null;
    }

    /** 解析 {@link #ontListCommand} 的回显：key 是端口号，value 是该口下的 ONT */
    default Map<Integer, List<com.tr069.acs.olt.model.OltOnt>> parseOntList(String text) {
        return Map.of();
    }

    /** 存盘命令：华为只写运行配置，不 save 掉电就回到注册前 */
    default String saveCommand() {
        return "save";
    }

    // ------------------------------------------------------------ OLT 本地用户

    /** 这台设备能不能建本地用户（登 OLT 用的账号） */
    default boolean supportsTerminalUser() {
        return false;
    }

    /** 建本地用户的命令序列，{@code >>} 开头的行是对设备追问的应答 */
    default List<String> terminalUserAddCommands(OltTerminalUserRequest request) {
        return List.of();
    }
}
