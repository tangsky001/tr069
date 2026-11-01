package com.tr069.acs.olt.model;

/**
 * 建 OLT 本地用户要填的东西。
 *
 * <p>这些字段不是我们自己定的，是照着华为 {@code terminal user name} 那串交互式提问来的：
 * 设备会依次问用户名、密码、确认密码、用户模板、级别、可重入次数、备注，最后问要不要再来一个。
 * 下面每个字段对应其中一问，长度限制也是设备给的。</p>
 */
public class OltTerminalUserRequest {

    /** 用户名：设备要求 6~15 位 */
    private String username;

    /** 密码：设备要求 6~15 位 */
    private String password;

    /**
     * 权限级别：{@code 1}=Common User（只能看），{@code 2}=Operator（能改配置）。
     *
     * <p>没有 Administrator 这一档：华为不让建比自己权限高的账号，用 Operator 级别的账号
     * 登录时，设备只给出前两个选项。所以这里给 {@code 3} 也会被设备顶回来</p>
     */
    private Integer level;

    /** 用户模板（profile）；留空走设备的默认值，一般是 root */
    private String profile;

    /** 允许重复登录的次数，0~4 */
    private Integer reenter;

    /** 备注，<=30 个字符 */
    private String appendedInfo;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public Integer getReenter() {
        return reenter;
    }

    public void setReenter(Integer reenter) {
        this.reenter = reenter;
    }

    public String getAppendedInfo() {
        return appendedInfo;
    }

    public void setAppendedInfo(String appendedInfo) {
        this.appendedInfo = appendedInfo;
    }
}
