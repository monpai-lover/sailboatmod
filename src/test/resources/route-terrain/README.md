# Routing test data

## `aegean-gshhs-i.rle`

Derived from the GSHHS intermediate shoreline dataset bundled with Basemap, using its land polygons
in longitude 22..30.3, latitude 34..41.5. GSHHG is maintained by Paul Wessel and Walter H. F. Smith.
Source/documentation: https://www.soest.hawaii.edu/wessel/gshhg/
NOAA description: https://www.ngdc.noaa.gov/mgg/shorelines/shorelines.html
Basemap project: https://github.com/matplotlib/basemap

This data derivative is under LGPL-3.0; see `GSHHS-LGPL-3.txt`. It does not change the license of the
Java source. The accompanying generator reproduces the sampling from locally installed Basemap data.
First row: width height minimumX minimumZ step. Subsequent rows: initial W/L followed by alternating
run lengths. Projection x=6378137*lonRadians/100, z=-6378137*log(tan(pi/4+latRadians/2))/100. Step=4.
Endpoint coordinates: (27997,65,-42143) / (32249,65,-50109); chosen offshore, not on city-centre land.
This is an approximate coastline regression, not a real Tellus world or a vessel navigation chart.

## `junshi-surface.txt`

Privacy-minimized derivative of the user's `junshi.nbt`, SHA256 `29e5868bc171344a69b44e0ccfde2ba719011ddeac052e50aa35d502af1bad24`.
Only 102x141 surface columns: # known non-water, ? no represented surface, digits local water-block Y.
Air/vegetation are skipped using the production water-surface conventions. All entity data, owner IDs,
country names, inventory and embedded block-entity NBT are omitted. The structure has no world origin;
world positioning used in the handoff is an alignment assumption based on the supplied dock log.
Outside this footprint is unknown, never invented land/water. Only this sanitized derivative is committed; the original structure is not distributed.
