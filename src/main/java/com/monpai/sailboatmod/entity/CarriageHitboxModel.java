package com.monpai.sailboatmod.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 马车「整模型」交互/碰撞子框模型。
 *
 * <p>MC 实体 AABB 引擎硬限制只能正方,罩不住长方形马车(世界尺寸长~5.16×宽~2.12×高~2.97 块),
 * 车头辕杆/车尾露在实体 AABB 外 → 点不到、撞不住。本类把车身拆成沿纵轴的几个子框,
 * 供两处共用:
 * <ul>
 *   <li>客户端 raytrace(ClientInputHandler):对子框做 clip,点模型任意部位都能上车;</li>
 *   <li>服务端软推(CarriageEntity.pushIntersectingEntities):正方框外突出段靠软推补挡。</li>
 * </ul>
 *
 * <p><b>坐标系=座位帧</b>:复用 {@link CarriageEntity#positionRider} 行 1299 已被游戏内验证的算子
 * {@code Vec3.yRot(-getYRot()*π/180 - π/2)}(座位/马都靠它精确落位),避免手推 sin/cos 符号
 * 导致「框飘车侧」(positionRider 注释记录过该坑)。座位帧约定:<b>+X=车头</b>,Z=车宽(左右),Y=世界竖直。
 *
 * <p>子框本地坐标由 carriage.geo.json 各 bone 范围(单位 1/16 块)按 model→座位帧换算得来:
 * {@code seatX=-(modelX)*1.8/16}(翻转车头到 +X)、{@code seatZ=modelZ*1.8/16}、
 * {@code worldY=modelY*1.8/16+0.02}(渲染缩放 1.8、Y 偏移 0.02)。常量集中此处,游戏内对 F3 微调。
 */
public final class CarriageHitboxModel {

    /** 单个子框的座位帧本地范围(块;+X=车头,Z=左右,Y=脚底起)。 */
    private record LocalBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    }

    // BODY 车厢主体(含轮):geo vieyinport X[-12.1,23.8]→座位帧 X[-2.68,+1.36],含余量。
    // SHAFT 辕杆/车头尖:geo before X[-21.9,-10.5]→座位帧 X[+1.18,+2.46],与 BODY 在 X=1.40 衔接。
    // CANOPY 车篷+柱+顶:geo TOP/pillar X[-11.2,24]→座位帧 X[-2.7,+1.26],并辕杆顶放宽。
    private static final List<LocalBox> LOCAL_BOXES = List.of(
            new LocalBox(-2.70D, 0.00D, -1.10D, +1.40D, 1.80D, +1.10D), // BODY
            new LocalBox(+1.40D, 0.80D, -0.80D, +2.50D, 1.20D, +0.80D), // SHAFT
            new LocalBox(-1.35D, 1.00D, -0.90D, +2.75D, 3.00D, +0.90D)  // CANOPY
    );

    // TODO 马可点击本期不做:SHAFT 到辕杆尖 +2.5 已够「点车头上车」。若要点马身,追加一个以
    //  CarriageVisualRig.renderedHorseWorldOffset 世界偏移为中心 ~1.4×0.8×2.0 的框。

    private CarriageHitboxModel() {
    }

    /**
     * 子框列表(世界坐标,轴对齐包络)。每框 4 个水平角经座位帧算子旋转后取 X/Z 包络,Y 直接平移。
     * 斜车时是轴对齐世界框的包络(略大于真实斜框):点击容错无害、软推可接受。
     *
     * @param partialTick 客户端渲染插值;服务端传 1.0
     */
    public static List<AABB> getInteractionBoxesWorld(CarriageEntity carriage, float partialTick) {
        double cx = Mth.lerp(partialTick, carriage.xo, carriage.getX());
        double cy = Mth.lerp(partialTick, carriage.yo, carriage.getY());
        double cz = Mth.lerp(partialTick, carriage.zo, carriage.getZ());
        float yaw = carriage.getViewYRot(partialTick);
        // 座位帧算子:与 positionRider 行 1299 同款(-yaw*DEG - π/2)。
        float rot = -yaw * Mth.DEG_TO_RAD - ((float) (Math.PI / 2.0D));

        List<AABB> boxes = new ArrayList<>(LOCAL_BOXES.size());
        for (LocalBox b : LOCAL_BOXES) {
            // 旋转 4 个水平角(minX/maxX × minZ/maxZ),Y 不参与旋转。
            double minWX = Double.MAX_VALUE, maxWX = -Double.MAX_VALUE;
            double minWZ = Double.MAX_VALUE, maxWZ = -Double.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                double lx = (i & 1) == 0 ? b.minX : b.maxX;
                double lz = (i & 2) == 0 ? b.minZ : b.maxZ;
                Vec3 w = new Vec3(lx, 0.0D, lz).yRot(rot);
                minWX = Math.min(minWX, w.x);
                maxWX = Math.max(maxWX, w.x);
                minWZ = Math.min(minWZ, w.z);
                maxWZ = Math.max(maxWZ, w.z);
            }
            boxes.add(new AABB(
                    cx + minWX, cy + b.minY, cz + minWZ,
                    cx + maxWX, cy + b.maxY, cz + maxWZ));
        }
        return boxes;
    }

    /** 所有子框的水平包络(单框,供软推一次性查询附近实体)。 */
    public static AABB getSweepBox(CarriageEntity carriage) {
        List<AABB> boxes = getInteractionBoxesWorld(carriage, 1.0F);
        AABB sweep = boxes.get(0);
        for (int i = 1; i < boxes.size(); i++) {
            sweep = sweep.minmax(boxes.get(i));
        }
        return sweep;
    }
}
