package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.entity.OltBoard;
import com.tr069.acs.olt.model.OltAction;
import com.tr069.acs.olt.telnet.OltSnapshot;
import com.tr069.acs.olt.telnet.OltVendor;

import java.util.List;

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
}
