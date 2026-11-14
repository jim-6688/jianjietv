package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** The ISP names come straight from the public-IP echo response; keep the parsing rules pinned. */
public class IspNamesTest {

    @Test
    public void readsChineseEchoResponses() {
        assertEquals("中国电信", IspNames.from("当前 IP：1.2.3.4 来自于：中国 北京 电信"));
        assertEquals("中国联通", IspNames.from("当前 IP：114.253.35.136 来自于：中国 北京 联通"));
        assertEquals("中国移动", IspNames.from("当前 IP：1.2.3.4 来自于：中国 广东 移动"));
    }

    @Test
    public void readsEnglishCarrierMarkers() {
        assertEquals("中国电信", IspNames.from("var returnCitySN = {\"cname\":\"CHINANET\"};"));
        assertEquals("中国联通", IspNames.from("cname=UNICOM"));
        assertEquals("中国移动", IspNames.from("cname: CMCC"));
    }

    @Test
    public void aBareIpOrEmptyResponseHasNoCarrier() {
        assertNull(IspNames.from("114.253.35.136"));
        assertNull(IspNames.from(""));
        assertNull(IspNames.from(null));
    }
}
