package com.PilotACS.olt.controller;

import io.swagger.v3.oas.annotations.Operation;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.PilotACS.common.log.Logs;

import com.PilotACS.common.result.Result;
import com.PilotACS.common.security.LoginUser;
import com.PilotACS.common.security.RequiresPermission;
import com.PilotACS.common.security.UserContext;
import com.PilotACS.olt.model.OltAction;
import com.PilotACS.olt.model.OltDetail;
import com.PilotACS.olt.model.OltDbaForm;
import com.PilotACS.olt.model.OltDbaProfileRequest;
import com.PilotACS.olt.model.OltExecRequest;
import com.PilotACS.olt.model.OltExecResult;
import com.PilotACS.olt.model.OltOntDeleteAllRequest;
import com.PilotACS.olt.model.OltOntQueryRequest;
import com.PilotACS.olt.model.OltLineProfileForm;
import com.PilotACS.olt.model.OltLineProfilePreviewRequest;
import com.PilotACS.olt.model.OltTcontRequest;
import com.PilotACS.olt.model.OltGemportRequest;
import com.PilotACS.olt.model.OltGemMappingRequest;
import com.PilotACS.olt.model.OltOntQueryResult;
import com.PilotACS.olt.model.OltPort;
import com.PilotACS.olt.model.OltQuickSetupRequest;
import com.PilotACS.olt.model.OltDeviceOptions;
import com.PilotACS.olt.model.OltDeviceView;
import com.PilotACS.olt.model.OltQuickSetupForm;
import com.PilotACS.olt.model.HuaweiQuickSetupRequest;
import com.PilotACS.olt.model.OltHuaweiSetupForm;
import com.PilotACS.olt.model.OltQuickSetupPreview;
import com.PilotACS.olt.model.OltSaveRequest;
import com.PilotACS.olt.model.OltTr069Form;
import com.PilotACS.olt.model.OltVlanForm;
import com.PilotACS.olt.model.OltVlanRequest;
import com.PilotACS.olt.model.OltSyncRecordView;
import com.PilotACS.olt.model.OltTestResult;
import com.PilotACS.olt.model.OltOntAutofind;
import com.PilotACS.olt.entity.OltOntRegConfig;
import com.PilotACS.olt.entity.OltOntRegRecord;
import com.PilotACS.olt.entity.OltOntRegTemplate;
import com.PilotACS.olt.model.OltOntRegisterRequest;
import com.PilotACS.olt.model.OltOntRegisterResult;
import com.PilotACS.olt.model.OltOntAutoFindStatus;
import com.PilotACS.olt.model.OltTerminalUserRequest;
import com.PilotACS.olt.model.OltTr069Profile;
import com.PilotACS.olt.model.OltTr069ProfileRequest;
import com.PilotACS.olt.model.OltVlanRequest;
import com.PilotACS.olt.model.OltKeepaliveRequest;
import com.PilotACS.olt.model.OltPortVlanRequest;
import com.PilotACS.olt.service.OltOntRegService;
import com.PilotACS.olt.service.OltQuickSetupConfigService;
import com.PilotACS.olt.service.OltService;
import com.PilotACS.olt.service.OltSessionPool;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
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

@Tag(name = "OLT 管理", description = "OLT 设备台账、Telnet 采集、板卡/ONT 查询、模板下发、快速开通、自动注册")
@RestController
@RequestMapping("/olt")
@RequiresPermission("olt:view")
@RequiredArgsConstructor
public class OltController {

    private final OltService oltService;

    private final OltOntRegService ontRegService;

    private final OltQuickSetupConfigService quickSetupConfigService;

    private final OltSessionPool sessionPool;

