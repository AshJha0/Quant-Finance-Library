package com.quantfinlib.fix;

/**
 * The FIX protocol versions the library reads and writes.
 *
 * <p>Plain idea: FIX 4.2 and FIX 4.4 carry the version in BeginString(8)
 * alone. FIX 5.0 SP2 splits the session layer (FIXT.1.1, which is what
 * BeginString says) from the application layer, whose version is named by
 * ApplVerID: the initiator announces it once on Logon in
 * DefaultApplVerID(1137) and individual messages may override it with
 * ApplVerID(1128) in the header.
 *
 * <p>Formal statement: BeginString values and ApplVerID enumeration (1128)
 * as published in the FIX Trading Community specifications
 * (FIX 4.2 Vol 1; FIX 4.4 Vol 2; FIXT 1.1 and FIX 5.0 SP2 Vol 1, ApplVerID
 * value 9 = FIX50SP2). Evidence class: published reference.
 *
 * <p>Example: {@code FIX_5_0_SP2.beginString()} is {@code "FIXT.1.1"} and
 * {@code FIX_5_0_SP2.applVerId()} is {@code "9"}; a Logon from such a
 * session carries {@code 8=FIXT.1.1|...|35=A|...|1137=9|}.
 */
public enum FixVersion {

    /** FIX 4.2: no Parties block, ExecTransType(20) still required on ExecutionReport. */
    FIX_4_2("FIX.4.2", null),
    /** FIX 4.4: the library default. */
    FIX_4_4("FIX.4.4", null),
    /** FIX 5.0 SP2 application messages over the FIXT.1.1 session layer. */
    FIX_5_0_SP2("FIXT.1.1", "9");

    /** ApplVerID(1128): per-message application version (FIXT header). */
    public static final int APPL_VER_ID = 1128;
    /** DefaultApplVerID(1137): application version announced on a FIXT Logon. */
    public static final int DEFAULT_APPL_VER_ID = 1137;
    /** ExecTransType(20): FIX 4.2 only (removed in 4.3). */
    public static final int EXEC_TRANS_TYPE = 20;

    private final String beginString;
    private final String applVerId;

    FixVersion(String beginString, String applVerId) {
        this.beginString = beginString;
        this.applVerId = applVerId;
    }

    /** BeginString(8) on the wire. */
    public String beginString() {
        return beginString;
    }

    /** ApplVerID enumeration value (1128/1137), or null for a pre-FIXT version. */
    public String applVerId() {
        return applVerId;
    }

    /** True when the session layer is FIXT.1.1 and Logon must carry DefaultApplVerID(1137). */
    public boolean isFixt() {
        return applVerId != null;
    }

    /** True when the version has the Parties component (NoPartyIDs(453)); added in 4.3. */
    public boolean hasParties() {
        return this != FIX_4_2;
    }

    /** True when ExecutionReport must carry ExecTransType(20) (FIX 4.2). */
    public boolean requiresExecTransType() {
        return this == FIX_4_2;
    }

    /**
     * Maps an ExecType(150) value to its 4.4/5.0 meaning. FIX 4.2 reported
     * fills as ExecType 1 (partial fill) and 2 (fill); 4.3 onwards uses F
     * (trade) and leaves the distinction to OrdStatus(39). Other values are
     * unchanged.
     */
    public char normalizeExecType(char execType) {
        if (this == FIX_4_2 && (execType == '1' || execType == '2')) {
            return 'F';
        }
        return execType;
    }

    /** The version for a BeginString value, or null when the library does not speak it. */
    public static FixVersion fromBeginString(String beginString) {
        for (FixVersion v : values()) {
            if (v.beginString.equals(beginString)) {
                return v;
            }
        }
        return null;
    }
}
