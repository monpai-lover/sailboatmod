package com.monpai.sailboatmod.compat.aeronautics;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Read-only projection of the native plot bounds at a proposed material-anchor pose. */
public final class RailAircraftProjectedHull {
    private static volatile Access access;
    private static volatile boolean attempted;
    private RailAircraftProjectedHull() { }

    /** Uses only an already loaded controller/body; does not acquire a motion lease or change native state. */
    public static Optional<AABB> at(BlockEntity controller, Direction localNose, Vec3 desiredAnchor,
                                    Vec3 desiredTangent, double bank) {
        try {
            if (controller==null || controller.isRemoved() || !(controller.getLevel() instanceof ServerLevel world)
                    || world.getServer()==null || !world.getServer().isSameThread()) return Optional.empty();
            var chunk=world.getChunkSource().getChunkNow(controller.getBlockPos().getX()>>4,controller.getBlockPos().getZ()>>4);
            if (chunk==null || chunk.getBlockEntities().get(controller.getBlockPos())!=controller) return Optional.empty();
            Access api=api();
            return api==null?Optional.empty():api.at(controller,localNose,desiredAnchor,desiredTangent,bank);
        } catch (ReflectiveOperationException|RuntimeException|LinkageError unavailable) {
            return Optional.empty();
        }
    }