    @Operation(summary = "OLT 列表，keyword 匹配名称 / 地址 / 型号")
    @GetMapping("/list")
    public Result<List<OltDeviceView>> list(@RequestParam(required = false) String keyword) {
        try {
            return Result.ok(oltService.list(keyword));
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "查询 OLT 列表失败", e);
            return Result.fail("查询 OLT 列表失败：" + e.getMessage());
        }
    }

    @Operation(summary = "可勾选的 Web 登录账号")
    @GetMapping("/web-accounts")
    public Result<List<Map<String, Object>>> webAccounts() {
        return Result.ok(oltService.webAccounts());
    }

    @Operation(summary = "会话池状态：保活开关、探活周期、每台设备的空闲会话数")
    @GetMapping("/session-pool")
    public Result<Map<String, Object>> sessionPool() {
        return Result.ok(sessionPool.status());
    }

    @Operation(summary = "保活开关（管理页）")
    @PostMapping("/session-pool/keepalive")
    public Result<Map<String, Object>> setSessionKeepalive(@RequestBody OltKeepaliveRequest request) {
        if (request.getEnabled() == null) {
            return Result.fail("缺少 enabled 参数");
        }
        try {
            return Result.ok(sessionPool.setKeepalive(request.getEnabled(), request.getIntervalMs()));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    @Operation(summary = "OLT 详情：台账 + 板卡 + 最近采集记录")
    @GetMapping("/{id}")
    public Result<OltDetail> detail(@PathVariable Long id) {
        try {
            return Result.ok(oltService.detail(id));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "查询 OLT 详情失败 id={}", id, e);
            return Result.fail("查询 OLT 详情失败：" + e.getMessage());
        }
    }

    @Operation(summary = "新增或修改 OLT")
    @PostMapping("/save")
    public Result<OltDeviceView> save(@RequestBody OltSaveRequest request) {
        try {
            return Result.ok(oltService.save(request));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "保存 OLT 失败", e);
            return Result.fail("保存 OLT 失败：" + e.getMessage());
        }
    }

    @Operation(summary = "删除 OLT（连同板卡数据）")
    @PostMapping("/delete")
    public Result<Void> delete(@RequestBody IdRequest request) {
        if (request == null || request.getId() == null) {
            return Result.fail("OLT id 不能为空");
        }
        try {
            oltService.delete(request.getId());
            return Result.ok(null);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "删除 OLT 失败 id={}", request.getId(), e);
            return Result.fail("删除 OLT 失败：" + e.getMessage());
        }
    }

    @Operation(summary = "诊断测试（未保存的表单也能测）")
    @PostMapping("/test")
    public Result<OltTestResult> test(@RequestBody OltSaveRequest request, HttpServletRequest httpRequest) {
        try {
            return Result.ok(oltService.test(request, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "OLT 诊断测试异常", e);
            return Result.ok(OltTestResult.fail("连接失败：" + e.getMessage()));
        }
    }

    @Operation(summary = "对已保存的 OLT 做诊断测试")
    @PostMapping("/{id}/test")
    public Result<OltTestResult> testSaved(@PathVariable Long id, HttpServletRequest httpRequest) {
        OltSaveRequest request = new OltSaveRequest();
        request.setId(id);
        return test(request, httpRequest);
    }

    @Operation(summary = "同步：登录采集版本与板卡并入库")
    @PostMapping("/{id}/sync")
    public Result<OltDetail> sync(@PathVariable Long id, HttpServletRequest httpRequest) {
        try {
            return Result.ok(oltService.sync(id, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "OLT 同步异常 id={}", id, e);
            return Result.fail("同步失败：" + e.getMessage());
        }
    }

    @Operation(summary = "快速同步：只采板卡（display board 0 / show card），秒级返回机框与型号")
    @PostMapping("/{id}/quick-sync")
    public Result<OltDetail> quickSync(@PathVariable Long id,
                                       @RequestParam(value = "scope", required = false) String scope,
                                       HttpServletRequest httpRequest) {
        try {
            return Result.ok(oltService.quickSync(id, operator(httpRequest), scope));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "OLT 快速同步异常 id={}", id, e);
            return Result.fail("同步失败：" + e.getMessage());
        }
    }

    @Operation(summary = "按页签同步：scope = boards / ont / vlan / template，只采目标页签需要的数据段")
    @PostMapping("/{id}/sync-section")
    public Result<OltDetail> syncSection(@PathVariable Long id,
                                         @RequestParam("scope") String scope,
                                         @RequestParam(value = "force", required = false,
                                                 defaultValue = "false") boolean force,
                                         HttpServletRequest httpRequest) {
        try {
            return Result.ok(oltService.syncSection(id, scope, operator(httpRequest), force));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "OLT 按页签同步异常 id={} scope={}", id, scope, e);
            return Result.fail("同步失败：" + e.getMessage());
        }
    }

    @Operation(summary = "实时刷新：直连 OLT 采最新数据并入库，页面打开或对 OLT 做过操作后调用")
    @PostMapping("/{id}/refresh")
    public Result<OltDetail> refresh(@PathVariable Long id, HttpServletRequest httpRequest) {
        try {
            return Result.ok(oltService.refresh(id, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "OLT 实时刷新异常 id={}", id, e);
            return Result.fail("实时刷新失败：" + e.getMessage());
        }
    }

    @Operation(summary = "确认所有待确认板卡（华为 board confirm 0）")
    @PostMapping("/{id}/boards/confirm")
    public Result<OltDetail> confirmBoards(@PathVariable Long id, HttpServletRequest httpRequest) {
        try {
            return Result.ok(oltService.confirmBoards(id, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "板卡确认异常 id={}", id, e);
            return Result.fail("板卡确认失败：" + e.getMessage());
        }
    }

    @Operation(summary = "单条 ONT 按需查询：光信息 / 版本 / 注册历史，点列表里某条的图标时触发")
    @PostMapping("/{id}/ont-query")
    public Result<OltOntQueryResult> queryOnt(@PathVariable Long id,
                                              @RequestBody OltOntQueryRequest request,
                                              HttpServletRequest httpRequest) {
        try {
            return Result.ok(oltService.queryOnt(id, request, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "ONT 按需查询异常 id={}", id, e);
            return Result.fail("ONT 查询失败：" + e.getMessage());
        }
    }

    @Operation(summary = "端口光模块按需查询：点开某个 PON 口的光模块弹窗才发一条 display port state")
    @PostMapping("/{id}/port-optics")
    public Result<OltPort> queryPortOptics(@PathVariable Long id,
                                           @RequestParam Integer slotNo,
                                           @RequestParam Integer portNo,
                                           HttpServletRequest httpRequest) {
        try {
            return Result.ok(oltService.queryPortOptics(id, slotNo, portNo, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "端口光模块查询异常 id={} slot={} port={}", id, slotNo, portNo, e);
            return Result.fail("端口光模块查询失败：" + e.getMessage());
        }
    }

    @Operation(summary = "「新增 DBA 模板」表单的元数据：五类模板各要填什么、取值上下限")
    @GetMapping("/{id}/dba-profile/form")
    public Result<OltDbaForm> dbaForm(@PathVariable Long id) {
        try {
            return Result.ok(oltService.dbaForm(id));
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "查询 DBA 表单异常 id={}", id, e);
            return Result.fail("查询 DBA 表单失败：" + e.getMessage());
        }
    }

    @Operation(summary = "DBA 模板预演：不下发，返回这次会执行的命令")
    @PostMapping("/{id}/dba-profile/preview")
    public Result<OltQuickSetupPreview> previewDbaProfile(@PathVariable Long id,
                                                          @RequestBody OltDbaProfileRequest request) {
        try {
            return Result.ok(oltService.previewDbaProfile(id, request));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "DBA 模板预演异常 id={}", id, e);
            return Result.fail("命令预演失败：" + e.getMessage());
        }
    }

    @Operation(summary = "新增 DBA 模板：下发 dba-profile add，成功后自动重采返回最新数据")
    @PostMapping("/{id}/dba-profile")
    public Result<OltDetail> createDbaProfile(@PathVariable Long id,
                                              @RequestBody OltDbaProfileRequest request,
                                              HttpServletRequest httpRequest) {
        try {
            return Result.ok(oltService.createDbaProfile(id, request, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "新增 DBA 模板异常 id={}", id, e);
            return Result.fail("新增 DBA 模板失败：" + e.getMessage());
        }
    }

    @Operation(summary = "线路模板三个弹窗的表单元数据：默认值、已占用的索引、可用区间、提示文字")
    @GetMapping("/{id}/line-profile/form")
    public Result<OltLineProfileForm> lineProfileForm(@PathVariable Long id,
                                                      @RequestParam Integer profileId,
                                                      @RequestParam(required = false) Integer gemportId) {
        try {
            return Result.ok(oltService.lineProfileForm(id, profileId, gemportId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "查询线路模板表单异常 id={}", id, e);
            return Result.fail("查询线路模板表单失败：" + e.getMessage());
        }
    }

    @Operation(summary = "线路模板三个弹窗的命令预演：不下发，返回这次会执行的命令与提醒")
    @PostMapping("/{id}/line-profile/preview")
    public Result<OltQuickSetupPreview> previewLineProfile(@PathVariable Long id,
                                                           @RequestBody OltLineProfilePreviewRequest request) {
        try {
            return Result.ok(oltService.previewLineProfile(id, request));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "线路模板命令预演异常 id={}", id, e);
            return Result.fail("命令预演失败：" + e.getMessage());
        }
    }

    @Operation(summary = "线路模板里加一条 T-CONT：tcont dba-profile-id")
    @PostMapping("/{id}/line-profile/tcont")
    public Result<OltDetail> addLineTcont(@PathVariable Long id,
                                          @RequestBody OltTcontRequest request,
                                          HttpServletRequest httpRequest) {
        try {
            return Result.ok(oltService.addLineTcont(id, request, operator(httpRequest)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "线路模板加 T-CONT 异常 id={}", id, e);
            return Result.fail("线路模板加 T-CONT 失败：" + e.getMessage());
        }
    }

    @Operation(summary = "线路模板里加一个 GEM Port：gem add eth tcont encrypt on")
    @PostMapping("/{id}/line-profile/gemport")
    public Result<OltDetail> addLineGemport(@PathVariable Long id,
                                            @RequestBody OltGemportRequest request,
                                            HttpServletRequest httpRequest) {
        try {
            return Result.ok(oltService.addLineGemport(id, request, operator(httpRequest)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "线路模板加 GEM Port 异常 id={}", id, e);
            return Result.fail("线路模板加 GEM Port 失败：" + e.getMessage());
        }
    }

    @Operation(summary = "给 GEM Port 加映射：gem mapping vlan （可一次多条）")
    @PostMapping("/{id}/line-profile/gem-mapping")
    public Result<OltDetail> addLineGemMapping(@PathVariable Long id,
                                               @RequestBody OltGemMappingRequest request,
                                               HttpServletRequest httpRequest) {
        try {
            return Result.ok(oltService.addLineGemMapping(id, request, operator(httpRequest)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "线路模板加 GEM 映射异常 id={}", id, e);
            return Result.fail("线路模板加 GEM 映射失败：" + e.getMessage());
        }
    }

    @Operation(summary = "删除勾选的 DBA 模板：逐条下发 dba-profile delete，成功后自动重采")
    @DeleteMapping("/{id}/dba-profile")
    public Result<OltDetail> deleteDbaProfiles(@PathVariable Long id,
                                               @RequestBody List<Integer> profileIds,
                                               HttpServletRequest httpRequest) {
        try {
            return Result.ok(oltService.deleteDbaProfiles(id, profileIds, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "删除 DBA 模板异常 id={}", id, e);
            return Result.fail("删除 DBA 模板失败：" + e.getMessage());
        }
    }

    @Operation(summary = "「新建 VLAN」表单的元数据：VLAN ID 范围、一次最多几个、哪些端口能做成员")
    @GetMapping("/{id}/vlan/form")
    public Result<OltVlanForm> vlanForm(@PathVariable Long id) {
        try {
            return Result.ok(oltService.vlanForm(id));
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "查询 VLAN 表单异常 id={}", id, e);
            return Result.fail("查询 VLAN 表单失败：" + e.getMessage());
        }
    }

    @Operation(summary = "VLAN 预演：不下发，返回这次会执行的命令")
    @PostMapping("/{id}/vlan/preview")
    public Result<OltQuickSetupPreview> previewVlan(@PathVariable Long id,
                                                    @RequestBody OltVlanRequest request) {
        try {
            return Result.ok(oltService.previewVlan(id, request));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "VLAN 预演异常 id={}", id, e);
            return Result.fail("命令预演失败：" + e.getMessage());
        }
    }

    @Operation(summary = "这台设备支持的命令动作目录（鸿升光对应手册 13 章里的一个个功能格）")
    @GetMapping("/{id}/actions")
    public Result<List<OltAction>> actions(@PathVariable Long id) {
        try {
            return Result.ok(oltService.actions(id));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "查询命令动作异常 id={}", id, e);
            return Result.fail("查询命令动作失败：" + e.getMessage());
        }
    }

    @Operation(summary = "执行一条命令动作：查询类返回回显，配置类返回重采后的详情")
    @PostMapping("/{id}/exec")
    public Result<OltExecResult> exec(@PathVariable Long id,
                                      @RequestBody OltExecRequest request,
                                      HttpServletRequest httpRequest) {
        try {
            return Result.ok(oltService.execAction(id, request, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "执行命令动作异常 id={}", id, e);
            return Result.fail("执行命令失败：" + e.getMessage());
        }
    }

    @Operation(summary = "新建 VLAN：下发 vlan standard ...，成功后自动重采返回最新数据")
    @PostMapping("/{id}/vlan")
    public Result<OltDetail> createVlan(@PathVariable Long id,
                                        @RequestBody OltVlanRequest request,
                                        HttpServletRequest httpRequest) {
        try {
            return Result.ok(oltService.createVlan(id, request, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "新增 VLAN 异常 id={}", id, e);
            return Result.fail("新增 VLAN 失败：" + e.getMessage());
        }
    }

    @Operation(summary = "上行口放通 VLAN：下发 port vlan 41,45-46 0/7 0（Native 时带 native 关键字）")
    @PostMapping("/{id}/vlan/port-vlan")
    public Result<OltDetail> addPortVlan(@PathVariable Long id,
                                         @RequestBody OltPortVlanRequest request,
                                         HttpServletRequest httpRequest) {
        try {
            return Result.ok(oltService.addPortVlan(id, request, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "上行口放通 VLAN 异常 id={}", id, e);
            return Result.fail("上行口放通 VLAN 失败：" + e.getMessage());
        }
    }

    @Operation(summary = "上行口解除 VLAN 绑定：下发 no port vlan ...，成功后同样立刻只重读 VLAN")
    @DeleteMapping("/{id}/vlan/port-vlan")
    public Result<OltDetail> removePortVlan(@PathVariable Long id,
                                            @RequestBody OltPortVlanRequest request,
                                            HttpServletRequest httpRequest) {
        try {
            return Result.ok(oltService.removePortVlan(id, request, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "上行口移除 VLAN 异常 id={}", id, e);
            return Result.fail("上行口移除 VLAN 失败：" + e.getMessage());
        }
    }

    @Operation(summary = "删除勾选的 VLAN：下发 no vlan ...，成功后自动重采")
    @DeleteMapping("/{id}/vlan")
    public Result<OltDetail> deleteVlans(@PathVariable Long id,
                                         @RequestBody List<Integer> vlanIds,
                                         HttpServletRequest httpRequest) {
        try {
            return Result.ok(oltService.deleteVlans(id, vlanIds, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "删除 VLAN 异常 id={}", id, e);
            return Result.fail("删除 VLAN 失败：" + e.getMessage());
        }
    }

    @Operation(summary = "TR-069（ACS）模板列表：不读库，实时登设备执行 show onu-tr069-profile all")
    @GetMapping("/{id}/tr069-profiles")
    public Result<List<OltTr069Profile>> tr069Profiles(@PathVariable Long id) {
        try {
            return Result.ok(oltService.tr069Profiles(id));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "查询 TR-069 模板异常 id={}", id, e);
            return Result.fail("查询 TR-069 模板失败：" + e.getMessage());
        }
    }

    @Operation(summary = "待注册 ONT：不读库，实时登设备执行 display ont autofind all")
    @GetMapping("/{id}/ont-autofind")
    public Result<List<OltOntAutofind>> ontAutofind(@PathVariable Long id) {
        try {
            return Result.ok(oltService.ontAutofind(id));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "查询待注册 ONT 异常 id={}", id, e);
            return Result.fail("查询待注册 ONT 失败：" + e.getMessage());
        }
    }

    @Operation(summary = "PON 口自动发现开关状态：逐口问设备 display ont-auto-find")
    @GetMapping("/{id}/ont-auto-find")
    public Result<OltOntAutoFindStatus> ontAutoFindStatus(@PathVariable Long id) {
        try {
            return Result.ok(oltService.ontAutoFindStatus(id));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "查询 PON 口自动发现异常 id={}", id, e);
            return Result.fail("查询自动发现状态失败：" + e.getMessage());
        }
    }

    @Operation(summary = "一键开启所有 GPON 业务板 PON 口的自动发现，下发后自动重采一次")
    @PostMapping("/{id}/ont-auto-find/enable")
    public Result<OltDetail> enableOntAutoFind(@PathVariable Long id, HttpServletRequest httpRequest) {
        try {
            return Result.ok(oltService.enableOntAutoFind(id, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "开启 PON 口自动发现异常 id={}", id, e);
            return Result.fail("开启自动发现失败：" + e.getMessage());
        }
    }

    @Operation(summary = "删除业务板上的全部 ONT：进该项业务的 PON 板视图逐口下发")
    @PostMapping("/{id}/ont/delete-all")
    public Result<OltDetail> deleteAllOnts(@PathVariable Long id,
                                           @RequestBody(required = false) OltOntDeleteAllRequest request,
                                           HttpServletRequest httpRequest) {
        try {
            return Result.ok(oltService.deleteAllOnts(id, request, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "删除业务板 ONT 异常 id={}", id, e);
            return Result.fail("删除业务板 ONT 失败：" + e.getMessage());
        }
    }

    @Operation(summary = "建一个 OLT 本地用户（登这台设备用的账号）")
    @PostMapping("/{id}/terminal-user")
    public Result<OltDetail> createTerminalUser(@PathVariable Long id,
                                                @RequestBody(required = false) OltTerminalUserRequest request,
                                                HttpServletRequest httpRequest) {
        if (request == null || request.getUsername() == null || request.getPassword() == null) {
            return Result.fail("用户名和密码都要填");
        }
        try {
            return Result.ok(oltService.createTerminalUser(id, request, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "建 OLT 用户异常 id={}", id, e);
            return Result.fail("建 OLT 用户失败：" + e.getMessage());
        }
    }

    @Operation(summary = "新增 TR-069 模板：下发 onu-tr069-profile add ...，返回下发后的最新列表")
    @PostMapping("/{id}/tr069-profiles")
    public Result<List<OltTr069Profile>> createTr069Profile(@PathVariable Long id,
                                                           @RequestBody OltTr069ProfileRequest request,
                                                           HttpServletRequest httpRequest) {
        try {
            return Result.ok(oltService.createTr069Profile(id, request, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "新增 TR-069 模板异常 id={}", id, e);
            return Result.fail("新增 TR-069 模板失败：" + e.getMessage());
        }
    }

    @Operation(summary = "一键开局：一次下发端口 VLAN 模式、业务 VLAN、TR-069 模板，成功后自动重采")
    @PostMapping("/{id}/quick-setup")
    public Result<OltDetail> quickSetup(@PathVariable Long id,
                                        @RequestBody OltQuickSetupRequest request,
                                        HttpServletRequest httpRequest) {
        try {
            return Result.ok(oltService.quickSetup(id, request, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "一键开局异常 id={}", id, e);
            return Result.fail("一键开局失败：" + e.getMessage());
        }
    }

    @Operation(summary = "一键开局预演：不下发，返回这次会执行的命令和提醒")
    @PostMapping("/{id}/quick-setup/preview")
    public Result<OltQuickSetupPreview> previewQuickSetup(@PathVariable Long id,
                                                          @RequestBody OltQuickSetupRequest request) {
        try {
            return Result.ok(oltService.previewQuickSetup(id, request));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "一键开局预演异常 id={}", id, e);
            return Result.fail("命令预演失败：" + e.getMessage());
        }
    }

    @Operation(summary = "一键开局表单的取值约束与默认值（可选模式、VLAN 范围、一次最多几个端口）")
    @GetMapping("/{id}/quick-setup/form")
    public Result<OltQuickSetupForm> quickSetupForm(@PathVariable Long id) {
        try {
            return Result.ok(oltService.quickSetupForm(id));
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "查询开局表单异常 id={}", id, e);
            return Result.fail("查询开局表单失败：" + e.getMessage());
        }
    }

    @Operation(summary = "一键开局参数：页面打开时问后端要，默认值也只有后端这一份")
    @GetMapping("/{id}/quick-setup/config")
    public Result<OltHuaweiSetupForm> quickSetupConfig(@PathVariable Long id,
                                                       @RequestParam(required = false) Boolean defaults) {
        try {
            return Result.ok(quickSetupConfigService.form(id, Boolean.TRUE.equals(defaults)));
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "查询开局参数异常 id={}", id, e);
            return Result.fail("查询开局参数失败：" + e.getMessage());
        }
    }

    @Operation(summary = "保存一键开局参数：存库，谁打开这台设备都是同一套")
    @PostMapping("/{id}/quick-setup/config")
    public Result<OltHuaweiSetupForm> saveQuickSetupConfig(@PathVariable Long id,
                                                           @RequestBody HuaweiQuickSetupRequest request) {
        try {
            quickSetupConfigService.save(id, request);
            return Result.ok(quickSetupConfigService.form(id, false));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "保存开局参数异常 id={}", id, e);
            return Result.fail("保存开局参数失败：" + e.getMessage());
        }
    }

    @Operation(summary = "TR-069 模板表单的默认值与取值约束，两个入口共用同一份")
    @GetMapping("/{id}/tr069/form")
    public Result<OltTr069Form> tr069Form(@PathVariable Long id) {
        try {
            return Result.ok(oltService.tr069Form(id));
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "查询 TR-069 表单异常 id={}", id, e);
            return Result.fail("查询 TR-069 表单失败：" + e.getMessage());
        }
    }

    @Operation(summary = "命令动作预演：把进视图、命令、退出算出来返回，不下发")
    @PostMapping("/{id}/actions/preview")
    public Result<OltExecResult> previewAction(@PathVariable Long id,
                                               @RequestBody OltExecRequest request) {
        try {
            return Result.ok(oltService.previewAction(id, request));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "命令预演异常 id={}", id, e);
            return Result.fail("命令预演失败：" + e.getMessage());
        }
    }

    @Operation(summary = "批量命令预演：页面把整页参数一次送上来，拿回 {动作编号: 命令文本}")
    @PostMapping("/{id}/actions/previews")
    public Result<Map<String, String>> previewActions(
            @PathVariable Long id,
            @RequestBody(required = false) List<OltExecRequest> requests) {
        try {
            return Result.ok(oltService.previewActions(id, requests));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "批量命令预演异常 id={}", id, e);
            return Result.fail("命令预演失败：" + e.getMessage());
        }
    }

    @Operation(summary = "设备台账表单的备选项：厂商码表、字符集、默认端口与默认账号")
    @GetMapping("/options")
    public Result<OltDeviceOptions> deviceOptions() {
        return Result.ok(OltService.deviceOptions());
    }

    @Operation(summary = "采集记录，页面展示最近若干次诊断/同步结果")
    @GetMapping("/{id}/records")
    public Result<List<OltSyncRecordView>> records(@PathVariable Long id,
                                                   @RequestParam(defaultValue = "10") int limit) {
        try {
            return Result.ok(oltService.records(id, limit));
        } catch (Exception e) {
            return Result.fail("查询采集记录失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------ ONT 注册模板与自动注册

    @Operation(summary = "注册模板列表")
    @GetMapping("/{id}/ont-reg/templates")
    public Result<List<OltOntRegTemplate>> ontRegTemplates(@PathVariable Long id,
                                                           @RequestParam(required = false) Boolean onlyCurrent) {
        try {
            return Result.ok(ontRegService.templates(id, Boolean.TRUE.equals(onlyCurrent)));
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "查询 ONT 注册模板异常 id={}", id, e);
            return Result.fail("查询注册模板失败：" + e.getMessage());
        }
    }

    @Operation(summary = "新建模板的初始表单：默认值只有后端这一份，页面不自己准备")
    @GetMapping("/{id}/ont-reg/template/default")
    public Result<OltOntRegTemplate> blankOntRegTemplate(@PathVariable Long id,
                                                         @RequestParam(required = false) String oltIp) {
        try {
            return Result.ok(ontRegService.blankTemplate(id, oltIp));
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "取新建模板默认值异常 id={}", id, e);
            return Result.fail("取模板默认值失败：" + e.getMessage());
        }
    }

    @Operation(summary = "新增或更新一条注册模板")
    @PostMapping("/{id}/ont-reg/template")
    public Result<OltOntRegTemplate> saveOntRegTemplate(@PathVariable Long id,
                                                        @RequestBody OltOntRegTemplate template) {
        try {
            return Result.ok(ontRegService.saveTemplate(id, template));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "保存 ONT 注册模板异常 id={}", id, e);
            return Result.fail("保存注册模板失败：" + e.getMessage());
        }
    }

    @Operation(summary = "批量删除注册模板")
    @DeleteMapping("/{id}/ont-reg/templates")
    public Result<Boolean> deleteOntRegTemplates(@PathVariable Long id, @RequestBody List<Long> ids) {
        try {
            ontRegService.deleteTemplates(id, ids);
            return Result.ok(true);
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "删除 ONT 注册模板异常 id={}", id, e);
            return Result.fail("删除注册模板失败：" + e.getMessage());
        }
    }

    @Operation(summary = "自动注册参数；没存过给一份默认值")
    @GetMapping("/{id}/ont-reg/config")
    public Result<OltOntRegConfig> ontRegConfig(@PathVariable Long id) {
        try {
            return Result.ok(ontRegService.config(id));
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "查询自动注册参数异常 id={}", id, e);
            return Result.fail("查询自动注册参数失败：" + e.getMessage());
        }
    }

    @Operation(summary = "保存自动注册参数：开关一打开，后台定时任务就按间隔去设备上扫并注册")
    @PostMapping("/{id}/ont-reg/config")
    public Result<OltOntRegConfig> saveOntRegConfig(@PathVariable Long id,
                                                    @RequestBody OltOntRegConfig config) {
        try {
            return Result.ok(ontRegService.saveConfig(id, config));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "保存自动注册参数异常 id={}", id, e);
            return Result.fail("保存自动注册参数失败：" + e.getMessage());
        }
    }

    @Operation(summary = "立刻跑一轮自动注册（扫描 → 按模板注册），返回本轮注册成功的台数")
    @PostMapping("/{id}/ont-reg/scan")
    public Result<Integer> runOntRegScan(@PathVariable Long id) {
        try {
            return Result.ok(ontRegService.scanNow(id));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "手动触发自动注册异常 id={}", id, e);
            return Result.fail("扫描注册失败：" + e.getMessage());
        }
    }

    @Operation(summary = "注册记录：哪台注册了、套了哪个模板、没成的因为什么")
    @GetMapping("/{id}/ont-reg/records")
    public Result<List<OltOntRegRecord>> ontRegRecords(@PathVariable Long id,
                                                       @RequestParam(required = false) Integer limit) {
        try {
            return Result.ok(ontRegService.records(id, limit == null ? 50 : limit));
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "查询 ONT 注册记录异常 id={}", id, e);
            return Result.fail("查询注册记录失败：" + e.getMessage());
        }
    }

    @Operation(summary = "只编命令不下发，下发前先在页面上核对一遍")
    @PostMapping("/{id}/ont-reg/preview")
    public Result<List<String>> previewOntRegister(@PathVariable Long id,
                                                   @RequestBody(required = false) OltOntRegisterRequest request) {
        try {
            return Result.ok(ontRegService.previewCommands(id, request));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "生成 ONT 注册命令异常 id={}", id, e);
            return Result.fail("生成注册命令失败：" + e.getMessage());
        }
    }

    @Operation(summary = "注册选中的待注册 ONT，逐台返回结果")
    @PostMapping("/{id}/ont-reg/register")
    public Result<List<OltOntRegisterResult>> registerOnt(@PathVariable Long id,
                                                          @RequestBody(required = false) OltOntRegisterRequest request,
                                                          HttpServletRequest httpRequest) {
        try {
            return Result.ok(ontRegService.register(id, request, operator(httpRequest),
                    OltOntRegService.SOURCE_MANUAL));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            Logs.error(Logs.Module.OLT, "注册 ONT 异常 id={}", id, e);
            return Result.fail("注册失败：" + e.getMessage());
        }
    }

    private String operator(HttpServletRequest request) {
        LoginUser user = UserContext.get();
        return user == null ? null : user.getUsername();
    }

    @Data
    public static class IdRequest {
        private Long id;
    }
}
