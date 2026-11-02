package com.quantfinlib.regreporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quantfinlib.regreporting.StatusAdvice.Record;
import com.quantfinlib.regreporting.StatusAdvice.Rule;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.junit.jupiter.api.Test;

/**
 * auth.031 status advice (ESMA65-8-2356 section 6.3, paragraphs 100 to 106) and the
 * business application header's {@code Rltd} element (paragraph 101; head.001 ESMA usage
 * guideline 1.0.0). Layout is published reference; values are invented (own test). XSD
 * validation needs QFL_ESMA_AUTH031_XSD, QFL_ESMA_HEAD001_XSD and QFL_ESMA_HEAD003_XSD.
 */
class StatusAdviceTest {
    private static final Instant SENT = Instant.parse("2026-11-03T08:00:00Z");
    private static final Instant BACK = Instant.parse("2026-11-04T06:30:00.9Z");
    private static final String K1 = Rts22Fixtures.FIRM + "TXN1";
    private static final String K2 = Rts22Fixtures.FIRM + "TXN2";
    private static final String K3 = Rts22Fixtures.FIRM + "TXN3";

    private static BusinessHeader feedbackHeader() {
        BusinessHeader submitted = new BusinessHeader(Rts22Fixtures.FIRM, "FR", "FILE0001", "auth.016.001.01", SENT);
        return new BusinessHeader("FR", Rts22Fixtures.FIRM, "FDBK0001", StatusAdvice.MESSAGE_DEFINITION, BACK)
                .relatedTo(submitted);
    }

    static StatusAdvice workedExample() {
        return StatusAdvice.of("FILE0001", List.of(
                new RecordOutcome(K1, false, RecordStatus.ACCEPTED, List.of()),
                new RecordOutcome(K2, false, RecordStatus.REJECTED,
                        List.of("CON-023: transaction reference already sent and not cancelled"))), false);
    }

    @Test
    void workedExamplePartialFile() {
        StatusAdvice a = workedExample();
        // Paragraph 105: some accepted, some not -> PART.
        assertEquals("PART", a.fileStatus());
        assertEquals(2, a.totalRecords());
        assertEquals(Map.of(RecordStatus.ACCEPTED, 1, RecordStatus.REJECTED, 1), a.recordsPerStatus());
        // Paragraph 106: on a first validation ACPT is only in the statistics.
        assertEquals(1, a.records().size());
        Record r = a.record(K2);
        assertEquals(RecordStatus.REJECTED, r.status());
        assertEquals(List.of(new Rule("CON-023", "transaction reference already sent and not cancelled")), r.rules());
        assertNull(a.record(K1));
    }

    @Test
    void fileStatusAllAcceptedAllRejectedAndListed() {
        RecordOutcome ok = new RecordOutcome(K1, false, RecordStatus.ACCEPTED, List.of());
        RecordOutcome bad = new RecordOutcome(K2, false, RecordStatus.REJECTED, List.of("CON-410: x"));
        RecordOutcome pend = new RecordOutcome(K3, false, RecordStatus.PENDING, List.of("CON-411: missing"));
        assertEquals("ACPT", StatusAdvice.of("", List.of(ok), false).fileStatus());
        assertEquals(List.of(), StatusAdvice.of("", List.of(ok), false).records());
        assertEquals(1, StatusAdvice.of("", List.of(ok), true).records().size());
        assertEquals("RJCT", StatusAdvice.of("", List.of(bad), false).fileStatus());
        assertEquals("PART", StatusAdvice.of("", List.of(ok, pend), false).fileStatus());
        assertEquals("PART", StatusAdvice.of("", List.of(pend), false).fileStatus());
        assertThrows(IllegalArgumentException.class, () -> StatusAdvice.of("", List.of(), false));
    }

    @Test
    void roundTripWithRelatedHeader() throws IOException {
        StatusAdvice a = workedExample();
        String xml = a.toFeedbackXml(feedbackHeader());
        assertTrue(xml.contains("<Rltd>"), xml);
        assertTrue(xml.contains("<MsgDefIdr>auth.031.001.01</MsgDefIdr>"));
        assertTrue(xml.contains("<CreDt>2026-11-04T06:30:00Z</CreDt>"), "created kept to the second");
        StatusAdvice.Parsed p = StatusAdvice.parse(xml);
        assertEquals(a, p.advice());
        assertEquals(1, p.adviceBlocks());
        BusinessHeader h = p.header();
        assertEquals("FR", h.from());
        assertEquals(Rts22Fixtures.FIRM, h.to());
        assertEquals("FDBK0001", h.businessMessageId());
        // Paragraph 101: Rltd names the submitted file.
        assertEquals("FILE0001", h.related().businessMessageId());
        assertEquals("auth.016.001.01", h.related().messageDefinitionId());
        assertEquals(Rts22Fixtures.FIRM, h.related().from());
        assertEquals("FR", h.related().to());
        assertEquals(SENT, h.related().created());
        assertEquals(feedbackHeader(), h);
    }

