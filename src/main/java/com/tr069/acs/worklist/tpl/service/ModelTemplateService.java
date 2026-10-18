package com.tr069.acs.worklist.tpl.service;

import com.tr069.acs.worklist.tpl.entity.Device;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 按型号的能力申报生成该型号的下发模板，落 SQLite 模板库。
 *
 * <p>型号主表（{@code device}）记的是「这台型号申报了什么能力」——几个网口、几路语音、
 * 什么 WiFi 规格；下发真正要用的「写哪个节点、值是什么」则由
 * {@link TemplateBuilder} 推导后落到各业务表。两者分开，是因为同一份能力申报
 * 换一台设备、换一次采集都要能重新推一遍，而不是把结果写死在型号档案里。</p>
 *
 * <p>所以每次型号档案更新（采集导出、能力申报变更）都要重新生成一次模板，
 * 否则库里留着旧路径，下发就会照着过时的适配结果去写。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelTemplateService {

    private final TemplateBuilder templateBuilder;

    /**
     * 生成该型号全部业务的模板。
     *
     * <p>各业务独立生成，某一项失败不影响其余——能力申报里没有的业务本来就不该写，
     * 而已经申报的业务不能因为别的一项出问题就一起丢掉。</p>
     *
     * @param device 型号主表记录，需已落库有 id
     * @return 各业务写了几条
     */
    public GenerateResult generateAll(Device device) {
        GenerateResult r = new GenerateResult();
        if (device == null || device.getId() == null) {
            log.debug("型号未落库，跳过模板生成");
            return r;
        }
        r.wifi = templateBuilder.buildWifi(device);
        r.sip = templateBuilder.buildSip(device);
        r.wanDhcp = templateBuilder.buildWanDhcp(device);
        r.wanPppoe = templateBuilder.buildWanPppoe(device);
        r.wanStatic = templateBuilder.buildWanStatic(device);
        r.vlan = r.wanDhcp.getVlan() + r.wanPppoe.getVlan() + r.wanStatic.getVlan();
        r.lan = templateBuilder.buildLan(device).getLan();

        log.info("生成型号模板: id={}, model={}, wifi={}, sip={}, wan={}, lan={}",
                device.getId(), device.getModelKey(),
                r.wifi.total(), r.sip.getSip(),
                r.wanDhcp.getWan() + r.wanPppoe.getWan() + r.wanStatic.getWan(), r.lan);
        return r;
    }

    /** 各业务本次写了几条，供回显与日志。 */
    @Data
    public static class GenerateResult {

        private TemplateBuilder.WifiResult wifi;
        private TemplateBuilder.SipResult sip;
        private TemplateBuilder.WanResult wanDhcp;
        private TemplateBuilder.WanResult wanPppoe;
        private TemplateBuilder.WanResult wanStatic;
        private int vlan;
        private int lan;

        /** 落库的总条数，用来判断「这次到底生成出东西没有」。 */
        public int total() {
            return wifi.total() + sip.getSip()
                    + wanDhcp.getWan() + wanPppoe.getWan() + wanStatic.getWan()
                    + vlan + lan;
        }
    }
}
