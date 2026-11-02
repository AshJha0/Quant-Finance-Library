# Moving to 2.3

## Removed: the initial margin package

The `com.quantfinlib.risk.simm` package, added in 2.2.0, is not in 2.3.0. It was
removed pending a licensing review: ISDA licenses its SIMM methodology for use, and
the library should not ship an implementation until that question is settled.

The package was a reference package under [STABILITY.md](STABILITY.md), outside the
API compatibility gate, so the policy allows removing it in a minor release. No other
package depends on it.

What to do:

- If you do not import `com.quantfinlib.risk.simm`, nothing changes.
- If you do, stay on 2.2.0 for that code, or move to an implementation licensed
  from ISDA. The 2.2.0 release jar remains available.
- If your build uses the module path, remove `com.quantfinlib.risk.simm` from any
  `requires`/`opens` handling; the module no longer exports it.

## Results that change

Each change below fixes a result that disagreed with the cited text or with what a
counterparty would compute. Where the old behaviour can still be had, the item says
how; where it was wrong, there is no switch back.

### `FixExecReportView.execType()` on FIX 4.2 reports

In 2.2 the view returned tag 150 as received. In 2.3 it returns the FIX 4.4 meaning
for a 4.2 report: ExecType 1 (partial fill) and 2 (fill) read as F (trade), and
ExecTransType(20) 1 (cancel) and 2 (correct) read as H and G. Reports in 4.4 and 5.0
SP2 are unchanged.

To keep the 2.2 value, call `rawExecType()`, which returns tag 150 exactly as sent.
Code that tested for `'1'` or `'2'` on 4.2 sessions should now test for `'F'` and
use `ordStatus()` or the quantities to tell partial from full fills.

### `FixMarketDataView` ends the entry group at a non-member field

In 2.2 every field after NoMDEntries(268) was read as part of the current entry. In
2.3 the first field that is not a member of the group (per `FixDictionary` for the
message's version) ends the group. A venue that puts custom tags inside entries now
sees them fall outside the group.

To keep them inside, pass a venue dictionary that declares them as group members:
`new FixMarketDataView(maxEntries, venueDictionary)`.

### `DropCopyReconciler.reconcile(PositionLedger, ...)` compares effective fills

The ledger side is now `ledger.effectiveFills()`: the fills that survive busts and
corrections. If nothing was busted or corrected the report is identical to 2.2.

To compare every logged fill as before, build the list from `loggedFill(i)` for
`i < loggedFills()` and call `reconcile(List, List, double)`. If the drop copy also
reports busts and corrections, use the new overload that takes its
`List<DropCopyReconciler.Adjustment>`.

### `SaCcr.subNettingSets` replacement cost

BCBS 279 para 185 applies the formula, replacement cost included, to each
sub-netting set. 2.2 took one RC for the whole netting set. The two agree when every
part has the same sign of `V - C`; they differ when they offset. Example (own hand
computation): a margined part with V = -30 and floor 5 and an unmargined part with
V = 50 give RC = 5 + 50 = 55 in 2.3 and 20 in 2.2. There is no switch back: the 2.2
number was not the para 185 number.

### FRTB sensitivities-based method

Two fixes change `FrtbSa` capital:

- Across buckets the first pass now uses `S_b = sum WS_k` as it stands, and only
  when the expression under the root is negative does it switch to the clamped
  alternative `max(min(S_b, K_b), -K_b)` (MAR21.4(5)). 2.2 clamped first, which
  understates books whose buckets offset.
- Curvature applies the low, medium and high scenario to the squared correlation
  (MAR21.101), and the up or down curvature is chosen per bucket (MAR21.5).

Books with one bucket and no curvature give the same numbers as 2.2. There is no
switch back. `FrtbNmrf` is unchanged (separate roots for credit and equity, verified
against the printed MAR33.17 formula).

### SA-CCR haircut table

Not a change: `SaCcrHaircuts` without a `Table` argument still uses the Basel II
table of BCBS 128 para 151. Pass `SaCcrHaircuts.Table.CRE22` for the CRE22.49 table.

### MiFIR RTS 22 validation

`Rts22TransactionReport.validate()` no longer runs the LEI check on CONCAT national
identifiers, no longer requires field 41 in every case, and checks code lists. A
report that 2.2 rejected for those reasons may now pass, and one with a bad code may
now fail. The new `validateEsmaRules()` is separate and opt-in.

## Additions that change nothing by default

Busts and corrections reach a `FillListener` only through the new `onBust` and
`onCorrect` methods, which default to doing nothing. The exposure simulator, the
haircut table and the order encoder keep their 2.2 behaviour unless you call the new
overloads. The new `reporting.registry`, `reporting.cat` and `reporting.emir`
packages are reference packages.

## Module path

The module now declares `requires transitive java.net.http` and `requires java.xml`.
A modular consumer reads `java.net.http` without declaring it.

[CHANGELOG.md](../CHANGELOG.md) has the full list for 2.3.0.