    @Test
    void bareDocumentAndFileRules() throws IOException {
        StatusAdvice a = new StatusAdvice("", "RJCT", List.of(new Rule("FIL-104", "file already received")), -1,
                Map.of(), List.of());
        String xml = a.toXml();
        assertTrue(!xml.contains("Sttstcs") && !xml.contains("MsgRptIdr"), xml);
        StatusAdvice.Parsed p = StatusAdvice.parse(xml);
        assertNull(p.header());
        assertEquals(a, p.advice());
    }

    @Test
    void parsesPrefixedNamespacesAndExtraBlocks() throws IOException {
        String xml = """
                <?xml version="1.0"?>
                <a:Document xmlns:a="urn:iso:std:iso:20022:tech:xsd:auth.031.001.01">
                 <a:FinInstrmRptgStsAdvc>
                  <a:StsAdvc><a:MsgSts><a:Sts>PART</a:Sts>
                   <a:Sttstcs><a:TtlNbOfRcrds>3</a:TtlNbOfRcrds>
                    <a:NbOfRcrdsPerSts><a:DtldNbOfRcrds>2</a:DtldNbOfRcrds><a:DtldSts>ACPT</a:DtldSts></a:NbOfRcrdsPerSts>
                    <a:NbOfRcrdsPerSts><a:DtldNbOfRcrds>1</a:DtldNbOfRcrds><a:DtldSts>PDNG</a:DtldSts></a:NbOfRcrdsPerSts>
                   </a:Sttstcs></a:MsgSts>
                   <a:RcrdSts><a:OrgnlRcrdId>K3</a:OrgnlRcrdId><a:Sts>PDNG</a:Sts>
                    <a:VldtnRule><a:Id>CON-411</a:Id><a:Desc>Pending instrument validation</a:Desc>
                     <a:SchmeNm><a:Prtry>ESMA</a:Prtry></a:SchmeNm><a:Issr>ESMA</a:Issr></a:VldtnRule>
                   </a:RcrdSts>
                  </a:StsAdvc>
                  <a:StsAdvc><a:MsgSts><a:Sts>RJCT</a:Sts></a:MsgSts>
                   <a:RcrdSts><a:OrgnlRcrdId>K9</a:OrgnlRcrdId><a:Sts>RJCT</a:Sts></a:RcrdSts></a:StsAdvc>
                 </a:FinInstrmRptgStsAdvc>
                </a:Document>
                """.strip();
        StatusAdvice.Parsed p = StatusAdvice.parse(xml);
        assertEquals(2, p.adviceBlocks());
        StatusAdvice a = p.advice();
        assertEquals("PART", a.fileStatus());
        assertEquals(3, a.totalRecords());
        assertEquals(Map.of(RecordStatus.ACCEPTED, 2, RecordStatus.PENDING, 1), a.recordsPerStatus());
        assertEquals(List.of(new Record("K3", RecordStatus.PENDING,
                List.of(new Rule("CON-411", "Pending instrument validation")))), a.records());
    }

