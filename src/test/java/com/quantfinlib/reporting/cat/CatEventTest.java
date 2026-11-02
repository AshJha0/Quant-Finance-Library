package com.quantfinlib.reporting.cat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatEventTest {
    private static final Instant T0 = Instant.parse("2026-03-02T14:30:00.123456789Z");

    static CatEvent meno() {
        return CatEventBuilder.newOrder().reporter("FRMA", "ROE1").orderKey("O-1", T0).eventTimestamp(T0)
                .symbol("TEST").sidePriceQty("B", new BigDecimal("10.25"), new BigDecimal("100"))
                .field("orderType", "LMT").field("timeInForce", "DAY=20260302").field("tradingSession", "REG")
                .field("handlingInstructions", "NH").field("deptType", "A")
                .field("firmDesignatedID", "ACCT1").field("accountHolderType", "I").build();
    }

    @Test
    void easternTimestampFormat() {
        CatEvent e = meno();
        // 14:30Z on 2 March 2026 is 09:30 EST (DST starts 8 March 2026).
        assertEquals("20260302 093000.123456789", e.fields().get("eventTimestamp"));
        Instant summer = Instant.parse("2026-07-01T13:30:00Z");
        CatEvent s = CatEventBuilder.route().eventTimestamp(summer).build();
        assertEquals("20260701 093000.000000000", s.fields().get("eventTimestamp"));
    }

    @Test
    void validNewOrderAndJson() {
        CatEvent e = meno();
        assertEquals(List.of(), e.validate());
        assertEquals("CAT", e.regime());
        assertEquals("ROE1", e.reference());
        assertEquals(CatEventType.MENO, e.type());
        String json = e.toJson();
        assertTrue(json.startsWith("{\"actionType\":\"NEW\",\"firmROEID\":\"ROE1\",\"type\":\"MENO\""), json);
        assertTrue(!json.contains("errorROEID"), json);
    }

    @Test
    void routeRequiresDestination() {
        CatEvent r = CatEventBuilder.route().reporter("FRMA", "ROE2").orderKey("O-1", T0).eventTimestamp(T0)
                .symbol("TEST").sidePriceQty("B", new BigDecimal("10.25"), new BigDecimal("100"))
                .field("orderType", "LMT").field("timeInForce", "DAY=20260302").field("tradingSession", "REG")
                .field("senderIMID", "FRMA").field("destinationType", "E").field("routedOrderID", "R-1")
                .field("session", "S1").field("handlingInstructions", "NH").build();
        assertEquals(List.of("missing destination"), r.validate());
    }

    @Test
    void tradeDoesNotNeedOrderKey() {
        CatEvent t = CatEventBuilder.trade().reporter("FRMA", "ROE3").eventTimestamp(T0).symbol("TEST")
                .field("tradeID", "T-1").field("price", "10.25").field("quantity", "100")
                .field("marketCenterID", "D").field("sideDetailsInd", "BOTH")
                .field("buyDetails", "O-1").field("sellDetails", "O-2").build();
        assertEquals(List.of(), t.validate());
    }

    @Test
    void badValuesReported() {
        CatEvent e = CatEventBuilder.fulfillment().reporter("lower", "ROE4").orderKey("O-1", T0).eventTimestamp(T0)
                .symbol("TEST").sidePriceQty("X", new BigDecimal("1.123456789"), BigDecimal.ZERO)
                .field("fulfillmentID", "F1").field("fulfillmentLinkType", "Y")
                .action("COR", "").build();
        List<String> v = e.validate();
        assertTrue(v.contains("errorROEID required for actionType COR"), v.toString());
        assertTrue(v.stream().anyMatch(s -> s.startsWith("side must")), v.toString());
        assertTrue(v.contains("CATReporterIMID must be 1-7 upper-case alphanumerics"), v.toString());
        assertTrue(v.contains("quantity must be positive"), v.toString());
        assertTrue(v.contains("price must be a decimal with at most 8 places"), v.toString());
    }

    @Test
    void sinkWritesLinesAndRejectsInvalid() {
        StringWriter w = new StringWriter();
        CatJsonLinesSink sink = new CatJsonLinesSink(w);
        sink.submit(meno());
        sink.submit(meno());
        assertEquals(2, sink.written());
        assertEquals(2, w.toString().split("\n").length);
        assertThrows(IllegalArgumentException.class, () -> sink.submit(CatEventBuilder.route().build()));
        assertEquals(2, sink.written());
    }
}
