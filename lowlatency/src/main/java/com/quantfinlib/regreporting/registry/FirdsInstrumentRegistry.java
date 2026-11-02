package com.quantfinlib.regreporting.registry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import com.quantfinlib.regreporting.registry.CodeRegistry.Window;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.jspecify.annotations.Nullable;

/**
 * Offline instrument registry loaded from ESMA FIRDS full reference-data files
 * ({@code FULINS_*.xml}, unzipped), published free on the ESMA registers site
 * ({@code registers.esma.europa.eu}, FIRDS files).
 *
 * <p>Streaming StAX parse that matches local names only, so the namespace version does not
 * matter. For each {@code RefData} element it reads {@code FinInstrmGnlAttrbts/Id} (ISIN),
 * {@code FinInstrmGnlAttrbts/FullNm}, {@code FinInstrmGnlAttrbts/ClssfctnTp} and every
 * {@code TradgVnRltdAttrbts/Id} (venue MIC). FIRDS repeats an ISIN once per venue; the
 * venues are merged. DTDs and external entities are disabled. The element paths follow the
 * FIRDS file layout; this class was checked only against a hand-made fixture (own test),
 * not a full ESMA file.</p>
 *
 * <p>Since 3.0.0 it also reads {@code FinInstrmGnlAttrbts/CmmdtyDerivInd} (the commodity
 * derivative indicator that CON-640 tests) and, per venue, {@code AdmssnApprvlDtByIssr},
 * {@code ReqForAdmssnDt}, {@code FrstTradDt} and {@code TermntnDt}. The instrument is taken
 * to be in the reference data for a venue from the earliest of the first three dates to the
 * termination date, both inclusive. The element names were checked against the FULINS
 * schema auth.017.001.02_ESMAUG_FULINS_1.1.0 (ESMA65-8-9654, schema set 1.2.1; published
 * reference). The inclusive end is published reference too: the termination date "is the
 * last day when the instrument is still traded" (ESMA65-11-1193 v2.1, section 2.3.4.2.12).
 * The start is the library's reading: FIRDS carries an instrument from the request for
 * admission onward (ESMA65-11-1193, description of the full file), so the earliest of the three dates is used.
 * A venue with none of the dates is present on every date, as in 2.4.</p>
 */
public final class FirdsInstrumentRegistry implements InstrumentRegistry {
    private final Map<String, InstrumentRecord> records;

    private FirdsInstrumentRegistry(Map<String, InstrumentRecord> records) {
        this.records = records;
    }

    /**
     * Loads one or more FIRDS XML files into one registry.
     *
     * @param files the files
     * @return the registry
     * @throws IOException on read or parse error
     */
    public static FirdsInstrumentRegistry load(Path... files) throws IOException {
        Builder b = new Builder();
        for (Path f : files) {
            try (InputStream in = Files.newInputStream(f)) {
                b.add(in);
            }
        }
        return b.build();
    }

    /**
     * Loads FIRDS XML from a stream.
     *
     * @param in the stream
     * @return the registry
     * @throws IOException on read or parse error
     */
    public static FirdsInstrumentRegistry load(InputStream in) throws IOException {
        Builder b = new Builder();
        b.add(in);
        return b.build();
    }

    /**
     * Number of distinct ISINs.
     *
     * @return the count
     */
    public int size() {
        return records.size();
    }

    @Override
    public Optional<InstrumentRecord> lookup(String isin) {
        return Optional.ofNullable(records.get(isin));
    }

    /** Window-aware: the instrument is known on a date if any venue window contains it. */
    @Override
    public boolean isKnownOn(String isin, LocalDate date) {
        InstrumentRecord r = records.get(isin);
        return r != null && r.isKnownOn(date);
    }

    /** Window-aware: the venue's admission window must contain the date. */
    @Override
    public boolean isAdmittedOn(String isin, String mic, LocalDate date) {
        InstrumentRecord r = records.get(isin);
        return r != null && r.isAdmittedOn(mic, date);
    }

    /** The {@code CmmdtyDerivInd} value from the file, empty when absent. */
    @Override
    public Optional<Boolean> isCommodityDerivative(String isin) {
        InstrumentRecord r = records.get(isin);
        return r == null ? Optional.empty() : Optional.ofNullable(r.commodityDerivative());
    }

    private static final class Builder {
        private final Map<String, String> names = new HashMap<>();
        private final Map<String, String> cfis = new HashMap<>();
        private final Map<String, Boolean> commodity = new HashMap<>();
        private final Map<String, Map<String, Window>> venues = new HashMap<>();

