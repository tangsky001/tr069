package com.tr069.acs.olt.controller;

import com.tr069.acs.common.Result;
import com.tr069.acs.config.AdminAuthInterceptor;
import com.tr069.acs.olt.model.OltAction;
import com.tr069.acs.olt.model.OltDetail;
import com.tr069.acs.olt.model.OltDbaProfileRequest;
import com.tr069.acs.olt.model.OltExecRequest;
import com.tr069.acs.olt.model.OltExecResult;
import com.tr069.acs.olt.model.OltOntQueryRequest;
import com.tr069.acs.olt.model.OltOntQueryResult;
import com.tr069.acs.olt.model.OltQuickSetupRequest;
import com.tr069.acs.olt.model.OltDeviceView;
import com.tr069.acs.olt.model.OltSaveRequest;
import com.tr069.acs.olt.model.OltSyncRecordView;
import com.tr069.acs.olt.model.OltTestResult;
import com.tr069.acs.olt.model.OltTr069Profile;
import com.tr069.acs.olt.model.OltTr069ProfileRequest;
import com.tr069.acs.olt.model.OltVlanRequest;
import com.tr069.acs.olt.service.OltService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * OLT 管理接口。
 *
 * <p>OLT 不走 TR-069，纳管方式是 Telnet 登录设备采集，因此这里的接口分成两类：
 * 台账增删改查，以及「连一下」——诊断测试与同步。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/olt")
@RequiredArgsConstructor
public class OltController {

    private final OltService oltService;

    /**
     * OLT 列表，keyword 匹配名称 / 地址 / 型号。
     */
    @GetMapping("/list")
    public Result<List<OltDeviceView>> list(@RequestParam(required = false) String keyword) {
        try {
            return Result.success(oltService.list(keyword));
        } catch (Exception e) {
            log.error("查询 OLT 列表失败", e);
            return Result.failure("查询 OLT 列表失败：" + e.getMessage());
        }
    }

    /**
     * 可勾选的 Web 登录账号。
     *
     * <p>必须排在 {@code /{id}} 之前声明，否则 web-accounts 会被当成 id 解析。</p>
     */
    @GetMapping("/web-accounts")
    public Result<List<Map<String, Object>>> webAccounts() {
        return Result.success(oltService.webAccounts());
    }

    /**
     * OLT 详情：台账 + 板卡 + 最近采集记录。
     */
    @GetMapping("/{id}")
    public Result<OltDetail> detail(@PathVariable Long id) {
        try {
            return Result.success(oltService.detail(id));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("查询 OLT 详情失败 id={}", id, e);
            return Result.failure("查询 OLT 详情失败：" + e.getMessage());
        }
    }

    /**
     * 新增或修改 OLT。
     */
    @PostMapping("/save")
    public Result<OltDeviceView> save(@RequestBody OltSaveRequest request) {
        try {
            return Result.success(oltService.save(request));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("保存 OLT 失败", e);
            return Result.failure("保存 OLT 失败：" + e.getMessage());
        }
    }

    /**
     * 删除 OLT（连同板卡数据）。
     */
    @PostMapping("/delete")
    public Result<Void> delete(@RequestBody IdRequest request) {
        if (request == null || request.getId() == null) {
            return Result.failure("OLT id 不能为空");
        }
        try {
            oltService.delete(request.getId());
            return Result.success(null);
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("删除 OLT 失败 id={}", request.getId(), e);
            return Result.failure("删除 OLT 失败：" + e.getMessage());
        }
    }

