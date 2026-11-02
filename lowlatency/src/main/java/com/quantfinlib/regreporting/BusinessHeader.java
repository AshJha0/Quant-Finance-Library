package com.quantfinlib.regreporting;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * The content of an ISO 20022 Business Application Header (head.001.001.01, ESMA usage
 * guideline 1.0.0): sender, receiver, business message id, message definition and
 * creation time, and optionally the header of the message this one relates to
 * ({@code Rltd}, type {@code BusinessApplicationHeader1__1}, which has the same five
 * elements and no {@code Rltd} of its own; published reference, XSD on disk).
 *
 * <p>ESMA65-8-2356 paragraph 101: the status advice (auth.031) is sent with a header whose
 * {@code Rltd} element refers to the original message the feedback is about. In practice
 * {@code related} is the header of the submitted auth.016 file.
 *
 * @param from sender id: an LEI, or a two-letter competent authority country code
 * @param to receiver id, same forms
 * @param businessMessageId {@code BizMsgIdr}, 1 to 35 characters
 * @param messageDefinitionId {@code MsgDefIdr}, for example {@code auth.016.001.01}
 * @param created {@code CreDt}, kept to the second in UTC
 * @param related the related header, or null; it must not have a related header itself
 * @since 3.0.0
 */
public record BusinessHeader(String from, String to, String businessMessageId, String messageDefinitionId,
        Instant created, BusinessHeader related) {
    /** Validates, and truncates {@code created} to the second. */
    public BusinessHeader {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(businessMessageId, "businessMessageId");
        Objects.requireNonNull(messageDefinitionId, "messageDefinitionId");
        created = Objects.requireNonNull(created, "created").truncatedTo(ChronoUnit.SECONDS);
        if (businessMessageId.isBlank() || businessMessageId.length() > 35) {
            throw new IllegalArgumentException("business message id must be 1 to 35 characters");
        }
        if (messageDefinitionId.isBlank() || messageDefinitionId.length() > 35) {
            throw new IllegalArgumentException("message definition id must be 1 to 35 characters");
        }
        for (String id : new String[] {from, to}) {
            if (id.isBlank() || id.length() > 35) {
                throw new IllegalArgumentException("party id must be 1 to 35 characters: '" + id + "'");
            }
        }
        if (related != null && related.related() != null) {
            throw new IllegalArgumentException("a related header cannot carry its own related header (Rltd)");
        }
    }

    /**
     * A header with no related header.
     *
     * @param from sender id
     * @param to receiver id
     * @param businessMessageId {@code BizMsgIdr}
     * @param messageDefinitionId {@code MsgDefIdr}
     * @param created {@code CreDt}
     */
    public BusinessHeader(String from, String to, String businessMessageId, String messageDefinitionId,
            Instant created) {
        this(from, to, businessMessageId, messageDefinitionId, created, null);
    }

    /**
     * This header with {@code Rltd} set.
     *
     * @param original the header of the message this one relates to
     * @return the new header
     */
    public BusinessHeader relatedTo(BusinessHeader original) {
        Objects.requireNonNull(original, "original");
        BusinessHeader flat = original.related() == null ? original
                : new BusinessHeader(original.from(), original.to(), original.businessMessageId(),
                        original.messageDefinitionId(), original.created());
        return new BusinessHeader(from, to, businessMessageId, messageDefinitionId, created, flat);
    }
}
