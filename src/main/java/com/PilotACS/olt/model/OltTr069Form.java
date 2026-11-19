/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.460
 *
 */

package com.PilotACS.olt.model;

/** TR-069（ACS）模板表单的默认值和取值约束，建模板的两个入口（一键开局、「TR069模板」 */
public record OltTr069Form(boolean supported, OltTr069ProfileRequest defaults,
                           int profileIdMin, int profileIdMax, int portMin, int portMax,
                           int nameMax, int urlMax, int fieldMax, String urlPattern) {
}
