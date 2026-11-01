# Security policy

## Supported versions

Only the latest release line gets fixes. Older tags stay available but are not patched.

## Reporting a problem

Please do not open a public issue for a security problem. Use GitHub's private
reporting instead: the "Report a vulnerability" button on the repository's
Security tab. Include the version, what you ran, what happened, and the smallest
input that shows it.

You should get a first reply within a week. If the report is confirmed, the fix
goes into the next release and the changelog credits you unless you ask
otherwise.

## Scope

The library has no runtime dependencies, so most reports will be about its own
code. The parts that touch the network are the most interesting ones: the FIX
session (`com.quantfinlib.fix`), the WebSocket feed (`com.quantfinlib.feed`),
the HTTP bar fetcher (`com.quantfinlib.data`) and the trading dashboard, which
starts an HTTP server. None of these are hardened for exposure to an untrusted
network; run them behind your own controls.

Wrong numbers from a pricing or risk model are bugs, not security issues; please
open a normal issue for those.
