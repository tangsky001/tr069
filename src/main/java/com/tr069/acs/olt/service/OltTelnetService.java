package com.tr069.acs.olt.service;

import com.tr069.acs.olt.entity.OltBoard;
import com.tr069.acs.olt.entity.OltDevice;
import com.tr069.acs.olt.model.OltLineProfile;
import com.tr069.acs.olt.model.OltOntDeleteResult;
import com.tr069.acs.olt.model.OltVlanInfo;
import com.tr069.acs.olt.telnet.OltCharset;
import com.tr069.acs.olt.telnet.OltSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;

/**
 * OLT 命令行采集入口。
 *
 * <p>负责两件事：按配置超时从会话池借会话、字符集自动识别失败时换 GBK 重试一次。
 * 命令与解析细节在 {@link OltCliSession} / {@code OltInfoParser} 里；
 * 会话的复用/保活/排队在 {@link OltSessionPool} 里。</p>
 */
@Slf4j
@Service
public class OltTelnetService {

    private final OltSessionPool sessionPool;

    @Value("${acs.olt.max-raw-output-chars:60000}")
    private int maxRawOutputChars;

    public OltTelnetService(OltSessionPool sessionPool) {
        this.sessionPool = sessionPool;
    }

    /**
     * 登录并采集。
     *
     * @param device     连接参数（密码已还原为明文）
     * @param withBoards 是否采集板卡
     */
    public OltSnapshot collect(OltDevice device, boolean withBoards) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        OltSnapshot snapshot = doCollect(device, charset, withBoards);

