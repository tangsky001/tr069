package com.tr069.acs.olt.telnet;

import com.tr069.acs.olt.model.OltOnt;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 用现场 MA5683T 的真实回显（2026-11-04 抓自 10.11.104.1）验证 ONT 数量 / 明细解析。
 *
 * <p>这个固件的明细表比老版多一列 Protect side，Description 表的描述是
 * {@code B482C-00}——十六进制样式，长得和 SN 一样，专门用来考正则。</p>
 */
class OltOntInfoParseTest {

    /** 真实回显：端口 0 共 8 台、端口 1 共 25 台，全部 online，后跟 Description 表 */
    private static final String REAL_ECHO = String.join("\n",
            "  -----------------------------------------------------------------------------",
            "  F/S/P   ONT         SN         Control     Run      Config   Match    Protect",
            "          ID                     flag        state    state    state    side ",
            "  -----------------------------------------------------------------------------",
            "  0/ 1/0    0  53554E4E7B806BF5  active      online   normal   match    no ",
            "  0/ 1/0    1  53554E4E7B68FDF6  active      online   normal   match    no ",
            "  0/ 1/0    2  53554E4E7B6AC34A  active      online   normal   match    no ",
            "  0/ 1/0    3  53554E4E7B68F83F  active      online   normal   match    no ",
            "  0/ 1/0    4  53554E4E7B67D2EF  active      online   normal   match    no ",
            "  0/ 1/0    5  53554E4E7B693541  active      online   normal   match    no ",
            "  0/ 1/0    6  53554E4E7B695A4A  active      online   normal   match    no ",
            "  0/ 1/0    7  53554E4E7B6C97E8  active      online   normal   match    no ",
            "  -----------------------------------------------------------------------------",
            "  F/S/P   ONT-ID   Description",
            "  -----------------------------------------------------------------------------",
            "  0/ 1/0       0   B482C-00",
            "  0/ 1/0       1   B482C-00",
            "  0/ 1/0       2   B482C-00",
            "  0/ 1/0       3   B482C-00",
            "  0/ 1/0       4   B482C-00",
            "  0/ 1/0       5   B482C-00",
            "  0/ 1/0       6   B482C-00",
            "  0/ 1/0       7   B482C-00",
            "  -----------------------------------------------------------------------------",
            "  In port 0/ 1/0 , the total of ONTs are: 8, online: 8",
            "  -----------------------------------------------------------------------------",
            "  F/S/P   ONT         SN         Control     Run      Config   Match    Protect",
            "          ID                     flag        state    state    state    side ",
            "  -----------------------------------------------------------------------------",
            "  0/ 1/1    0  53554E4E7B67B7C5  active      online   normal   match    no ",
            "  0/ 1/1    1  53554E4E7B692A58  active      online   normal   match    no ",
            "  0/ 1/1    2  53554E4E7B6ACCDD  active      online   normal   match    no ",
            "  0/ 1/1    3  53554E4E7B83AC19  active      online   normal   match    no ",
            "  0/ 1/1    4  53554E4E7B851AAA  active      online   normal   match    no ",
            "  0/ 1/1    5  53554E4E7B875C4F  active      online   normal   match    no ",
            "  0/ 1/1    6  53554E4E7BADA371  active      online   normal   match    no ",
            "  0/ 1/1    7  53554E4E7B691B5A  active      online   normal   match    no ",
            "  0/ 1/1    8  53554E4E7B850382  active      online   normal   match    no ",
            "  0/ 1/1    9  53554E4E7B832480  active      online   normal   match    no ",
            "  0/ 1/1   10  53554E4E7BAD91DA  active      online   normal   match    no ",
            "  0/ 1/1   11  53554E4E7B6B2A9A  active      online   normal   match    no ",
            "  0/ 1/1   12  53554E4E7B7F952B  active      online   normal   match    no ",
            "  0/ 1/1   13  53554E4E7B69D876  active      online   normal   match    no ",
            "  0/ 1/1   14  53554E4E7B84C894  active      online   normal   match    no ",
            "  0/ 1/1   15  53554E4E7B65DDCF  active      online   normal   match    no ",
            "  0/ 1/1   16  53554E4E7B84CACE  active      online   normal   match    no ",
            "  0/ 1/1   17  53554E4E7B6A2AEB  active      online   normal   match    no ",
            "  0/ 1/1   18  53554E4E7B6CD2E9  active      online   normal   match    no ",
            "  0/ 1/1   19  53554E4E7B81EF15  active      online   normal   match    no ",
            "  0/ 1/1   20  53554E4E7B6632A4  active      online   normal   match    no ",
            "  0/ 1/1   21  53554E4E7B662EDB  active      online   normal   match    no ",
            "  0/ 1/1   22  53554E4E7B647CD3  active      online   normal   match    no ",
            "  0/ 1/1   23  53554E4E7B850252  active      online   normal   match    no ",
            "  0/ 1/1   24  53554E4E7B67D315  active      online   normal   match    no ",
            "  -----------------------------------------------------------------------------",
            "  F/S/P   ONT-ID   Description",
            "  -----------------------------------------------------------------------------",
            "  0/ 1/1       0   B482C-00",
            "  0/ 1/1       1   B482C-00",
            "  0/ 1/1       2   B482C-00",
            "  0/ 1/1       3   B482C-00",
            "  0/ 1/1       4   B482C-00",
            "  0/ 1/1       5   B482C-00",
            "  0/ 1/1       6   B482C-00",
            "  0/ 1/1       7   B482C-00",
            "  0/ 1/1       8   B482C-00",
            "  0/ 1/1       9   B482C-00",
            "  0/ 1/1      10   B482C-00",
            "  0/ 1/1      11   B482C-00",
            "  0/ 1/1      12   B482C-00",
            "  0/ 1/1      13   B482C-00",
            "  0/ 1/1      14   B482C-00",
            "  0/ 1/1      15   B482C-00",
            "  0/ 1/1      16   B482C-00",
            "  0/ 1/1      17   B482C-00",
            "  0/ 1/1      18   B482C-00",
            "  0/ 1/1      19   B482C-00",
            "  0/ 1/1      20   B482C-00",
            "  0/ 1/1      21   B482C-00",
            "  0/ 1/1      22   B482C-00",
            "  0/ 1/1      23   B482C-00",
            "  0/ 1/1      24   B482C-00",
            "  -----------------------------------------------------------------------------",
            "  In port 0/ 1/1 , the total of ONTs are: 25, online: 25");

