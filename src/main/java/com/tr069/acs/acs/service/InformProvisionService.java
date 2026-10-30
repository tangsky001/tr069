package com.tr069.acs.acs.service;

import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.cwmp.model.InformRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 设备纳管：收到 Inform 后按预设把设备配置收敛到目标值。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InformProvisionService {

    /** 总开关。 */
    @Value("${acs.device.provision.enabled:true}")
    private boolean enabled;

    private final PresetService presetService;

    public void onInform(CpeSession session, InformRequest inform) {
        if (!enabled || session == null) {
            return;
        }
        presetService.converge(session, inform);
    }
}