    @Test
    void parseErrors() {
        assertThrows(IOException.class, () -> StatusAdvice.parse("<Document/>"));
        assertThrows(IOException.class, () -> StatusAdvice.parse("<Document><StsAdvc><MsgSts><Sts>NOPE</Sts>"
                + "</MsgSts></StsAdvc></Document>"));
        assertThrows(IOException.class, () -> StatusAdvice.parse("not xml"));
        assertThrows(IOException.class, () -> StatusAdvice.parse("<!DOCTYPE d [<!ENTITY x SYSTEM \"file:///c:/\">]>"
                + "<Document><StsAdvc><MsgSts><Sts>&x;</Sts></MsgSts></StsAdvc></Document>"));
    }

    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> new Rule("", "d"));
        assertThrows(IllegalArgumentException.class, () -> new Rule("x".repeat(36), "d"));
        assertThrows(IllegalArgumentException.class, () -> new Rule("CON-1", ""));
        assertThrows(IllegalArgumentException.class, () -> new Rule("CON-1", "d".repeat(351)));
        assertEquals(new Rule("CON-1", "CON-1"), Rule.ofError("CON-1:"));
        assertEquals(new Rule("plain", "plain"), Rule.ofError("plain"));
        assertEquals(350, Rule.ofError("C: " + "d".repeat(400)).description().length());
        assertEquals(35, Rule.ofError("y".repeat(40) + ": d").id().length());
        assertThrows(IllegalArgumentException.class, () -> new Record("", RecordStatus.ACCEPTED, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Record("k".repeat(141), RecordStatus.ACCEPTED,
                List.of()));
        assertThrows(IllegalArgumentException.class, () -> new StatusAdvice("", "ACPD", List.of(), -1, Map.of(),
                List.of()));
        assertThrows(IllegalArgumentException.class, () -> new StatusAdvice("m".repeat(36), "ACPT", List.of(), -1,
                Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> workedExample().toFeedbackXml(
                new BusinessHeader("FR", Rts22Fixtures.FIRM, "F", "auth.016.001.01", BACK)));
    }

    @Test
    void lengthLimitsAreInclusive() {
        assertEquals(35, new Rule("x".repeat(35), "d".repeat(350)).id().length());
        assertEquals(140, new Record("k".repeat(140), RecordStatus.ACCEPTED, List.of()).recordId().length());
        assertEquals(35, new StatusAdvice("m".repeat(35), "ACPT", List.of(), 0, Map.of(), List.of())
                .messageReportId().length());
        assertEquals(35, new BusinessHeader("a".repeat(35), "b".repeat(35), "c".repeat(35), "d".repeat(35), BACK)
                .businessMessageId().length());
        // Zero records still writes statistics; -1 means none.
        assertTrue(new StatusAdvice("", "RCVD", List.of(), 0, Map.of(), List.of()).toXml()
                .contains("<TtlNbOfRcrds>0</TtlNbOfRcrds>"));
        assertEquals(List.of("", "x"), new RecordOutcome("K", false, RecordStatus.REJECTED, List.of(":x", "x"))
                .codes());
    }

    @Test
    void headerValidation() {
        BusinessHeader h = feedbackHeader();
        assertThrows(IllegalArgumentException.class, () -> new BusinessHeader("A", "B", "C", "D", BACK, h));
        assertThrows(IllegalArgumentException.class, () -> new BusinessHeader("A", "B", "", "D", BACK));
        assertThrows(IllegalArgumentException.class, () -> new BusinessHeader("A", "B", "x".repeat(36), "D", BACK));
        assertThrows(IllegalArgumentException.class, () -> new BusinessHeader("A", "B", "C", "", BACK));
        assertThrows(IllegalArgumentException.class, () -> new BusinessHeader("A", "B", "C", "d".repeat(36), BACK));
        assertThrows(IllegalArgumentException.class, () -> new BusinessHeader("", "B", "C", "D", BACK));
        assertThrows(IllegalArgumentException.class, () -> new BusinessHeader("A", "b".repeat(36), "C", "D", BACK));
        // relatedTo flattens a header that has its own Rltd.
        BusinessHeader again = new BusinessHeader("A", "B", "C", "D", BACK).relatedTo(h);
        assertNull(again.related().related());
        assertEquals("FDBK0001", again.related().businessMessageId());
        assertThrows(NullPointerException.class, () -> h.relatedTo(null));
    }

    private static File env(String name) {
        String p = System.getenv(name);
        return p == null ? null : new File(p);
    }

    @Test
    void validatesAgainstEsmaXsds() throws Exception {
        File a31 = env("QFL_ESMA_AUTH031_XSD");
        File h1 = env("QFL_ESMA_HEAD001_XSD");
        File h3 = env("QFL_ESMA_HEAD003_XSD");
        ReferenceFiles.require(a31 != null && a31.isFile() && h1 != null && h1.isFile() && h3 != null && h3.isFile(),
                "QFL_ESMA_AUTH031_XSD, QFL_ESMA_HEAD001_XSD, QFL_ESMA_HEAD003_XSD not set: XSDs are not in the repo");
        SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        sf.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        sf.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
        Validator doc = sf.newSchema(a31).newValidator();
        doc.validate(new StreamSource(new StringReader(workedExample().toXml())));
        StatusAdvice listed = StatusAdvice.of("FILE0001", List.of(
                new RecordOutcome(K1, false, RecordStatus.ACCEPTED, List.of()),
                new RecordOutcome(K3, false, RecordStatus.PENDING, List.of("CON-411: pending"))), true);
        doc.validate(new StreamSource(new StringReader(listed.toXml())));
        Validator hdr = sf.newSchema(h1).newValidator();
        hdr.validate(new StreamSource(new StringReader(BusinessEnvelopeWriter.appHdr(feedbackHeader(), ""))));
        Validator all = sf.newSchema(new Source[] {new StreamSource(h3), new StreamSource(h1),
                new StreamSource(a31)}).newValidator();
        all.validate(new StreamSource(new StringReader(workedExample().toFeedbackXml(feedbackHeader()))));
    }
}
