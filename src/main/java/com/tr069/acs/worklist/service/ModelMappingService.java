package com.tr069.acs.worklist.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.acs.model.Isp;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.domain.entity.CpeModel;
import com.tr069.acs.domain.entity.CpeParameter;

import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.mapper.CpeModelMapper;
import com.tr069.acs.mapper.CpeParameterMapper;

import com.tr069.acs.worklist.model.SemanticSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 型号语义映射：登记型号、保存「业务字段 → CWMP 节点」映射、给候选节点 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelMappingService {

    private static final int CANDIDATE_LIMIT = 500;

    private final CpeModelMapper modelMapper;

    private final CpeParameterMapper parameterMapper;
    private final CpeDeviceMapper deviceMapper;

    // ==================== 查询 ====================

    public List<CpeModel> list(String status, String keyword) {
        return modelMapper.selectList(new LambdaQueryWrapper<CpeModel>()
                .eq(status != null && !status.isBlank(), CpeModel::getStatus, status)
                .and(kw(keyword), w -> w
                        .like(CpeModel::getModelKey, keyword)
                        .or().like(CpeModel::getProductClass, keyword)
                        .or().like(CpeModel::getManufacturer, keyword)
                        .or().like(CpeModel::getSoftwareVersion, keyword))
                .orderByAsc(CpeModel::getStatus)
                .orderByDesc(CpeModel::getUpdatedAt));
    }

    public CpeModel get(Long id) {
        return modelMapper.selectById(id);
    }

    /** 型号详情：档案 + 该型号在网设备数 */
    public Map<String, Object> detail(Long id) {
        CpeModel model = modelMapper.selectById(id);
        if (model == null) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("model", model);
        data.put("deviceCount", countDevices(model));
        return data;
    }

    /** 该型号在网设备数：按型号 + 固件统计（运营商字段可空，空则不参与） */
    public long countDevices(CpeModel model) {
        try {
            return deviceMapper.selectCount(new LambdaQueryWrapper<CpeDevice>()
                    .eq(model.getProductClass() != null, CpeDevice::getProductClass, model.getProductClass())
                    .eq(model.getSoftwareVersion() != null, CpeDevice::getSoftwareVersion,
                            model.getSoftwareVersion())
                    .eq(model.getIsp() != null, CpeDevice::getIsp, model.getIsp()));
        } catch (Exception e) {
            log.warn("统计型号设备数失败: model={}, err={}", model.getModelKey(), e.getMessage());
            return 0;
        }
    }

    // ==================== 写入 ====================

    public CpeModel create(CpeModel input) {
        if (input.getModelKey() == null || input.getModelKey().isBlank()) {
            input.setModelKey(buildModelKey(input.getProductClass(), input.getSoftwareVersion()));
        }
        if (input.getIsp() == null || input.getIsp().isBlank()) {
            input.setIsp("standard");
        }
        if (input.getStatus() == null || input.getStatus().isBlank()) {
            input.setStatus(CpeModel.STATUS_PENDING);
        }
        if (input.getConfigPath() == null || input.getConfigPath().isBlank()) {
            input.setConfigPath(defaultConfigPath(input.getIsp(), input.getModelKey()));
        }
        modelMapper.insert(input);
        return input;
    }

    /** 局部更新：只覆盖传入的非空字段 */
    public CpeModel update(Long id, CpeModel patch) {
        CpeModel current = modelMapper.selectById(id);
        if (current == null) {
            return null;
        }
        if (patch.getIsp() != null && !patch.getIsp().isBlank()) current.setIsp(patch.getIsp());
        if (patch.getModelKey() != null && !patch.getModelKey().isBlank()) current.setModelKey(patch.getModelKey());
        if (patch.getManufacturer() != null) current.setManufacturer(patch.getManufacturer());
        if (patch.getProductClass() != null) current.setProductClass(patch.getProductClass());
        if (patch.getSoftwareVersion() != null) current.setSoftwareVersion(patch.getSoftwareVersion());
        if (patch.getHardwareVersion() != null) current.setHardwareVersion(patch.getHardwareVersion());
        if (patch.getConfigPath() != null) current.setConfigPath(patch.getConfigPath());
        if (patch.getStatus() != null && !patch.getStatus().isBlank()) current.setStatus(patch.getStatus());
        if (patch.getSampleSn() != null) current.setSampleSn(patch.getSampleSn());
        if (patch.getAdaptPath() != null) current.setAdaptPath(patch.getAdaptPath());
        if (patch.getCapLanPorts() != null) current.setCapLanPorts(patch.getCapLanPorts());
        if (patch.getCapLanSpeed() != null) current.setCapLanSpeed(patch.getCapLanSpeed());
        if (patch.getCapSipCount() != null) current.setCapSipCount(patch.getCapSipCount());
        if (patch.getCapWifiSpec() != null) current.setCapWifiSpec(patch.getCapWifiSpec());
        if (patch.getRemark() != null) current.setRemark(patch.getRemark());
        modelMapper.updateById(current);
        return current;
    }

    /** 语义映射已整体迁到 SQLite 适配库：型号采集适配后由 */

    // ==================== 设备上线登记 ====================

    /** 设备上线登记：按「运营商 + 型号 + 固件」定位型号，没有就登记为待适配 */
    public CpeModel resolveOrRegister(String isp, String manufacturer, String productClass,
                                      String softwareVersion, String hardwareVersion, String sn) {
        String owner = (isp == null || isp.isBlank()) ? "standard" : isp.trim();
        String key = buildModelKey(productClass, softwareVersion);
        CpeModel exist = modelMapper.selectOne(new LambdaQueryWrapper<CpeModel>()
                .eq(CpeModel::getIsp, owner)
                .eq(CpeModel::getModelKey, key)
                .last("LIMIT 1"));
        if (exist != null) {
            return exist;
        }
        CpeModel model = new CpeModel();
        model.setIsp(owner);
        model.setModelKey(key);
        model.setManufacturer(manufacturer);
        model.setProductClass(productClass);
        model.setSoftwareVersion(softwareVersion);
        model.setHardwareVersion(hardwareVersion);
        model.setConfigPath(defaultConfigPath(owner, key));
        model.setStatus(CpeModel.STATUS_PENDING);
        model.setSampleSn(sn);
        try {
            modelMapper.insert(model);
            log.info("登记新型号待适配: isp={}, model={}, firmware={}, 样机={}",
                    owner, productClass, softwareVersion, sn);
            return model;
        } catch (DuplicateKeyException e) {
            return modelMapper.selectOne(new LambdaQueryWrapper<CpeModel>()
                    .eq(CpeModel::getIsp, owner)
                    .eq(CpeModel::getModelKey, key)
                    .last("LIMIT 1"));
        } catch (Exception e) {
            log.warn("型号登记失败: model={}, err={}", key, e.getMessage());
            return null;
        }
    }

    /** 该型号是否已完成映射可直接下发 */
    public boolean isReady(CpeModel model) {
        return model != null && CpeModel.STATUS_READY.equals(model.getStatus());
    }

    // ==================== 映射候选 ====================

    /** 从样机能力树取候选参数节点，按业务对象的推荐关键字打分排序，便于人工选择 */
    public List<Map<String, Object>> candidates(String sn, String bizObject, String keyword) {
        LambdaQueryWrapper<CpeParameter> wrapper = new LambdaQueryWrapper<CpeParameter>()
                .eq(CpeParameter::getSn, sn)
                .eq(CpeParameter::getNodeType, CpeParameter.TYPE_PARAM)
                .orderByAsc(CpeParameter::getParamPath)
                .last("LIMIT " + CANDIDATE_LIMIT);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(CpeParameter::getParamPath, keyword.trim());
        }
        List<CpeParameter> rows = parameterMapper.selectList(wrapper);
        List<String> hints = hintsOf(bizObject);
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (CpeParameter row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", row.getParamPath());
            item.put("value", row.getParamValue());
            item.put("writable", row.getWritable() != null && row.getWritable() == 1);
            item.put("valueType", row.getValueType());
            item.put("suggested", matchHint(row.getNodeName(), hints));
            result.add(item);
        }
        result.sort((a, b) -> Boolean.compare(!(boolean) b.get("suggested"), !(boolean) a.get("suggested")));
        return result;
    }

    /** 语义字典：前端映射页左侧的业务对象与字段列表 */
    public List<Map<String, Object>> schema() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SemanticSchema.BizObject object : SemanticSchema.OBJECTS) {
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("code", object.code());
            obj.put("label", object.label());
            obj.put("needInstance", object.needInstance());
            List<Map<String, Object>> fields = new ArrayList<>();
            for (SemanticSchema.BizField field : object.fields()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("code", field.code());
                f.put("label", field.label());
                f.put("type", field.type());
                f.put("required", field.required());
                fields.add(f);
            }
            obj.put("fields", fields);
            result.add(obj);
        }
        return result;
    }

    // ==================== 内部方法 ====================

    /** 必填字段是否齐备，改由 SQLite 适配结果判断：型号有没有生成出对应业务的模板 */

    private List<String> hintsOf(String bizObject) {
        SemanticSchema.BizObject object = SemanticSchema.objectOf(bizObject);
        if (object == null) {
            return List.of();
        }
        List<String> hints = new ArrayList<>();
        for (SemanticSchema.BizField field : object.fields()) {
            hints.addAll(List.of(field.hints()));
        }
        return hints;
    }

    private boolean matchHint(String nodeName, List<String> hints) {
        if (nodeName == null || hints.isEmpty()) {
            return false;
        }
        for (String hint : hints) {
            if (nodeName.toLowerCase().contains(hint.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean kw(String keyword) {
        return keyword != null && !keyword.isBlank();
    }

    /** 型号标识：型号 + 固件，固件为空时只用型号 */
    public static String buildModelKey(String productClass, String softwareVersion) {
        String model = productClass == null || productClass.isBlank() ? "UNKNOWN" : productClass.trim();
        String fw = softwareVersion == null || softwareVersion.isBlank() ? "" : softwareVersion.trim();
        return fw.isEmpty() ? model : model + "_" + fw;
    }

    /** 型号文件存放的子目录：按运营商分目录，移动 CM / 电信 CT / 联通 CU / 其它 standard */
    public static String ispDir(String isp) {
        // 归一化与目录名集中在 Isp 枚举里：四家运营商 + 广电 + 通用档只维护一处
        return Isp.of(isp).getDir();
    }

    /** 型号能力树 XML 的默认相对路径：{运营商}/standard/{型号标识}.xml */
    public static String defaultConfigPath(String isp, String modelKey) {
        return ispDir(isp) + "/standard/" + modelFileName(modelKey) + ".xml";
    }

    /** 适配状态文件 YML 的默认相对路径：与能力树 XML 同名同目录，后缀 .yml */
    public static String defaultAdaptPath(String isp, String modelKey) {
        return ispDir(isp) + "/standard/" + modelFileName(modelKey) + ".yml";
    }

    /** 型号文件名（不含扩展名）：小写 + 非法字符转下划线，xml 与 yml 同名 */
    public static String modelFileName(String modelKey) {
        return String.valueOf(modelKey).toLowerCase().replaceAll("[^a-z0-9._-]", "_");
    }
}
