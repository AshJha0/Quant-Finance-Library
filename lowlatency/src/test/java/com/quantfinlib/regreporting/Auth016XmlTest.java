package com.quantfinlib.regreporting;

import com.quantfinlib.core.Side;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * auth.016.001.01 output. The structural checks always run. The XSD check needs
 * {@code QFL_ESMA_XSD} set to the path of auth.016.001.01_ESMAUG_Reporting_1.1.0.xsd
 * from ESMA65-8-2598 Annex 2 (esma.europa.eu); the schema is SWIFT MyStandards
 * licensed with redistribution restricted, so it is not in the repository.
 */
class Auth016XmlTest {

    private static List<Rts22TransactionReport> samples() {
        Rts22TransactionReport neg = Rts22Fixtures.build(Rts22Fixtures.equityDeal(), Side.BUY, 1, "N1");
        java.util.Map<Rts22Field, String> m = new java.util.EnumMap<>(Rts22Field.class);
        for (Rts22Field f : Rts22Field.values()) {
            m.put(f, neg.get(f));
        }
        m.put(Rts22Field.PRICE, "-0.25");
        m.put(Rts22Field.WAIVER_INDICATOR, "");
        return List.of(
                Rts22Fixtures.build(Rts22Fixtures.equityDeal(), Side.BUY, 101.25, "T1"),
                Rts22Fixtures.build(Rts22Fixtures.agencyForPerson(), Side.BUY, 55.5, "T2"),
                Rts22Fixtures.build(Rts22Fixtures.otcOption(), Side.SELL, 12.4, "T3"),
                Rts22Fixtures.build(Rts22Fixtures.equityDeal().cancellation(true), Side.BUY, 101.25, "T1"),
                new Rts22TransactionReport(m));
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String xp(Document d, String path) throws Exception {
        return (String) XPathFactory.newInstance().newXPath().evaluate(path, d, XPathConstants.STRING);
    }

    @Test
    void samplesPassEsmaRulesSoTheXsdCheckIsMeaningful() {
        for (Rts22TransactionReport r : samples()) {
            assertEquals(List.of(), r.validate(), r.reference());
            assertEquals(List.of(), r.validateEsmaRules(), r.reference());
        }
    }

    @Test
    void structureFollowsTheUsageGuideline() throws Exception {
        Document d = parse(Rts22TransactionReport.toAuth016Xml(samples()));
        assertEquals(Auth016Writer.NAMESPACE, d.getDocumentElement().getNamespaceURI());
        assertEquals("Document", d.getDocumentElement().getLocalName());
        assertEquals("5", xp(d, "count(/*/*[local-name()='FinInstrmRptgTxRpt']/*[local-name()='Tx'])"));
        // Field 2 to TxId, 7 to Buyr/AcctOwnr/Id/LEI, 33/34 to Pric/Pric/MntryVal/Amt[@Ccy] (ESMA65-8-2356 s6).
        String t1 = "/*/*/*[local-name()='Tx'][1]/*[local-name()='New']";
        assertEquals("QFLT1", xp(d, t1 + "/*[local-name()='TxId']"));
        assertEquals(Rts22Fixtures.FIRM, xp(d, t1 + "/*[local-name()='Buyr']//*[local-name()='LEI']"));
        assertEquals("GBP", xp(d, t1 + "//*[local-name()='Amt']/@Ccy"));
        assertEquals("101.25", xp(d, t1 + "//*[local-name()='Amt']"));
        // Natural-person buyer with CONCAT scheme.
        String t2 = "/*/*/*[local-name()='Tx'][2]/*[local-name()='New']";
        assertEquals("CONCAT", xp(d, t2 + "/*[local-name()='Buyr']//*[local-name()='Prtry']"));
        assertEquals("1980-04-13", xp(d, t2 + "//*[local-name()='BirthDt']"));
        // Cancellation carries only fields 2, 4, 6.
        assertEquals("3", xp(d, "count(/*/*/*[local-name()='Tx'][4]/*[local-name()='Cxl']/*)"));
        // Negative price: absolute amount plus Sgn false.
        String t5 = "/*/*/*[local-name()='Tx'][5]/*[local-name()='New']";
        assertEquals("0.25", xp(d, t5 + "//*[local-name()='Amt']"));
        assertEquals("false", xp(d, t5 + "//*[local-name()='Sgn']"));
    }

    @Test
    void emptyListIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Rts22TransactionReport.toAuth016Xml(List.of()));
    }

    @Test
    void validatesAgainstEsmaXsd() throws Exception {
        String path = System.getenv("QFL_ESMA_XSD");
        assumeTrue(path != null && new File(path).isFile(),
                "QFL_ESMA_XSD not set: the ESMA auth.016 XSD is SWIFT-licensed and not in the repo");
        SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        sf.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        sf.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
        Validator v = sf.newSchema(new File(path)).newValidator();
        v.validate(new StreamSource(new StringReader(Rts22TransactionReport.toAuth016Xml(samples()))));
        for (Rts22TransactionReport r : samples()) {
            v.validate(new StreamSource(new StringReader(r.toAuth016Xml())));
        }
        // A report missing field 59 breaks the XSD, and validateEsmaRules says so first.
        Rts22TransactionReport bad = Rts22Fixtures.build(
                Rts22Fixtures.equityDeal().set(Rts22Field.EXECUTION_WITHIN_FIRM, ""), Side.BUY, 1, "B1");
        assertTrue(bad.validateEsmaRules().stream().anyMatch(s -> s.contains("field 59")));
        assertThrows(SAXException.class,
                () -> v.validate(new StreamSource(new StringReader(bad.toAuth016Xml()))));
    }
}
