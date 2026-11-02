# Auto-route candidate list and flag preview fix

## Scope

Based on `1.21.1-neoforge` at `c3e6109f3fe60c9eac06e71642adcdc66e92b439` and the uploaded
`sailboatmod-1.3.13-fix_12-neoforge-1.21.1.jar`.

### 1. Destination list incorrectly rejects the source berth

The packet handler previously called `WaterAutoRouteService.canListCandidate(Level, ...)`.
That overload samples `ServerWaterRouteWorld` noise-derived terrain, whereas route submission
uses `RealBlockWaterMap` / `RealBlockWaterWorld`. Imported or edited terrain can therefore
be real water and still be rejected by the list. Non-NoiseBased generators also make the
noise sampler unusable. The reported `NO_SOURCE_BERTH` is the source at
`28246,66,-43642`, not necessarily the named destination at `32258,66,-50127`.

The packet now uses a dedicated, cheap `WaterRouteCandidateService`. It checks endpoint
presence, port terminal kind, town/nation association and existing diplomacy rules only.
It never scans noise, loads terrain for a berth probe, or assumes missing terrain is land.
A listed port is a candidate, not a guarantee of a valid route. **Strict real-water berth
validation, route planning and route persistence in `submitAutoRoute` are unchanged.**
Post-station/road candidate handling and networking payload formats are unchanged.

### 2. Uploaded flag is covered by the preview background

`FlagPreview` previously called an immediate vanilla texture blit without first submitting
the batched chart background. That background can be flushed later and cover the flag.
Drawing after `super.drawContents` also runs outside the element's own content/scissor
lifecycle. The preview now draws in `drawBackgroundAdditional`, flushes before the blit,
uses a neutral shader colour, and restores the previous shader colour afterwards.
The nation and town screens share this widget, so both receive the correction.

Aspect-fit geometry is extracted into a client-independent helper. The reported 512x458
image fits a 172x90 content box as 101x90, centred, without stretching. Small textures
are not enlarged, and hidden/degenerate boxes are skipped.

## Validation

- Nine regression methods cover listing permissions, missing endpoints/ownership,
  terminal kinds, 512x458 sizing, fallback dimensions and degenerate/extreme layouts.
- A local headless harness executed those methods with small Minecraft/UI/JUnit test
  doubles; the unchanged permission/result classes came from the uploaded JAR.
- A modeled batched-background renderer exercised the uploaded original FlagPreview
  bytecode and the modified code: old preview covered, modified preview visible;
  shader colour restored. This is **not a GPU/macOS or full Minecraft integration test**.
- The included GitHub Actions workflow compiles the actual project, runs the focused
  JUnit tests and packages the main JAR. Its result must be checked separately.

## Manual acceptance

1. Open AutoRoute at the reported source port in an imported/Tellus/edited world.
   A permitted destination must be listed without a noise-based `NO_SOURCE_BERTH`.
2. Attempt route creation. Truly dry/invalid berths and forbidden diplomacy must still
   be rejected; a valid water route must be planned before it is saved.
3. Open both nation and town flag tabs; upload a 512x458 image and test resizing,
   mirror, tab switching, UI scaling and reopening. Check on the affected macOS client.
4. Confirm post-station route selection is unchanged.

## Separate performance findings (not claimed fixed here)

Route creation still contains synchronous NBT waits in its preflight, and the destination
loop still obtains target block entities synchronously. This patch removes the list's
noise-berth work but does not claim to eliminate all main-thread stalls, the reported
9-second tick backlog, or `moved too quickly` warnings. Those require profiling and a
separate bounded asynchronous preparation change with world-save integration tests.

No world save was modified and no production server was deployed by this change.
