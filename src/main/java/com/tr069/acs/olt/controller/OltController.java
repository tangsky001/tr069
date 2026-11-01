package com.tr069.acs.olt.controller;

import com.tr069.acs.common.Result;
import com.tr069.acs.config.AdminAuthInterceptor;
import com.tr069.acs.olt.model.OltAction;
import com.tr069.acs.olt.model.OltDetail;
import com.tr069.acs.olt.model.OltDbaForm;
import com.tr069.acs.olt.model.OltDbaProfileRequest;
import com.tr069.acs.olt.model.OltExecRequest;
import com.tr069.acs.olt.model.OltExecResult;
import com.tr069.acs.olt.model.OltOntQueryRequest;
import com.tr069.acs.olt.model.OltOntQueryResult;
import com.tr069.acs.olt.model.OltQuickSetupRequest;
import com.tr069.acs.olt.model.OltDeviceOptions;
import com.tr069.acs.olt.model.OltDeviceView;
import com.tr069.acs.olt.model.OltQuickSetupForm;
import com.tr069.acs.olt.model.HuaweiQuickSetupRequest;
import com.tr069.acs.olt.model.OltHuaweiSetupForm;
import com.tr069.acs.olt.model.OltQuickSetupPreview;
import com.tr069.acs.olt.model.OltSaveRequest;
import com.tr069.acs.olt.model.OltTr069Form;
import com.tr069.acs.olt.model.OltVlanForm;
import com.tr069.acs.olt.model.OltVlanRequest;
import com.tr069.acs.olt.model.OltSyncRecordView;
import com.tr069.acs.olt.model.OltTestResult;
import com.tr069.acs.olt.model.OltOntAutofind;
import com.tr069.acs.olt.entity.OltOntRegConfig;
import com.tr069.acs.olt.entity.OltOntRegRecord;
import com.tr069.acs.olt.entity.OltOntRegTemplate;
import com.tr069.acs.olt.model.OltOntRegisterRequest;
import com.tr069.acs.olt.model.OltOntRegisterResult;
import com.tr069.acs.olt.model.OltOntAutoFindStatus;
import com.tr069.acs.olt.model.OltTerminalUserRequest;
import com.tr069.acs.olt.model.OltTr069Profile;
import com.tr069.acs.olt.model.OltTr069ProfileRequest;
import com.tr069.acs.olt.model.OltVlanRequest;
import com.tr069.acs.olt.service.OltOntRegService;
import com.tr069.acs.olt.service.OltQuickSetupConfigService;
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

    private final OltOntRegService ontRegService;

    private final OltQuickSetupConfigService quickSetupConfigService;

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
     * 确认所有待确认板卡（华为 {@code board confirm 0}）。
     *
     * <p>新插的板卡上电后停在 auto-find，不确认不加载业务。返回确认后重采的最新详情，
     * 页面据此判断按钮还要不要显示。</p>
     */
    @PostMapping("/{id}/boards/confirm")
    public Result<OltDetail> confirmBoards(@PathVariable Long id, HttpServletRequest httpRequest) {
        try {
            return Result.success(oltService.confirmBoards(id, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("板卡确认异常 id={}", id, e);
            return Result.failure("板卡确认失败：" + e.getMessage());
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
     * 「新增 DBA 模板」表单的元数据：五类模板各要填什么、取值上下限。
     */
    @GetMapping("/{id}/dba-profile/form")
    public Result<OltDbaForm> dbaForm(@PathVariable Long id) {
        try {
            return Result.success(oltService.dbaForm(id));
        } catch (Exception e) {
            log.error("查询 DBA 表单异常 id={}", id, e);
            return Result.failure("查询 DBA 表单失败：" + e.getMessage());
        }
    }

    /**
     * DBA 模板预演：不下发，返回这次会执行的命令。
     */
    @PostMapping("/{id}/dba-profile/preview")
    public Result<OltQuickSetupPreview> previewDbaProfile(@PathVariable Long id,
                                                          @RequestBody OltDbaProfileRequest request) {
        try {
            return Result.success(oltService.previewDbaProfile(id, request));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("DBA 模板预演异常 id={}", id, e);
            return Result.failure("命令预演失败：" + e.getMessage());
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
     * 「新建 VLAN」表单的元数据：VLAN ID 范围、一次最多几个、哪些端口能做成员。
     */
    @GetMapping("/{id}/vlan/form")
    public Result<OltVlanForm> vlanForm(@PathVariable Long id) {
        try {
            return Result.success(oltService.vlanForm(id));
        } catch (Exception e) {
            log.error("查询 VLAN 表单异常 id={}", id, e);
            return Result.failure("查询 VLAN 表单失败：" + e.getMessage());
        }
    }

    /**
     * VLAN 预演：不下发，返回这次会执行的命令。
     */
    @PostMapping("/{id}/vlan/preview")
    public Result<OltQuickSetupPreview> previewVlan(@PathVariable Long id,
                                                    @RequestBody OltVlanRequest request) {
        try {
            return Result.success(oltService.previewVlan(id, request));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("VLAN 预演异常 id={}", id, e);
            return Result.failure("命令预演失败：" + e.getMessage());
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
     * 待注册 ONT：不读库，实时登设备执行 {@code display ont autofind all}。
     *
     * <p>返回的是这一瞬间设备上等着授权的光猫，注册完就不在这个表里，所以每次点都重查。</p>
     */
    @GetMapping("/{id}/ont-autofind")
    public Result<List<OltOntAutofind>> ontAutofind(@PathVariable Long id) {
        try {
            return Result.success(oltService.ontAutofind(id));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("查询待注册 ONT 异常 id={}", id, e);
            return Result.failure("查询待注册 ONT 失败：" + e.getMessage());
        }
    }

    /**
     * PON 口自动发现开关状态：逐口问设备 {@code display ont-auto-find}。
     *
     * <p>查不出来也返回 200 + message，不抛 500——这块状态查不到属于正常情况
     * （有的版本没这条命令），不该让页面显示成故障。</p>
     */
    @GetMapping("/{id}/ont-auto-find")
    public Result<OltOntAutoFindStatus> ontAutoFindStatus(@PathVariable Long id) {
        try {
            return Result.success(oltService.ontAutoFindStatus(id));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("查询 PON 口自动发现异常 id={}", id, e);
            return Result.failure("查询自动发现状态失败：" + e.getMessage());
        }
    }

    /**
     * 一键开启所有 GPON 业务板 PON 口的自动发现，下发后自动重采一次。
     */
    @PostMapping("/{id}/ont-auto-find/enable")
    public Result<OltDetail> enableOntAutoFind(@PathVariable Long id, HttpServletRequest httpRequest) {
        try {
            return Result.success(oltService.enableOntAutoFind(id, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("开启 PON 口自动发现异常 id={}", id, e);
            return Result.failure("开启自动发现失败：" + e.getMessage());
        }
    }

    /**
     * 建一个 OLT 本地用户（登这台设备用的账号）。
     *
     * <p>华为这边走 {@code terminal user name} 那条交互式命令：登上去之后设备会
     * 依次问用户名、密码、确认密码、用户模板、级别、可重入次数、备注，
     * 后端按顺序应答，建完自动重采一次整机。</p>
     *
     * <p>级别只有 1（Common User）和 2（Operator）两档——华为不允许建比当前
     * 登录账号更高级别的账号，用 Operator 级的账号连设备时，设备就只给这两个选项。</p>
     */
    @PostMapping("/{id}/terminal-user")
    public Result<OltDetail> createTerminalUser(@PathVariable Long id,
                                                @RequestBody(required = false) OltTerminalUserRequest request,
                                                HttpServletRequest httpRequest) {
        if (request == null || request.getUsername() == null || request.getPassword() == null) {
            return Result.failure("用户名和密码都要填");
        }
        try {
            return Result.success(oltService.createTerminalUser(id, request, operator(httpRequest)));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (IllegalStateException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("建 OLT 用户异常 id={}", id, e);
            return Result.failure("建 OLT 用户失败：" + e.getMessage());
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
     * 一键开局预演：不下发，返回这次会执行的命令和提醒。
     *
     * <p>命令由后端现算，和实际下发同源，页面照着显示即可——前端不再自己拼命令。</p>
     */
    @PostMapping("/{id}/quick-setup/preview")
    public Result<OltQuickSetupPreview> previewQuickSetup(@PathVariable Long id,
                                                          @RequestBody OltQuickSetupRequest request) {
        try {
            return Result.success(oltService.previewQuickSetup(id, request));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("一键开局预演异常 id={}", id, e);
            return Result.failure("命令预演失败：" + e.getMessage());
        }
    }

    /**
     * 一键开局表单的取值约束与默认值（可选模式、VLAN 范围、一次最多几个端口）。
     */
    @GetMapping("/{id}/quick-setup/form")
    public Result<OltQuickSetupForm> quickSetupForm(@PathVariable Long id) {
        try {
            return Result.success(oltService.quickSetupForm(id));
        } catch (Exception e) {
            log.error("查询开局表单异常 id={}", id, e);
            return Result.failure("查询开局表单失败：" + e.getMessage());
        }
    }

    /**
     * 一键开局参数：页面打开时问后端要，默认值也只有后端这一份。
     *
     * @param defaults true 时忽略存过的，直接给默认值（「默认设置」按钮）
     */
    @GetMapping("/{id}/quick-setup/config")
    public Result<OltHuaweiSetupForm> quickSetupConfig(@PathVariable Long id,
                                                       @RequestParam(required = false) Boolean defaults) {
        try {
            return Result.success(quickSetupConfigService.form(id, Boolean.TRUE.equals(defaults)));
        } catch (Exception e) {
            log.error("查询开局参数异常 id={}", id, e);
            return Result.failure("查询开局参数失败：" + e.getMessage());
        }
    }

    /** 保存一键开局参数：存库，谁打开这台设备都是同一套 */
    @PostMapping("/{id}/quick-setup/config")
    public Result<OltHuaweiSetupForm> saveQuickSetupConfig(@PathVariable Long id,
                                                           @RequestBody HuaweiQuickSetupRequest request) {
        try {
            quickSetupConfigService.save(id, request);
            return Result.success(quickSetupConfigService.form(id, false));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("保存开局参数异常 id={}", id, e);
            return Result.failure("保存开局参数失败：" + e.getMessage());
        }
    }

    /**
     * TR-069 模板表单的默认值与取值约束，两个入口共用同一份。
     */
    @GetMapping("/{id}/tr069/form")
    public Result<OltTr069Form> tr069Form(@PathVariable Long id) {
        try {
            return Result.success(oltService.tr069Form(id));
        } catch (Exception e) {
            log.error("查询 TR-069 表单异常 id={}", id, e);
            return Result.failure("查询 TR-069 表单失败：" + e.getMessage());
        }
    }

    /**
     * 命令动作预演：把进视图、命令、退出算出来返回，不下发。
     */
    @PostMapping("/{id}/actions/preview")
    public Result<OltExecResult> previewAction(@PathVariable Long id,
                                               @RequestBody OltExecRequest request) {
        try {
            return Result.success(oltService.previewAction(id, request));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("命令预演异常 id={}", id, e);
            return Result.failure("命令预演失败：" + e.getMessage());
        }
    }

    /**
     * 设备台账表单的备选项：厂商码表、字符集、默认端口与默认账号。
     */
    @GetMapping("/options")
    public Result<OltDeviceOptions> deviceOptions() {
        return Result.success(OltService.deviceOptions());
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

    // ------------------------------------------------------------ ONT 注册模板与自动注册

    /**
     * 注册模板列表。
     *
     * @param onlyCurrent true 时只返回「不限设备」和绑在这台 OLT 上的模板
     */
    @GetMapping("/{id}/ont-reg/templates")
    public Result<List<OltOntRegTemplate>> ontRegTemplates(@PathVariable Long id,
                                                           @RequestParam(required = false) Boolean onlyCurrent) {
        try {
            return Result.success(ontRegService.templates(id, Boolean.TRUE.equals(onlyCurrent)));
        } catch (Exception e) {
            log.error("查询 ONT 注册模板异常 id={}", id, e);
            return Result.failure("查询注册模板失败：" + e.getMessage());
        }
    }

    /** 新建模板的初始表单：默认值只有后端这一份，页面不自己准备 */
    @GetMapping("/{id}/ont-reg/template/default")
    public Result<OltOntRegTemplate> blankOntRegTemplate(@PathVariable Long id,
                                                         @RequestParam(required = false) String oltIp) {
        try {
            return Result.success(ontRegService.blankTemplate(id, oltIp));
        } catch (Exception e) {
            log.error("取新建模板默认值异常 id={}", id, e);
            return Result.failure("取模板默认值失败：" + e.getMessage());
        }
    }

    /** 新增或更新一条注册模板 */
    @PostMapping("/{id}/ont-reg/template")
    public Result<OltOntRegTemplate> saveOntRegTemplate(@PathVariable Long id,
                                                        @RequestBody OltOntRegTemplate template) {
        try {
            return Result.success(ontRegService.saveTemplate(id, template));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("保存 ONT 注册模板异常 id={}", id, e);
            return Result.failure("保存注册模板失败：" + e.getMessage());
        }
    }

    /** 批量删除注册模板 */
    @DeleteMapping("/{id}/ont-reg/templates")
    public Result<Boolean> deleteOntRegTemplates(@PathVariable Long id, @RequestBody List<Long> ids) {
        try {
            ontRegService.deleteTemplates(ids);
            return Result.success(true);
        } catch (Exception e) {
            log.error("删除 ONT 注册模板异常 id={}", id, e);
            return Result.failure("删除注册模板失败：" + e.getMessage());
        }
    }

    /** 自动注册参数；没存过给一份默认值 */
    @GetMapping("/{id}/ont-reg/config")
    public Result<OltOntRegConfig> ontRegConfig(@PathVariable Long id) {
        try {
            return Result.success(ontRegService.config(id));
        } catch (Exception e) {
            log.error("查询自动注册参数异常 id={}", id, e);
            return Result.failure("查询自动注册参数失败：" + e.getMessage());
        }
    }

    /** 保存自动注册参数：开关一打开，后台定时任务就按间隔去设备上扫并注册 */
    @PostMapping("/{id}/ont-reg/config")
    public Result<OltOntRegConfig> saveOntRegConfig(@PathVariable Long id,
                                                    @RequestBody OltOntRegConfig config) {
        try {
            return Result.success(ontRegService.saveConfig(id, config));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("保存自动注册参数异常 id={}", id, e);
            return Result.failure("保存自动注册参数失败：" + e.getMessage());
        }
    }

    /**
     * 立刻跑一轮自动注册（扫描 → 按模板注册），返回本轮注册成功的台数。
     *
     * <p>和后台定时任务走的是同一个 {@code autoRegister}，用来验证参数和模板
     * 配得对不对，不用干等下一轮扫描。</p>
     */
    @PostMapping("/{id}/ont-reg/scan")
    public Result<Integer> runOntRegScan(@PathVariable Long id) {
        try {
            return Result.success(ontRegService.scanNow(id));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("手动触发自动注册异常 id={}", id, e);
            return Result.failure("扫描注册失败：" + e.getMessage());
        }
    }

    /** 注册记录：哪台注册了、套了哪个模板、没成的因为什么 */
    @GetMapping("/{id}/ont-reg/records")
    public Result<List<OltOntRegRecord>> ontRegRecords(@PathVariable Long id,
                                                       @RequestParam(required = false) Integer limit) {
        try {
            return Result.success(ontRegService.records(id, limit == null ? 50 : limit));
        } catch (Exception e) {
            log.error("查询 ONT 注册记录异常 id={}", id, e);
            return Result.failure("查询注册记录失败：" + e.getMessage());
        }
    }

    /** 只编命令不下发，下发前先在页面上核对一遍 */
    @PostMapping("/{id}/ont-reg/preview")
    public Result<List<String>> previewOntRegister(@PathVariable Long id,
                                                   @RequestBody(required = false) OltOntRegisterRequest request) {
        try {
            return Result.success(ontRegService.previewCommands(id, request));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("生成 ONT 注册命令异常 id={}", id, e);
            return Result.failure("生成注册命令失败：" + e.getMessage());
        }
    }

    /** 注册选中的待注册 ONT，逐台返回结果 */
    @PostMapping("/{id}/ont-reg/register")
    public Result<List<OltOntRegisterResult>> registerOnt(@PathVariable Long id,
                                                          @RequestBody(required = false) OltOntRegisterRequest request,
                                                          HttpServletRequest httpRequest) {
        try {
            return Result.success(ontRegService.register(id, request, operator(httpRequest),
                    OltOntRegService.SOURCE_MANUAL));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("注册 ONT 异常 id={}", id, e);
            return Result.failure("注册失败：" + e.getMessage());
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
