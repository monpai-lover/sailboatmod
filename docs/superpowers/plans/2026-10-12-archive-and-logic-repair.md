# Archive integration and logic repair

Target: NeoForge 1.21.1, Java 21, existing `1.21.1-neoforge` default branch.

The user supplied Claude's archive and authorized internal bug fixes and push. The approved payment rule is escrow until successful delivery; refunds of legacy orders must recover the seller's gross payment first.

1. Import the archive's changed market frontend while retaining current migration/build fixes. Verify actual server startup and packaged HTTP resources. Record any unavailable visual verification.
2. Reproduce and fix refund idempotence, cancellation cargo loss, split-price overflow, payment/tax escrow persistence and proportional splitting. A failed physical delivery must never also recreate listing inventory.
3. Reproduce and fix treasury item insertion/partial withdrawal, cross-nation access and client-controlled allocation counts. Reject payments exceeding the supported integer bridge amount before changing balances or debt.
4. Preserve nation cleanup and authoritative leader permission fixes. Update stale web/map assertions to test the current documented rendering contract.
5. Run focused regressions, independent review, full tests, frontend checks and production packaging. Test final startup alongside Tellus/TellusMinimap in the isolated test world.
6. Document evidence and limitations, selectively commit source/tests/docs/tools (leave generated logs untouched), push and verify remote HEAD. Link production artifacts and test environment.

Escrow persists gross, seller net and tax recipients/amounts at purchase. Settlement consumes the escrow exactly once when the authoritative order reaches ARRIVED/CLAIMED. Partial shipments conserve the gross and every tax/net component. Legacy refunds fail without balance/stock changes when seller funds cannot be recovered; historic taxes cannot be identified and are included in gross seller recovery.
