package com.quantfinlib.regreporting;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * A MiFIR transaction reporting status advice: the auth.031.001.01 feedback file a competent
 * authority returns for a submitted auth.016 file (ESMA65-8-2356 section 6.3, paragraphs 100
 * to 106; ESMA usage guideline auth.031.001.01_ESMAUG_Reporting_1.1.0, both published
 * reference, on disk).
 *
 * <p>Plain idea: one file status, the rules the whole file broke, record counts per status,
 * and one entry for each record that was not simply accepted, with the error codes that
 * explain it. The entry names the record by field 4 followed by field 2 (paragraph 103).
 *
 * <p>Formal statement: {@code FinInstrmRptgStsAdvc/StsAdvc} holds an optional
 * {@code MsgRptIdr}, {@code MsgSts} ({@code Sts}, zero or more {@code VldtnRule}, optional
 * {@code Sttstcs} with {@code TtlNbOfRcrds} and one {@code NbOfRcrdsPerSts} per status) and
 * zero or more {@code RcrdSts} ({@code OrgnlRcrdId}, {@code Sts}, zero or more
 * {@code VldtnRule} with {@code Id} and {@code Desc}). File statuses are those of
 * {@code ReportingMessageStatus1Code__1}: ACPT, PART, RCVD, RJCT, RMDR, WARN, INCF, CRPT.
 * The XSD allows several {@code StsAdvc} blocks; this class reads the first and reports
 * the count.
 *
 * <p>Worked example ({@code StatusAdviceTest}): two records, one ACPT and one RJCT with
 * CON-023, give file status PART (paragraph 105: some records accepted, some not), totals
 * 2 with one ACPT and one RJCT, and a single {@code RcrdSts} for the rejected record, since
 * on a first validation ACPT appears only in the statistics (paragraph 106).
 *
 * @param messageReportId {@code MsgRptIdr}, empty when absent
 * @param fileStatus the file status code
 * @param fileRules validation rules the whole file broke
 * @param totalRecords {@code TtlNbOfRcrds}, or -1 when there are no statistics
 * @param recordsPerStatus {@code NbOfRcrdsPerSts}, empty when there are no statistics
 * @param records the {@code RcrdSts} entries
 * @since 3.0.0
 */
