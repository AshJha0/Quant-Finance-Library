package com.quantfinlib.data;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.screener.Fundamentals;
import com.quantfinlib.screener.StockScreener;
import com.quantfinlib.screener.StockSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Point-in-time membership: intervals (including drop-and-re-add), terminal
 * events ending membership, as-of queries, validation, and the screener's
 * point-in-time filter.
 */
class PointInTimeUniverseTest {

    @Test
    void membershipIntervalsAnswerAsOfQueries() {
        PointInTimeUniverse u = new PointInTimeUniverse()
                .addMembership("ALIVE", 100)              // current member since 100
                .addMembership("DROPPED", 100, 200)       // left the index at 200
                .addMembership("REJOINED", 100, 150)
                .addMembership("REJOINED", 300);          // drop and later re-add

        assertTrue(u.isMember("ALIVE", 100));             // from is inclusive
        assertTrue(u.isMember("ALIVE", 1_000_000));
        assertFalse(u.isMember("ALIVE", 99));

        assertTrue(u.isMember("DROPPED", 200));           // to is inclusive
        assertFalse(u.isMember("DROPPED", 201));

        assertTrue(u.isMember("REJOINED", 120));
        assertFalse(u.isMember("REJOINED", 200));         // the gap
        assertTrue(u.isMember("REJOINED", 300));

        assertEquals(Set.of("ALIVE", "DROPPED", "REJOINED"), u.membersAsOf(120));
        assertEquals(Set.of("ALIVE"), u.membersAsOf(250));
        assertFalse(u.isMember("NEVER_ADDED", 120));
    }

    @Test
    void terminalEventsEndMembershipPermanently() {
        PointInTimeUniverse u = new PointInTimeUniverse()
                .addMembership("DOOM", 0)                 // open-ended membership...
                .recordDelisting("DOOM", 500, -1.0);      // ...until bankruptcy
        assertTrue(u.isMember("DOOM", 499));
        assertFalse(u.isMember("DOOM", 500));             // dead exactly at the event
        assertFalse(u.isMember("DOOM", 501));
        PointInTimeUniverse.TerminalEvent event = u.terminalEvent("DOOM");
        assertEquals(PointInTimeUniverse.EventType.DELISTING, event.type());
        assertEquals(-1.0, event.delistingReturn());
        assertEquals(Set.of("DOOM"), u.allSymbols());
        // The literature's default haircut is available as a named constant.
        assertEquals(-0.30, PointInTimeUniverse.DEFAULT_INVOLUNTARY_DELISTING_RETURN);
    }

    @Test
    void validationRejectsBadInput() {
        PointInTimeUniverse u = new PointInTimeUniverse();
        assertThrows(IllegalArgumentException.class, () -> u.addMembership("X", 10, 5));
        assertThrows(IllegalArgumentException.class, () -> u.recordDelisting("X", 1, -1.5));
        assertThrows(IllegalArgumentException.class, () -> u.recordMerger("X", 1, -1, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> u.recordMerger("X", 1, 0, 0.5, null)); // stock deal needs acquirer
        u.recordDelisting("DEAD", 1, -1);
        assertThrows(IllegalArgumentException.class,
                () -> u.recordMerger("DEAD", 2, 1, 0, null)); // one terminal event per life
    }

    @Test
    void screenerFiltersToPointInTimeMembers() {
        PointInTimeUniverse u = new PointInTimeUniverse()
                .addMembership("AAA", 0)
                .addMembership("GONE", 0, 50);
        List<StockSnapshot> all = List.of(snapshot("AAA"), snapshot("GONE"), snapshot("NEVER"));
        // As of t=40 both listed names are members; the never-added one is not.
        assertEquals(2, StockScreener.membersAsOf(all, u, 40).size());
        // As of t=60 only the survivor remains — dead tickers stay in the
        // snapshot list (that's the point) but drop out of the screen.
        List<StockSnapshot> later = StockScreener.membersAsOf(all, u, 60);
        assertEquals(1, later.size());
        assertEquals("AAA", later.get(0).symbol());
    }

    private static StockSnapshot snapshot(String symbol) {
        BarSeries.Builder b = BarSeries.builder(symbol);
        for (int i = 0; i < 10; i++) {
            b.add(i, 100, 101, 99, 100, 1_000);
        }
        return new StockSnapshot(symbol, b.build(), Fundamentals.unknown());
    }
}
