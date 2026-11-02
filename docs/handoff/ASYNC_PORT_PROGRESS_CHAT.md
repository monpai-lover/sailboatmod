# Async port planning, progress, and chat flags

- PORT destination menus: offline dock metadata IO and independent terrain probes use four bounded IO workers. No synchronous loading of distant dock chunks to list them. Three candidate lanes share only immutable source results, not mutable terrain caches.
- Main-thread mailbox: global 2 ms cooperative budget and max 8 steps/tick. Live snapshots copy at most 32 columns/step. This is a cooperative target, not a preemptive hard deadline; live world reads and applying route results necessarily remain on the tick thread.
- Tellus / NBT route preparation and search: four bounded route workers, no CallerRuns fallback. UI starts NBT mode. Legacy internal non-Tellus NOISE/HYBRID code is not covered by the nonblocking guarantee and is no longer offered by the new dialog.
- Existing NBT is read asynchronously then copied in a budgeted main-thread operation; removed the per-read server-wide save request. Missing data stays uncertain in port validation.
- Destination chunk loading for explicit creation is future-based. Two temporary tickets last until success/failure/cancel/logout/timeout/shutdown; listing never generates those chunks.
- UI: request IDs on list/build/progress/cancel, live checked/total counts, elapsed time, retry/cancel controls. Closing cancels the list but leaves an explicit route running with its bossbar. Old progress cannot update a newer request. Route percentages are stage estimates, not elapsed-time estimates.
- Network protocol 18: update BOTH clients and server.

## Chat

Existing leader-only commands now allow all office display names, including leader/member, while IDs and permissions remain unchanged:

```text
/nation office rename leader 皇帝
/nation office rename officer 海军大臣
/nation office rename member 国民
```

Names persist in existing office NBT. The display-name refresh key includes the office title and flag appearance, so no relog is required (normal refresh runs once per second). Control/bidi/format-spoofing title text is rejected.

Server display-name metadata identifies the uploaded flag; a client chat event inserts a single 18-pixel spacer glyph before the national prefix. A client-only mixin paints the actual 16x16 memory-cached thumbnail at that wrapped line position. Chat line-height is at least 18 while flag-bearing messages are present, so icons do not overlap. Source aspect ratio is preserved inside the square. Unknown images show a colour fallback until flag bytes arrive. The text body is not rewritten server-side and signed chat is not cancelled/rebroadcast. Tab entries/overhead names retain textual prefixes without an extra image spacer.

## Validation

Added executor concurrency/rejection, stale request token, title persistence, metadata parsing, chat decoration and exact Minecraft bytecode injection-target tests. Existing berth/terrain and flag layout regressions remain in the CI set. Compilation/JUnit/packaging status is reported in the PR after the run. These are not Tellus multiplayer or macOS GPU gameplay tests.

Acceptance: open/close/reopen the port window during checks; create and cancel a long route; log out mid-request; verify TPS while cold terrain loads; rename leader/officer; upload/change/mirror the flag; test chat fade, scrolling, wrapping, GUI scale and resource reload on macOS.
