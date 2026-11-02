package com.quantfinlib.reporting.registry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

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

    private static final class Builder {
        private final Map<String, String> names = new HashMap<>();
        private final Map<String, String> cfis = new HashMap<>();
        private final Map<String, Set<String>> venues = new HashMap<>();

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

        private void parse(XMLStreamReader r) throws XMLStreamException {
            Deque<String> path = new ArrayDeque<>();
            String isin = "";
            String name = "";
            String cfi = "";
            Set<String> mics = new HashSet<>();
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String ln = r.getLocalName();
                    String parent = path.isEmpty() ? "" : path.peek();
                    if ("RefData".equals(ln)) {
                        isin = "";
                        name = "";
                        cfi = "";
                        mics = new HashSet<>();
                    } else if ("FinInstrmGnlAttrbts".equals(parent) && "Id".equals(ln)) {
                        isin = r.getElementText().trim();
                        continue;
                    } else if ("FinInstrmGnlAttrbts".equals(parent) && "FullNm".equals(ln)) {
                        name = r.getElementText().trim();
                        continue;
                    } else if ("FinInstrmGnlAttrbts".equals(parent) && "ClssfctnTp".equals(ln)) {
                        cfi = r.getElementText().trim();
                        continue;
                    } else if ("TradgVnRltdAttrbts".equals(parent) && "Id".equals(ln)) {
                        mics.add(r.getElementText().trim());
                        continue;
                    }
                    path.push(ln);
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String ln = path.pop();
                    if ("RefData".equals(ln) && !isin.isEmpty()) {
                        names.putIfAbsent(isin, name);
                        if (!cfi.isEmpty()) {
                            cfis.putIfAbsent(isin, cfi);
                        }
                        venues.computeIfAbsent(isin, k -> new HashSet<>()).addAll(mics);
                    }
                }
            }
        }

        FirdsInstrumentRegistry build() {
            Map<String, InstrumentRecord> m = new HashMap<>();
            for (Map.Entry<String, Set<String>> e : venues.entrySet()) {
                String k = e.getKey();
                m.put(k, new InstrumentRecord(k, names.getOrDefault(k, ""), cfis.getOrDefault(k, ""),
                        e.getValue()));
            }
            return new FirdsInstrumentRegistry(Map.copyOf(m));
        }
    }
}
