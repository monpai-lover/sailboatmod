# Bosphorus and remote route debug implementation plan

**Goal:** Restore the navigable Constantinople–Sevastopol route and allow explicit, authenticated debug access through a server panel mapping, without changing protocol 22 or requiring client updates.

**Evidence:** Official Tellus Land Polygons v1.0.0 (SHA-256 `ab142dc692ee9607eeb23802219c25f7dff5340cfe3cc8d7ee2083acfac3afa2`) reproduces the 16-block failure with 683092 expansions. The same grid implementation at 8 blocks finds a path in 2317 expansions. A nine-block Bosphorus channel misses the 16-block centre lattice. Debug HTTP currently binds loopback unconditionally; the supplied external mapping returned no HTTP response.

**Implementation:** Preserve the 16–128 initial grid. After a completed no-path result, allow 8/4/2/1-block attempts capped at 65536 expansions each, combined ceiling 262144. Official Bosphorus at scale300 independently requires the 2-block grid, so an 8-block-only patch is insufficient. Unknown source data returns TERRAIN_NOT_READY rather than cached land. Keep detailed boat-width verification. Expose `start <port> public` with a fresh per-start token and authenticated browser cookie; keep existing local command behavior. Make debug NBT preview call the production solver without saving routes or forcing map chunks. No network payload, dependency, or metadata version changes.

- [x] Add synthetic narrow-strait and official-mask bidirectional regressions; observe failure before changing production.
- [x] Add public HTTP authentication and local compatibility regressions; observe failure before changing production.
- [x] Implement bounded native-resolution fallback, exact direct-leg certification, and public debug startup.
- [x] Run route, HTTP, and full project tests; package server main JAR and inspect protocol/class compatibility. 2612 tests, no failures or skips; 17 production-server checks; 889 unchanged client/network/asset entries.
- [x] Document tested boundaries and verify the server-only ZIP, CRC, safe paths, internal hashes, and Downloads copy. Reviewed release targets the existing `1.21.1-neoforge` branch.
