/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.reg;

import java.net.InetSocketAddress;

/** 分机注册条目 */
public class Registration {

    private final String extension;
    private volatile InetSocketAddress address;
    private volatile String contact;
    private volatile String userAgent = "";
    private volatile long expiresAt;

    public Registration(String extension) {
        this.extension = extension;
    }

    public String getExtension() { return extension; }
    public InetSocketAddress getAddress() { return address; }
    public void setAddress(InetSocketAddress address) { this.address = address; }
    public String getContact() { return contact; }
    public void setContact(String contact) { this.contact = contact; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }

    public boolean isAlive() {
        return address != null && System.currentTimeMillis() < expiresAt;
    }
}
