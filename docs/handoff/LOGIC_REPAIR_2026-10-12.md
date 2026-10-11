# NeoForge 1.21.1 internal logic repair

The Claude frontend archive was integrated first; see `ARCHIVE_IMPORT_2026-10-12.md`. The user authorized the repair and push to the existing default branch, `1.21.1-neoforge`.

## Payment and cargo rules

- Every new market purchase funds its escrow from the buyer wallet, including creative players. The escrow stores gross, seller net, sales tax and import tariff recipients/amounts.
- Seller and treasury payments occur once on authoritative ARRIVED/CLAIMED delivery. Pending escrows persist through reloads; payment failures keep all money held and are retried through the server tick.
- If a snapshotted tax nation is disbanded before delivery, that tax returns to the buyer; the seller receives the original quoted net and surviving nations receive their quoted taxes. This follows the recommended option presented to the user; no reply was received before implementation.
- Legacy orders have already paid the seller. Refunds must recover the gross from the seller wallet first. Historical tax recipients were not stored, so gross recovery includes old taxes. Missing seller identity or insufficient funds rejects the refund before money/stock mutation.
- Refunds use the persisted current order, so stale snapshots cannot refund twice. Listing cancellation checks aggregate funding and recipient headroom before returning available plus cancellable reserved cargo.
- Physical cargo on failed delivery stays physical; it is not also recreated in listing stock. Refunded fallback cargo belongs to the seller. A refunded buyer cannot claim its old waybill. Failed refund recovery retains buyer entitlement.
- Shipment and pickup splitting conserve gross and all payment components using long intermediates. Pickup dispatch releases shipped reservations while preserving its pending remainder.

## Other repairs

Treasury inventory uses resizable storage, reuses empty slots and preserves loaded overflow slots. Partial inventory withdrawals restore only the actual uninserted remainder and save it even when the player inventory is full. Wallet treasury withdrawals require membership in the requested nation. Trade and road-edit collection counts are checked before allocations. Physical/Vault bridge payments above Integer.MAX_VALUE are rejected instead of silently truncated; treasury/debt records change only after successful payout.

Town/nation deletion removes town membership and invitations. Nation leader claim permissions use the authoritative nation leader UUID before stale office data.

## Evidence

Each changed financial, cargo, permission and allocation behavior has regression tests. Original failures were recorded under ignored `build/logic-audit-*-red.log`. Final `releaseMod -PfixIndex=1` completed successfully; all 1,333 tests in 311 suites passed, with no failures/errors/skips (`build/archive-logic-final-release.log`). The independent re-review confirmed the tax, refunded cargo ownership and pickup reservation findings were resolved. Node syntax and frontend checks also passed. Stale frontend/resource assertions were updated to native cached Leaflet tile URLs and progressive map tile merging, with preservation of untouched pixels checked.

Runtime startup checks use `run-neoforge/tellus-worldgen-smoke`, the isolated Tellus Earth world, Tellus 0.8.3 and the supplied TellusMinimap 0.0.1. This is development server startup plus actual HTTP asset/API verification, not a full multiplayer gameplay simulation. Browser visual verification was unavailable because localhost access was denied.

The production main JAR, separate server web addon and mandatory GeckoLib dependency are packaged together with installation notes in `artifacts/sailboatmod-1.21.1-neoforge-fix_1.zip` (30,343,558 bytes, SHA256 `2ab7a74b519aebb0a825b06a3c6293c5c64ae5f95592d958e31b895d9f5dec7c`). Package checks validate ZIP CRCs, parse production TOML mod IDs, inspect bundled dependency resources and compare exact frontend bytes. Generated runtime logs and local build files are excluded from the commit.

If an old refunded shipment no longer has a recoverable seller identity, its cargo remains private under a recovery marker; an administrator with permission level 2 can recover it. The original refunded buyer is denied even if they have that permission.