    /** Native cell maxima are inclusive. Copies all input values; no native pose or mutable bounds escape. */
    static Optional<AABB> transform(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                   Vec3 scale, Vec3 localAnchor, Direction localNose, Vec3 desiredAnchor,
                                   Vec3 desiredTangent, double bank) {
        if (maxX<minX || maxY<minY || maxZ<minZ || !RailAircraftMotionState.finite(scale)
                || scale.x<=0 || scale.y<=0 || scale.z<=0 || !RailAircraftMotionState.finite(localAnchor)
                || !RailAircraftMotionState.finite(desiredAnchor)) return Optional.empty();
        try {
            var q=RailAircraftMotionState.orientation(localNose,desiredTangent,bank);
            double loX=Double.POSITIVE_INFINITY,loY=loX,loZ=loX;
            double hiX=Double.NEGATIVE_INFINITY,hiY=hiX,hiZ=hiX;
            for (double x:new double[]{minX,(double)maxX+1})
                for (double y:new double[]{minY,(double)maxY+1})
                    for (double z:new double[]{minZ,(double)maxZ+1}) {
                        // posePosition = anchor - Q*((localAnchor-pivot)*scale), so the native pivot cancels.
                        Vector3d p=q.transform(new Vector3d(x-localAnchor.x,y-localAnchor.y,z-localAnchor.z)
                                .mul(scale.x,scale.y,scale.z)).add(desiredAnchor.x,desiredAnchor.y,desiredAnchor.z);
                        if (!Double.isFinite(p.x) || !Double.isFinite(p.y) || !Double.isFinite(p.z)) return Optional.empty();
                        loX=Math.min(loX,p.x);loY=Math.min(loY,p.y);loZ=Math.min(loZ,p.z);
                        hiX=Math.max(hiX,p.x);hiY=Math.max(hiY,p.y);hiZ=Math.max(hiZ,p.z);
                    }
            return hiX>loX && hiY>loY && hiZ>loZ?Optional.of(new AABB(loX,loY,loZ,hiX,hiY,hiZ)):Optional.empty();
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    private static synchronized Access api() throws ReflectiveOperationException {
        if (access!=null) return access;
        if (attempted) return null;
        var mods=ModList.get();
        if (mods==null || !mods.isLoaded("sable")) return null;
        attempted=true;
        access=new Access();
        return access;
    }

    /** Only the audited pure getter ABI is bound; no physics, force, update or chunk creation methods. */
    private static final class Access {
        private static final String BODY="dev.ryanhcode.sable.sublevel.ServerSubLevel";
        private static final String PLOT="dev.ryanhcode.sable.sublevel.plot.LevelPlot";
        private static final String POSE="dev.ryanhcode.sable.companion.math.Pose3dc";
        private static final String BOUNDS="dev.ryanhcode.sable.companion.math.BoundingBox3ic";
        private static final Map<String,String> PINS=Map.of(
                "dev.ryanhcode.sable.ActiveSableCompanion","17b2b226a5b1c953a6070670a7d34bc150485a6f68b2ed459eb15c9b4add309a",
                "dev.ryanhcode.sable.sublevel.SubLevel","160aa58d14ae277470fc6b6afe85a6fc2a57899f97a2e37d9433d8522bbdf677",
                BODY,"033eff21106f748c94b4dc51426a450398392a2e896a9ba7095a7ce4270b9119",
                PLOT,"a9c45a8d468ab91af1bd15f8696dbc66a89c1af7b0ceedec2b8f34484aafe310",
                POSE,"2a3f95cd68b483400ac95f7175ec7116ed7b9eb3f3550a216ec11254d45a86db",
                "dev.ryanhcode.sable.companion.math.Pose3d","24e131357c78f42798579afdc73f76803c3ee3e2d7dce058fb0d5f7085db7ebe",
                BOUNDS,"d0657957224d162e1ba4eebfd3baea78e46aeaf07f122c0d3a2e263aae7d76ad",
                "dev.ryanhcode.sable.companion.math.BoundingBox3i","f34c3ec08ea18242465e364bfdb6816407b86807202a904238a87d1cd303e9c6");
        private final Object helper;
        private final Class<?> bodyType;
        private final Method containing,id,level,removed,plot,bounds,pose,scale;
        private final Method[] coordinates=new Method[6];

        private Access() throws ReflectiveOperationException {
            for (var pin:PINS.entrySet()) pin(find(pin.getKey()),pin.getValue());
            bodyType=find(BODY);
            helper=find("dev.ryanhcode.sable.Sable").getField("HELPER").get(null);
            containing=find("dev.ryanhcode.sable.ActiveSableCompanion").getMethod("getContaining",BlockEntity.class);
            id=bodyType.getMethod("getUniqueId");level=bodyType.getMethod("getLevel");removed=bodyType.getMethod("isRemoved");
            plot=bodyType.getMethod("getPlot");bounds=find(PLOT).getMethod("getBoundingBox");
            pose=bodyType.getMethod("logicalPose");scale=find(POSE).getMethod("scale");
            if (helper==null || !bounds.getReturnType().equals(find(BOUNDS))
                    || !Vector3dc.class.isAssignableFrom(scale.getReturnType())) throw new ReflectiveOperationException("invalid_projected_hull_abi");
            String[] names={"minX","minY","minZ","maxX","maxY","maxZ"};
            for (int i=0;i<names.length;i++) {
                coordinates[i]=find(BOUNDS).getMethod(names[i]);
                if (coordinates[i].getReturnType()!=int.class) throw new ReflectiveOperationException("invalid_projected_hull_bounds_abi");
            }
        }

        private Optional<AABB> at(BlockEntity controller,Direction nose,Vec3 anchor,Vec3 tangent,double bank)
                throws ReflectiveOperationException {
            Object body=containing.invoke(helper,controller);
            if (body==null || body.getClass()!=bodyType || Boolean.TRUE.equals(removed.invoke(body))
                    || level.invoke(body)!=controller.getLevel() || !(id.invoke(body) instanceof UUID)) return Optional.empty();
            Object b=bounds.invoke(plot.invoke(body));
            int[] v=new int[6];
            for (int i=0;i<v.length;i++) v[i]=(Integer)coordinates[i].invoke(b);
            Vector3dc s=(Vector3dc)scale.invoke(pose.invoke(body));
            return transform(v[0],v[1],v[2],v[3],v[4],v[5],new Vec3(s.x(),s.y(),s.z()),
                    Vec3.atCenterOf(controller.getBlockPos()),nose,anchor,tangent,bank);
        }
        private static Class<?> find(String name) throws ClassNotFoundException {
            return Class.forName(name,false,RailAircraftProjectedHull.class.getClassLoader());
        }
        private static void pin(Class<?> type,String expected) throws ReflectiveOperationException {
            try (var input=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
                if (input==null) throw new ReflectiveOperationException("missing_projected_hull_class");
                byte[] bytes=input.readNBytes(1_048_577);
                if (bytes.length>1_048_576 || !expected.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
                    throw new ReflectiveOperationException("unsupported_projected_hull_class");
            } catch (java.io.IOException|java.security.NoSuchAlgorithmException failure) {
                throw new ReflectiveOperationException(failure);
            }
        }
    }
}