public record StatusAdvice(String messageReportId, String fileStatus, List<Rule> fileRules, int totalRecords,
        Map<RecordStatus, Integer> recordsPerStatus, List<Record> records) {
    /** auth.031.001.01 namespace. */
    public static final String NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:auth.031.001.01";
    /** Message definition id for the business application header. */
    public static final String MESSAGE_DEFINITION = "auth.031.001.01";
    /** {@code ReportingMessageStatus1Code__1} values (XSD 1.1.0). */
    public static final Set<String> FILE_STATUSES = Set.of("ACPT", "PART", "RCVD", "RJCT", "RMDR", "WARN",
            "INCF", "CRPT");

    /**
     * A validation rule reference ({@code GenericValidationRuleIdentification1__1}).
     *
     * @param id the error code, for example {@code CON-411}, at most 35 characters
     * @param description the error text, at most 350 characters
     */
    public record Rule(String id, String description) {
        /** Validates lengths. */
        public Rule {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(description, "description");
            if (id.isEmpty() || id.length() > 35) {
                throw new IllegalArgumentException("rule id must be 1 to 35 characters");
            }
            if (description.isEmpty() || description.length() > 350) {
                throw new IllegalArgumentException("rule description must be 1 to 350 characters");
            }
        }

        /**
         * A rule from a library error string {@code "CODE: text"}; the text is cut to 350
         * characters, and an error without a colon becomes its own description.
         *
         * @param error the error string
         * @return the rule
         */
        public static Rule ofError(String error) {
            int c = error.indexOf(':');
            String id = (c < 0 ? error : error.substring(0, c)).trim();
            String desc = (c < 0 ? error : error.substring(c + 1)).trim();
            if (desc.isEmpty()) {
                desc = id;
            }
            return new Rule(id.length() > 35 ? id.substring(0, 35) : id,
                    desc.length() > 350 ? desc.substring(0, 350) : desc);
        }
    }

    /**
     * One {@code RcrdSts} entry.
     *
     * @param recordId {@code OrgnlRcrdId}: field 4 followed by field 2, at most 140 characters
     * @param status the record status
     * @param rules the rules the record broke (CON-411 or CON-471 for a pending record)
     */
    public record Record(String recordId, RecordStatus status, List<Rule> rules) {
        /** Validates and copies. */
        public Record {
            Objects.requireNonNull(recordId, "recordId");
            Objects.requireNonNull(status, "status");
            if (recordId.isEmpty() || recordId.length() > 140) {
                throw new IllegalArgumentException("record id must be 1 to 140 characters");
            }
            rules = List.copyOf(rules);
        }
    }

    /** Validates and copies. */
    public StatusAdvice {
        Objects.requireNonNull(messageReportId, "messageReportId");
        Objects.requireNonNull(fileStatus, "fileStatus");
        if (!FILE_STATUSES.contains(fileStatus)) {
            throw new IllegalArgumentException("not an auth.031 file status: '" + fileStatus + "'");
        }
        if (messageReportId.length() > 35) {
            throw new IllegalArgumentException("message report id must be at most 35 characters");
        }
        fileRules = List.copyOf(fileRules);
        recordsPerStatus = recordsPerStatus.isEmpty() ? Map.of() : Map.copyOf(recordsPerStatus);
        records = List.copyOf(records);
    }

    /**
     * Builds the advice for a set of record outcomes (for a dry run or a test authority).
     * File status (paragraph 105): ACPT when every record is accepted, RJCT when every record
     * is rejected, otherwise PART. Statistics count every outcome; a {@code RcrdSts} entry is
     * written for each record that is not ACPT, and for ACPT records too when
     * {@code listAccepted} is set (paragraph 106: a record accepted after pending is listed).
     *
     * @param messageReportId {@code MsgRptIdr}, may be empty
     * @param outcomes the outcomes, at least one
     * @param listAccepted whether accepted records get their own entry
     * @return the advice
     */
    public static StatusAdvice of(String messageReportId, List<RecordOutcome> outcomes, boolean listAccepted) {
        if (outcomes.isEmpty()) {
            throw new IllegalArgumentException("at least one record outcome is needed");
        }
        Map<RecordStatus, Integer> counts = new EnumMap<>(RecordStatus.class);
        List<Record> recs = new ArrayList<>();
        for (RecordOutcome o : outcomes) {
            counts.merge(o.status(), 1, Integer::sum);
            if (o.status() != RecordStatus.ACCEPTED || listAccepted) {
                List<Rule> rules = new ArrayList<>();
                for (String e : o.errors()) {
                    rules.add(Rule.ofError(e));
                }
                recs.add(new Record(o.recordId(), o.status(), rules));
            }
        }
        int n = outcomes.size();
        String sts = counts.getOrDefault(RecordStatus.ACCEPTED, 0) == n ? "ACPT"
                : counts.getOrDefault(RecordStatus.REJECTED, 0) == n ? "RJCT" : "PART";
        return new StatusAdvice(messageReportId, sts, List.of(), n, counts, recs);
    }

    /**
     * The entry for a record id, if listed.
     *
     * @param recordId field 4 followed by field 2
     * @return the entry, or null when the record is not listed (for example accepted)
     */
    public Record record(String recordId) {
        for (Record r : records) {
            if (r.recordId().equals(recordId)) {
                return r;
            }
        }
        return null;
    }

    /**
     * The auth.031 {@code Document}.
     *
     * @return the XML, with declaration
     */
    public String toXml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<Document xmlns=\"").append(NAMESPACE).append("\">\n");
        sb.append("  <FinInstrmRptgStsAdvc>\n");
        sb.append("    <StsAdvc>\n");
        if (!messageReportId.isEmpty()) {
            leaf(sb, "      ", "MsgRptIdr", messageReportId);
        }
        sb.append("      <MsgSts>\n");
        leaf(sb, "        ", "Sts", fileStatus);
        rules(sb, "        ", fileRules);
        if (totalRecords >= 0) {
            sb.append("        <Sttstcs>\n");
            leaf(sb, "          ", "TtlNbOfRcrds", Integer.toString(totalRecords));
            for (Map.Entry<RecordStatus, Integer> e : new EnumMap<>(recordsPerStatusOrEmpty()).entrySet()) {
                sb.append("          <NbOfRcrdsPerSts>\n");
                leaf(sb, "            ", "DtldNbOfRcrds", Integer.toString(e.getValue()));
                leaf(sb, "            ", "DtldSts", e.getKey().code());
                sb.append("          </NbOfRcrdsPerSts>\n");
            }
            sb.append("        </Sttstcs>\n");
        }
        sb.append("      </MsgSts>\n");
        for (Record r : records) {
            sb.append("      <RcrdSts>\n");
            leaf(sb, "        ", "OrgnlRcrdId", r.recordId());
            leaf(sb, "        ", "Sts", r.status().code());
            rules(sb, "        ", r.rules());
            sb.append("      </RcrdSts>\n");
        }
        sb.append("    </StsAdvc>\n");
        sb.append("  </FinInstrmRptgStsAdvc>\n");
        sb.append("</Document>\n");
        return sb.toString();
    }

    /**
     * The feedback file: head.003 {@code BizData} with a head.001 header and this document.
     * Its {@code MsgDefIdr} must be {@code auth.031.001.01}; per paragraph 101 its
     * {@code Rltd} should be the header of the submitted file.
     *
     * @param header the business application header
     * @return the XML file
     * @throws IllegalArgumentException when the header's message definition is not auth.031
     */
    public String toFeedbackXml(BusinessHeader header) {
        if (!MESSAGE_DEFINITION.equals(Objects.requireNonNull(header, "header").messageDefinitionId())) {
            throw new IllegalArgumentException("feedback header must have MsgDefIdr " + MESSAGE_DEFINITION);
        }
        return BusinessEnvelopeWriter.wrap(BusinessEnvelopeWriter.appHdr(header, "    "), toXml());
    }

    private Map<RecordStatus, Integer> recordsPerStatusOrEmpty() {
        return recordsPerStatus.isEmpty() ? new EnumMap<>(RecordStatus.class) : recordsPerStatus;
    }

    private static void rules(StringBuilder sb, String i, List<Rule> rules) {
        for (Rule r : rules) {
            sb.append(i).append("<VldtnRule>\n");
            leaf(sb, i + "  ", "Id", r.id());
            leaf(sb, i + "  ", "Desc", r.description());
            sb.append(i).append("</VldtnRule>\n");
        }
    }

    private static void leaf(StringBuilder sb, String i, String tag, String v) {
        sb.append(i).append('<').append(tag).append('>')
                .append(v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                .append("</").append(tag).append(">\n");
    }

    /**
     * A parsed feedback file: the advice and, when the file has a business application
     * header, that header (with its {@code Rltd}).
     *
     * @param advice the status advice
     * @param header the header, or null for a bare {@code Document}
     * @param adviceBlocks number of {@code StsAdvc} blocks in the file (only the first is read)
     */
    public record Parsed(StatusAdvice advice, BusinessHeader header, int adviceBlocks) {
        /** Validates. */
        public Parsed {
            Objects.requireNonNull(advice, "advice");
        }
    }

    /**
     * Parses a feedback file: a head.003 {@code BizData} envelope or a bare auth.031
     * {@code Document}. Elements are matched by local name, so the namespace prefix does not
     * matter; DTDs and external entities are refused.
     *
     * @param in the XML
     * @return the parsed file
     * @throws IOException on a read or parse error, or when no {@code StsAdvc} is present
     */
    public static Parsed parse(InputStream in) throws IOException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try {
            XMLStreamReader r = f.createXMLStreamReader(Objects.requireNonNull(in, "in"));
            try {
                return new Reader().read(r);
            } finally {
                r.close();
            }
        } catch (XMLStreamException | IllegalArgumentException | DateTimeParseException e) {
            throw new IOException("auth.031 parse error: " + e.getMessage(), e);
        }
    }

    /**
     * Parses a feedback file held in a string.
     *
     * @param xml the XML
     * @return the parsed file
     * @throws IOException on a parse error
     */
    public static Parsed parse(String xml) throws IOException {
        return parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** Streaming reader state. */
    private static final class Reader {
        private final Deque<String> path = new ArrayDeque<>();
        private final Map<String, String> hdr = new HashMap<>();
        private final Map<String, String> rltd = new HashMap<>();
        private int blocks;
        private String msgRptId = "";
        private String fileSts = "";
        private final List<Rule> fileRules = new ArrayList<>();
        private int total = -1;
        private final Map<RecordStatus, Integer> perSts = new EnumMap<>(RecordStatus.class);
        private final List<Record> records = new ArrayList<>();
        private String recId = "";
        private String recSts = "";
        private List<Rule> recRules = new ArrayList<>();
        private String ruleId = "";
        private String ruleDesc = "";
        private String cntNb = "";
        private String cntSts = "";

        Parsed read(XMLStreamReader r) throws XMLStreamException, IOException {
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String ln = r.getLocalName();
                    if (blocks > 1 && path.contains("StsAdvc")) {
                        path.push(ln);
                        continue;
                    }
                    if ("StsAdvc".equals(ln)) {
                        blocks++;
                    }
                    if (isLeaf(ln, path.isEmpty() ? "" : path.peek())) {
                        leaf(ln, r.getElementText().trim());
                        continue;
                    }
                    path.push(ln);
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    end(path.pop());
                }
            }
            if (blocks == 0) {
                throw new IOException("no StsAdvc element: not an auth.031 status advice");
            }
            StatusAdvice a = new StatusAdvice(msgRptId, fileSts, fileRules, total, perSts, records);
            return new Parsed(a, header(), blocks);
        }

        private static boolean isLeaf(String ln, String parent) {
            return switch (ln) {
                case "Id" -> "Othr".equals(parent) || "VldtnRule".equals(parent);
                case "MsgRptIdr", "Sts", "Desc", "TtlNbOfRcrds", "DtldNbOfRcrds", "DtldSts", "OrgnlRcrdId",
                        "BizMsgIdr", "MsgDefIdr", "CreDt" -> true;
                default -> false;
            };
        }

        private void leaf(String ln, String text) {
            String parent = path.isEmpty() ? "" : path.peek();
            boolean inRltd = path.contains("Rltd");
            boolean inHdr = path.contains("AppHdr");
            if (inHdr) {
                Map<String, String> m = inRltd ? rltd : hdr;
                if ("Id".equals(ln) && "Othr".equals(parent)) {
                    m.put(path.contains("Fr") ? "Fr" : "To", text);
                } else if (!"Id".equals(ln)) {
                    m.put(ln, text);
                }
                return;
            }
            switch (ln) {
                case "MsgRptIdr" -> msgRptId = text;
                case "Sts" -> {
                    if ("MsgSts".equals(parent)) {
                        fileSts = text;
                    } else if ("RcrdSts".equals(parent)) {
                        recSts = text;
                    }
                }
                case "Id" -> {
                    if ("VldtnRule".equals(parent)) {
                        ruleId = text;
                    }
                }
                case "Desc" -> ruleDesc = text;
                case "TtlNbOfRcrds" -> total = Integer.parseInt(text);
                case "DtldNbOfRcrds" -> cntNb = text;
                case "DtldSts" -> cntSts = text;
                case "OrgnlRcrdId" -> recId = text;
                default -> {
                    // BizMsgIdr, MsgDefIdr, CreDt outside a header are ignored.
                }
            }
        }

        private void end(String ln) {
            if (blocks > 1 && path.contains("StsAdvc")) {
                return;
            }
            switch (ln) {
                case "VldtnRule" -> {
                    Rule rule = new Rule(ruleId, ruleDesc.isEmpty() ? ruleId : ruleDesc);
                    if ("RcrdSts".equals(path.peek())) {
                        recRules.add(rule);
                    } else {
                        fileRules.add(rule);
                    }
                    ruleId = "";
                    ruleDesc = "";
                }
                case "NbOfRcrdsPerSts" -> {
                    perSts.merge(RecordStatus.ofCode(cntSts), Integer.parseInt(cntNb), Integer::sum);
                    cntNb = "";
                    cntSts = "";
                }
                case "RcrdSts" -> {
                    records.add(new Record(recId, RecordStatus.ofCode(recSts), recRules));
                    recId = "";
                    recSts = "";
                    recRules = new ArrayList<>();
                }
                default -> {
                    // structural element, nothing to collect
                }
            }
        }

        private BusinessHeader header() {
            if (hdr.isEmpty()) {
                return null;
            }
            BusinessHeader h = header(hdr);
            return rltd.isEmpty() ? h : h.relatedTo(header(rltd));
        }

        private static BusinessHeader header(Map<String, String> m) {
            return new BusinessHeader(m.getOrDefault("Fr", ""), m.getOrDefault("To", ""),
                    m.getOrDefault("BizMsgIdr", ""), m.getOrDefault("MsgDefIdr", ""),
                    instant(m.getOrDefault("CreDt", "")));
        }

        private static Instant instant(String s) {
            return OffsetDateTime.parse(s).toInstant();
        }
    }
}