    /**
     * 诊断测试（未保存的表单也能测）。
     */
    @PostMapping("/test")
    public Result<OltTestResult> test(@RequestBody OltSaveRequest request, HttpServletRequest httpRequest) {
        try {
            return Result.success(oltService.test(request, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("OLT 诊断测试异常", e);
            return Result.success(OltTestResult.fail("连接失败：" + e.getMessage()));
        }
    }

    /**
     * 对已保存的 OLT 做诊断测试。
     */
    @PostMapping("/{id}/test")
    public Result<OltTestResult> testSaved(@PathVariable Long id, HttpServletRequest httpRequest) {
        OltSaveRequest request = new OltSaveRequest();
        request.setId(id);
        return test(request, httpRequest);
    }

    /**
     * 同步：登录采集版本与板卡并入库。
     */
    @PostMapping("/{id}/sync")
    public Result<OltDetail> sync(@PathVariable Long id, HttpServletRequest httpRequest) {
        try {
            return Result.success(oltService.sync(id, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("OLT 同步异常 id={}", id, e);
            return Result.failure("同步失败：" + e.getMessage());
        }
    }

    /**
     * 快速同步：只采板卡（display board 0 / show card），秒级返回机框与型号；
     * 端口、ONT、VLAN、模板由后台异步补采，页面轮询即可看到补齐的数据。
     */
    @PostMapping("/{id}/quick-sync")
    public Result<OltDetail> quickSync(@PathVariable Long id, HttpServletRequest httpRequest) {
        try {
            return Result.success(oltService.quickSync(id, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("OLT 快速同步异常 id={}", id, e);
            return Result.failure("同步失败：" + e.getMessage());
        }
    }

    /**
     * 实时刷新：直连 OLT 采最新数据并入库，页面打开或对 OLT 做过操作后调用。
     * 数据以设备为准，库里那份快照只是连不上设备时的兜底。
     */
    @PostMapping("/{id}/refresh")
    public Result<OltDetail> refresh(@PathVariable Long id, HttpServletRequest httpRequest) {
        try {
            return Result.success(oltService.refresh(id, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("OLT 实时刷新异常 id={}", id, e);
            return Result.failure("实时刷新失败：" + e.getMessage());
        }
    }

    /**
     * 单条 ONT 按需查询：光信息 / 版本 / 注册历史，点列表里某条的图标时触发。
     */
    @PostMapping("/{id}/ont-query")
    public Result<OltOntQueryResult> queryOnt(@PathVariable Long id,
                                              @RequestBody OltOntQueryRequest request,
                                              HttpServletRequest httpRequest) {
        try {
            return Result.success(oltService.queryOnt(id, request, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("ONT 按需查询异常 id={}", id, e);
            return Result.failure("ONT 查询失败：" + e.getMessage());
        }
    }

    /**
     * 新增 DBA 模板：下发 dba-profile add，成功后自动重采返回最新数据。
     */
    @PostMapping("/{id}/dba-profile")
    public Result<OltDetail> createDbaProfile(@PathVariable Long id,
                                              @RequestBody OltDbaProfileRequest request,
                                              HttpServletRequest httpRequest) {
        try {
            return Result.success(oltService.createDbaProfile(id, request, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("新增 DBA 模板异常 id={}", id, e);
            return Result.failure("新增 DBA 模板失败：" + e.getMessage());
        }
    }

    /**
     * 删除勾选的 DBA 模板：逐条下发 dba-profile delete，成功后自动重采。
     */
    @DeleteMapping("/{id}/dba-profile")
    public Result<OltDetail> deleteDbaProfiles(@PathVariable Long id,
                                               @RequestBody List<Integer> profileIds,
                                               HttpServletRequest httpRequest) {
        try {
            return Result.success(oltService.deleteDbaProfiles(id, profileIds, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("删除 DBA 模板异常 id={}", id, e);
            return Result.failure("删除 DBA 模板失败：" + e.getMessage());
        }
    }

    /**
     * 这台设备支持的命令动作目录（鸿升光对应手册 13 章里的一个个功能格）。
     */
    @GetMapping("/{id}/actions")
    public Result<List<OltAction>> actions(@PathVariable Long id) {
        try {
            return Result.success(oltService.actions(id));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("查询命令动作异常 id={}", id, e);
            return Result.failure("查询命令动作失败：" + e.getMessage());
        }
    }

    /**
     * 执行一条命令动作：查询类返回回显，配置类返回重采后的详情。
     */
    @PostMapping("/{id}/exec")
    public Result<OltExecResult> exec(@PathVariable Long id,
                                      @RequestBody OltExecRequest request,
                                      HttpServletRequest httpRequest) {
        try {
            return Result.success(oltService.execAction(id, request, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("执行命令动作异常 id={}", id, e);
            return Result.failure("执行命令失败：" + e.getMessage());
        }
    }

    /**
     * 新建 VLAN：下发 {@code vlan standard ...}，成功后自动重采返回最新数据。
     */
    @PostMapping("/{id}/vlan")
    public Result<OltDetail> createVlan(@PathVariable Long id,
                                        @RequestBody OltVlanRequest request,
                                        HttpServletRequest httpRequest) {
        try {
            return Result.success(oltService.createVlan(id, request, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("新增 VLAN 异常 id={}", id, e);
            return Result.failure("新增 VLAN 失败：" + e.getMessage());
        }
    }

    /**
     * 删除勾选的 VLAN：下发 {@code no vlan ...}，成功后自动重采。
     */
    @DeleteMapping("/{id}/vlan")
    public Result<OltDetail> deleteVlans(@PathVariable Long id,
                                         @RequestBody List<Integer> vlanIds,
                                         HttpServletRequest httpRequest) {
        try {
            return Result.success(oltService.deleteVlans(id, vlanIds, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("删除 VLAN 异常 id={}", id, e);
            return Result.failure("删除 VLAN 失败：" + e.getMessage());
        }
    }

    /**
     * TR-069（ACS）模板列表：不读库，实时登设备执行 {@code show onu-tr069-profile all}。
     */
    @GetMapping("/{id}/tr069-profiles")
    public Result<List<OltTr069Profile>> tr069Profiles(@PathVariable Long id) {
        try {
            return Result.success(oltService.tr069Profiles(id));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("查询 TR-069 模板异常 id={}", id, e);
            return Result.failure("查询 TR-069 模板失败：" + e.getMessage());
        }
    }

    /**
     * 新增 TR-069 模板：下发 {@code onu-tr069-profile add ...}，返回下发后的最新列表。
     */
    @PostMapping("/{id}/tr069-profiles")
    public Result<List<OltTr069Profile>> createTr069Profile(@PathVariable Long id,
                                                           @RequestBody OltTr069ProfileRequest request,
                                                           HttpServletRequest httpRequest) {
        try {
            return Result.success(oltService.createTr069Profile(id, request, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("新增 TR-069 模板异常 id={}", id, e);
            return Result.failure("新增 TR-069 模板失败：" + e.getMessage());
        }
    }

    /**
     * 一键开局：一次下发端口 VLAN 模式、业务 VLAN、TR-069 模板，成功后自动重采。
     */
    @PostMapping("/{id}/quick-setup")
    public Result<OltDetail> quickSetup(@PathVariable Long id,
                                        @RequestBody OltQuickSetupRequest request,
                                        HttpServletRequest httpRequest) {
        try {
            return Result.success(oltService.quickSetup(id, request, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("一键开局异常 id={}", id, e);
            return Result.failure("一键开局失败：" + e.getMessage());
        }
    }

    /**
     * 采集记录，页面展示最近若干次诊断/同步结果。
     */
    @GetMapping("/{id}/records")
    public Result<List<OltSyncRecordView>> records(@PathVariable Long id,
                                                   @RequestParam(defaultValue = "10") int limit) {
        try {
            return Result.success(oltService.records(id, limit));
        } catch (Exception e) {
            return Result.failure("查询采集记录失败：" + e.getMessage());
        }
    }

    private String operator(HttpServletRequest request) {
        Object username = request.getAttribute(AdminAuthInterceptor.USERNAME_ATTR);
        return username == null ? null : String.valueOf(username);
    }

    /**
     * 仅含 id 的请求体。
     */
    @Data
    public static class IdRequest {
        private Long id;
    }
}