        // 「自动」编码：回显里出现替换符说明按 UTF-8 解错了，用 GBK 再连一次
        if (OltCharset.isAuto(device.getCharset()) && garbled(snapshot)) {
            log.debug("OLT {} 回显疑似非 UTF-8，改用 GBK 重试", device.getHost());
            try {
                OltSnapshot retried = doCollect(device, OltCharset.fallback(), withBoards);
                if (!garbled(retried)) {
                    return retried;
                }
            } catch (IOException e) {
                log.debug("OLT {} GBK 重试失败，保留首次结果: {}", device.getHost(), e.getMessage());
            }
        }
        return snapshot;
    }

    private OltSnapshot doCollect(OltDevice device, Charset charset, boolean withBoards) throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.collect(withBoards));
    }

    /**
     * 快速采集：只要板卡（型号 / 板卡类型 / 槽位 / 机框槽位数），秒级返回。
     *
     * <p>端口、ONT、VLAN、模板那些几十条命令的数据留给后台异步补采，
     * 页面第一次打开 OLT 不用干等十几秒才看到机框。</p>
     */
    public OltSnapshot collectBoards(OltDevice device) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        OltSnapshot snapshot = doCollectBoards(device, charset);
        if (OltCharset.isAuto(device.getCharset()) && garbled(snapshot)) {
            log.debug("OLT {} 快速采集回显疑似非 UTF-8，改用 GBK 重试", device.getHost());
            try {
                OltSnapshot retried = doCollectBoards(device, OltCharset.fallback());
                if (!garbled(retried)) {
                    return retried;
                }
            } catch (IOException e) {
                log.debug("OLT {} GBK 重试失败，保留首次结果: {}", device.getHost(), e.getMessage());
            }
        }
        return snapshot;
    }

    private OltSnapshot doCollectBoards(OltDevice device, Charset charset) throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.collectBoardsOnly());
    }

    /**
     * 只重读一条线路模板（加完 T-CONT / GEM Port / 映射后刷新用）：一条
     * {@code display ont-lineprofile gpon profile-id X}，不跑全量采集。
     */
    public OltLineProfile collectLineProfile(OltDevice device, int profileId, String ponType)
            throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.collectLineProfile(profileId, ponType));
    }

    /**
     * 清空业务板上的全部 ONT：登录一次，只发那几条命令（板卡位置 → 进板视图 → 逐口删除），
     * 不跑全量采集。「自动」编码解出乱码时换 GBK 再来一次。
     */
    public List<OltOntDeleteResult> deleteBoardOnts(OltDevice device, List<Integer> slots) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        List<OltOntDeleteResult> results = doDeleteBoardOnts(device, charset, slots);
        if (OltCharset.isAuto(device.getCharset()) && garbledDelete(results)) {
            log.debug("OLT {} 删除 ONT 回显疑似非 UTF-8，改用 GBK 重试", device.getHost());
            results = doDeleteBoardOnts(device, OltCharset.fallback(), slots);
        }
        return results;
    }

    private List<OltOntDeleteResult> doDeleteBoardOnts(OltDevice device, Charset charset, List<Integer> slots)
            throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.deleteBoardOnts(slots));
    }

    private static boolean garbledDelete(List<OltOntDeleteResult> results) {
        if (results == null) {
            return false;
        }
        return results.stream().flatMap(result -> result.getOutputs().stream())
                .anyMatch(out -> out != null && out.indexOf('\uFFFD') >= 0);
    }

    /**
     * 只重采 VLAN（上行口 VLAN 下发后立刻刷新用）：一条 {@code display vlan all}
     * + 各上行口 {@code display port vlan}，不跑全量采集；「自动」编码解出乱码时同样换 GBK 再来一次。
     *
     * @param boards 库里已有的板卡，用来判断哪些槽位是上行口
     */
    public OltVlanInfo collectVlans(OltDevice device, List<OltBoard> boards) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        OltVlanInfo info = doCollectVlans(device, charset, boards);
        if (OltCharset.isAuto(device.getCharset()) && garbledVlans(info)) {
            log.debug("OLT {} VLAN 回显疑似非 UTF-8，改用 GBK 重试", device.getHost());
            info = doCollectVlans(device, OltCharset.fallback(), boards);
        }
        return info;
    }

    private OltVlanInfo doCollectVlans(OltDevice device, Charset charset, List<OltBoard> boards)
            throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.collectVlansOnly(boards));
    }

    /** VLAN 快照里有没有解错的替换符（端口清单这类文本最可能先暴露出来） */
    private static boolean garbledVlans(OltVlanInfo info) {
        if (info == null || info.getVlans() == null) {
            return false;
        }
        return info.getVlans().stream().anyMatch(vlan ->
                (vlan.getType() != null && vlan.getType().indexOf('\uFFFD') >= 0)
                        || (vlan.getTaggedPorts() != null && vlan.getTaggedPorts().indexOf('\uFFFD') >= 0)
                        || (vlan.getUntaggedPorts() != null && vlan.getUntaggedPorts().indexOf('\uFFFD') >= 0));
    }

    /**
     * 单条 ONT 详情查询（光信息 / 版本 / 注册历史）：登录一次、进 GPON 端口视图发命令。
     * 页面上点「光信息」「注册历史」这类图标时才触发，不进定时采集。
     */
    public String queryOnt(OltDevice device, int slot, int port, int ontId, String subcommand) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        String out = doQueryOnt(device, charset, slot, port, ontId, subcommand);
        // 「自动」编码：回显里出现替换符说明按 UTF-8 解错了，用 GBK 再连一次
        if (OltCharset.isAuto(device.getCharset()) && out.indexOf('\uFFFD') >= 0) {
            return doQueryOnt(device, OltCharset.fallback(), slot, port, ontId, subcommand);
        }
        return out;
    }

    private String doQueryOnt(OltDevice device, Charset charset, int slot, int port,
                              int ontId, String subcommand) throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.queryOntDetail(slot, port, ontId, subcommand));
    }

    /**
     * 单条 ONT 的配置摘录 + 业务流表格 + 详情，返回 {配置摘录, 业务流回显, 详情回显}，点序列号时触发。
     */
    public String[] queryOntConfigAndServicePorts(OltDevice device, int slot, int port, int ontId) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        String[] outs = doQueryOntConfig(device, charset, slot, port, ontId);
        if (OltCharset.isAuto(device.getCharset())
                && (outs[0].indexOf('\uFFFD') >= 0 || outs[1].indexOf('\uFFFD') >= 0
                        || outs[2].indexOf('\uFFFD') >= 0)) {
            return doQueryOntConfig(device, OltCharset.fallback(), slot, port, ontId);
        }
        return outs;
    }

    private String[] doQueryOntConfig(OltDevice device, Charset charset, int slot, int port, int ontId)
            throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.queryOntConfigAndServicePorts(slot, port, ontId));
    }

    /**
     * 单条 ONT 的业务流表格（display service-port port 0/x/x ont n），点业务流列「查看」时触发。
     */
    public String queryOntServicePorts(OltDevice device, int slot, int port, int ontId) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        String out = doQueryOntServicePorts(device, charset, slot, port, ontId);
        if (OltCharset.isAuto(device.getCharset()) && out.indexOf('\uFFFD') >= 0) {
            return doQueryOntServicePorts(device, OltCharset.fallback(), slot, port, ontId);
        }
        return out;
    }

    private String doQueryOntServicePorts(OltDevice device, Charset charset, int slot, int port, int ontId)
            throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.queryServicePorts(slot, port, ontId));
    }

    /**
     * 只读查询（TR-069 模板列表这类不进库的实时数据）：登录 → config 视图逐条 show。
     */
    public List<String> queryCommands(OltDevice device, List<String> commands) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        List<String> outputs = doQueryCommands(device, charset, commands);
        if (OltCharset.isAuto(device.getCharset())
                && outputs.stream().anyMatch(out -> out != null && out.indexOf('\uFFFD') >= 0)) {
            return doQueryCommands(device, OltCharset.fallback(), commands);
        }
        return outputs;
    }

    private List<String> doQueryCommands(OltDevice device, Charset charset, List<String> commands)
            throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.queryCommands(commands));
    }

    /**
     * 下发配置命令（DBA 模板增删这类写操作）：登录 → config 视图逐条执行。
     * 「自动」编码时回显解出替换符就用备用编码重发一遍，避免中文错误提示读不懂。
     */
    public List<String> sendConfig(OltDevice device, List<String> commands) throws IOException {
        return sendConfig(device, commands, false);
    }

    /**
     * @param tolerateExisting 设备回「已经有了」时跳过继续，不当作失败。
     *                         一键开局要打开：整套配置声明一遍时，已存在的 VLAN / 模板
     *                         会让严格模式当场中断，后面的命令全部下发不了。
     */
    public List<String> sendConfig(OltDevice device, List<String> commands, boolean tolerateExisting)
            throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        List<String> outputs = doSendConfig(device, charset, commands, tolerateExisting);
        if (OltCharset.isAuto(device.getCharset())
                && outputs.stream().anyMatch(out -> out != null && out.indexOf('\uFFFD') >= 0)) {
            return doSendConfig(device, OltCharset.fallback(), commands, tolerateExisting);
        }
        return outputs;
    }

    private List<String> doSendConfig(OltDevice device, Charset charset, List<String> commands,
                                      boolean tolerateExisting) throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.sendConfigCommands(commands, tolerateExisting));
    }

    /**
     * 池中会话的字符集要不要严格匹配：台账指定了编码、或者这次是 GBK 重试
     * （请求的编码跟「自动」解析出来的不一样）时必须一致才复用；
     * 「自动」编码首次采集时哪种都行——解错了上层会用 GBK 重试。
     */
    private boolean charsetStrict(OltDevice device, Charset charset) {
        return !OltCharset.isAuto(device.getCharset())
                || !charset.equals(OltCharset.resolve(device.getCharset()));
    }

    private boolean garbled(OltSnapshot snapshot) {
        return containsReplacement(snapshot.getVersionText())
                || containsReplacement(snapshot.getBoardText())
                || containsReplacement(snapshot.getTranscript());
    }

    private boolean containsReplacement(String text) {
        return text != null && text.indexOf('\uFFFD') >= 0;
    }

    /**
     * 回显落库前截断，避免一条异常输出撑爆记录表。
     */
    public String truncateRaw(String raw) {
        if (raw == null) {
            return null;
        }
        int limit = maxRawOutputChars > 0 ? maxRawOutputChars : 60000;
        return raw.length() <= limit ? raw : raw.substring(0, limit) + "\n...[已截断，原长 " + raw.length() + " 字符]";
    }
}
