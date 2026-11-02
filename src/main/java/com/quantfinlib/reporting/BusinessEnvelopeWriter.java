package com.quantfinlib.reporting;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * Writes the ISO 20022 Business Application Header (head.001.001.01, ESMA usage
 * guideline head.001.001.01_ESMAUG_1.0.0) and the business file header envelope
 * (head.003.001.01) around an auth.016 document, as set out in sections 6.4 and 6.5
 * of the ESMA technical reporting instructions (ESMA65-8-2356, paragraphs 124 to 132).
 *
 * <p>Paragraph 128 fixes the content: {@code Fr} carries the LEI of the submitting
 * entity, {@code To} the country code of the competent authority, both under
 * {@code OrgId/Id/OrgId/Othr/Id}; {@code MsgDefIdr} is {@code auth.016.001.01};
 * {@code CreDt} is a UTC time. The scheme names {@code LEI} and {@code Authority code}
 * follow the example XML of paragraph 132. The rules for {@code BizMsgIdr} are set
 * nationally, so the caller supplies it.
 */
final class BusinessEnvelopeWriter {
    static final String HEAD001 = "urn:iso:std:iso:20022:tech:xsd:head.001.001.01";
    static final String HEAD003 = "urn:iso:std:iso:20022:tech:xsd:head.003.001.01";
    static final String MSG_DEF = "auth.016.001.01";

    private BusinessEnvelopeWriter() {
    }

    static String appHdr(String submittingLei, String ncaCountry, String bizMsgId, Instant created, String indent) {
        if (!Auth016Writer.isLei(Objects.requireNonNull(submittingLei, "submittingLei"))) {
            throw new IllegalArgumentException("submitting entity '" + submittingLei + "' is not a valid LEI");
        }
        if (!Objects.requireNonNull(ncaCountry, "ncaCountry").matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("competent authority country '" + ncaCountry
                    + "' must be two capital letters");
        }
        Objects.requireNonNull(bizMsgId, "bizMsgId");
        if (bizMsgId.isBlank() || bizMsgId.length() > 35) {
            throw new IllegalArgumentException("business message id must be 1 to 35 characters");
        }
        String creDt = DateTimeFormatter.ISO_INSTANT.format(
                Objects.requireNonNull(created, "created").truncatedTo(ChronoUnit.SECONDS));
        String i = indent;
        StringBuilder sb = new StringBuilder();
        sb.append(i).append("<AppHdr xmlns=\"").append(HEAD001).append("\">\n");
        party(sb, i + "  ", "Fr", submittingLei, "LEI");
        party(sb, i + "  ", "To", ncaCountry, "Authority code");
        sb.append(i).append("  <BizMsgIdr>").append(esc(bizMsgId)).append("</BizMsgIdr>\n");
        sb.append(i).append("  <MsgDefIdr>").append(MSG_DEF).append("</MsgDefIdr>\n");
        sb.append(i).append("  <CreDt>").append(creDt).append("</CreDt>\n");
        sb.append(i).append("</AppHdr>\n");
        return sb.toString();
    }

    private static void party(StringBuilder sb, String i, String tag, String id, String scheme) {
        sb.append(i).append('<').append(tag).append(">\n")
                .append(i).append("  <OrgId>\n")
                .append(i).append("    <Id>\n")
                .append(i).append("      <OrgId>\n")
                .append(i).append("        <Othr>\n")
                .append(i).append("          <Id>").append(esc(id)).append("</Id>\n")
                .append(i).append("          <SchmeNm>\n")
                .append(i).append("            <Prtry>").append(scheme).append("</Prtry>\n")
                .append(i).append("          </SchmeNm>\n")
                .append(i).append("        </Othr>\n")
                .append(i).append("      </OrgId>\n")
                .append(i).append("    </Id>\n")
                .append(i).append("  </OrgId>\n")
                .append(i).append("</").append(tag).append(">\n");
    }

    static String envelope(List<Rts22TransactionReport> reports, String submittingLei, String ncaCountry,
            String bizMsgId, Instant created) {
        for (Rts22TransactionReport r : reports) {
            if (!submittingLei.equals(r.get(Rts22Field.SUBMITTING_ENTITY_ID))) {
                throw new IllegalArgumentException("report " + r.reference() + " has submitting entity '"
                        + r.get(Rts22Field.SUBMITTING_ENTITY_ID) + "', not the header's " + submittingLei);
            }
        }
        String hdr = appHdr(submittingLei, ncaCountry, bizMsgId, created, "    ");
        String doc = Auth016Writer.document(reports);
        doc = doc.substring(doc.indexOf('\n') + 1); // drop the XML declaration
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<BizData xmlns=\"").append(HEAD003).append("\">\n");
        sb.append("  <Hdr>\n").append(hdr).append("  </Hdr>\n");
        sb.append("  <Pyld>\n");
        for (String line : doc.split("\n")) {
            sb.append("    ").append(line).append('\n');
        }
        sb.append("  </Pyld>\n");
        sb.append("</BizData>\n");
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
