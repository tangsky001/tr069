package com.tr069.acs.cwmp;

import lombok.Getter;

/**
 * CWMP / SOAP 命名空间常量。
 *
 * <p>取值对齐 Python 版 construct.py 的 {@code create_soap_envelope}。</p>
 */
public final class SoapNamespace {

    private SoapNamespace() {
    }

    public static final String SOAP_ENV = "http://schemas.xmlsoap.org/soap/envelope/";

    public static final String SOAP_ENC = "http://schemas.xmlsoap.org/soap/encoding/";

    public static final String XSD = "http://www.w3.org/2001/XMLSchema";

    public static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";

    /**
     * CWMP 命名空间的固定前缀，其后拼接版本号，如 cwmp-1-0
     */
    public static final String CWMP_PREFIX = "urn:dslforum-org:";

    public static final String PREFIX_SOAP_ENV = "SOAP-ENV";

    public static final String PREFIX_SOAP_ENC = "SOAP-ENC";

    public static final String PREFIX_XSD = "xsd";

    public static final String PREFIX_XSI = "xsi";

    public static final String PREFIX_CWMP = "cwmp";

    /**
     * 组装指定 CWMP 版本的命名空间。
     *
     * @param cwmpVersion 如 cwmp-1-0
     * @return 完整命名空间 URI
     */
    public static String cwmp(String cwmpVersion) {
        return CWMP_PREFIX + (cwmpVersion == null ? "cwmp-1-0" : cwmpVersion);
    }
}
