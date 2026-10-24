package com.tr069.acs.olt.controller;

import com.tr069.acs.common.Result;
import com.tr069.acs.config.AdminAuthInterceptor;
import com.tr069.acs.olt.model.OltDetail;
import com.tr069.acs.olt.model.OltDeviceView;
import com.tr069.acs.olt.model.OltSaveRequest;
import com.tr069.acs.olt.model.OltSyncRecordView;
import com.tr069.acs.olt.model.OltTestResult;
import com.tr069.acs.olt.service.OltService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
