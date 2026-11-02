package com.quantfinlib.reporting;

/**
 * The 65 fields of a MiFIR transaction report, numbered and named as in Annex I,
 * Table 2 of Commission Delegated Regulation (EU) 2017/590 (RTS 22). The names
 * are paraphrased short labels, not the full legal text; always read the
 * regulation and the ESMA reporting guidelines (ESMA/2016/1452) for the content
 * rules of each field.
 *
 * <p>{@link #source()} says who fills the field in this library:
 * <ul>
 *   <li>{@link Source#LIBRARY}: derived by {@link Rts22ReportBuilder} from the
 *       execution report and the firm's reference data passed to the builder.</li>
 *   <li>{@link Source#FIRM}: left blank unless the caller sets it with
 *       {@link Rts22ReportBuilder#set}. These need client data, decision-maker
 *       identities, waivers, instrument reference data and so on that the
 *       library does not hold.</li>
 * </ul>
 */
public enum Rts22Field {
    REPORT_STATUS(1, "Report status", Source.LIBRARY),
    TRANSACTION_REFERENCE_NUMBER(2, "Transaction reference number", Source.LIBRARY),
    TRADING_VENUE_TRANSACTION_ID(3, "Trading venue transaction identification code", Source.LIBRARY),
    EXECUTING_ENTITY_ID(4, "Executing entity identification code", Source.LIBRARY),
    INVESTMENT_FIRM_INDICATOR(5, "Investment firm covered by Directive 2014/65/EU", Source.LIBRARY),
    SUBMITTING_ENTITY_ID(6, "Submitting entity identification code", Source.LIBRARY),
    BUYER_ID(7, "Buyer identification code", Source.LIBRARY),
    BUYER_BRANCH_COUNTRY(8, "Country of the branch for the buyer", Source.FIRM),
    BUYER_FIRST_NAMES(9, "Buyer first name(s)", Source.FIRM),
    BUYER_SURNAMES(10, "Buyer surname(s)", Source.FIRM),
    BUYER_DATE_OF_BIRTH(11, "Buyer date of birth", Source.FIRM),
    BUYER_DECISION_MAKER_ID(12, "Buyer decision maker code", Source.FIRM),
    BUYER_DECISION_MAKER_FIRST_NAMES(13, "Buy decision maker first name(s)", Source.FIRM),
    BUYER_DECISION_MAKER_SURNAMES(14, "Buy decision maker surname(s)", Source.FIRM),
    BUYER_DECISION_MAKER_DATE_OF_BIRTH(15, "Buy decision maker date of birth", Source.FIRM),
    SELLER_ID(16, "Seller identification code", Source.LIBRARY),
    SELLER_BRANCH_COUNTRY(17, "Country of the branch for the seller", Source.FIRM),
    SELLER_FIRST_NAMES(18, "Seller first name(s)", Source.FIRM),
    SELLER_SURNAMES(19, "Seller surname(s)", Source.FIRM),
    SELLER_DATE_OF_BIRTH(20, "Seller date of birth", Source.FIRM),
    SELLER_DECISION_MAKER_ID(21, "Seller decision maker code", Source.FIRM),
    SELLER_DECISION_MAKER_FIRST_NAMES(22, "Sell decision maker first name(s)", Source.FIRM),
    SELLER_DECISION_MAKER_SURNAMES(23, "Sell decision maker surname(s)", Source.FIRM),
    SELLER_DECISION_MAKER_DATE_OF_BIRTH(24, "Sell decision maker date of birth", Source.FIRM),
    TRANSMISSION_OF_ORDER_INDICATOR(25, "Transmission of order indicator", Source.LIBRARY),
    TRANSMITTING_FIRM_ID_BUYER(26, "Transmitting firm identification code for the buyer", Source.FIRM),
    TRANSMITTING_FIRM_ID_SELLER(27, "Transmitting firm identification code for the seller", Source.FIRM),
    TRADING_DATE_TIME(28, "Trading date time", Source.LIBRARY),
    TRADING_CAPACITY(29, "Trading capacity", Source.LIBRARY),
    QUANTITY(30, "Quantity", Source.LIBRARY),
    QUANTITY_CURRENCY(31, "Quantity currency", Source.FIRM),
    DERIVATIVE_NOTIONAL_CHANGE(32, "Derivative notional increase/decrease", Source.FIRM),
    PRICE(33, "Price", Source.LIBRARY),
    PRICE_CURRENCY(34, "Price currency", Source.LIBRARY),
    NET_AMOUNT(35, "Net amount", Source.FIRM),
    VENUE(36, "Venue", Source.LIBRARY),
    COUNTRY_OF_BRANCH_MEMBERSHIP(37, "Country of the branch membership", Source.FIRM),
    UP_FRONT_PAYMENT(38, "Up-front payment", Source.FIRM),
    UP_FRONT_PAYMENT_CURRENCY(39, "Up-front payment currency", Source.FIRM),
    COMPLEX_TRADE_COMPONENT_ID(40, "Complex trade component id", Source.FIRM),
    INSTRUMENT_ID(41, "Instrument identification code", Source.LIBRARY),
    INSTRUMENT_FULL_NAME(42, "Instrument full name", Source.FIRM),
    INSTRUMENT_CLASSIFICATION(43, "Instrument classification", Source.FIRM),
    NOTIONAL_CURRENCY_1(44, "Notional currency 1", Source.FIRM),
    NOTIONAL_CURRENCY_2(45, "Notional currency 2", Source.FIRM),
    PRICE_MULTIPLIER(46, "Price multiplier", Source.FIRM),
    UNDERLYING_INSTRUMENT_ID(47, "Underlying instrument code", Source.FIRM),
    UNDERLYING_INDEX_NAME(48, "Underlying index name", Source.FIRM),
    UNDERLYING_INDEX_TERM(49, "Term of the underlying index", Source.FIRM),
    OPTION_TYPE(50, "Option type", Source.FIRM),
    STRIKE_PRICE(51, "Strike price", Source.FIRM),
    STRIKE_PRICE_CURRENCY(52, "Strike price currency", Source.FIRM),
    OPTION_EXERCISE_STYLE(53, "Option exercise style", Source.FIRM),
    MATURITY_DATE(54, "Maturity date", Source.FIRM),
    EXPIRY_DATE(55, "Expiry date", Source.FIRM),
    DELIVERY_TYPE(56, "Delivery type", Source.FIRM),
    INVESTMENT_DECISION_WITHIN_FIRM(57, "Investment decision within firm", Source.FIRM),
    INVESTMENT_DECISION_BRANCH_COUNTRY(58, "Country of the branch responsible for the person making the investment decision", Source.FIRM),
    EXECUTION_WITHIN_FIRM(59, "Execution within firm", Source.FIRM),
    EXECUTION_BRANCH_COUNTRY(60, "Country of the branch supervising the person responsible for the execution", Source.FIRM),
    WAIVER_INDICATOR(61, "Waiver indicator", Source.FIRM),
    SHORT_SELLING_INDICATOR(62, "Short selling indicator", Source.FIRM),
    OTC_POST_TRADE_INDICATOR(63, "OTC post-trade indicator", Source.FIRM),
    COMMODITY_DERIVATIVE_INDICATOR(64, "Commodity derivative indicator", Source.FIRM),
    SECURITIES_FINANCING_TRANSACTION_INDICATOR(65, "Securities financing transaction indicator", Source.LIBRARY);

    /** Who populates a field. */
    public enum Source { LIBRARY, FIRM }

    private final int number;
    private final String label;
    private final Source source;

    Rts22Field(int number, String label, Source source) {
        this.number = number;
        this.label = label;
        this.source = source;
    }

    /** Field number in RTS 22 Annex I Table 2 (1..65). */
    public int number() {
        return number;
    }

    /** Short label. */
    public String label() {
        return label;
    }

    /** Who populates it. */
    public Source source() {
        return source;
    }
}
