package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.entity.OltBoard;
import com.tr069.acs.olt.model.OltAction;
import com.tr069.acs.olt.model.OltTerminalUserRequest;
import com.tr069.acs.olt.telnet.OltSnapshot;
import com.tr069.acs.olt.telnet.OltVendor;

import java.util.List;
import java.util.Map;

/**
 * 一家 OLT 厂商（或一条产品线）的全部差异。
 *
 * <p>同一件事各家命令不一样：华为是 {@code display}、第三方是 {@code show}；
 * 华为 MA5600 关分页是 {@code scroll}，其它家是 {@code terminal length 0}；
 * 中兴要认 {@code show card}，而鸿升光一体化根本没有板卡概念。
 * 这些差别以前散落在会话和解析器里，每加一个品牌就要在好几处加 {@code if (vendor == X)}，
 * 现在集中到这里。</p>
 *
 * <p><b>新增一个品牌/型号只需要两步：</b></p>
 * <ol>
 *   <li>写一个类继承 {@link AbstractOltVendorProfile}，只覆写跟别人不一样的那几个方法
 *       （多数情况就是命令清单、{@link #matches}、{@link #extractModel}）；</li>
 *   <li>在 {@link OltVendorProfiles} 的注册表里加一行，枚举里加一个取值。</li>
 * </ol>
 * 会话主流程（登录、探测风格、采板卡、深度采集的编排）不用改。
 *
 * <p>同一个品牌的不同型号也在这里消化：型号带来的差异（PON 口数、命令写法）
 * 属于 {@code XxxOltProfile} 内部的事，见 {@code HsgqOltProfile} 里的型号表。</p>
 */
public interface OltVendorProfile {

    /** 这个 profile 对应哪个厂商枚举值 */
    OltVendor vendor();

    /** 命令风格：华为 {@code display}，第三方 {@code show} */
    OltVendor.CliStyle cliStyle();

    /**
     * 回显里能不能认出这家厂商——只看厂商关键字，命令风格不参与判断。
     *
     * <p>早先用 {@code display} 有回显就记作华为，结果第三方 OLT（也用 display 那一套）
     * 全被存成 HUAWEI，页面一律出华为界面，华为专属命令在它们上面全是报错。</p>
     */
    boolean matches(String output);

    /** 关分页命令；空表表示这家设备不用关（回显都很短，或奔不出来分页提示符） */
    List<String> pagingCommands();

    /** 版本命令，用于探测厂商与取型号 */
    List<String> versionCommands();

    /** 板卡命令，取不到时可多条依次试 */
    List<String> boardCommands();

    /** 从版本 / 版本或板卡回显里抠型号，抠不出返回 null */
    String extractModel(String text);

    /**
     * 一体化整机：没有板卡插框的概念，整台设备按型号生成一张卡片。
     *
     * <p>鸿升光 HSGQ 这类就是这样——{@code show pon-info} 在部分固件上只回一声命令回显，
     * 采不到实际的板卡表，硬按有板卡的方式走会画出一台空的机框。</p>
     */
    default boolean wholeDevice() {
        return false;
    }

    /**
     * 整机设备按型号生成整机卡片（{@link #wholeDevice()} 为真时必须实现）。
     *
     * @param model 已经取到的型号，取不到时为 null（各家给个兜底型号）
     */
    default OltBoard buildWholeDevice(String model) {
        return null;
    }

    /**
     * 进配置视图（DBA 模板增删、临时查询这类写操作要在里面做）。
     *
     * <p>默认是 {@code enable} → {@code config}，判据是提示符变成 {@code (config)#}；
     * 进法不一样的厂商覆写这个方法。</p>
     */
    default boolean enterConfigView(OltCli cli, int timeoutMs) {
        if (cli == null) {
            return false;
        }
        cli.run("enable", timeoutMs);
        String out = cli.run("config", timeoutMs);
        return out != null && out.contains("(config)#");
    }

    /**
     * 厂商专属的深度采集：配置视图里要问的那一堆数据。
     *
     * <p>华为那套（温度、逐板端口与光模块、ONT 明细、VLAN、模板）目前还在
     * {@code OltCliSession} 里逐条编排，没搬到这里；新厂商从这里接管：
     * 会话进配置视图前调一次，把采到的 VLAN 快照之类直接塞进 {@code snapshot}。</p>
     *
     * @param snapshot 本次采集结果，采到什么写什么（没采到就留 null，库里保留上一次的）
     */
    default void collectDetails(OltCli cli, OltSnapshot snapshot, List<OltBoard> boards,
                                List<String> notes, int timeoutMs) {
        // 默认没有厂商专属采集
    }