        void add(InputStream in) throws IOException {
            XMLInputFactory f = XMLInputFactory.newFactory();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            try {
                XMLStreamReader r = f.createXMLStreamReader(in);
                try {
                    parse(r);
                } finally {
                    r.close();
                }
            } catch (XMLStreamException e) {
                throw new IOException("FIRDS parse error: " + e.getMessage(), e);
            }
        }

        private void parse(XMLStreamReader r) throws XMLStreamException, IOException {
            Deque<String> path = new ArrayDeque<>();
            Rec rec = new Rec();
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String ln = r.getLocalName();
                    String parent = path.isEmpty() ? "" : path.peek();
                    if ("RefData".equals(ln)) {
                        rec = new Rec();
                    } else if ("FinInstrmGnlAttrbts".equals(parent) && rec.general(ln, r)) {
                        continue;
                    } else if ("TradgVnRltdAttrbts".equals(ln)) {
                        rec.venue = new Venue();
                    } else if ("TradgVnRltdAttrbts".equals(parent) && rec.venue != null
                            && rec.venue.read(ln, r)) {
                        continue;
                    }
                    path.push(ln);
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String ln = path.pop();
                    Venue v = rec.venue;
                    if ("TradgVnRltdAttrbts".equals(ln) && v != null) {
                        if (!v.mic.isEmpty()) {
                            rec.venues.put(v.mic, InstrumentRecord.widen(rec.venues.get(v.mic), v.window()));
                        }
                        rec.venue = null;
                    } else if ("RefData".equals(ln) && !rec.isin.isEmpty()) {
                        store(rec);
                    }
                }
            }
        }

        private void store(Rec rec) {
            names.putIfAbsent(rec.isin, rec.name);
            if (!rec.cfi.isEmpty()) {
                cfis.putIfAbsent(rec.isin, rec.cfi);
            }
            Boolean c = rec.commodity;
            if (c != null) {
                commodity.putIfAbsent(rec.isin, c);
            }
            Map<String, Window> m = venues.computeIfAbsent(rec.isin, k -> new HashMap<>());
            for (Map.Entry<String, Window> e : rec.venues.entrySet()) {
                m.put(e.getKey(), InstrumentRecord.widen(m.get(e.getKey()), e.getValue()));
            }
        }

        FirdsInstrumentRegistry build() {
            Map<String, InstrumentRecord> m = new HashMap<>();
            for (Map.Entry<String, Map<String, Window>> e : venues.entrySet()) {
                String k = e.getKey();
                m.put(k, new InstrumentRecord(k, names.getOrDefault(k, ""), cfis.getOrDefault(k, ""),
                        e.getValue().keySet(), e.getValue(), commodity.get(k)));
            }
            return new FirdsInstrumentRegistry(Map.copyOf(m));
        }
    }

    /** One {@code RefData} element being read. */
    private static final class Rec {
        String isin = "";
        String name = "";
        String cfi = "";
        @Nullable Boolean commodity;
        @Nullable Venue venue;
        final Map<String, Window> venues = new HashMap<>();

        /** Reads a known {@code FinInstrmGnlAttrbts} child; returns false for any other. */
        boolean general(String ln, XMLStreamReader r) throws XMLStreamException {
            switch (ln) {
                case "Id" -> isin = r.getElementText().trim();
                case "FullNm" -> name = r.getElementText().trim();
                case "ClssfctnTp" -> cfi = r.getElementText().trim();
                case "CmmdtyDerivInd" -> commodity = "true".equalsIgnoreCase(r.getElementText().trim());
                default -> {
                    return false;
                }
            }
            return true;
        }
    }

    /** One {@code TradgVnRltdAttrbts} element being read. */
    private static final class Venue {
        String mic = "";
        @Nullable LocalDate approval;
        @Nullable LocalDate request;
        @Nullable LocalDate firstTrade;
        @Nullable LocalDate termination;

        /** Reads a known child element; returns false for any other element. */
        boolean read(String ln, XMLStreamReader r) throws XMLStreamException, IOException {
            switch (ln) {
                case "Id" -> mic = r.getElementText().trim();
                case "AdmssnApprvlDtByIssr" -> approval = Csv.date(r.getElementText());
                case "ReqForAdmssnDt" -> request = Csv.date(r.getElementText());
                case "FrstTradDt" -> firstTrade = Csv.date(r.getElementText());
                case "TermntnDt" -> termination = Csv.date(r.getElementText());
                default -> {
                    return false;
                }
            }
            return true;
        }

        /** From the earliest of the three start dates to the termination date, both inclusive. */
        Window window() {
            LocalDate from = earlier(earlier(approval, request), firstTrade);
            return new Window(from, termination);
        }

        private static @Nullable LocalDate earlier(@Nullable LocalDate a, @Nullable LocalDate b) {
            if (a == null) {
                return b;
            }
            return b == null || !b.isBefore(a) ? a : b;
        }
    }
}
