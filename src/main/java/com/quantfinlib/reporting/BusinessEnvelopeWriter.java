package com.quantfinlib.reporting;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
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
        return appHdr(new BusinessHeader(submittingLei, ncaCountry, bizMsgId, MSG_DEF,
                Objects.requireNonNull(created, "created")), indent);
    }

    /**
     * Writes {@code AppHdr} from a header, with {@code Rltd} when the header has one. A party
     * id that is a valid LEI gets scheme {@code LEI}, any other id {@code Authority code}
     * (paragraph 132 example; using the same two schemes inside {@code Rltd} is the
     * library's reading).
     */
    static String appHdr(BusinessHeader h, String indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append("<AppHdr xmlns=\"").append(HEAD001).append("\">\n");
        headerBody(sb, indent + "  ", h);
        BusinessHeader rel = h.related();
        if (rel != null) {
            sb.append(indent).append("  <Rltd>\n");
            headerBody(sb, indent + "    ", rel);
            sb.append(indent).append("  </Rltd>\n");
        }
        sb.append(indent).append("</AppHdr>\n");
        return sb.toString();
    }

    private static void headerBody(StringBuilder sb, String i, BusinessHeader h) {
        party(sb, i, "Fr", h.from(), scheme(h.from()));
        party(sb, i, "To", h.to(), scheme(h.to()));
        sb.append(i).append("<BizMsgIdr>").append(esc(h.businessMessageId())).append("</BizMsgIdr>\n");
        sb.append(i).append("<MsgDefIdr>").append(esc(h.messageDefinitionId())).append("</MsgDefIdr>\n");
        sb.append(i).append("<CreDt>").append(DateTimeFormatter.ISO_INSTANT.format(h.created()))
                .append("</CreDt>\n");
    }

    private static String scheme(String id) {
        return Auth016Writer.isLei(id) ? "LEI" : "Authority code";
    }

    /** head.003 {@code BizData} around a header and a payload document (declaration stripped). */
    static String wrap(String appHdr, String document) {
        String doc = document.startsWith("<?xml") ? document.substring(document.indexOf('\n') + 1) : document;
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<BizData xmlns=\"").append(HEAD003).append("\">\n");
        sb.append("  <Hdr>\n").append(appHdr).append("  </Hdr>\n");
        sb.append("  <Pyld>\n");
        for (String line : doc.split("\n")) {
            sb.append("    ").append(line).append('\n');
        }
        sb.append("  </Pyld>\n");
        sb.append("</BizData>\n");
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
        return wrap(hdr, Auth016Writer.document(reports));
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
