import pathlib,hashlib,json
root=pathlib.Path.cwd();prefix='src/main/java/com/monpai/sailboatmod/route/water/'
expected={
'RealBlockWaterMap.java':('6382ff8ca6deffd33f881140b1efb2e49e609a7f10c04d7967e500ac22fc3593','88bf8e4146f3564a283a9b33060420fa1d141b97f8323b3700d8181e2bf79c47'),
'RealBlockWaterWorld.java':('ca938546de06b2e8467e30c2e3899b595a92a1c2c4de65808e6401d26ce31371','4182fff55c5e6491169a0302dfa615564246bd1e7dc9e2ca18992756d5014e6b'),
'WaterAutoRouteService.java':('b802749f583dc8eb41b90ba6862eb98df995301bc73d2a42dbf590deb2348653','6d8e834004e07743f6dba438ad31bfcf445001d6a976d7c9cb120c308cc52dcb'),
'WaterRoutePathfinder.java':('c8772952ce6bb6af3e46ca8231762cdb7e0b018257c98984f254daa3e622598b','28f68b3fd3f77cda654bfc62128c5db914db59347ebc6df40c42636265919842')}
expected={prefix+k:v for k,v in expected.items()}
test='src/test/java/com/monpai/sailboatmod/route/water/LongWaterGeometryTest.java'
expected[test]=('442424cc8144e3077abd88c00d1d3a8b1237876a144b1a6b57f3ea03374fe09a','825d176c800f4f2f4fec08e9a33346e415d7692c158c41eb84cd7d65e2561e50')
for name,(before,after) in expected.items():assert hashlib.sha256((root/name).read_bytes()).hexdigest()==before,name
p=root/(prefix+'WaterRoutePathfinder.java');s=p.read_text()
s=s.replace('        int unresolved = 0;\n','        int unresolved = 0;\n        Boolean microAllowed = null;\n        boolean microPending = false;\n',1)
s=s.replace('                WaterRouteCancellation.check();\n                int baseX = minimumHop','''                WaterRouteCancellation.check();
                if (hop < 4 && step >= 4 && edgeSampleSpacing > 1) {
                    if (microAllowed == null) {
                        pendingTerrain = false;
                        microAllowed = constrainedWater(current.pos);
                        microPending = pendingTerrain;
                    }
                    if (!microAllowed) {
                        if (microPending) unresolved |= 1 << i;
                        break;
                    }
                }
                int baseX = minimumHop''')
pos=s.index('    /**\n     * 前向链')
s=s[:pos]+'''    /** Refine to unit cells only between nearby opposing banks. A one-sided coast
     * must retain the coarse graph; otherwise its unit fringe exhausts global node budgets.
     * Unknown cells remain deferred and are never counted as banks.
     */
    private boolean constrainedWater(BlockPos at) {
        boolean unknown = false;
        int[][] axes = {{3,0},{0,3}};
        for (int[] axis : axes) {
            WaterColumn a = world.sample(at.getX() + axis[0], at.getZ() + axis[1], policy);
            WaterColumn b = world.sample(at.getX() - axis[0], at.getZ() - axis[1], policy);
            if (a == null || b == null) { unknown = true; continue; }
            if (!a.passable() && !b.passable()) { pendingTerrain = false; return true; }
        }
        pendingTerrain = unknown;
        return false;
    }

'''+s[pos:];p.write_text(s)
p=root/(prefix+'RealBlockWaterMap.java');s=p.read_text().replace('''        if (hullClear(x, z, halfWidth)) {
            return new BlockPos(x, seaLevel, z);
        }
        for (int r = 1; r <= maxR; r++) {''','''        BlockPos center = hullPosition(x, z, halfWidth);
        if (center != null) return center;
        for (int r = 1; r <= maxR; r++) {''').replace('''                    if (hullClear(x + dx, z + dz, halfWidth)) {
                        return new BlockPos(x + dx, seaLevel, z + dz);
                    }''','''                    BlockPos candidate = hullPosition(x + dx, z + dz, halfWidth);
                    if (candidate != null) return candidate;''')
pos=s.index('    /**\n     * <b>NBT 局部绕行 BFS');s=s[:pos]+'''    private BlockPos hullPosition(int x, int z, int halfWidth) {
        if (!retryUnknownPrediction) return hullClear(x, z, halfWidth) ? new BlockPos(x, seaLevel, z) : null;
        WaterColumn column = sampleHullStrict(x, z, halfWidth);
        return column != null && column.passable() ? column.surfacePos() : null;
    }

'''+s[pos:];p.write_text(s)
p=root/(prefix+'RealBlockWaterWorld.java');s=p.read_text().replace('''    public int waterSurfaceY(int x, int z) {
        return seaLevel;
    }''','''    public int waterSurfaceY(int x, int z) {
        if (preserveUnknown) {
            WaterColumn column = map.sampleHullStrict(x, z, boatHalfWidth);
            if (column != null && column.passable()) return column.surfacePos().getY();
        }
        return seaLevel;
    }''');p.write_text(s)
p=root/(prefix+'WaterAutoRouteService.java');s=p.read_text().replace('RealBlockWaterWorld.coarse(probe, seaY, null, null), destination, policy,','RealBlockWaterWorld.streaming(probe, seaY, 1, null, null), destination, policy,');p.write_text(s)
p=root/test;s=p.read_text().replace('x>=-7010&&x<=0&&(z==-3||z==-2)','x>=-7010&&x<=2&&(z==-3||z==-2)');pos=s.index('    @Test void genuineClosedBarrier');s=s[:pos]+'''    @Test void sevenKilometresOfTwoWideRightAngleChannel() throws Exception {
        run("7000m-two-wide-right-angle", (x,z) ->
                (x>=0 && x<=3501 && z>=3 && z<=4)
                || (x>=3500 && x<=3501 && z>=3 && z<=3504),
                new BlockPos(0,65,3), new BlockPos(3500,65,3503));
    }
'''+s[pos:];p.write_text(s)
manifest=json.loads((root/'build/native-manifest.json').read_text())
for name,(before,after) in expected.items():
    assert hashlib.sha256((root/name).read_bytes()).hexdigest()==after,'follow-up digest '+name
    manifest[name]={'before':manifest.get(name,{}).get('before',before),'after':after}
(root/'build/native-manifest.json').write_text(json.dumps(manifest,indent=2))
print('Verified narrow-water refinement and rescue height follow-up')
