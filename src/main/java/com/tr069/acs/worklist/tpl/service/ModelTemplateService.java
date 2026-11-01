package com.tr069.acs.worklist.tpl.service;

import com.tr069.acs.worklist.tpl.entity.Device;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 按型号的能力申报生成该型号的下发模板，落 SQLite 模板库。
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
        r.wanBridge = templateBuilder.buildWanBridge(device);
        r.vlan = r.wanDhcp.getVlan() + r.wanPppoe.getVlan() + r.wanStatic.getVlan()
                + r.wanBridge.getVlan();
        r.lan = templateBuilder.buildLan(device).getLan();

        log.info("生成型号模板: id={}, model={}, wifi={}, sip={}, wan={}, lan={}",
                device.getId(), device.getModelKey(),
                r.wifi.total(), r.sip.getSip(),
                r.wanDhcp.getWan() + r.wanPppoe.getWan() + r.wanStatic.getWan()
                        + r.wanBridge.getWan(), r.lan);
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
        private TemplateBuilder.WanResult wanBridge;
        private int vlan;
        private int lan;

        /** 落库的总条数，用来判断「这次到底生成出东西没有」。 */
        public int total() {
            return wifi.total() + sip.getSip()
                    + wanDhcp.getWan() + wanPppoe.getWan() + wanStatic.getWan()
                    + wanBridge.getWan() + vlan + lan;
        }
    }
}
