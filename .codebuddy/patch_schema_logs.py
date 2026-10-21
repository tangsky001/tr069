"""一次性脚本：把 6 个建表初始化器的 log.info 换成统一分类日志。"""
import pathlib

BASE = pathlib.Path(r"e:\tr069\src\main\java\com\tr069\acs")

# 文件相对路径 -> (业务分类键, 中文说明)
TARGETS = {
    "common/startup/BizSchemaInitializer.java": ("业务模板表",),
    "common/startup/CapabilitySchemaInitializer.java": ("设备能力树表",),
    "common/startup/ConfigTemplateSchemaInitializer.java": ("配置意图模板表",),
    "common/startup/ModelSchemaInitializer.java": ("型号映射表",),
    "exec/config/DeviceTaskSchemaInitializer.java": ("下发任务表",),
    "openapi/config/OpenApiSchemaInitializer.java": ("北向事件表",),
}

IMPORTS = ("import com.tr069.acs.common.log.AcsCoreLog;\n"
           "import com.tr069.acs.common.log.LogCategory;\n")

for rel, (label,) in TARGETS.items():
    p = BASE / rel
    src = p.read_text(encoding="utf-8")

    assert "import com.tr069.acs.common.log.AcsCoreLog;" not in src, rel

    # 1) 注入 AcsCoreLog
    old_field = "    private final JdbcTemplate jdbcTemplate;\n"
    assert old_field in src, rel
    src = src.replace(old_field, old_field + "\n    private final AcsCoreLog coreLog;\n", 1)

    # 2) 补 import：放在第一个 lombok / org.springframework import 之前，保持字母序
    anchor = "import lombok."
    idx = src.index(anchor)
    src = src[:idx] + IMPORTS + "\n" + src[idx:]

    # 3) 建表循环后补一条分类日志
    anchor2 = "                jdbcTemplate.execute(sql);\n            }\n"
    assert anchor2 in src, rel
    src = src.replace(
        anchor2,
        anchor2
        + '            coreLog.entry(LogCategory.STARTUP).bizKey("自动建表").code("'
        + label + '").result(true)\n'
        + '                    .with("表", String.join(", ", TABLES))\n'
        + '                    .info("启动时已补齐缺失的表");\n',
        1,
    )

    p.write_text(src, encoding="utf-8", newline="")
    print("ok", rel)
