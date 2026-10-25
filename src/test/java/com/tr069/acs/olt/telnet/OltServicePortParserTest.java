package com.tr069.acs.olt.telnet;

import com.tr069.acs.olt.model.OltServicePort;
import com.tr069.acs.olt.model.OltSrvProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务流（display service-port all）与 ONT 能力模板（display ont-srvprofile gpon all）解析。
 */
class OltServicePortParserTest {

    @Test
    void parsesServicePortRows() {
        String text = "MA5683T(config)#display service-port all\r\n"
                + "  ------------------------------------------------------------------------------\r\n"
                + "  INDEX  VLAN  VLAN  PORT  F/ S/ P   VPI  VCI  FLOW  FLOW  RX  TX  STATE\r\n"
                + "         ID    ATTR  TYPE\r\n"
                + "  ------------------------------------------------------------------------------\r\n"
                + "     0    45    45  gpon  0/ 1/ 0    1    0   -   -   -   -   -\r\n"
                + "     1    41    41  gpon  0/ 1/ 0    1    1   -   -   -   -   -\r\n"
                + "  ------------------------------------------------------------------------------\r\n"
                + "  Total : 2\r\n";

        List<OltServicePort> ports = OltInfoParser.parseServicePorts(text);
        assertEquals(2, ports.size());
        OltServicePort first = ports.get(0);
        assertEquals(0, first.getIndex());
        assertEquals(45, first.getVlanId());
        assertEquals("gpon", first.getPortType());
        assertEquals("0/1/0", first.iface());
        assertEquals(1, first.getOntId());
        assertEquals(0, first.getGemport());
        assertEquals(1, ports.get(1).getGemport());
    }

    @Test
    void ignoresUnparsableRows() {
        List<OltServicePort> ports = OltInfoParser.parseServicePorts("Total : 0");
        assertTrue(ports.isEmpty());
        assertTrue(OltInfoParser.parseServicePorts(null).isEmpty());
    }

    @Test
    void parsesSrvProfilesWithTr069() {
        String text = "MA5683T(config)#display ont-srvprofile gpon all\r\n"
                + "  ------------------------------------------------------------------------------\r\n"
                + "  Profile-ID                    : 10\r\n"
                + "  Profile-name                  : HG5145F\r\n"
                + "  Binding times                 : 22\r\n"
                + "  TR069 management              : Enable\r\n"
                + "  TR069 ACS URL                 : http://10.172.10.2:9090/acs\r\n"
                + "  ------------------------------------------------------------------------------\r\n"
                + "  Profile-ID                    : 11\r\n"
                + "  Profile-name                  : FTTH_BASIC\r\n"
                + "  ------------------------------------------------------------------------------\r\n";

        List<OltSrvProfile> profiles = OltInfoParser.parseSrvProfiles(text);
        assertEquals(2, profiles.size());
        OltSrvProfile first = profiles.get(0);
        assertEquals(10, first.getProfileId());
        assertEquals("HG5145F", first.getName());
        // TR069 那几项单独一组，能力项里不再重复
        assertEquals("http://10.172.10.2:9090/acs", first.getTr069().get("TR069 ACS URL"));
        assertTrue(first.getTr069().containsKey("TR069 management"));
        assertTrue(first.getItems().containsKey("Binding times"));
        assertNull(first.getItems().get("TR069 ACS URL"));
        // 第二条没有 TR069 项，不该报错
        assertTrue(profiles.get(1).getTr069().isEmpty());
    }
}
