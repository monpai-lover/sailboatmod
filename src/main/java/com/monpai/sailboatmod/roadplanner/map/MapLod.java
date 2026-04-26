package com.monpai.sailboatmod.roadplanner.map;

public enum MapLod {
    LOD_1(1),
    LOD_2(2),
    LOD_4(4);

    private final int blocksPerPixel;

    MapLod(int blocksPerPixel) {
        this.blocksPerPixel = blocksPerPixel;
    }

    public int blocksPerPixel() {
        return blocksPerPixel;
    }
}
