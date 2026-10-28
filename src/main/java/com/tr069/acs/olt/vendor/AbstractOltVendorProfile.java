package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.entity.OltBoard;
import com.tr069.acs.olt.telnet.OltInfoParser;
import com.tr069.acs.olt.telnet.OltVendor;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 厂商 profile 的默认实现：第三方 OLT 的常见样子。
 *
 * <p>继承它只需要覆写跟这家不一样的方法（多数情况就是命令清单、
 * {@link #matches}、{@link #extractModel}）。</p>
 */
public abstract class AbstractOltVendorProfile implements OltVendorProfile {

    /** 型号常见写法：Model: xxx / Product name : xxx / 设备型号 / 产品名称 */
    protected static final Pattern MODEL_LABEL = Pattern.compile(
            "(?im)^\\s*(?:model(?:\\s*name)?|product\\s*name|device\\s*type|equipment\\s*type|设备型号|产品名称)"
                    + "\\s*[:：]\\s*([A-Za-z0-9._/-]{3,40})\\s*$");

    @Override
    public OltVendor.CliStyle cliStyle() {
        return OltVendor.CliStyle.SHOW;
    }

    @Override
    public List<String> pagingCommands() {
        return List.of("terminal length 0");
    }

    @Override
    public List<String> versionCommands() {
        return List.of("show version");
    }

    @Override
    public List<String> boardCommands() {
        return List.of("show card", "show slot");
    }

    @Override
    public String extractModel(String text) {
        return firstMatch(text, modelPatterns());
    }

    /** 自家型号的识别顺序，子类覆写后把自己的型号放前面 */
    protected List<Pattern> modelPatterns() {
        return List.of(MODEL_LABEL);
    }

    /** 按给定顺序逐个试，第一个匹配上的就是型号 */
    protected static String firstMatch(String text, List<Pattern> patterns) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                String model = matcher.group(1).trim();
                if (!model.isBlank()) {
                    return model;
                }
            }
        }
        return null;
    }

    /** 回显关键字匹配，避免每个子类都写一遍大小写转换 */
    protected static boolean containsAny(String output, String... keywords) {
        if (output == null || output.isBlank()) {
            return false;
        }
        String lower = output.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Integer slotCount(String boardText, List<OltBoard> boards) {
        if (boardText == null || boardText.isBlank()) {
            return null;
        }
        return OltInfoParser.parseSlotCount(boardText);
    }
}
