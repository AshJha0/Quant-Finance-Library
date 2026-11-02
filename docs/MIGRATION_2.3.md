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

[CHANGELOG.md](../CHANGELOG.md) has the full list for 2.3.0.
