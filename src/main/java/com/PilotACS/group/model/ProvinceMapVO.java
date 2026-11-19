package com.PilotACS.group.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 省份地图汇总：省级节点的设备数与配色。 */
@Data
@Schema(description = "省份地图汇总：每省设备数与配色（颜色后端裁定，前端只渲染）")
public class ProvinceMapVO {

    @Schema(description = "省份清单：有设备的省按设备数降序在前，无设备的省殿后（颜色同样下发，供地图整幅渲染）")
    private List<Province> provinces = new ArrayList<>();

    @Data
    @Schema(description = "省份行：名称、设备数（含下级汇总）、地图填充色")
    public static class Province {

        @Schema(description = "省份名称（与区划骨架一致，如「浙江省」）")
        private String name;

        @Schema(description = "该省下辖设备总数（含下级汇总）")
        private long value;

        @Schema(description = "地图填充色：有设备的省统一高亮蓝，无设备的省随机观感粉彩色（逐省不同、稳定不变）")
        private String color;
    }
}