    /**
     * 页面能不能对这台设备新建 / 删除 VLAN。
     *
     * <p>各家 VLAN 命令差异很大（华为 {@code vlan 100} + {@code port default vlan}，
     * 鸿升光 {@code vlan standard 100} + {@code no vlan 100}），没接入下发能力的厂商
     * 返回 false，页面就不显示「新增 VLAN」这类按钮，免得上线一个按了报错的入口。</p>
     */
    default boolean supportsVlanConfig() {
        return false;
    }

    /**
     * 这台设备的哪些端口能做 VLAN 成员（页面「创建 VLAN」弹窗按这个类型给勾选项）。
     *
     * <p>鸿升光 EPON 是 {@code epon / ge / xge}；GPON 只有 {@code ge / xge}——
     * 它的 PON 口不是二层口，业务靠 service-port 绑 GEM Port。</p>
     */
    default java.util.Set<String> vlanMemberPortTypes() {
        return java.util.Set.of();
    }

    /**
     * 新建 VLAN 的命令：单个 ID 或一段连续 ID（{@code start == end} 表示单个）。
     *
     * @return 要逐条下发的命令；不支持时返回空表
     */
    default List<String> createVlanCommands(int startVlanId, int endVlanId) {
        return List.of();
    }

    /**
     * 新建 VLAN 并顺手把端口加进去。
     *
     * <p>{@code tagged} / {@code untagged} 是 {@code epon1}、{@code ge3} 这种写法，
     * 由实现自己按类型分组拼命令（{@code port epon 1,2 tagged}）。
     * 默认实现忽略端口，只有支持 VLAN 下发的厂商会覆写。</p>
     *
     * @return 要逐条下发的命令；不支持时返回空表
     */
    default List<String> createVlanCommands(int startVlanId, int endVlanId,
                                            List<String> tagged, List<String> untagged) {
        return createVlanCommands(startVlanId, endVlanId);
    }

    /**
     * 删除 VLAN 的命令：勾选的 ID 可能不连续，实现自己决定合并成一段还是逐条删。
     *
     * @return 要逐条下发的命令；不支持时返回空表
     */
    default List<String> deleteVlanCommands(List<Integer> vlanIds) {
        return List.of();
    }

    /**
     * 这台设备支持在页面上直接下发的命令动作（对应手册里一个个「功能 / 命令」格子）。
     *
     * <p>默认空表：没接入动作目录的厂商，页面不显示「设备配置」入口。
     * 鸿升光的是 {@link HsgqActionCatalog}，加功能就是往目录里加一行。</p>
     */
    default List<OltAction> actions() {
        return List.of();
    }

    /**
     * 这台设备能不能在页面上读写下发 TR-069（ACS）模板。
     *
     * <p>模板不落库，页面「刷新」就是登设备查一次，「增加」就是当场下发。
     * 只有接入过命令的厂商会覆写。</p>
     */
    default boolean supportsTr069Profile() {
        return false;
    }

    /** 查询 TR-069 模板的命令（config 视图），结果交给 {@link #parseTr069Profiles} */
    default List<String> tr069ProfileQueryCommands() {
        return List.of();
    }

    /**
     * 新增 TR-069 模板的命令。
     *
     * @return 要逐条下发的命令；不支持时返回空表
     */
    default List<String> createTr069ProfileCommands(com.tr069.acs.olt.model.OltTr069ProfileRequest request) {
        return List.of();
    }

    /** 解析查询回显里的模板列表 */
    default List<com.tr069.acs.olt.model.OltTr069Profile> parseTr069Profiles(String text) {
        return List.of();
    }

    /**
     * 这台设备能不能采集「待注册 ONT」（还没被授权的那批，光猫报上 SN 但没正式上线）。
     *
     * <p>和 {@link #supportsTr069Profile()} 一样是道开关：没接过 {@code display ont autofind}
     * 回显格式的设备不去瞎猜，页面上也就不会给出「查找新的 ONT 设备」这种点了没反应的按钮。</p>
     */
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

    /**
     * 能不能查/开 PON 口的自动发现（{@code ont-auto-find}）。
     *
     * <p>这个开关不开，光猫接上去设备根本不会去发现，待注册列表就永远是空的。</p>
     */
    default boolean supportsOntAutoFind() {
        return false;
    }