    @Test
    void countsMatchSummaryRows() {
        Map<Integer, int[]> counts = OltInfoParser.parseOntCounts(REAL_ECHO);
        assertEquals(8, counts.get(0)[0], "端口 0 总数应为 8");
        assertEquals(8, counts.get(0)[1], "端口 0 在线应为 8");
        assertEquals(25, counts.get(1)[0], "端口 1 总数应为 25");
        assertEquals(25, counts.get(1)[1], "端口 1 在线应为 25");
    }

    @Test
    void detailsMatchRealOntRows() {
        Map<Integer, List<OltOnt>> details = OltInfoParser.parseOntDetails(REAL_ECHO);
        List<OltOnt> port0 = details.get(0);
        List<OltOnt> port1 = details.get(1);
        assertEquals(8, port0.size(), "端口 0 明细应为 8 条，Description 表的 B482C-00 不能算 ONT");
        assertEquals(25, port1.size(), "端口 1 明细应为 25 条");
        OltOnt first = port0.get(0);
        assertEquals("53554E4E7B806BF5", first.getSn(), "SN 应取明细表的真 SN");
        assertEquals("B482C-00", first.getDescription(), "型号应由 Description 表回填");
        assertEquals(Boolean.TRUE, first.getOnline(), "Run state=online 应解析为在线");
    }
}
