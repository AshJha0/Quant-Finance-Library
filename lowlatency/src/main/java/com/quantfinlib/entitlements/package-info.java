/**
 * Who may do what, where, and when. An {@link com.quantfinlib.entitlements.EntitlementService}
 * holds users, the desk each belongs to and the roles they hold, plus a list of
 * {@link com.quantfinlib.entitlements.Grant}s. A grant allows or denies one
 * {@link com.quantfinlib.entitlements.Permission} (an action on an instrument,
 * venue and account, each of which may be a wildcard) to a user, a desk or a role,
 * optionally only inside a time window.
 *
 * <p>Decisions use deny-overrides: any matching, in-force DENY wins over every
 * ALLOW; with no matching ALLOW the answer is the default, which is deny. Every
 * decision is appended to an audit log together with the grant that settled it.
 * {@link com.quantfinlib.entitlements.EntitlementPreTradeCheck} adapts this to
 * the order path so a pre-trade gate can call it before its own limit checks.
 *
 * <p>Since 3.0 this package is in the artifact
 * {@code quant-finance-library-lowlatency} (module {@code com.quantfinlib.lowlatency});
 * the package name did not change, so a 2.x user changes the dependency, not the
 * imports. See docs/MIGRATION_3.0.md.</p>
 */
@NullMarked
package com.quantfinlib.entitlements;

import org.jspecify.annotations.NullMarked;
