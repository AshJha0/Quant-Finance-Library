package com.quantfinlib.regreporting;

import com.quantfinlib.orderbook.Side;
import java.io.File;
import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * head.003 envelope and head.001 header around auth.016 (ESMA65-8-2356 sections 6.4
 * and 6.5, paragraphs 124 to 132; published reference for the layout, own test for the
 * values). XSD validation needs the ESMA schemas, which are not redistributed: set
 * QFL_ESMA_XSD (auth.016), QFL_ESMA_HEAD001_XSD and QFL_ESMA_HEAD003_XSD to the files.
 */
class Auth016EnvelopeTest {
    static final Instant CREATED = Instant.parse("2026-11-02T10:15:30.500Z");

    private static List<Rts22TransactionReport> reports() {
        return List.of(Rts22Fixtures.build(Rts22Fixtures.equityDeal(), Side.BUY, 10, "B1"),
                Rts22Fixtures.build(Rts22Fixtures.equityDeal().cancellation(true), Side.SELL, 10, "S1"));
    }

    private static String file() {
        return Rts22TransactionReport.toSubmissionXml(reports(), Rts22Fixtures.FIRM, "FR", "FILE0001", CREATED);
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static String xp(Document d, String expr) throws Exception {
        return XPathFactory.newInstance().newXPath().evaluate(expr, d);
    }

    @Test
    void workedExampleLayout() throws Exception {
        Document d = parse(file());
        assertEquals(BusinessEnvelopeWriter.HEAD003, d.getDocumentElement().getNamespaceURI());
        assertEquals("BizData", d.getDocumentElement().getLocalName());
        String hdr = "/*/*[local-name()='Hdr']/*[local-name()='AppHdr']";
        assertEquals(BusinessEnvelopeWriter.HEAD001, xp(d, "namespace-uri(" + hdr + ")"));
        String fr = hdr + "/*[local-name()='Fr']/*/*/*/*[local-name()='Othr']";
        assertEquals(Rts22Fixtures.FIRM, xp(d, fr + "/*[local-name()='Id']"));
        assertEquals("LEI", xp(d, fr + "//*[local-name()='Prtry']"));
        String to = hdr + "/*[local-name()='To']/*/*/*/*[local-name()='Othr']";
        assertEquals("FR", xp(d, to + "/*[local-name()='Id']"));
        assertEquals("Authority code", xp(d, to + "//*[local-name()='Prtry']"));
        assertEquals("FILE0001", xp(d, hdr + "/*[local-name()='BizMsgIdr']"));
        assertEquals("auth.016.001.01", xp(d, hdr + "/*[local-name()='MsgDefIdr']"));
        assertEquals("2026-11-02T10:15:30Z", xp(d, hdr + "/*[local-name()='CreDt']"));
        String doc = "/*/*[local-name()='Pyld']/*[local-name()='Document']";
        assertEquals(Auth016Writer.NAMESPACE, xp(d, "namespace-uri(" + doc + ")"));
        assertEquals("2", xp(d, "count(" + doc + "/*/*[local-name()='Tx'])"));
        assertEquals(1, file().split("<\\?xml").length - 1, "one XML declaration only");
    }

    @Test
    void rejectsBadHeaderInputs() {
        List<Rts22TransactionReport> r = reports();
        assertThrows(IllegalArgumentException.class,
                () -> Rts22TransactionReport.toSubmissionXml(r, "NOTALEI", "FR", "F1", CREATED));
        assertThrows(IllegalArgumentException.class,
                () -> Rts22TransactionReport.toSubmissionXml(r, Rts22Fixtures.FIRM, "fra", "F1", CREATED));
        assertThrows(IllegalArgumentException.class,
                () -> Rts22TransactionReport.toSubmissionXml(r, Rts22Fixtures.FIRM, "FR", "", CREATED));
        assertThrows(IllegalArgumentException.class,
                () -> Rts22TransactionReport.toSubmissionXml(r, Rts22Fixtures.FIRM, "FR", "X".repeat(36), CREATED));
        assertThrows(IllegalArgumentException.class,
                () -> Rts22TransactionReport.toSubmissionXml(List.of(), Rts22Fixtures.FIRM, "FR", "F1", CREATED));
        // The header sender must be the submitting entity of every report (field 6).
        assertThrows(IllegalArgumentException.class,
                () -> Rts22TransactionReport.toSubmissionXml(r, Rts22Fixtures.CPTY, "FR", "F1", CREATED));
        assertThrows(NullPointerException.class,
                () -> Rts22TransactionReport.toSubmissionXml(r, Rts22Fixtures.FIRM, "FR", "F1", null));
    }

    @Test
    void escapesMessageId() throws Exception {
        String x = Rts22TransactionReport.toSubmissionXml(reports(), Rts22Fixtures.FIRM, "FR", "A&B<1>", CREATED);
        assertEquals("A&B<1>", xp(parse(x), "//*[local-name()='BizMsgIdr']"));
    }

    private static File env(String name) {
        String p = System.getenv(name);
        return p == null ? null : new File(p);
    }

    @Test
    void validatesAgainstEsmaXsds() throws Exception {
        File auth = env("QFL_ESMA_XSD");
        File h1 = env("QFL_ESMA_HEAD001_XSD");
        File h3 = env("QFL_ESMA_HEAD003_XSD");
        assumeTrue(auth != null && auth.isFile() && h1 != null && h1.isFile() && h3 != null && h3.isFile(),
                "QFL_ESMA_XSD, QFL_ESMA_HEAD001_XSD, QFL_ESMA_HEAD003_XSD not set: ESMA XSDs are not in the repo");
        SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        sf.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        sf.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
        // head.001 alone.
        Validator hv = sf.newSchema(h1).newValidator();
        hv.validate(new StreamSource(new StringReader(
                BusinessEnvelopeWriter.appHdr(Rts22Fixtures.FIRM, "FR", "FILE0001", CREATED, ""))));
        // The whole file: head.003 checks Hdr and Pyld strictly, so all three schemas are loaded.
        Validator all = sf.newSchema(new Source[] {new StreamSource(h3), new StreamSource(h1),
                new StreamSource(auth)}).newValidator();
        all.validate(new StreamSource(new StringReader(file())));
        // A header with a non-UTC creation time breaks ISONormalisedDateTime (pattern .*Z).
        String bad = file().replace("10:15:30Z", "10:15:30+01:00");
        assertTrue(bad.contains("+01:00"));
        assertThrows(SAXException.class, () -> all.validate(new StreamSource(new StringReader(bad))));
    }
}
