package com.tr069.acs.cwmp;

import com.tr069.acs.cwmp.model.ParameterValueStruct;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SetParameterValues 报文构造。
 */
class SoapEnvelopeBuilderTest {

    private static final String PATH = "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1."
            + "4.WANIPConnection.VLANIDMark";

    @Test
    void buildsParameterListFromStructs() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ParameterList", List.of(ParameterValueStruct.builder()
                .name(PATH).value("41").valueType("int").build()));
        args.put("ParameterKey", "");

        String xml = SoapEnvelopeBuilder.build(RpcMethod.SET_PARAMETER_VALUES, args, "1");

        assertTrue(xml.contains("<cwmp:Name xsi:type=\"xsd:string\">" + PATH + "</cwmp:Name>"),
                "参数路径必须写进报文");
        assertTrue(xml.contains(">41</cwmp:Value>"), "参数值必须写进报文");
    }

    /**
     * 指令经队列 JSON 往返后，ParameterValueStruct 退化成 Map，键名是字段名（小写开头）。
     * 构造时必须忽略大小写取值，否则下发的是一组空参数。
     */
    @Test
    void buildsParameterListFromQueuedMaps() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ParameterList", List.of(Map.of("name", PATH, "value", "41", "valueType", "int")));
        args.put("ParameterKey", "");

        String xml = SoapEnvelopeBuilder.build(RpcMethod.SET_PARAMETER_VALUES, args, "1");

        assertTrue(xml.contains("<cwmp:Name xsi:type=\"xsd:string\">" + PATH + "</cwmp:Name>"),
                "队列往返后参数路径不能丢");
        assertTrue(xml.contains(">41</cwmp:Value>"), "队列往返后参数值不能丢");
    }
}