    /** 进某块 PON 板视图的命令，如 {@code interface gpon 0/1} */
    default String ponViewCommand(int frame, int slot) {
        return null;
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

    /**
     * 这台设备能不能用「一键开局」一次性下发端口模式、业务 VLAN 和 TR-069 模板。
     *
     * <p>开局这几步的写法各厂商不一样，没接过命令的设备不允许下发——
     * 发出去只会得到一串语法错误，不如当面说不支持。</p>
     */
    default boolean supportsQuickSetup() {
        return false;
    }

    /**
     * 一键开局的命令序列。
     *
     * @return 要逐条下发的命令；不支持或没勾选任何开局项时返回空表
     */
    default List<String> quickSetupCommands(com.tr069.acs.olt.model.OltQuickSetupRequest request) {
        return List.of();
    }

    /**
     * 机框槽位数。默认解析板卡命令回显里的槽位表；整机设备没有槽位表，按卡片数给。
     */
    default Integer slotCount(String boardText, List<OltBoard> boards) {
        if (boardText == null || boardText.isBlank()) {
            return null;
        }
        return com.tr069.acs.olt.telnet.OltInfoParser.parseSlotCount(boardText);
    }

    // ------------------------------------------------------------ ONT 注册（ont confirm）

    /**
     * 这台设备能不能把「自动发现到的 ONT」确认成正式 ONT（{@code ont confirm}）。
     *
     * <p>没接过回显与命令的厂商不猜：命令发出去只会得到一串语法错误，
     * 不如当面说不支持，页面也就不会给出点了没反应的按钮。</p>
     */
    default boolean supportsOntConfirm() {
        return false;
    }

    /**
     * 确认一台待注册 ONT 的命令（在 {@link #ponViewCommand} 进的 PON 板视图里执行）。
     *
     * <p>ONT ID 由设备自己分配：不用先查一遍该口用了哪些 ID，少一个来回，
     * 也避免了自己挑的 ID 正好被占。</p>
     *
     * @param port          PON 口号
     * @param authMode      注册方式：SN / MAC / LOID / PASSWORD
     * @param authValue     认证串（SN / MAC / LOID / 密码）。设备语法里 sn-auth 后面
     *                      必须跟值，只给关键字会被回 {@code % Unknown command}
     * @param lineProfileId 线路模板 ID，为空就不带这个参数
     * @param srvProfileId  业务模板 ID，为空就不带这个参数
     * @return 命令；不支持时返回 null
     */
    default String ontConfirmCommand(int port, String authMode, String authValue,
                                     Integer lineProfileId, Integer srvProfileId) {
        return null;
    }

    /**
     * 确认所有待确认板卡的命令（config 视图下执行）。
     *
     * <p>新插的板卡上电后停在 auto-find 状态，不确认就不加载业务——
     * 华为的确认命令是 {@code board confirm 0}（0 是机框号）。
     * 没有这套机制的厂商返回 null。</p>
     */
    default String boardConfirmCommand() {
        return null;
    }

    /**
     * 查一条 ONT 线路模板详情的命令（config 视图下执行），用来读它 GEM 映射了哪些 VLAN。
     *
     * @param ponType    GPON / EPON
     * @param profileId  线路模板 ID
     * @return 命令；不支持时返回 null
     */
    default String lineProfileDetailCommand(String ponType, int profileId) {
        return null;
    }

    /**
     * 这台设备支不支持给已注册的 ONT 建业务虚口。
     *
     * <p>光猫注册上（{@code ont confirm}）只是通了 OMCI 管理通道，用户流量要走
     * 业务虚口（service-port）绑 GEM Port 才放得出去——没这层虚口，页面「业务流」
     * 就是空的，用户也没网。</p>
     */
    default boolean supportsServicePorts() {
        return false;
    }

    /**
     * 给一台已注册的 ONT 建一条业务虚口的命令（config 视图下执行）。
     *
     * @param index   业务虚口索引，调用方保证不与现有冲突
     * @param vlan    业务 VLAN（OLT 侧 = 用户侧，translate 透传）
     * @param frame   框号
     * @param slot    槽位号
     * @param port    端口号
     * @param ontId   注册后设备分配的 ONT ID
     * @param gemPort 线路模板里的 GEM Port 号
     * @return 命令；不支持时返回 null
     */
    default String servicePortCommand(int index, int vlan, int frame, int slot, int port,
                                      int ontId, int gemPort) {
        return null;
    }

    /** 删掉一个已注册 ONT 的命令（PON 板视图里执行）：{@code ont delete <port> <ontId>} */
    default String ontDeleteCommand(int port, int ontId) {
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

    /**
     * 建本地用户的命令序列，{@code >>} 开头的行是对设备追问的应答。
     *
     * <p>华为的 {@code terminal user name} 是交互式命令：回车后设备一问一答地把用户名、
     * 密码、级别这些信息逐个要过去。普通命令发完就回提示符，这条不回——所以这里给的
     * 不只是命令，还得带上按提问顺序排好的应答，会话那边照着问一条回一条。</p>
     *
     * @return 命令序列；不支持或参数不合规时返回空表
     */
    default List<String> terminalUserAddCommands(OltTerminalUserRequest request) {
        return List.of();
    }
}
