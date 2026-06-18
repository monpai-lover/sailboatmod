package com.monpai.sailboatmod.entity;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.market.MarketListing;
import com.monpai.sailboatmod.market.MarketSavedData;
import com.monpai.sailboatmod.market.PurchaseOrder;
import com.monpai.sailboatmod.market.ShipmentManifestEntry;
import com.monpai.sailboatmod.market.ShippingOrder;
import com.monpai.sailboatmod.market.logistics.ShippingTraceService;
import com.monpai.sailboatmod.item.RouteBookItem;
import com.monpai.sailboatmod.integration.bluemap.BlueMapIntegration;
import com.monpai.sailboatmod.registry.ModItems;
import com.monpai.sailboatmod.route.RouteDefinition;
import com.monpai.sailboatmod.route.RouteNbtUtil;
import com.monpai.sailboatmod.route.water.WaterAutoRouteService;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class SailboatEntity extends Boat implements GeoEntity, MenuProvider, TransportEntity {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final RawAnimation SAIL_DOWN_ANIMATION = RawAnimation.begin().thenPlayAndHold("sail down");
    private static final RawAnimation SAIL_UP_ANIMATION = RawAnimation.begin().thenPlayAndHold("sail up");
    private static final EntityDataAccessor<Boolean> DATA_SAIL_DEPLOYED =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_HANDLING_PRESET =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ENGINE_GEAR =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_AUTOPILOT_ACTIVE =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_AUTOPILOT_PAUSED =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_ROUTE_COUNT =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ROUTE_INDEX =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> DATA_ROUTE_NAME =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_SEAT_0 =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_SEAT_1 =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_SEAT_2 =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_SEAT_3 =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_SEAT_4 =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_RENTAL_PRICE =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ARRIVAL_NOTICE_UNTIL_TICK =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> DATA_ARRIVAL_NOTICE_STATION_NAME =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_ARRIVAL_NOTICE_ELAPSED_SECONDS =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> DATA_ARRIVAL_NOTICE_DATE_TEXT =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.STRING);
    // 服务端权威移动后，把当前前向速度(格/tick)同步给客户端，供 HUD 显示。
    // 帆船是服务端权威(isControlledByLocalInstance=!isClientSide)，客户端 deltaMovement 恒=ZERO，
    // 不能像以前那样用客户端本地 deltaMovement 算速度——必须靠这个同步字段(仿马车 DATA_CURRENT_SPEED)。
    private static final EntityDataAccessor<Float> DATA_CURRENT_SPEED =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.FLOAT);
    private static final int INVENTORY_SIZE = 27;
    private static final int SEAT_COUNT = 5;
    private static final int ARRIVAL_NOTICE_TICKS = 100;
    private static final DateTimeFormatter ARRIVAL_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final float MAX_TURN_DEGREES_PER_TICK = 0.85F;
    // 手动驾驶时船头 yaw 跟随航向(漂移转向后的速度矢量方向)的每 tick 最大角度。双端预测下两端同跑此公式，
    // 驾驶者客户端本地即时转向，不再依赖服务端 Rot 包+插值。
    private static final float MANUAL_HEADING_FOLLOW_DEGREES_PER_TICK = 3.0F;
    // 网络插值步数(autopilot 服务端权威时用，照抄马车)。
    private static final int NETWORK_LERP_STEPS = 10;
    private static final double STOWED_FORWARD_ACCEL = 1.006D;
    private static final double STOWED_MAX_SPEED_FACTOR = 0.42D;
    private static final double COASTING_DRAG = 0.992D;       // STOP档松手乘性阻力,每tick掉0.8%约4秒长缓滑(大货轮惯性)
    private static final double GEAR_DRIVE_DRAG = 0.9968D;
    private static final double GEAR_ACCEL_RAMP_UP = 0.0007D;
    private static final double GEAR_ACCEL_RAMP_DOWN = 0.0009D;
    private static final double TURN_VELOCITY_ROTATE_RAD = 0.045D;
    private static final double TURN_SPEED_LOSS = 0.997D;
    private static final double THROTTLE_LATERAL_DAMP = 0.28D;
    private static final double GLIDE_LATERAL_DAMP = 0.36D;
    private static final double DEPLOYED_MAX_FORWARD_KNOTS = 20.0D;
    private static final double STOWED_MAX_FORWARD_KNOTS = 7.0D;
    private static final double MAX_REVERSE_KNOTS = 5.0D;
    private static final double KNOTS_TO_BLOCKS_PER_TICK = 1.0D / 38.87689D;
    private static final double DEPLOYED_MAX_FORWARD_BLOCKS_PER_TICK = DEPLOYED_MAX_FORWARD_KNOTS * KNOTS_TO_BLOCKS_PER_TICK;
    private static final double STOWED_MAX_FORWARD_BLOCKS_PER_TICK = STOWED_MAX_FORWARD_KNOTS * KNOTS_TO_BLOCKS_PER_TICK;
    private static final double MAX_REVERSE_BLOCKS_PER_TICK = MAX_REVERSE_KNOTS * KNOTS_TO_BLOCKS_PER_TICK;
    private static final int LEGACY_LIGHT_CLEAN_RADIUS = 3;
    // 船头主导转向模型（仿 smallships rotationSpeed 累积）+ 重船感惯性 + 转弯掉速。"惯性强/大货轮"档。
    // turnInput 累积 rotationSpeed 驱动 yaw，速度永远沿船头方向；急转掉速；航向低通滑后于船头。
    private static final float ROT_ACCELERATION = 0.06F;       // 转向角加速度,小=启动慢(惯性强)
    private static final float MAX_ROT_SPEED = 1.4F;           // 最大转向角速度°/tick,小=转弯慢(重船感)
    private static final float ROT_RESISTANCE = 0.04F;         // 松舵衰减步长,小=停止拖
    private static final double TURN_SPEED_LOSS_MAX = 0.975D;  // 满舵每tick掉2.5%速,小=掉速猛
    private static final double HEADING_CATCHUP = 0.35D;      // 航向追船头低通系数,小=滑后明显(重船感),只影响方向不吃速度
    private static final double COASTING_DECEL = 0.0015D;       // STOP档线性兜底,保证乘性衰减最终归零(必停)
    private static final double SPEED_WATER_DAMP = 1.0D;       // 标量水阻,先1.0(顶速靠gearCap),要更粘降到0.995
    private static final int BLUEMAP_BOAT_SYNC_INTERVAL_TICKS = 10;
    private static final int PICKUP_LOAD_SCAN_INTERVAL_TICKS = 20;
    // 航行水花/划水音特效（仿 smallships）：前向速度(格/tick)超过阈值且在水里才触发；音效每 N tick 一次循环。
    private static final float WAKE_EFFECT_SPEED_THRESHOLD = 0.04F;
    private static final int WAKE_SOUND_INTERVAL_TICKS = 8;
    private static final float RAM_SPEED_THRESHOLD = 0.12F;     // 撞击伤害的最低巡航速度(格/tick)
    private static final float RAM_DAMAGE_PER_SPEED = 8.0F;     // 撞击伤害系数(伤害=速度×此值,借鉴 smallships 7.5)
    // checkInWater 判定容差：自定义浮力 applyFallbackBuoyancy 把 getY() 顶到 ≈水面高度，而 vanilla Boat
    // box.minY==getY()，故船底精确贴在水面（实测 boxMinY==surfaceY==62.889）。原版 smallships 的船吃水深
    // (box.minY 明显低于水面)所以严格小于成立；我们的船浮在水面上沿，严格小于恒 false。给一格向下容差：
    // 船底在水面上方 1 格内（且扫到了水方块）即算在水里，消除「边界相等→不进物理→船完全不动」。岸上扫不到
    // 水方块不受影响。
    private static final double WATER_DETECT_TOLERANCE = 1.0D;
    private static final double AUTOPILOT_ARRIVAL_RADIUS = 3.2D;
    private static final double AUTOPILOT_START_WAYPOINT_CAPTURE_RADIUS = 7.5D;
    private static final double AUTOPILOT_SLOWDOWN_RADIUS = 14.0D;
    private static final double AUTOPILOT_FINAL_SLOWDOWN_RADIUS = 11.0D;
    private static final double AUTOPILOT_FINAL_STOP_RADIUS = 4.5D;
    private static final double AUTOPILOT_FINAL_APPROACH_MAX_SPEED = 0.05D;
    private static final double AUTOPILOT_FINAL_STOP_MAX_SPEED = 0.025D;
    private static final double AUTOPILOT_DEPARTURE_YIELD_LOOKAHEAD = 9.0D;
    private static final double AUTOPILOT_DEPARTURE_YIELD_LATERAL = 3.2D;
    private static final double AUTOPILOT_DEPARTURE_YIELD_START_RADIUS = 12.0D;
    private static final float AUTOPILOT_TURN_IN_PLACE_DEGREES = 95.0F;
    private static final float AUTOPILOT_SLOW_TURN_DEGREES = 55.0F;
    private static final int AUTOPILOT_NO_PROGRESS_TICKS_LIMIT = 70;
    // 多站连运：等待自动航线生成的最长停泊时长（约 30 秒），超时则放弃续运、货留船。
    private static final int WATER_LEG_GENERATION_TIMEOUT_TICKS = 600;
    private static final double AUTOPILOT_PROGRESS_EPSILON = 0.08D;
    private static final double AUTOPILOT_STALL_SKIP_RADIUS = 48.0D;
    // 窄河道物理卡死自救：撞墙且几乎不动累计达 DETECT_TICKS → 进入脱困；每轮温和倒车摆舵 REVERSE_TICKS；
    // 连续 MAX_ATTEMPTS 轮仍没挪动 → 暂停求助。MOVE_EPSILON 为单 tick 平面位移阈值，YAW_STEP 为脱困摆舵每 tick 角度。
    private static final int AUTOPILOT_STUCK_DETECT_TICKS = 50;
    private static final double AUTOPILOT_STUCK_MOVE_EPSILON = 0.02D;
    private static final int AUTOPILOT_UNSTICK_REVERSE_TICKS = 24;
    private static final int AUTOPILOT_UNSTICK_MAX_ATTEMPTS = 5;
    private static final float AUTOPILOT_UNSTICK_YAW_STEP = 4.0F;
    private static final double DOCK_PARKING_EDGE_PADDING = 1.5D;
    private static final double DOCK_APPROACH_CLEAR_RADIUS = 3.8D;
    private static final double DOCK_PARKING_GRID_STEP = 2.75D;
    private static final double DOCK_PARKING_DOCK_EXCLUSION_RADIUS = 3.25D;
    private static final double DOCK_PARKING_PREFERRED_DOCK_DISTANCE = 3.9D;
    private static final float DOCK_HOLD_TURN_STEP_DEGREES = 2.5F;
    private static final double AUTOPILOT_PASSED_PROGRESS_THRESHOLD = 1.05D;
    private static final double AUTOPILOT_PASSED_LATERAL_THRESHOLD = 14.0D;
    private static final int AUTOPILOT_CHUNK_RADIUS = 2;
    private static final int AUTOPILOT_TARGET_CHUNK_RADIUS = 1;
    private static final int AUTOPILOT_DEST_DOCK_CHUNK_RADIUS = 2;
    private static final int MAX_AUTOPILOT_WAYPOINTS = 256;
    public static final int DEFAULT_RENTAL_PRICE = 100;
    public static final int DISABLED_RENTAL_PRICE = -1;
    public static final int MIN_RENTAL_PRICE = DISABLED_RENTAL_PRICE;
    public static final int MAX_RENTAL_PRICE = 1_000_000;
    private static final Vec3[] PASSENGER_OFFSETS = new Vec3[] {
            new Vec3(0.0D, 0.55D, 0.4D),
            new Vec3(-0.9D, 0.55D, -0.1D),
            new Vec3(0.9D, 0.55D, -0.1D),
            new Vec3(-0.65D, 0.55D, -1.1D),
            new Vec3(0.65D, 0.55D, -1.1D)
    };

    private final AnimatableInstanceCache animatableCache = GeckoLibUtil.createInstanceCache(this);
    private final NonNullList<ItemStack> inventory = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
    private final Map<UUID, Integer> seatAssignments = new HashMap<>();
    private int legacyLightCleanupTicks = 80;
    private float lastTickYaw;
    private Vec3 inertialPlanarVelocity = Vec3.ZERO;
    private double commandedForwardAccel = 0.0D;
    // 转向角速度(度/tick)。本地字段非同步：两端各自从 turnInput 累积，manualInputState +
    // applyClientControlInput 保证两端输入一致，rotationSpeed 是输入纯函数→结果一致(同 commandedForwardAccel)。
    // 同步反而引入服务端→客户端覆盖延迟、破坏本地预测零延迟。不持久化 NBT(瞬时量)。
    private float rotationSpeed = 0.0F;
    private int nonWaterTicks = 0;
    private boolean forwardPressedLastTick = false;
    private boolean reversePressedLastTick = false;
    private int lastGearIdForSound = Integer.MIN_VALUE; // 服务端挡位变化音效的上次挡位 id(MIN=未初始化)
    private final SailboatManualInputState manualInputState = new SailboatManualInputState();
    // 自管网络插值(覆写 vanilla Boat 的 private lerp)：手动 local-control 端清 0 步=本地预测不被服务端拖；
    // autopilot 服务端权威时用 1/NETWORK_LERP_STEPS 步插值跟随。照抄 CarriageEntity 的 tickNetworkLerp/lerpTo。
    private int sailboatLerpSteps;
    private double sailboatLerpX;
    private double sailboatLerpY;
    private double sailboatLerpZ;
    private double sailboatLerpYaw;
    private double sailboatLerpPitch;
    private float previousSailDeployProgress = 1.0F;
    private float sailDeployProgress = 1.0F;
    private final List<Vec3> autopilotRoute = new ArrayList<>();
    private final List<RouteDefinition> routeCatalog = new ArrayList<>();
    private final Set<Long> forcedAutopilotChunks = new HashSet<>();
    // 幽灵船修复（同马车）：已对其补发过 spawn 的附近玩家；autopilot 全程 + 到港宽限期每帧维持可见。
    private final Set<UUID> spawnedToPlayers = new HashSet<>();
    // 到港宽限期：到港后仍强加载区块并持续 spawn 心跳这么多 tick，给 ChunkMap 重建追踪窗口。
    private static final int POST_ARRIVAL_FORCED_HOLD_TICKS = 100;
    private int postArrivalForcedHoldTicks = 0;
    // enroute spawn 心跳周期(tick)：每隔这么久对范围内所有玩家重发整套 spawn 兜底被丢弃的实体。
    private static final int ENROUTE_SPAWN_HEARTBEAT_TICKS = 60;
    private int autopilotTargetIndex = 0;
    private String autopilotRouteName = "";
    private BlockPos routeDockPos = null;
    @Nullable
    private Vec3 autopilotDepartureOrigin = null;
    private int selectedRouteIndex = 0;
    private int autopilotNoProgressTicks = 0;
    private double autopilotLastTargetDistance = Double.NaN;
    // 窄河道脱困运行时状态
    private int autopilotStuckTicks = 0;          // 撞墙且几乎不动的累计 tick
    private int autopilotUnstickTicks = 0;        // 当前脱困剩余 tick（>0=正在脱困）
    private int autopilotUnstickAttempts = 0;     // 已连续脱困轮数
    private int autopilotUnstickYawDir = 1;       // 脱困摆舵方向，每轮交替 ±1
    private Vec3 autopilotUnstickStartPos = null; // 本轮脱困起点，判断是否见效
    private boolean autopilotTraceStuck = false;  // 已把 webmap 轨迹标记为 STUCK（避免重复写/无谓恢复）
    private String pendingShipperName = "";
    private String autopilotShipmentShipperName = "";
    private String autopilotShipmentStartDockName = "";
    private String autopilotShipmentEndDockName = "";
    private String autopilotShipmentRecipientName = "";
    private String autopilotShipmentRecipientUuid = "";
    private String autopilotShipmentPurchaseOrderId = "";
    private String autopilotShipmentShippingOrderId = "";
    private long autopilotShipmentDepartureEpochMillis = 0L;
    private double autopilotShipmentDistanceMeters = 0.0D;
    private boolean autopilotAllowNonOrderAutoReturn = false;
    private boolean autopilotAllowNonOrderAutoUnload = true;
    private static final int TRACE_LIVE_SYNC_INTERVAL_TICKS = 40; // 2s @20tps
    private int traceLiveSyncTicks = 0;
    private boolean autopilotReturnTrip = false;
    private final List<ShipmentManifestEntry> autopilotShipmentManifest = new ArrayList<>();
    private BlockPos autopilotDestinationDockHintPos = null;
    @Nullable
    private Vec3 autopilotDockingSpot = null;
    private int dockHoldTicks = 0;
    @Nullable
    private Vec3 dockHoldPos = null;
    private float dockHoldYaw = Float.NaN;
    // 多站连运：到港后若无现成航线，提交自动航线生成并停泊等待，命中后续运到下一港。
    @Nullable
    private BlockPos awaitingNextLegPort = null;
    @Nullable
    private BlockPos awaitingNextLegFromDock = null;
    private int awaitingNextLegTicks = 0;
    private String ownerName = "";
    private String ownerUuid = "";
    private int rentalPrice = DEFAULT_RENTAL_PRICE;
    private boolean pendingBlueMapRemoval = false;

    private final Container container = new Container() {
        @Override
        public int getContainerSize() {
            return INVENTORY_SIZE;
        }

        @Override
        public boolean isEmpty() {
            for (ItemStack stack : inventory) {
                if (!stack.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            return inventory.get(slot);
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            return net.minecraft.world.ContainerHelper.removeItem(inventory, slot, amount);
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return net.minecraft.world.ContainerHelper.takeItem(inventory, slot);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            inventory.set(slot, stack);
            if (stack.getCount() > getMaxStackSize()) {
                stack.setCount(getMaxStackSize());
            }
        }

        @Override
        public void setChanged() {
        }

        @Override
        public boolean stillValid(Player player) {
            return SailboatEntity.this.isAlive() && SailboatEntity.this.distanceTo(player) < 8.0F;
        }

        @Override
        public void clearContent() {
            inventory.clear();
        }
    };

    public SailboatEntity(EntityType<? extends Boat> entityType, Level level) {
        super(entityType, level);
        this.lastTickYaw = getYRot();
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_SAIL_DEPLOYED, true);
        this.entityData.define(DATA_HANDLING_PRESET, HandlingPreset.BALANCED.id);
        this.entityData.define(DATA_ENGINE_GEAR, EngineGear.STOP.id);
        this.entityData.define(DATA_AUTOPILOT_ACTIVE, false);
        this.entityData.define(DATA_AUTOPILOT_PAUSED, false);
        this.entityData.define(DATA_ROUTE_COUNT, 0);
        this.entityData.define(DATA_ROUTE_INDEX, 0);
        this.entityData.define(DATA_ROUTE_NAME, "-");
        this.entityData.define(DATA_SEAT_0, -1);
        this.entityData.define(DATA_SEAT_1, -1);
        this.entityData.define(DATA_SEAT_2, -1);
        this.entityData.define(DATA_SEAT_3, -1);
        this.entityData.define(DATA_SEAT_4, -1);
        this.entityData.define(DATA_RENTAL_PRICE, DEFAULT_RENTAL_PRICE);
        this.entityData.define(DATA_ARRIVAL_NOTICE_UNTIL_TICK, 0);
        this.entityData.define(DATA_ARRIVAL_NOTICE_STATION_NAME, "");
        this.entityData.define(DATA_ARRIVAL_NOTICE_ELAPSED_SECONDS, 0);
        this.entityData.define(DATA_ARRIVAL_NOTICE_DATE_TEXT, "");
        this.entityData.define(DATA_CURRENT_SPEED, 0.0F);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isInvulnerableTo(source)) {
            return false;
        }
        if (!level().isClientSide && !isRemoved()) {
            setHurtDir(-getHurtDir());
            setHurtTime(10);
            setDamage(getDamage() + amount * 10.0F);
            markHurt();
            gameEvent(GameEvent.ENTITY_DAMAGE, source.getEntity());
            boolean instabuild = source.getEntity() instanceof Player attackPlayer && attackPlayer.getAbilities().instabuild;
            if (instabuild || getDamage() > 40.0F) {
                if (!instabuild && level().getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOENTITYDROPS)) {
                    destroy(source);
                } else {
                    pendingBlueMapRemoval = true;
                    BlueMapIntegration.removeBoat(level(), getUUID());
                    container.clearContent();
                }
                discard();
            }
        }
        return true;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (held.getItem() instanceof RouteBookItem routeBookItem) {
            return routeBookItem.useOnSailboat(player, hand, this);
        }

        if (player.isSecondaryUseActive()) {
            if (!canPlayerAccessStorage(player)) {
                return InteractionResult.sidedSuccess(level().isClientSide);
            }
            openStorage(player);
            return InteractionResult.sidedSuccess(level().isClientSide);
        }

        if (!player.isPassenger() && this.canAddPassenger(player)) {
            if (!canPlayerBoard(player)) {
                return InteractionResult.sidedSuccess(level().isClientSide);
            }
            if (!level().isClientSide) {
                int seat = firstFreeSeat();
                if (seat < 0) {
                    return InteractionResult.PASS;
                }
                if (player.startRiding(this)) {
                    initializeOwnerIfAbsent(player);
                    seatAssignments.put(player.getUUID(), seat);
                    syncSeatEntityData();
                }
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }

        return InteractionResult.PASS;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().size() < SEAT_COUNT;
    }

    @Override
    public void tick() {
        boolean skipVanillaBoatMovementTick = skipsVanillaBoatMovementTick();
        if (skipVanillaBoatMovementTick) {
            tickBaseEntityWithoutBoatMovement();
        } else {
            super.tick();
        }
        tickNetworkLerp();
        cleanupSeatAssignments();
        previousSailDeployProgress = sailDeployProgress;
        float sailTarget = isSailDeployed() ? 1.0F : 0.0F;
        sailDeployProgress += (sailTarget - sailDeployProgress) * 0.18F;

        setYBodyRot(getYRot());
        setYHeadRot(getYRot());

        if (!level().isClientSide) {
            setGlowingTag(false);
            cleanupLegacyNightLightBlocks();
            applyTransportSupport();
            updateAutopilotChunkLoading();
            tickArrivalNoticeCountdown();
        }
        limitTurnRate();
        LivingEntity captain = getControllingPassenger();
        if (!level().isClientSide && !(captain instanceof Player)) {
            manualInputState.clear();
        }
        boolean autopilotControl = !level().isClientSide
                && isAutopilotActive()
                && !isAutopilotPaused()
                && hasAutopilotRoute();
        if (!level().isClientSide && isAutopilotActive() && isAutopilotPaused() && !(captain instanceof Player)) {
            resumeAutopilot();
            autopilotControl = !isAutopilotPaused() && hasAutopilotRoute();
        }
        // 双端预测：客户端驾驶者也跑移动物理(本地预测=零延迟跟手)，但仅手动(非 autopilot)、有玩家驾驶时。
        // autopilot 时 isControlledByLocalInstance()=false→客户端不本地控制，靠 tickNetworkLerp 插值服务端权威位置。
        boolean clientLocalDrive = level().isClientSide
                && isControlledByLocalInstance()
                && (captain instanceof Player)
                && isPrimaryTravelMedium();
        // webmap: 航行中每 2 秒把实时坐标 + 进度推给轨迹（订单车用订单 id，手动车用 manual id）
        if (autopilotControl && ++traceLiveSyncTicks >= TRACE_LIVE_SYNC_INTERVAL_TICKS) {
            traceLiveSyncTicks = 0;
            String traceId = (autopilotShipmentShippingOrderId == null || autopilotShipmentShippingOrderId.isBlank())
                    ? com.monpai.sailboatmod.market.logistics.ShippingTraceService.manualTraceId(getUUID())
                    : autopilotShipmentShippingOrderId;
            int routeSize = autopilotRoute.size();
            int completed = routeSize <= 0 ? 0 : Mth.clamp(autopilotTargetIndex, 0, routeSize - 1);
            double progress = routeSize <= 0 ? 0.0D : (double) completed / routeSize;
            double speedPerTick = getDeltaMovement().horizontalDistance();
            com.monpai.sailboatmod.market.logistics.ShippingTraceService.updateLivePositionProgressAndSpeed(
                    level(), traceId, getX(), getZ(), completed, progress, speedPerTick);
        }
        if (!level().isClientSide && !isAutopilotActive() && awaitingNextLegPort != null) {
            tryResumeAwaitedWaterLeg();
        }
        if (!level().isClientSide && !isAutopilotActive() && dockHoldTicks > 0
                && !(getControllingPassenger() instanceof Player)) {
            // 有玩家驾驶时不做到站 dock-hold 阻尼：否则服务端把船拉回 dock，与客户端本地预测(玩家想开走)
            // 打架成橡皮筋。无人时(autopilot 到站后空置)才阻尼保持停泊位。
            dockHoldTicks--;
            entityData.set(DATA_ENGINE_GEAR, EngineGear.STOP.id);
            Vec3 vel = getDeltaMovement();
            Vec3 adjusted = new Vec3(vel.x * 0.15D, vel.y * 0.6D, vel.z * 0.15D);
            if (dockHoldPos != null) {
                Vec3 delta = dockHoldPos.subtract(position());
                double planarDistSq = delta.x * delta.x + delta.z * delta.z;
                if (planarDistSq <= 0.09D) {
                    adjusted = new Vec3(0.0D, vel.y * 0.4D, 0.0D);
                } else {
                    adjusted = adjusted.add(delta.x * 0.05D, 0.0D, delta.z * 0.05D);
                }
            }
            if (!Float.isNaN(dockHoldYaw)) {
                float nextYaw = getYRot() + Mth.clamp(Mth.wrapDegrees(dockHoldYaw - getYRot()),
                        -DOCK_HOLD_TURN_STEP_DEGREES, DOCK_HOLD_TURN_STEP_DEGREES);
                setYRot(nextYaw);
                setYHeadRot(nextYaw);
                setYBodyRot(nextYaw);
            }
            setDeltaMovement(adjusted);
        }
        if ((!level().isClientSide || clientLocalDrive) && isPrimaryTravelMedium() && (isVehicle() || autopilotControl)) {
            nonWaterTicks = 0;
            // 客户端本地预测也要跑浮力(否则预测 Y 沉)；服务端浮力已在上面副作用块跑过。
            // 浮力设 deltaMovement.y，须在下面物理积分(读 current.y 保留)之前。
            if (clientLocalDrive) {
                applyTransportSupport();
            }
            Vec3 current = getDeltaMovement();
            HandlingPreset preset = getHandlingPreset();
            boolean sailDeployed = isSailDeployed();
            double yawRad = getYRot() * (Math.PI / 180.0D);
            double dirX = -Math.sin(yawRad);
            double dirZ = Math.cos(yawRad);
            double maxForwardCap = sailDeployed ? DEPLOYED_MAX_FORWARD_BLOCKS_PER_TICK : STOWED_MAX_FORWARD_BLOCKS_PER_TICK;
            double maxSpeed = Math.min(preset.maxSpeed, maxForwardCap);
            boolean wantsForward = false;
            boolean wantsReverse = false;
            boolean wantsTurn = false;
            float turnInput = 0.0F;
            boolean hasManualInput = false;

            if (captain instanceof Player captainPlayer) {
                SailboatControlInput controlInput = manualInputState.currentInput(captainPlayer.getUUID(), tickCount);
                boolean playerWantsForward = controlInput.wantsForward();
                boolean playerWantsReverse = controlInput.wantsReverse();
                boolean playerWantsTurn = controlInput.wantsTurn();
                hasManualInput = playerWantsForward || playerWantsReverse || playerWantsTurn;
                if (AutopilotPassengerInputPolicy.shouldUseAutopilotCommand(autopilotControl, hasManualInput)) {
                    // Keep route control authoritative while riders are aboard; stop/pause stays explicit.
                    // 清掉乘客残留输入，避免玩家在自动航行中操作导致转向/齿轮抖动。
                    manualInputState.clear();
                    AutopilotCommand autopilotCommand = computeAutopilotCommand();
                    if (autopilotCommand.active) {
                        if (autopilotCommand.yawStep != 0.0F) {
                            float nextYaw = getYRot() + autopilotCommand.yawStep;
                            setYRot(nextYaw);
                            setYHeadRot(nextYaw);
                            setYBodyRot(nextYaw);
                            yawRad = getYRot() * (Math.PI / 180.0D);
                            dirX = -Math.sin(yawRad);
                            dirZ = Math.cos(yawRad);
                        }
                        wantsTurn = autopilotCommand.wantsTurn;
                        turnInput = autopilotCommand.turnInput;
                        entityData.set(DATA_ENGINE_GEAR, autopilotCommand.gear.id);
                    } else {
                        entityData.set(DATA_ENGINE_GEAR, EngineGear.STOP.id);
                    }
                    forwardPressedLastTick = false;
                    reversePressedLastTick = false;
                } else {
                    wantsForward = playerWantsForward;
                    wantsReverse = playerWantsReverse;
                    wantsTurn = playerWantsTurn;
                    turnInput = controlInput.turnInput();
                    // 客户端本地驾驶端(clientLocalDrive)也跑换挡：客户端 entityData.set 只改本地副本(下一 tick
                    // 被服务端权威值同步覆盖纠正)，给本地预测提供油门源。否则客户端 gear 恒 STOP→预测无推力→
                    // 船不动，且客户端 isControlledByLocalInstance=true 会经 ServerboundMoveVehiclePacket 把
                    // 「不动」上报、服务端 absMoveTo 覆盖→两端冻结。服务端照旧权威切挡。
                    if (!level().isClientSide || clientLocalDrive) {
                        if (usesHoldToDriveControls()) {
                            entityData.set(DATA_ENGINE_GEAR, EngineGear.STOP.id);
                            forwardPressedLastTick = false;
                            reversePressedLastTick = false;
                        } else if (clientLocalDrive) {
                            // 六段挡边沿检测只在客户端本地驾驶端跑：客户端用本地实时按键算目标挡位(可靠)，
                            // 设本地 DATA_ENGINE_GEAR 供预测+随包发服务端。服务端不再重放边沿(网络包丢/合批
                            // 会漏沿致两端永久错位、一卡一卡)，改由 applyManualControlInput 收 packet gear 幂等设挡。
                            updateGearFromInput(wantsForward, wantsReverse);
                        }
                    }
                }
            } else if (autopilotControl) {
                manualInputState.clear();
                AutopilotCommand autopilotCommand = computeAutopilotCommand();
                if (autopilotCommand.active) {
                    if (autopilotCommand.yawStep != 0.0F) {
                        float nextYaw = getYRot() + autopilotCommand.yawStep;
                        setYRot(nextYaw);
                        setYHeadRot(nextYaw);
                        setYBodyRot(nextYaw);
                        yawRad = getYRot() * (Math.PI / 180.0D);
                        dirX = -Math.sin(yawRad);
                        dirZ = Math.cos(yawRad);
                    }
                    wantsTurn = autopilotCommand.wantsTurn;
                    turnInput = autopilotCommand.turnInput;
                    entityData.set(DATA_ENGINE_GEAR, autopilotCommand.gear.id);
                } else {
                    entityData.set(DATA_ENGINE_GEAR, EngineGear.STOP.id);
                }
                forwardPressedLastTick = false;
                reversePressedLastTick = false;
            } else {
                if (!level().isClientSide && isAutopilotActive() && isAutopilotPaused()) {
                    entityData.set(DATA_ENGINE_GEAR, EngineGear.STOP.id);
                }
                forwardPressedLastTick = false;
                reversePressedLastTick = false;
            }

            EngineGear gear = getEngineGear();
            if (usesCustomGroundDriveModel()) {
                applyCustomGroundDriveModel(new GroundDriveContext(autopilotControl, hasManualInput, wantsForward, wantsReverse, wantsTurn, turnInput, gear));
            } else {
                boolean gearDriving = gear != EngineGear.STOP;
                boolean manualSteering = !autopilotControl && (captain instanceof Player);

                // ---------- (A) 转向：船头主导。仅手动累积 rotationSpeed 驱动 yaw（重船感惯性）----------
                if (manualSteering) {
                    if (wantsTurn) {
                        // turnInput: left(+1)/right(-1)。steerSign=-turnInput → A 让 yaw 减(左转)、D 让 yaw 增(右转)。
                        // 按住时纯累积到 ±MAX_ROT_SPEED(启动渐快=惯性)。不在此处乘水阻——否则累积与衰减每 tick
                        // 打架，稳态被钳在 ROT_ACCELERATION*damp/(1-damp) 远低于 MAX_ROT_SPEED，转向极慢。
                        float steerSign = -turnInput;
                        rotationSpeed += steerSign * ROT_ACCELERATION;
                        rotationSpeed = Mth.clamp(rotationSpeed, -MAX_ROT_SPEED, MAX_ROT_SPEED);
                    } else {
                        rotationSpeed = subtractToZero(rotationSpeed, ROT_RESISTANCE); // 松舵渐归零=停止拖
                    }
                    float newYaw = getYRot() + rotationSpeed;
                    setYRot(newYaw);
                    setYHeadRot(newYaw);
                    setYBodyRot(newYaw);
                    // 绕过 limitTurnRate(本 tick 早期已跑)：同步 lastTickYaw 让下一 tick 读到 delta≈0 不被 0.85°夹死。
                    lastTickYaw = newYaw;
                    yawRad = getYRot() * (Math.PI / 180.0D);
                    dirX = -Math.sin(yawRad);
                    dirZ = Math.cos(yawRad);
                } else {
                    // autopilot：yaw 由 computeAutopilotCommand 自管(上方已 setYRot+重算 dirX/dirZ)，清零转向惯性防残留。
                    rotationSpeed = 0.0F;
                }

                // ---------- (B) 标量速度积分：速度永远沿船头方向。复用 commandedForwardAccel/gear ----------
                double maxForward = maxSpeed;
                double maxReverse = Math.min(maxForward * 0.55D, MAX_REVERSE_BLOCKS_PER_TICK);
                // 上一 tick 速度的带符号模长(前进+/倒车-)：保留动能。不用 current·newDir 投影——急转时船头已转、
                // 投影丢 cos(θ) 会让速度凭空蒸发(叠加转弯掉速→90°转向瞬间掉光)。方向由下方 dir*speed 重新给定。
                double prevSpeedMag = current.horizontalDistance();
                double currentForwardSpeed = (current.x * dirX + current.z * dirZ) < 0.0D ? -prevSpeedMag : prevSpeedMag;
                double targetAccel = gear.accelTarget(sailDeployed);
                double accelDelta = targetAccel - commandedForwardAccel;
                double accelResponse = accelDelta >= 0.0D ? GEAR_ACCEL_RAMP_UP : GEAR_ACCEL_RAMP_DOWN;
                commandedForwardAccel += Mth.clamp(accelDelta, -accelResponse, accelResponse);
                double speed = currentForwardSpeed + commandedForwardAccel;

                double targetForwardSpeed = gear.targetSpeed(maxForward, maxReverse, sailDeployed);
                double gearForwardCap = gear.maxAllowedSpeed(maxForward, maxReverse, sailDeployed);
                speed = applyGearCap(speed, currentForwardSpeed, targetForwardSpeed, gearForwardCap, gear);

                if (!gearDriving) {
                    // STOP 档：乘性阻力(滑停手感,开始快接近停慢) + 线性兜底(乘性永远到不了 0，必停)。
                    speed *= COASTING_DRAG;
                    speed = subtractToZero(speed, COASTING_DECEL);
                } else {
                    speed *= GEAR_DRIVE_DRAG;
                }
                speed *= SPEED_WATER_DAMP;

                // ---------- (C) 转弯掉速（重船感：转得越急掉得越多。投影已不掉速，这里是唯一掉速源）----------
                if (manualSteering && wantsTurn) {
                    double rotMag = Math.abs(rotationSpeed) / MAX_ROT_SPEED;
                    speed *= Mth.lerp(Mth.clamp(rotMag, 0.0D, 1.0D), 1.0D, TURN_SPEED_LOSS_MAX);
                }

                // ---------- (D) 航向滑后于船头（重船感：方向带惯性追船头。autopilot 直通）----------
                // 速度大小=speed(已含动能保留+掉速)，方向用低通从上一 tick 速度方向滑向船头方向。
                double desiredX = dirX * speed;
                double desiredZ = dirZ * speed;
                double nextX;
                double nextZ;
                if (manualSteering && (inertialPlanarVelocity.x != 0.0D || inertialPlanarVelocity.z != 0.0D)) {
                    // 对「方向」做低通(归一化后插值)，再乘回 speed 模长——这样滑后只影响航向、不吃掉速度大小(修起步肉)。
                    double prevLen = inertialPlanarVelocity.horizontalDistance();
                    double prevDirX = prevLen > 1.0E-6D ? inertialPlanarVelocity.x / prevLen : dirX;
                    double prevDirZ = prevLen > 1.0E-6D ? inertialPlanarVelocity.z / prevLen : dirZ;
                    double targetDirX = Math.signum(speed == 0.0D ? 1.0D : speed) * dirX;
                    double targetDirZ = Math.signum(speed == 0.0D ? 1.0D : speed) * dirZ;
                    double blendX = Mth.lerp(HEADING_CATCHUP, prevDirX, targetDirX);
                    double blendZ = Mth.lerp(HEADING_CATCHUP, prevDirZ, targetDirZ);
                    double blendLen = Math.sqrt(blendX * blendX + blendZ * blendZ);
                    if (blendLen > 1.0E-6D) {
                        double mag = Math.abs(speed);
                        nextX = blendX / blendLen * mag;
                        nextZ = blendZ / blendLen * mag;
                    } else {
                        nextX = desiredX;
                        nextZ = desiredZ;
                    }
                } else {
                    nextX = desiredX;
                    nextZ = desiredZ;
                }

                // ---------- (E) 进港限速（autopilot dock 接近，原样保留）+ 写回 ----------
                double finalDockDistance = getFinalDockApproachDistance();
                if (!Double.isNaN(finalDockDistance)) {
                    double maxApproachSpeed = finalDockDistance <= AUTOPILOT_FINAL_STOP_RADIUS
                            ? AUTOPILOT_FINAL_STOP_MAX_SPEED
                            : AUTOPILOT_FINAL_APPROACH_MAX_SPEED;
                    double approachSpeed = Math.sqrt(nextX * nextX + nextZ * nextZ);
                    if (approachSpeed > maxApproachSpeed && approachSpeed > 1.0E-4D) {
                        double scale = maxApproachSpeed / approachSpeed;
                        nextX *= scale;
                        nextZ *= scale;
                    }
                }

                inertialPlanarVelocity = new Vec3(nextX, 0.0D, nextZ);
                setDeltaMovement(nextX, current.y, nextZ);
            }
        } else if (!level().isClientSide) {
            if (isOutsidePrimaryTravelMedium()) {
                nonWaterTicks++;
                // Avoid one-frame water-state flicker instantly killing coasting momentum.
                if (nonWaterTicks > 20 && (onGround() || isInLava())) {
                    inertialPlanarVelocity = Vec3.ZERO;
                    commandedForwardAccel = 0.0D;
                }
            } else {
                nonWaterTicks = 0;
            }
        }
        if ((!level().isClientSide || clientLocalDrive) && skipVanillaBoatMovementTick) {
            move(MoverType.SELF, getDeltaMovement());
        }
        if (!level().isClientSide) {
            // 服务端权威移动后，把水平前向速度(格/tick)同步给客户端供 HUD 显示。
            // 覆盖所有出口(手动 692 / 自定义地面模型 / autopilot)——它们都已把本 tick 位移写进 deltaMovement。
            // 客户端 deltaMovement 恒=ZERO，只能靠这个同步值(与马车 DATA_CURRENT_SPEED 口径一致)。
            Vec3 vel = getDeltaMovement();
            double yawRad = getYRot() * (Math.PI / 180.0D);
            double signedForward = vel.x * -Math.sin(yawRad) + vel.z * Math.cos(yawRad);
            entityData.set(DATA_CURRENT_SPEED, (float) signedForward);
        }
        if (!level().isClientSide && (tickCount <= 1 || tickCount % BLUEMAP_BOAT_SYNC_INTERVAL_TICKS == 0)) {
            BlueMapIntegration.syncBoat(this);
        }
        if (!level().isClientSide && tickCount % PICKUP_LOAD_SCAN_INTERVAL_TICKS == 0) {
            tickPickupLoadDetection();
        }
        tickWakeEffects();
        if (!level().isClientSide) {
            tickGearShiftSound();
            tickRamDamage();
        }
    }

    /**
     * 撞击伤害(借鉴 smallships):巡航时撞到非乘客生物 → 按船速造成伤害 + 顺船头方向击退,给撞角重量感。
     * 仅服务端;靠 vanilla 伤害无敌帧天然节流,不额外加冷却。
     */
    private void tickRamDamage() {
        float speed = Math.abs(getCurrentSpeedForHud());
        if (speed < RAM_SPEED_THRESHOLD || !isPrimaryTravelMedium()) {
            return;
        }
        java.util.List<LivingEntity> victims = level().getEntitiesOfClass(LivingEntity.class,
                getBoundingBox().inflate(0.3D, 0.0D, 0.3D),
                e -> e.isAlive() && !e.isPassengerOfSameVehicle(this) && !hasPassenger(e));
        if (victims.isEmpty()) {
            return;
        }
        double yawRad = getYRot() * (Math.PI / 180.0D);
        double dirX = -Math.sin(yawRad);
        double dirZ = Math.cos(yawRad);
        float damage = speed * RAM_DAMAGE_PER_SPEED;
        double knockback = 0.5D + speed * 1.1D;
        for (LivingEntity victim : victims) {
            victim.hurt(damageSources().generic(), damage);
            victim.push(dirX * knockback, 0.15D, dirZ * knockback);
        }
    }

    /**
     * 服务端检测挡位变化并播切挡音效(权威挡位 DATA_ENGINE_GEAR 由 vanilla 广播给附近客户端)。
     * pitch 随挡位升高而升高(借鉴 smallships:大挡音调高,给操作清晰反馈)。覆盖手动/autopilot/hold-to-drive 所有改挡来源。
     */
    private void tickGearShiftSound() {
        int gearId = getEngineGear().id;
        if (lastGearIdForSound == Integer.MIN_VALUE) {
            lastGearIdForSound = gearId; // 首次只记录,不播(避免出生/加载即响)
            return;
        }
        if (gearId != lastGearIdForSound) {
            lastGearIdForSound = gearId;
            // 挡位 id 范围约 [reverse..forwardMax],映射 pitch 0.7~1.5:挡越高音越高。
            float pitch = Mth.clamp(0.9F + gearId * 0.12F, 0.6F, 1.6F);
            level().playSound(null, getX(), getY(), getZ(), SoundEvents.LEVER_CLICK,
                    SoundSource.NEUTRAL, 0.5F, pitch);
        }
    }

    /**
     * 航行视听特效（移植自 smallships AbstractSailShip.tick 的 WaterSplash + GENERIC_SWIM）：
     * 船以一定速度在水面航行时，船尾两侧生成水花/气泡粒子并循环播放划水音。
     * 速度判定用同步的 getCurrentSpeedForHud()（格/tick 前向，两端都有值）——不能用 deltaMovement，
     * 因为客户端 deltaMovement 恒=ZERO（服务端权威移动）。粒子只在客户端生成（本地表现，无需联网），
     * 音效只在服务端 playSound(null,...) 触发由 vanilla 广播给附近客户端。
     */
    private void tickWakeEffects() {
        float speed = Math.abs(getCurrentSpeedForHud());
        if (speed < WAKE_EFFECT_SPEED_THRESHOLD || !isPrimaryTravelMedium()) {
            return;
        }

        double yawRad = getYRot() * (Math.PI / 180.0D);
        double dirX = -Math.sin(yawRad);
        double dirZ = Math.cos(yawRad);
        // 船尾方向（与前进相反）+ 两侧偏移
        double sternX = -dirX;
        double sternZ = -dirZ;
        double sideX = -dirZ;
        double sideZ = dirX;
        double waterY = getY() + 0.1D;

        if (level().isClientSide) {
            for (int i = 0; i < 2; i++) {
                double back = 1.4D + random.nextDouble() * 0.9D;
                double side = (random.nextDouble() - 0.5D) * 1.6D;
                double px = getX() + sternX * back + sideX * side;
                double pz = getZ() + sternZ * back + sideZ * side;
                level().addParticle(ParticleTypes.SPLASH, px, waterY + 0.2D, pz, 0.0D, 0.0D, 0.0D);
                level().addParticle(ParticleTypes.BUBBLE, px, waterY, pz,
                        sternX * 0.04D, 0.0D, sternZ * 0.04D);
            }
            // 船头破浪小水花
            double bowX = getX() + dirX * 1.3D;
            double bowZ = getZ() + dirZ * 1.3D;
            level().addParticle(ParticleTypes.SPLASH, bowX, waterY + 0.3D, bowZ, 0.0D, 0.0D, 0.0D);
        } else if (tickCount % WAKE_SOUND_INTERVAL_TICKS == 0) {
            level().playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_SWIM,
                    SoundSource.NEUTRAL, 0.06F, 0.8F + random.nextFloat() * 0.4F);
        }
    }

    /**
     * 进-zone 自提装货检测（真人自提=玩家把空车开进港口 zone；自动自提=系统空车驶入 zone）。
     * 仅空车触发（hasCargo()=false），避免载货途经其它 zone 被误装；幂等（装过的锁定单已转 IN_TRANSIT，重扫空转）。
     */
    private void tickPickupLoadDetection() {
        if (level().isClientSide || hasCargo()) {
            return;
        }
        BlockPos hubPos = findTransportHubZoneContains(position());
        if (hubPos == null) {
            return;
        }
        DockBlockEntity hub = getTransportHub(hubPos);
        if (hub != null) {
            hub.tryLoadPickupCargo(this);
        }
    }

    protected boolean skipsVanillaBoatMovementTick() {
        // 恒 true：帆船完全接管移动(floatBoat 浮力→applyTransportSupport、controlBoat 操控→自定义物理、
        // move 自己调)，绕开 vanilla Boat 在 isControlledByLocalInstance() 分支里的那套，消除双重浮力/双重
        // 转向/划桨动画。两端都绕开(手动 local-control 端跑本地预测物理、autopilot 服务端权威)。
        return true;
    }

    protected void tickBaseEntityWithoutBoatMovement() {
        if (getHurtTime() > 0) {
            setHurtTime(getHurtTime() - 1);
        }
        if (getDamage() > 0.0F) {
            setDamage(getDamage() - 1.0F);
        }
        baseTick();
    }

    protected boolean usesCustomGroundDriveModel() {
        return false;
    }

    protected boolean usesHoldToDriveControls() {
        return false;
    }

    protected void applyCustomGroundDriveModel(GroundDriveContext context) {
    }

    protected final void applyCustomGroundDriveResult(Vec3 nextDelta) {
        Vec3 motion = nextDelta == null ? Vec3.ZERO : nextDelta;
        inertialPlanarVelocity = new Vec3(motion.x, 0.0D, motion.z);
        commandedForwardAccel = 0.0D;
        setDeltaMovement(motion);
    }

    protected void applyTransportSupport() {
        applyFallbackBuoyancy();
    }

    protected boolean isPrimaryTravelMedium() {
        // 不能用 vanilla isInWater()：skipsVanillaBoatMovementTick()=true 绕开了 Boat.tick/Entity.tick，
        // vanilla 的 updateInWaterStateAndDoFluidPushing 不再跑→wasTouchingWater 恒 false→isInWater() 恒 false。
        // 改自给自足扫流体判定（照抄 smallships AbstractWaterVehicle.checkInWater）：船体包围盒底层一格
        // 内只要有水方块、且船体 minY 低于该水面高度即「在水里」。
        return checkInWater();
    }

    protected boolean isOutsidePrimaryTravelMedium() {
        return !checkInWater();
    }

    /**
     * 自给自足的「在水里」判定，不依赖 vanilla 水状态字段(被 skipsVanillaBoatMovementTick 绕开)。
     * 参照 smallships AbstractWaterVehicle.checkInWater：扫包围盒底层一格的水方块。
     * 关键差异（实测坐实，2026-06-17）：判定从 smallships 的 `box.minY < surfaceY` 改为
     * `box.minY < surfaceY + WATER_DETECT_TOLERANCE`。原因：本帆船自定义浮力把 getY()(==box.minY) 顶到
     * 精确等于水面高度(实测三者都=62.889)，严格小于恒 false → 两端都不进物理 → 船完全不动。smallships 船吃水
     * 深所以严格小于成立，我们的船浮在水面上沿，必须给向下容差。岸上无水方块，sawWater=false 不受影响。
     */
    private boolean checkInWater() {
        AABB box = getBoundingBox();
        int minX = Mth.floor(box.minX);
        int maxX = Mth.ceil(box.maxX);
        int minY = Mth.floor(box.minY) - 1;
        int maxYExclusive = Mth.ceil(box.minY + 0.001D);
        int minZ = Mth.floor(box.minZ);
        int maxZ = Mth.ceil(box.maxZ);

        boolean inWater = false;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxYExclusive; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    cursor.set(x, y, z);
                    var fluid = level().getFluidState(cursor);
                    if (fluid.is(FluidTags.WATER)) {
                        double surfaceY = y + fluid.getHeight(level(), cursor);
                        inWater |= box.minY < surfaceY + WATER_DETECT_TOLERANCE;
                    }
                }
            }
        }
        return inWater;
    }

    /** 把 value 朝 0 衰减固定步长 step（不越过 0）。照抄 smallships Utils.subtractToZero。 */
    private static float subtractToZero(float value, float step) {
        if (value > 0.0F) {
            return Math.max(value - step, 0.0F);
        }
        if (value < 0.0F) {
            return Math.min(value + step, 0.0F);
        }
        return 0.0F;
    }

    private static double subtractToZero(double value, double step) {
        if (value > 0.0D) {
            return Math.max(value - step, 0.0D);
        }
        if (value < 0.0D) {
            return Math.min(value + step, 0.0D);
        }
        return 0.0D;
    }

    /**
     * 挡位速度上限钳制（标量版，从旧矢量物理 676-694 行原样搬来，语义不变）：
     * 保持加速手感——不瞬间砸到 gear cap，但阻止超过 cap 继续加速；降挡过顶时不瞬停、靠阻力自然回落。
     */
    private static double applyGearCap(double speed, double currentForwardSpeed,
                                       double targetForwardSpeed, double gearForwardCap, EngineGear gear) {
        if (gear == EngineGear.STOP || gearForwardCap <= 0.0D) {
            return speed;
        }
        if (targetForwardSpeed > 0.0D) {
            if (currentForwardSpeed >= gearForwardCap && speed > currentForwardSpeed) {
                return currentForwardSpeed;
            }
            if (currentForwardSpeed < gearForwardCap && speed > gearForwardCap) {
                return gearForwardCap;
            }
        } else if (targetForwardSpeed < 0.0D) {
            double reverseCap = -gearForwardCap;
            if (currentForwardSpeed <= reverseCap && speed < currentForwardSpeed) {
                return currentForwardSpeed;
            }
            if (currentForwardSpeed > reverseCap && speed < reverseCap) {
                return reverseCap;
            }
        }
        return speed;
    }

    protected void applyFallbackBuoyancy() {
        double waterSurfaceY = sampleNearbyWaterSurfaceY();
        if (Double.isNaN(waterSurfaceY)) {
            return;
        }

        Vec3 motion = getDeltaMovement();
        double depthFromSurface = waterSurfaceY - getY();
        double lift = depthFromSurface > 0.0D
                ? Mth.clamp(0.02D + depthFromSurface * 0.08D, 0.02D, 0.14D)
                : Mth.clamp(depthFromSurface * 0.05D, -0.03D, 0.01D);

        double adjustedY = motion.y * 0.75D + lift;
        adjustedY = Mth.clamp(adjustedY, -0.01D, 0.16D);
        if (depthFromSurface > -0.20D && adjustedY < -0.002D) {
            adjustedY = -0.002D;
        }
        setDeltaMovement(motion.x, adjustedY, motion.z);
    }

    private void limitTurnRate() {
        Entity controller = getControllingPassenger();
        if (controller == null) {
            lastTickYaw = getYRot();
            return;
        }
        float yaw = getYRot();
        float delta = Mth.wrapDegrees(yaw - lastTickYaw);
        float clamped = Mth.clamp(delta, -MAX_TURN_DEGREES_PER_TICK, MAX_TURN_DEGREES_PER_TICK);
        if (clamped != delta) {
            float targetYaw = lastTickYaw + clamped;
            setYRot(targetYaw);
            setYHeadRot(targetYaw);
            setYBodyRot(targetYaw);
            lastTickYaw = targetYaw;
            return;
        }
        lastTickYaw = yaw;
    }

    private void cleanupLegacyNightLightBlocks() {
        if (legacyLightCleanupTicks <= 0) {
            return;
        }
        legacyLightCleanupTicks--;

        BlockPos center = blockPosition().above();
        for (int dx = -LEGACY_LIGHT_CLEAN_RADIUS; dx <= LEGACY_LIGHT_CLEAN_RADIUS; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -LEGACY_LIGHT_CLEAN_RADIUS; dz <= LEGACY_LIGHT_CLEAN_RADIUS; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (level().getBlockState(pos).is(net.minecraft.world.level.block.Blocks.LIGHT)) {
                        level().removeBlock(pos, false);
                    }
                }
            }
        }
    }

    private double sampleNearbyWaterSurfaceY() {
        AABB box = getBoundingBox();
        int minX = Mth.floor(box.minX) - 1;
        int maxX = Mth.floor(box.maxX) + 1;
        int minZ = Mth.floor(box.minZ) - 1;
        int maxZ = Mth.floor(box.maxZ) + 1;
        int minY = Mth.floor(box.minY) - 2;
        int maxY = Mth.floor(box.maxY) + 1;

        double highestSurface = Double.NEGATIVE_INFINITY;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level().getFluidState(pos).is(FluidTags.WATER)) {
                        continue;
                    }
                    double surfaceY = y + level().getFluidState(pos).getHeight(level(), pos);
                    if (surfaceY > highestSurface) {
                        highestSurface = surfaceY;
                    }
                }
            }
        }

        return highestSurface == Double.NEGATIVE_INFINITY ? Double.NaN : highestSurface;
    }

    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        for (Entity passenger : getPassengers()) {
            if (getSeatFor(passenger) == 0 && passenger instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    @Override
    public boolean isControlledByLocalInstance() {
        // 手动驾驶=客户端本地控制(super=vanilla Boat：驾驶者客户端 true、服务端 false、旁观/无人 false)，
        // 让驾驶者客户端跑本地物理预测=零延迟跟手(双端跑同一份确定性物理，见 tickSailDrive)。
        // autopilot=服务端权威(返 false)：vanilla else 分支服务端 setDeltaMovement+move 权威驱动、客户端
        // tickNetworkLerp 插值跟随，保 rail 返航+防沉。照抄马车 isLocalInstanceControlAuthoritative。
        return isLocalInstanceControlAuthoritative(super.isControlledByLocalInstance(), isAutopilotActive());
    }

    /**
     * autopilot 时交还服务端权威(返 false)，手动时保留 vanilla 本地控制维持客户端预测手感。
     * 照抄 CarriageEntity.isLocalInstanceControlAuthoritative。
     */
    private static boolean isLocalInstanceControlAuthoritative(boolean vanillaLocalControl, boolean autopilotActive) {
        return !autopilotActive && vanillaLocalControl;
    }

    // ===== 自管网络插值（覆写 vanilla Boat 的 tickLerp/lerpTo，照抄 CarriageEntity 1087-1123）=====
    // vanilla Boat.tickLerp 在 local-control 端仍会 setDeltaMovement 干扰本地预测，且其 lerp 字段 private 不可控，
    // 故覆写：手动 local-control 端清 0 步即时预测、autopilot 服务端权威时插值跟随。

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int posRotationIncrements, boolean teleport) {
        this.sailboatLerpX = x;
        this.sailboatLerpY = y;
        this.sailboatLerpZ = z;
        this.sailboatLerpYaw = yRot;
        this.sailboatLerpPitch = xRot;
        this.sailboatLerpSteps = networkLerpSteps(posRotationIncrements, isAutopilotActive());
    }

    private void tickNetworkLerp() {
        boolean autopilotNetworkAuthoritative = isAutopilotActive();
        if (isControlledByLocalInstance()) {
            this.sailboatLerpSteps = lerpStepsAfterLocalControl(this.sailboatLerpSteps, autopilotNetworkAuthoritative);
            if (!autopilotNetworkAuthoritative) {
                syncPacketPositionCodec(getX(), getY(), getZ());
            }
        }
        if (this.sailboatLerpSteps > 0) {
            double nextX = getX() + (this.sailboatLerpX - getX()) / (double) this.sailboatLerpSteps;
            double nextY = getY() + (this.sailboatLerpY - getY()) / (double) this.sailboatLerpSteps;
            double nextZ = getZ() + (this.sailboatLerpZ - getZ()) / (double) this.sailboatLerpSteps;
            double nextYawDelta = Mth.wrapDegrees(this.sailboatLerpYaw - (double) getYRot());
            setYRot((float) ((double) getYRot() + nextYawDelta / (double) this.sailboatLerpSteps));
            setXRot((float) ((double) getXRot() + (this.sailboatLerpPitch - (double) getXRot()) / (double) this.sailboatLerpSteps));
            --this.sailboatLerpSteps;
            setPos(nextX, nextY, nextZ);
            setRot(getYRot(), getXRot());
        }
    }

    private static int networkLerpSteps(int posRotationIncrements, boolean autopilotActive) {
        return autopilotActive ? 1 : NETWORK_LERP_STEPS;
    }

    private static int lerpStepsAfterLocalControl(int currentLerpSteps, boolean autopilotActive) {
        return autopilotActive ? currentLerpSteps : 0;
    }

    /**
     * HUD 用的当前前向速度(格/tick)。帆船服务端权威、客户端 deltaMovement 恒=ZERO，
     * 故读服务端同步的 DATA_CURRENT_SPEED 而非客户端本地 deltaMovement(仿马车 getCurrentSpeedForHud)。
     */
    public float getCurrentSpeedForHud() {
        return entityData.get(DATA_CURRENT_SPEED);
    }

    @Override
    public void positionRider(Entity passenger, MoveFunction moveFunction) {
        if (!hasPassenger(passenger)) {
            return;
        }

        int seatIndex = getSeatFor(passenger);
        if (seatIndex < 0 || seatIndex >= PASSENGER_OFFSETS.length) {
            seatIndex = Math.min(getPassengers().indexOf(passenger), PASSENGER_OFFSETS.length - 1);
        }

        Vec3 seat = PASSENGER_OFFSETS[seatIndex];
        float yawRad = -getYRot() * ((float) Math.PI / 180F);
        double sin = Math.sin(yawRad);
        double cos = Math.cos(yawRad);

        double x = getX() + seat.x * cos - seat.z * sin;
        double y = getY() + getPassengersRidingOffset() + seat.y + passenger.getMyRidingOffset();
        double z = getZ() + seat.x * sin + seat.z * cos;

        moveFunction.accept(passenger, x, y, z);
        passenger.setYBodyRot(getYRot());
    }

    @Override
    public double getPassengersRidingOffset() {
        return 0.30D;
    }

    @Override
    public Item getDropItem() {
        return ModItems.SAILBOAT_ITEM.get();
    }

    @Override
    protected void destroy(DamageSource source) {
        if (!level().isClientSide) {
            pendingBlueMapRemoval = true;
            BlueMapIntegration.removeBoat(level(), getUUID());
            Containers.dropContents(level(), blockPosition(), container);
            container.clearContent();
        }
        super.destroy(source);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        net.minecraft.world.ContainerHelper.saveAllItems(tag, inventory);

        ListTag seats = new ListTag();
        for (Map.Entry<UUID, Integer> entry : seatAssignments.entrySet()) {
            CompoundTag seatTag = new CompoundTag();
            seatTag.putUUID("Player", entry.getKey());
            seatTag.putInt("Seat", entry.getValue());
            seats.add(seatTag);
        }
        tag.put("SeatAssignments", seats);
        tag.putBoolean("SailDeployed", isSailDeployed());
        tag.putInt("HandlingPreset", getHandlingPreset().id);
        tag.putInt("EngineGear", getEngineGear().id);
        tag.putBoolean("AutopilotActive", isAutopilotActive());
        tag.putBoolean("AutopilotPaused", isAutopilotPaused());
        tag.putInt("AutopilotTargetIndex", autopilotTargetIndex);
        tag.putString("AutopilotRouteName", autopilotRouteName);
        tag.putString("PendingShipperName", pendingShipperName);
        tag.putString("AutopilotShipmentShipperName", autopilotShipmentShipperName);
        tag.putString("AutopilotShipmentStartDockName", autopilotShipmentStartDockName);
        tag.putString("AutopilotShipmentEndDockName", autopilotShipmentEndDockName);
        tag.putString("AutopilotShipmentRecipientName", autopilotShipmentRecipientName);
        tag.putString("AutopilotShipmentRecipientUuid", autopilotShipmentRecipientUuid);
        tag.putString("AutopilotShipmentPurchaseOrderId", autopilotShipmentPurchaseOrderId);
        tag.putString("AutopilotShipmentShippingOrderId", autopilotShipmentShippingOrderId);
        tag.putLong("AutopilotShipmentDepartureEpochMillis", autopilotShipmentDepartureEpochMillis);
        tag.putDouble("AutopilotShipmentDistanceMeters", autopilotShipmentDistanceMeters);
        tag.putBoolean("AutopilotAllowNonOrderAutoReturn", autopilotAllowNonOrderAutoReturn);
        tag.putBoolean("AutopilotAllowNonOrderAutoUnload", autopilotAllowNonOrderAutoUnload);
        tag.putBoolean("AutopilotReturnTrip", autopilotReturnTrip);
        ListTag manifestTag = new ListTag();
        for (ShipmentManifestEntry entry : autopilotShipmentManifest) {
            manifestTag.add(entry.save());
        }
        tag.put("AutopilotShipmentManifest", manifestTag);
        tag.putString("OwnerName", ownerName == null ? "" : ownerName);
        tag.putString("OwnerUuid", ownerUuid == null ? "" : ownerUuid);
        tag.putInt("RentalPrice", rentalPrice);
        if (autopilotDestinationDockHintPos != null) {
            tag.putLong("AutopilotDestinationDockHintPos", autopilotDestinationDockHintPos.asLong());
        }
        tag.putInt("SelectedRouteIndex", selectedRouteIndex);
        RouteNbtUtil.writeRoutes(tag, "RouteCatalog", routeCatalog);
        if (routeDockPos != null) {
            tag.putLong("RouteDockPos", routeDockPos.asLong());
        }

        ListTag routeTag = new ListTag();
        for (Vec3 waypoint : autopilotRoute) {
            CompoundTag waypointTag = new CompoundTag();
            waypointTag.putDouble("X", waypoint.x);
            waypointTag.putDouble("Y", waypoint.y);
            waypointTag.putDouble("Z", waypoint.z);
            routeTag.add(waypointTag);
        }
        tag.put("AutopilotRoute", routeTag);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        net.minecraft.world.ContainerHelper.loadAllItems(tag, inventory);

        seatAssignments.clear();
        ListTag seats = tag.getList("SeatAssignments", Tag.TAG_COMPOUND);
        for (Tag seatTag : seats) {
            if (seatTag instanceof CompoundTag compound && compound.hasUUID("Player")) {
                seatAssignments.put(compound.getUUID("Player"), compound.getInt("Seat"));
            }
        }
        if (tag.contains("SailDeployed")) {
            entityData.set(DATA_SAIL_DEPLOYED, tag.getBoolean("SailDeployed"));
        }
        if (tag.contains("HandlingPreset")) {
            entityData.set(DATA_HANDLING_PRESET, HandlingPreset.byId(tag.getInt("HandlingPreset")).id);
        }
        if (tag.contains("EngineGear")) {
            entityData.set(DATA_ENGINE_GEAR, EngineGear.byId(tag.getInt("EngineGear")).id);
        }
        routeCatalog.clear();
        routeCatalog.addAll(RouteNbtUtil.readRoutes(tag, "RouteCatalog"));
        selectedRouteIndex = routeCatalog.isEmpty() ? 0 : Mth.clamp(tag.getInt("SelectedRouteIndex"), 0, routeCatalog.size() - 1);
        routeDockPos = tag.contains("RouteDockPos") ? BlockPos.of(tag.getLong("RouteDockPos")) : null;
        autopilotRoute.clear();
        ListTag routeTag = tag.getList("AutopilotRoute", Tag.TAG_COMPOUND);
        for (Tag entry : routeTag) {
            if (entry instanceof CompoundTag waypointTag) {
                autopilotRoute.add(new Vec3(
                        waypointTag.getDouble("X"),
                        waypointTag.getDouble("Y"),
                        waypointTag.getDouble("Z")
                ));
            }
        }
        autopilotTargetIndex = Mth.clamp(tag.getInt("AutopilotTargetIndex"), 0, Math.max(autopilotRoute.size() - 1, 0));
        autopilotRouteName = tag.getString("AutopilotRouteName");
        pendingShipperName = tag.getString("PendingShipperName");
        autopilotShipmentShipperName = tag.getString("AutopilotShipmentShipperName");
        autopilotShipmentStartDockName = tag.getString("AutopilotShipmentStartDockName");
        autopilotShipmentEndDockName = tag.getString("AutopilotShipmentEndDockName");
        autopilotShipmentRecipientName = tag.getString("AutopilotShipmentRecipientName");
        autopilotShipmentRecipientUuid = tag.getString("AutopilotShipmentRecipientUuid");
        autopilotShipmentPurchaseOrderId = tag.getString("AutopilotShipmentPurchaseOrderId");
        autopilotShipmentShippingOrderId = tag.getString("AutopilotShipmentShippingOrderId");
        autopilotShipmentDepartureEpochMillis = Math.max(0L, tag.getLong("AutopilotShipmentDepartureEpochMillis"));
        autopilotShipmentDistanceMeters = Math.max(0.0D, tag.getDouble("AutopilotShipmentDistanceMeters"));
        autopilotAllowNonOrderAutoReturn = tag.getBoolean("AutopilotAllowNonOrderAutoReturn");
        autopilotAllowNonOrderAutoUnload = tag.getBoolean("AutopilotAllowNonOrderAutoUnload");
        autopilotReturnTrip = tag.getBoolean("AutopilotReturnTrip");
        autopilotShipmentManifest.clear();
        ListTag manifestTag = tag.getList("AutopilotShipmentManifest", Tag.TAG_COMPOUND);
        for (Tag raw : manifestTag) {
            if (raw instanceof CompoundTag compound) {
                autopilotShipmentManifest.add(ShipmentManifestEntry.load(compound));
            }
        }
        if (autopilotShipmentManifest.isEmpty() && (!autopilotShipmentPurchaseOrderId.isBlank()
                || !autopilotShipmentShippingOrderId.isBlank()
                || !autopilotShipmentRecipientUuid.isBlank()
                || !autopilotShipmentRecipientName.isBlank())) {
            autopilotShipmentManifest.add(new ShipmentManifestEntry(
                    "",
                    ItemStack.EMPTY,
                    autopilotShipmentPurchaseOrderId,
                    autopilotShipmentShippingOrderId,
                    autopilotShipmentRecipientUuid,
                    autopilotShipmentRecipientName,
                    0
            ));
        }
        ownerName = tag.getString("OwnerName");
        ownerUuid = tag.getString("OwnerUuid");
        int loadedRentalPrice = tag.contains("RentalPrice", Tag.TAG_INT) ? tag.getInt("RentalPrice") : DEFAULT_RENTAL_PRICE;
        rentalPrice = clampRentalPrice(loadedRentalPrice);
        entityData.set(DATA_RENTAL_PRICE, rentalPrice);
        autopilotDestinationDockHintPos = tag.contains("AutopilotDestinationDockHintPos")
                ? BlockPos.of(tag.getLong("AutopilotDestinationDockHintPos"))
                : null;
        entityData.set(DATA_AUTOPILOT_PAUSED, tag.getBoolean("AutopilotPaused"));
        boolean autopilotActive = tag.getBoolean("AutopilotActive") && !autopilotRoute.isEmpty();
        entityData.set(DATA_AUTOPILOT_ACTIVE, autopilotActive);
        if (!autopilotActive) {
            clearAutopilotShipmentContext();
        }
        updateRouteSyncData();
        syncSeatEntityData();
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!level().isClientSide && level() instanceof ServerLevel serverLevel) {
            clearAutopilotForcedChunks(serverLevel);
        }
        if (!level().isClientSide
                && (reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED
                    || reason == RemovalReason.CHANGED_DIMENSION)) {
            // 载具被破坏/移除：刷新掉它的物流轨迹，避免网页地图残留。
            // activeTraceId() 解析真实轨迹 id：订单船=shippingOrderId、手动船=manual-uuid。
            // 旧实现写死 manualTraceId，导致跑订单的船被破坏后 shippingOrderId 轨迹永久残留在 webmap。
            com.monpai.sailboatmod.market.logistics.ShippingTraceService.removeTrace(level(), activeTraceId());
            // 兼容兜底：若船既跑过订单又留过 manual 轨迹，连 manual id 一并清掉。
            com.monpai.sailboatmod.market.logistics.ShippingTraceService.removeTrace(
                    level(), com.monpai.sailboatmod.market.logistics.ShippingTraceService.manualTraceId(getUUID()));
        }
        if (!level().isClientSide
                && (reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED)) {
            // 载具在途被破坏/卸载兜底：回滚/退款订单货 + 把订单轨迹标 FAILED（webmap 不再可见）。
            // rollbackMarketShipment 内部：空 manifest 早退；可回滚则把货退回市场（船容器随之清空），
            // 不可回滚则把货 loadCargo 留在船上——之后 KILLED 的 dropContents 才掉落，避免货物双重处理/凭空消失。
            // CHANGED_DIMENSION 不回滚（船随维度迁移，运输继续）。
            rollbackMarketShipment();
            clearAutopilotShipmentContext(); // 清 manifest/订单字段，保证回滚幂等（防任何路径重复退款）
        }
        if (!level().isClientSide) {
            if (pendingBlueMapRemoval || reason == RemovalReason.KILLED) {
                if (!pendingBlueMapRemoval) {
                    BlueMapIntegration.removeBoat(level(), getUUID());
                    Containers.dropContents(level(), blockPosition(), container);
                    container.clearContent();
                }
            } else if (reason == RemovalReason.CHANGED_DIMENSION) {
                BlueMapIntegration.removeBoat(level(), getUUID());
            } else {
                BlueMapIntegration.syncBoat(this);
            }
        }
        super.remove(reason);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "sail_state", 0, state ->
                state.setAndContinue(isSailDeployed() ? SAIL_UP_ANIMATION : SAIL_DOWN_ANIMATION)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return animatableCache;
    }

    @Override
    public Component getDisplayName() {
        return getStorageMenuTitle();
    }

    public void openStorage(Player player) {
        if (!level().isClientSide && player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openScreen(serverPlayer, this);
        }
    }

    protected boolean canPlayerAccessStorage(Player player) {
        return true;
    }

    protected boolean canPlayerBoard(Player player) {
        return true;
    }

    protected Component getStorageMenuTitle() {
        return Component.translatable("container.sailboatmod.sailboat_storage");
    }

    public Component getInfoScreenTitle() {
        return Component.translatable("screen.sailboatmod.info");
    }

    public boolean showsSailControl() {
        return true;
    }

    public int getStorageSlotCount() {
        return INVENTORY_SIZE;
    }

    public boolean requestSeat(Player player, int requestedSeat) {
        if (!player.isPassengerOfSameVehicle(this)) {
            return false;
        }
        if (requestedSeat < 0 || requestedSeat >= SEAT_COUNT) {
            return false;
        }
        UUID playerId = player.getUUID();
        if (isSeatTaken(requestedSeat, playerId)) {
            return false;
        }
        seatAssignments.put(playerId, requestedSeat);
        syncSeatEntityData();
        return true;
    }

    public int getSeatFor(Entity passenger) {
        int synced = seatFromEntityData(passenger.getId());
        if (synced >= 0) {
            return synced;
        }

        Integer mapped = seatAssignments.get(passenger.getUUID());
        if (mapped != null) {
            return mapped;
        }

        int fallback = getPassengers().indexOf(passenger);
        if (!level().isClientSide && fallback >= 0 && fallback < SEAT_COUNT && !isSeatTaken(fallback, passenger.getUUID())) {
            seatAssignments.put(passenger.getUUID(), fallback);
            syncSeatEntityData();
            return fallback;
        }
        return fallback;
    }

    public boolean isCaptain(Player player) {
        return getSeatFor(player) == 0;
    }

    public boolean isSailDeployed() {
        return entityData.get(DATA_SAIL_DEPLOYED);
    }

    public boolean isAutopilotActive() {
        return entityData.get(DATA_AUTOPILOT_ACTIVE);
    }

    public boolean isAutopilotPaused() {
        return entityData.get(DATA_AUTOPILOT_PAUSED);
    }

    public int getRouteCount() {
        return entityData.get(DATA_ROUTE_COUNT);
    }

    public int getSelectedRouteIndex() {
        return entityData.get(DATA_ROUTE_INDEX);
    }

    public String getSelectedRouteName() {
        return entityData.get(DATA_ROUTE_NAME);
    }

    public String getAutopilotRouteName() {
        return autopilotRouteName;
    }

    public String getOwnerName() {
        return ownerName == null || ownerName.isBlank() ? "-" : ownerName;
    }

    public String getOwnerUuid() {
        return ownerUuid == null ? "" : ownerUuid;
    }

    public int getRentalPrice() {
        return Mth.clamp(entityData.get(DATA_RENTAL_PRICE), MIN_RENTAL_PRICE, MAX_RENTAL_PRICE);
    }

    public boolean isAvailableForRent() {
        return getRentalPrice() >= 0;
    }

    public boolean isOwnedBy(Player player) {
        if (player == null) {
            return false;
        }
        String currentOwner = getOwnerUuid();
        return !currentOwner.isBlank() && currentOwner.equals(player.getUUID().toString());
    }

    public void setRentalPrice(int newPrice) {
        if (level().isClientSide) {
            return;
        }
        int clamped = clampRentalPrice(newPrice);
        rentalPrice = clamped;
        entityData.set(DATA_RENTAL_PRICE, clamped);
    }

    public void initializeOwnerIfAbsent(Player player) {
        if (level().isClientSide || player == null) {
            return;
        }
        if (ownerUuid != null && !ownerUuid.isBlank()) {
            return;
        }
        ownerUuid = player.getUUID().toString();
        ownerName = player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName();
    }

    private static int clampRentalPrice(int value) {
        return Mth.clamp(value, MIN_RENTAL_PRICE, MAX_RENTAL_PRICE);
    }

    public void setPendingShipper(@Nullable String shipperName) {
        pendingShipperName = shipperName == null ? "" : shipperName.trim();
    }

    public void setPendingMarketDelivery(@Nullable String recipientName, @Nullable String recipientUuid,
                                         @Nullable String purchaseOrderId, @Nullable String shippingOrderId) {
        setPendingShipmentManifest(List.of(new ShipmentManifestEntry(
                "",
                ItemStack.EMPTY,
                purchaseOrderId,
                shippingOrderId,
                recipientUuid,
                recipientName,
                0
        )));
    }

    public void clearPendingMarketDelivery() {
        autopilotShipmentManifest.clear();
        syncPrimaryShipmentFieldsFromManifest();
    }

    public void setPendingShipmentManifest(List<ShipmentManifestEntry> manifest) {
        autopilotShipmentManifest.clear();
        autopilotAllowNonOrderAutoReturn = false;
        autopilotAllowNonOrderAutoUnload = false;
        if (manifest != null) {
            for (ShipmentManifestEntry entry : manifest) {
                if (entry == null) {
                    continue;
                }
                autopilotShipmentManifest.add(entry);
            }
        }
        syncPrimaryShipmentFieldsFromManifest();
    }

    /**
     * 多站连运剪枝：只更新留车 manifest，**不重置**非订单自动卸货/返航开关。
     * 与 setPendingShipmentManifest 的区别——后者用于发新单（清旧标志），本方法用于中途剪枝（保留开关）。
     */
    public void pruneShipmentManifest(List<ShipmentManifestEntry> manifest) {
        autopilotShipmentManifest.clear();
        if (manifest != null) {
            for (ShipmentManifestEntry entry : manifest) {
                if (entry == null) {
                    continue;
                }
                autopilotShipmentManifest.add(entry);
            }
        }
        syncPrimaryShipmentFieldsFromManifest();
    }

    public boolean hasCargo() {
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public List<ShipmentManifestEntry> getPendingShipmentManifest() {
        return List.copyOf(autopilotShipmentManifest);
    }

    public void setAllowNonOrderAutoReturn(boolean allow) {
        autopilotAllowNonOrderAutoReturn = allow;
    }

    public void setAllowNonOrderAutoUnload(boolean allow) {
        autopilotAllowNonOrderAutoUnload = allow;
    }

    public boolean isUnloadOnArrival() {
        return autopilotAllowNonOrderAutoUnload;
    }

    public boolean loadCargo(List<ItemStack> cargo) {
        if (!canLoadCargo(cargo)) {
            return false;
        }
        for (ItemStack stack : cargo) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ItemStack remaining = stack.copy();
            mergeIntoInventory(remaining);
        }
        return true;
    }

    public boolean canLoadCargo(List<ItemStack> cargo) {
        if (cargo == null || cargo.isEmpty()) {
            return true;
        }
        NonNullList<ItemStack> snapshot = NonNullList.withSize(inventory.size(), ItemStack.EMPTY);
        for (int i = 0; i < inventory.size(); i++) {
            snapshot.set(i, inventory.get(i).copy());
        }
        NonNullList<ItemStack> working = NonNullList.withSize(inventory.size(), ItemStack.EMPTY);
        for (int i = 0; i < snapshot.size(); i++) {
            working.set(i, snapshot.get(i).copy());
        }
        for (ItemStack stack : cargo) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ItemStack remaining = stack.copy();
            mergeIntoInventory(remaining, working);
            if (!remaining.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public void setRouteCatalog(List<RouteDefinition> routes, int preferredIndex, @Nullable BlockPos dockPos) {
        if (level().isClientSide) {
            return;
        }
        routeCatalog.clear();
        for (RouteDefinition route : routes) {
            if (route.waypoints().size() >= 2) {
                routeCatalog.add(route.copy());
            }
        }
        selectedRouteIndex = routeCatalog.isEmpty() ? 0 : Mth.clamp(preferredIndex, 0, routeCatalog.size() - 1);
        routeDockPos = dockPos == null ? null : dockPos.immutable();
        updateRouteSyncData();
    }

    public boolean startAutopilot() {
        return startAutopilotInternal();
    }

    public boolean startAutopilotFromRouteStart() {
        return startAutopilotInternal();
    }

    private boolean startAutopilotInternal() {
        if (level().isClientSide) {
            return false;
        }
        resetArrivalNotice(); // 新发车/续运/返航开始：清掉上一程到站通知，避免幽灵残留
        resetUnstickState();  // 新发车：清掉上一程脱困状态，避免误判/残留 STUCK
        dockHoldTicks = 0;
        dockHoldPos = null;
        dockHoldYaw = Float.NaN;
        autopilotDockingSpot = null;
        autopilotDepartureOrigin = position();
        if (routeCatalog.isEmpty()) {
            stopAutopilot();
            return false;
        }
        selectedRouteIndex = Mth.clamp(selectedRouteIndex, 0, routeCatalog.size() - 1);
        RouteDefinition route = routeCatalog.get(selectedRouteIndex);
        if (!isInsideRouteStartWaitingZone(route)) {
            stopAutopilot();
            return false;
        }
        autopilotRoute.clear();
        int count = Math.min(route.waypoints().size(), MAX_AUTOPILOT_WAYPOINTS);
        for (int i = 0; i < count; i++) {
            autopilotRoute.add(route.waypoints().get(i));
        }
        if (autopilotRoute.size() < 2) {
            stopAutopilot();
            return false;
        }
        autopilotRouteName = route.name();
        if (autopilotRouteName.isBlank()) {
            autopilotRouteName = "Route-" + (selectedRouteIndex + 1);
        }
        initializeAutopilotShipmentContext(route);
        autopilotTargetIndex = determineInitialAutopilotTargetIndex();
        entityData.set(DATA_AUTOPILOT_ACTIVE, true);
        entityData.set(DATA_AUTOPILOT_PAUSED, false);
        autopilotNoProgressTicks = 0;
        autopilotLastTargetDistance = Double.NaN;
        updateRouteSyncData();
        forwardPressedLastTick = false;
        reversePressedLastTick = false;
        // webmap: 手动发车（无市场订单）建一条 manual 轨迹，供地图显示
        if (autopilotShipmentShippingOrderId == null || autopilotShipmentShippingOrderId.isBlank()) {
            com.monpai.sailboatmod.market.logistics.ShippingTraceService.createOrUpdateManualTrace(
                    level(), getUUID(), new java.util.ArrayList<>(autopilotRoute),
                    traceShipperUuid(), traceShipperNationId(),
                    "PORT", "SAILING",
                    autopilotShipmentStartDockName, autopilotShipmentEndDockName,
                    getX(), getZ(),
                    com.monpai.sailboatmod.market.logistics.ShippingTraceService.cargoFromItems(inventory));
        }
        return true;
    }

    /** 手动轨迹归属：当前驾驶玩家 uuid；无人驾驶（码头派发空驶）时回退船主 uuid，保证地图可见。 */
    private String traceShipperUuid() {
        if (getControllingPassenger() instanceof net.minecraft.world.entity.player.Player driver) {
            return driver.getUUID().toString();
        }
        return getOwnerUuid();
    }

    /** 手动轨迹归属国家：驾驶玩家或船主所属国家 id（用于同国可见）；无则空。 */
    private String traceShipperNationId() {
        if (level().isClientSide) {
            return "";
        }
        java.util.UUID shipperId = null;
        if (getControllingPassenger() instanceof net.minecraft.world.entity.player.Player driver) {
            shipperId = driver.getUUID();
        } else {
            String owner = getOwnerUuid();
            if (owner != null && !owner.isBlank()) {
                try {
                    shipperId = java.util.UUID.fromString(owner);
                } catch (IllegalArgumentException ignored) {
                    return "";
                }
            }
        }
        if (shipperId == null) {
            return "";
        }
        com.monpai.sailboatmod.nation.model.NationMemberRecord member =
                com.monpai.sailboatmod.nation.data.NationSavedData.get(level()).getMember(shipperId);
        return member == null ? "" : member.nationId();
    }

    private int determineInitialAutopilotTargetIndex() {
        if (autopilotRoute.size() < 2) {
            return 0;
        }
        DockBlockEntity startDock = getAutopilotStartDock();
        if (startDock != null && startDock.isInsideDockZone(position())) {
            return 1;
        }
        return 0;
    }

    public void stopAutopilot() {
        stopAutopilot(true);
    }

    private void stopAutopilot(boolean rollbackShipment) {
        if (level().isClientSide) {
            return;
        }
        // webmap: 清理本载具的手动轨迹（订单轨迹由订单生命周期管理，不在此删）
        com.monpai.sailboatmod.market.logistics.ShippingTraceService.removeTrace(
                level(), com.monpai.sailboatmod.market.logistics.ShippingTraceService.manualTraceId(getUUID()));
        if (rollbackShipment) {
            rollbackMarketShipment();
        }
        entityData.set(DATA_AUTOPILOT_ACTIVE, false);
        autopilotRoute.clear();
        autopilotTargetIndex = 0;
        autopilotRouteName = getSelectedRouteName();
        autopilotNoProgressTicks = 0;
        autopilotLastTargetDistance = Double.NaN;
        resetUnstickState();
        autopilotDockingSpot = null;
        autopilotDepartureOrigin = null;
        if (level() instanceof ServerLevel serverLevel) {
            // 释放票据前给追踪玩家补发 spawn 包族：多 mod 环境下票据加载的实体可能未被
            // EntityTracker pair（同马车幽灵车问题），到站「看不见但有音效」。
            com.monpai.sailboatmod.util.EntityRetrackHelper.resendSpawnToNearby(serverLevel, this);
            clearAutopilotForcedChunks(serverLevel);
        }
        clearAutopilotShipmentContext();
        entityData.set(DATA_AUTOPILOT_PAUSED, false);
        entityData.set(DATA_ENGINE_GEAR, EngineGear.STOP.id);
        updateRouteSyncData();
    }

    public void pauseAutopilot() {
        if (level().isClientSide || !isAutopilotActive()) {
            return;
        }
        entityData.set(DATA_AUTOPILOT_PAUSED, true);
        entityData.set(DATA_ENGINE_GEAR, EngineGear.STOP.id);
    }

    public void resumeAutopilot() {
        if (level().isClientSide || !isAutopilotActive()) {
            return;
        }
        if (!hasAutopilotRoute()) {
            startAutopilot();
            return;
        }
        entityData.set(DATA_AUTOPILOT_PAUSED, false);
        clearStuckTraceStatus(); // 玩家手动恢复被卡死暂停的船：webmap 状态从 STUCK 恢复 SAILING
    }

    public void selectNextRoute() {
        if (level().isClientSide || routeCatalog.isEmpty()) {
            return;
        }
        selectedRouteIndex = (selectedRouteIndex + 1) % routeCatalog.size();
        updateRouteSyncData();
    }

    public void selectPreviousRoute() {
        if (level().isClientSide || routeCatalog.isEmpty()) {
            return;
        }
        selectedRouteIndex = (selectedRouteIndex - 1 + routeCatalog.size()) % routeCatalog.size();
        updateRouteSyncData();
    }

    public void controlAutopilot(Player player, AutopilotControlAction action) {
        if (level().isClientSide || !isCaptain(player)) {
            return;
        }
        switch (action) {
            case START -> {
                setPendingShipper(player.getName().getString());
                if (!startAutopilot()) {
                    player.displayClientMessage(Component.translatable("screen.sailboatmod.route_start_need_zone"), true);
                }
            }
            case PAUSE -> pauseAutopilot();
            case RESUME -> resumeAutopilot();
            case STOP -> stopAutopilot();
            case NEXT_ROUTE -> selectNextRoute();
            case PREV_ROUTE -> selectPreviousRoute();
        }
    }

    public void toggleSail(Player player) {
        if (!level().isClientSide && isCaptain(player)) {
            entityData.set(DATA_SAIL_DEPLOYED, !entityData.get(DATA_SAIL_DEPLOYED));
        }
    }

    public void applyManualControlInput(Player player, SailboatControlInput input, int gear) {
        if (player == null || level().isClientSide || player.getVehicle() != this || !hasPassenger(player) || !isCaptain(player)) {
            return;
        }
        manualInputState.update(player.getUUID(), input, tickCount);
        // 挡位是离散状态：客户端用本地实时按键做边沿检测算出目标挡位 id，随包传来，服务端在此幂等设挡。
        // 不在服务端重放边沿（网络包丢/合批会漏沿致两端永久错位、一卡一卡）。手动六段挡才采用，autopilot
        // 与 hold-to-drive 各自管挡，不被覆盖。EngineGear.byId 越界回退 STOP，脏包安全。
        if (!isAutopilotActive() && !usesHoldToDriveControls()) {
            entityData.set(DATA_ENGINE_GEAR, EngineGear.byId(gear).id);
        }
    }

    /**
     * 客户端把本地驾驶玩家输入喂进 manualInputState，供客户端本地预测(tick 里的物理)读取——
     * 这是「两端跑同一份物理」的输入来源(服务端靠 SailboatControlInputPacket→applyManualControlInput)。
     * 照抄 CarriageEntity.applyClientControlInput。
     */
    public void applyClientControlInput(SailboatControlInput input) {
        if (!level().isClientSide) {
            return;
        }
        if (getControllingPassenger() instanceof Player driver) {
            manualInputState.update(driver.getUUID(), input == null ? SailboatControlInput.IDLE : input, tickCount);
        }
    }

    public float getSailDeployProgress(float partialTick) {
        return Mth.lerp(partialTick, previousSailDeployProgress, sailDeployProgress);
    }

    public HandlingPreset getHandlingPreset() {
        return HandlingPreset.byId(entityData.get(DATA_HANDLING_PRESET));
    }

    public void setHandlingPreset(Player player, int presetId) {
        if (!level().isClientSide && isCaptain(player)) {
            entityData.set(DATA_HANDLING_PRESET, HandlingPreset.byId(presetId).id);
        }
    }

    public void cycleHandlingPreset(Player player) {
        if (!level().isClientSide || !isCaptain(player)) {
            return;
        }
        HandlingPreset next = getHandlingPreset().next();
        entityData.set(DATA_HANDLING_PRESET, next.id);
    }

    public EngineGear getEngineGear() {
        return EngineGear.byId(entityData.get(DATA_ENGINE_GEAR));
    }

    private void updateRouteSyncData() {
        if (level().isClientSide) {
            return;
        }
        int count = routeCatalog.size();
        entityData.set(DATA_ROUTE_COUNT, count);
        int index = count == 0 ? 0 : Mth.clamp(selectedRouteIndex, 0, count - 1);
        entityData.set(DATA_ROUTE_INDEX, index);
        if (count == 0) {
            entityData.set(DATA_ROUTE_NAME, "-");
            return;
        }
        String name = routeCatalog.get(index).name();
        if (name == null || name.isBlank()) {
            name = "Route-" + (index + 1);
        }
        entityData.set(DATA_ROUTE_NAME, name);
    }

    private boolean isSeatTaken(int seat, @Nullable UUID exceptPlayer) {
        for (Map.Entry<UUID, Integer> entry : seatAssignments.entrySet()) {
            if (entry.getValue() == seat && (exceptPlayer == null || !entry.getKey().equals(exceptPlayer))) {
                return true;
            }
        }
        return false;
    }

    private int firstFreeSeat() {
        for (int seat = 0; seat < SEAT_COUNT; seat++) {
            if (!isSeatTaken(seat, null)) {
                return seat;
            }
        }
        return -1;
    }

    protected void cleanupSeatAssignments() {
        Set<UUID> currentPassengers = new HashSet<>();
        for (Entity passenger : getPassengers()) {
            currentPassengers.add(passenger.getUUID());
        }
        seatAssignments.keySet().removeIf(id -> !currentPassengers.contains(id));
        syncSeatEntityData();
    }

    private void updateGearFromInput(boolean forwardPressed, boolean reversePressed) {
        if (forwardPressed && !forwardPressedLastTick && !reversePressed) {
            EngineGear next = getEngineGear().shiftUp();
            entityData.set(DATA_ENGINE_GEAR, next.id);
        } else if (reversePressed && !reversePressedLastTick && !forwardPressed) {
            EngineGear next = getEngineGear().shiftDown();
            entityData.set(DATA_ENGINE_GEAR, next.id);
        }
        forwardPressedLastTick = forwardPressed;
        reversePressedLastTick = reversePressed;
    }

    protected AutopilotCommand computeAutopilotCommand() {
        if (!hasAutopilotRoute()) {
            stopAutopilot();
            return AutopilotCommand.inactive();
        }
        if (isReadyToUnloadAtDestination()) {
            finishAutopilotAndUnloadAtDestination();
            return AutopilotCommand.inactive();
        }
        // 窄河道脱困：正在脱困期间，优先执行温和后退 + 小幅交替摆舵，不走正常寻路。
        if (autopilotUnstickTicks > 0) {
            autopilotUnstickTicks--;
            if (autopilotUnstickTicks == 0) {
                endUnstickAttempt();
                // 脱困轮结束后本 tick 不再寻路，下一 tick 重新评估（已脱困则正常前进，仍卡则再触发）。
                return new AutopilotCommand(true, false, 0.0F, 0.0F, EngineGear.STOP);
            }
            float unstickYaw = AUTOPILOT_UNSTICK_YAW_STEP * autopilotUnstickYawDir;
            float nextYaw = getYRot() + unstickYaw;
            setYRot(nextYaw);
            setYHeadRot(nextYaw);
            setYBodyRot(nextYaw);
            return new AutopilotCommand(true, true, autopilotUnstickYawDir, unstickYaw, EngineGear.HALF_ASTERN);
        }
        autopilotTargetIndex = Mth.clamp(autopilotTargetIndex, 0, autopilotRoute.size() - 1);
        Vec3 target = autopilotRoute.get(autopilotTargetIndex);
        boolean finalTarget = autopilotTargetIndex >= autopilotRoute.size() - 1;
        if (finalTarget) {
            Vec3 approach = computeDockApproachPoint(getAutopilotDestinationDock());
            if (approach == null) {
                return AutopilotCommand.inactive();
            }
            target = approach;
        }
        double dx = target.x - getX();
        double dz = target.z - getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        int captureGuard = 0;
        while (captureGuard < 3) {
            double arrivalRadius = autopilotTargetIndex == 0
                    ? Math.max(AUTOPILOT_ARRIVAL_RADIUS, AUTOPILOT_START_WAYPOINT_CAPTURE_RADIUS)
                    : AUTOPILOT_ARRIVAL_RADIUS;
            finalTarget = autopilotTargetIndex >= autopilotRoute.size() - 1;
            boolean reachedWaypoint = dist <= arrivalRadius;
            boolean passedWaypoint = !finalTarget && hasClearlyPassedCurrentWaypoint(target, dist);
            if (!reachedWaypoint && !passedWaypoint) {
                break;
            }
            if (finalTarget) {
                finishAutopilotAndUnloadAtDestination();
                return AutopilotCommand.inactive();
            }
            if (!advanceAutopilotTargetOrStop()) {
                return AutopilotCommand.inactive();
            }
            target = autopilotRoute.get(autopilotTargetIndex);
            if (autopilotTargetIndex >= autopilotRoute.size() - 1) {
                Vec3 approach = computeDockApproachPoint(getAutopilotDestinationDock());
                if (approach == null) {
                    return AutopilotCommand.inactive();
                }
                target = approach;
            }
            dx = target.x - getX();
            dz = target.z - getZ();
            dist = Math.sqrt(dx * dx + dz * dz);
            autopilotNoProgressTicks = 0;
            autopilotLastTargetDistance = dist;
            captureGuard++;
        }

        float desiredYaw = (float) (Mth.atan2(-dx, dz) * (180.0D / Math.PI));
        float yawError = Mth.wrapDegrees(desiredYaw - getYRot());
        float yawStep = Mth.clamp(yawError, -MAX_TURN_DEGREES_PER_TICK, MAX_TURN_DEGREES_PER_TICK);
        float turnInput = Mth.clamp(yawError / 40.0F, -1.0F, 1.0F);
        boolean wantsTurn = Math.abs(yawError) > 1.5F;
        float absYawError = Math.abs(yawError);

        if (shouldYieldForDeparture(target, dist)) {
            autopilotNoProgressTicks = 0;
            autopilotLastTargetDistance = dist;
            return new AutopilotCommand(true, false, 0.0F, 0.0F, EngineGear.STOP);
        }

        if (absYawError > AUTOPILOT_TURN_IN_PLACE_DEGREES) {
            // Intentional pivot turn: do not treat as "stuck with no progress".
            autopilotNoProgressTicks = 0;
        } else if (!Double.isNaN(autopilotLastTargetDistance) && dist + AUTOPILOT_PROGRESS_EPSILON >= autopilotLastTargetDistance) {
            autopilotNoProgressTicks++;
        } else {
            autopilotNoProgressTicks = 0;
        }
        autopilotLastTargetDistance = dist;

        // 窄河道物理卡死检测：撞墙（horizontalCollision）且上一 tick 几乎没动且想前进（gear!=STOP），
        // 区别于主动慢速/大角度 pivot 转向。累计达阈值 → 进入脱困（后退+摆舵）。
        boolean physicallyStuck = horizontalCollision
                && getDeltaMovement().horizontalDistanceSqr() < AUTOPILOT_STUCK_MOVE_EPSILON * AUTOPILOT_STUCK_MOVE_EPSILON
                && getEngineGear() != EngineGear.STOP
                && absYawError <= AUTOPILOT_TURN_IN_PLACE_DEGREES;
        if (physicallyStuck) {
            autopilotStuckTicks++;
        } else {
            autopilotStuckTicks = 0;
        }
        if (autopilotStuckTicks >= AUTOPILOT_STUCK_DETECT_TICKS) {
            beginUnstickAttempt();
            return new AutopilotCommand(true, false, 0.0F, 0.0F, EngineGear.STOP);
        }

        if (autopilotNoProgressTicks >= AUTOPILOT_NO_PROGRESS_TICKS_LIMIT
                && dist <= AUTOPILOT_STALL_SKIP_RADIUS
                && autopilotTargetIndex < autopilotRoute.size() - 1) {
            if (!advanceAutopilotTargetOrStop()) {
                return AutopilotCommand.inactive();
            }
            target = autopilotRoute.get(autopilotTargetIndex);
            dx = target.x - getX();
            dz = target.z - getZ();
            dist = Math.sqrt(dx * dx + dz * dz);

            desiredYaw = (float) (Mth.atan2(-dx, dz) * (180.0D / Math.PI));
            yawError = Mth.wrapDegrees(desiredYaw - getYRot());
            yawStep = Mth.clamp(yawError, -MAX_TURN_DEGREES_PER_TICK, MAX_TURN_DEGREES_PER_TICK);
            turnInput = Mth.clamp(yawError / 40.0F, -1.0F, 1.0F);
            wantsTurn = Math.abs(yawError) > 1.5F;
            absYawError = Math.abs(yawError);
            autopilotNoProgressTicks = 0;
            autopilotLastTargetDistance = dist;
        }

        // 终点停滞兜底：已在终点航段、长时间无进展且已进入/贴近目的 zone → 视为到站，触发卸货+停止+标记完成。
        // 修复返航到出发港时停在 3.2~4.5 死区（始终 >AUTOPILOT_ARRIVAL_RADIUS）导致自动驾驶永远「运行中」。
        if (finalTarget
                && autopilotNoProgressTicks >= AUTOPILOT_NO_PROGRESS_TICKS_LIMIT
                && (isInsideAutopilotDestinationDockZone() || dist <= AUTOPILOT_FINAL_STOP_RADIUS + 1.0D)) {
            finishAutopilotAndUnloadAtDestination();
            return AutopilotCommand.inactive();
        }

        double slowdownRadius = computeAutopilotSlowdownRadius(finalTarget);
        double stopRadius = finalTarget ? AUTOPILOT_FINAL_STOP_RADIUS : AUTOPILOT_ARRIVAL_RADIUS * 1.2D;
        EngineGear desiredGear = selectAutopilotGear(finalTarget, dist, stopRadius, absYawError, slowdownRadius);
        return new AutopilotCommand(true, wantsTurn, turnInput, yawStep, desiredGear);
    }

    /** 进入一轮窄河道脱困：记起点、累加轮数、交替摆舵方向。 */
    private void beginUnstickAttempt() {
        autopilotUnstickTicks = AUTOPILOT_UNSTICK_REVERSE_TICKS;
        autopilotUnstickStartPos = position();
        autopilotUnstickAttempts++;
        autopilotUnstickYawDir = -autopilotUnstickYawDir;
        autopilotStuckTicks = 0;
    }

    /** 一轮脱困结束：挪动够远→成功复位；连续多轮无效→暂停求助并在 webmap 标 STUCK。 */
    private void endUnstickAttempt() {
        boolean moved = autopilotUnstickStartPos != null
                && position().distanceToSqr(autopilotUnstickStartPos) > 1.0D;
        autopilotStuckTicks = 0;
        autopilotUnstickStartPos = null;
        if (moved) {
            autopilotUnstickAttempts = 0;
            autopilotNoProgressTicks = 0;
            autopilotLastTargetDistance = Double.NaN;
            clearStuckTraceStatus();
        } else if (autopilotUnstickAttempts >= AUTOPILOT_UNSTICK_MAX_ATTEMPTS) {
            beginStuckHelpNotice();
            markStuckTraceStatus();
            pauseAutopilot();
            autopilotUnstickAttempts = 0;
        }
        // 否则下次检测到仍卡死会再次 beginUnstickAttempt（attempts 继续累加直至上限）。
    }

    /** 重置全部脱困运行时状态（发车/到站/停止时调用，避免跨程残留）。 */
    private void resetUnstickState() {
        autopilotStuckTicks = 0;
        autopilotUnstickTicks = 0;
        autopilotUnstickAttempts = 0;
        autopilotUnstickStartPos = null;
        autopilotTraceStuck = false;
    }

    /** 卡住头顶提示（复用到站通知通道）+ 给船主/驾驶员发聊天提示。 */
    private void beginStuckHelpNotice() {
        if (level().isClientSide) {
            return;
        }
        entityData.set(DATA_ARRIVAL_NOTICE_STATION_NAME,
                Component.translatable("entity.sailboatmod.autopilot.stuck").getString());
        entityData.set(DATA_ARRIVAL_NOTICE_ELAPSED_SECONDS, 0);
        entityData.set(DATA_ARRIVAL_NOTICE_DATE_TEXT, "");
        setArrivalNoticeTicks(ARRIVAL_NOTICE_TICKS);
        notifyStuckToOwner();
    }

    /** 给船主/驾驶员（若在线）发送卡住聊天提示。 */
    private void notifyStuckToOwner() {
        if (!(level() instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return;
        }
        String uuid = traceShipperUuid();
        if (uuid == null || uuid.isBlank()) {
            return;
        }
        java.util.UUID playerId;
        try {
            playerId = java.util.UUID.fromString(uuid);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        net.minecraft.server.level.ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(playerId);
        if (player != null) {
            player.sendSystemMessage(Component.translatable("message.sailboatmod.autopilot.stuck"));
        }
    }

    /** 当前激活轨迹 id：手动车用 manualTraceId，订单车用 shippingOrderId。 */
    private String activeTraceId() {
        return (autopilotShipmentShippingOrderId == null || autopilotShipmentShippingOrderId.isBlank())
                ? ShippingTraceService.manualTraceId(getUUID())
                : autopilotShipmentShippingOrderId;
    }

    /** 把当前轨迹状态标为 STUCK，webmap 据此红色告警「阻塞·需救援」。 */
    private void markStuckTraceStatus() {
        if (level().isClientSide) {
            return;
        }
        ShippingTraceService.updateStatus(level(), activeTraceId(), "STUCK");
        autopilotTraceStuck = true;
    }

    /** 脱困成功/玩家恢复后把轨迹状态从 STUCK 恢复 SAILING（仅在之前标过 STUCK 时）。 */
    private void clearStuckTraceStatus() {
        if (level().isClientSide || !autopilotTraceStuck) {
            return;
        }
        ShippingTraceService.updateStatus(level(), activeTraceId(), "SAILING");
        autopilotTraceStuck = false;
    }

    protected EngineGear selectAutopilotGear(boolean finalTarget,
                                             double dist,
                                             double stopRadius,
                                             float absYawError,
                                             double slowdownRadius) {
        if (dist < stopRadius) {
            return EngineGear.STOP;
        }
        if (absYawError > AUTOPILOT_TURN_IN_PLACE_DEGREES) {
            // Large heading mismatch: pivot first, then advance.
            return EngineGear.STOP;
        }
        if (absYawError > AUTOPILOT_SLOW_TURN_DEGREES) {
            return EngineGear.ONE_THIRD_AHEAD;
        }
        if (dist < slowdownRadius) {
            return EngineGear.ONE_THIRD_AHEAD;
        }
        return isSailDeployed() ? EngineGear.FULL_AHEAD : EngineGear.TWO_THIRDS_AHEAD;
    }

    protected List<Vec3> getAutopilotRoutePoints() {
        return List.copyOf(autopilotRoute);
    }

    protected int getAutopilotTargetIndexForDriveModel() {
        return autopilotTargetIndex;
    }

    public List<Vec3> getMarketWebActiveRoutePoints() {
        return List.copyOf(autopilotRoute);
    }

    public int getMarketWebActiveRouteTargetIndex() {
        return autopilotTargetIndex;
    }

    private double computeAutopilotSlowdownRadius(boolean finalTarget) {
        if (finalTarget) {
            return AUTOPILOT_FINAL_SLOWDOWN_RADIUS;
        }
        double segmentLength = getAutopilotTargetSegmentLength();
        if (Double.isNaN(segmentLength)) {
            return AUTOPILOT_SLOWDOWN_RADIUS;
        }
        return Mth.clamp(segmentLength * 0.45D, AUTOPILOT_ARRIVAL_RADIUS + 0.8D, AUTOPILOT_SLOWDOWN_RADIUS);
    }

    private double getAutopilotTargetSegmentLength() {
        if (autopilotRoute.isEmpty()) {
            return Double.NaN;
        }
        int targetIndex = Mth.clamp(autopilotTargetIndex, 0, autopilotRoute.size() - 1);
        Vec3 target = autopilotRoute.get(targetIndex);
        Vec3 reference = null;
        if (targetIndex > 0) {
            reference = autopilotRoute.get(targetIndex - 1);
        } else if (targetIndex + 1 < autopilotRoute.size()) {
            reference = autopilotRoute.get(targetIndex + 1);
        }
        if (reference == null) {
            return Double.NaN;
        }
        return target.distanceTo(reference);
    }

    private boolean shouldYieldForDeparture(Vec3 target, double dist) {
        if (level() == null || autopilotTargetIndex > 1 || dist <= AUTOPILOT_ARRIVAL_RADIUS) {
            return false;
        }
        DockBlockEntity startDock = getAutopilotStartDock();
        if (startDock == null || !startDock.isInsideDockZone(position())) {
            return false;
        }
        if (autopilotDepartureOrigin == null
                || position().distanceToSqr(autopilotDepartureOrigin)
                > AUTOPILOT_DEPARTURE_YIELD_START_RADIUS * AUTOPILOT_DEPARTURE_YIELD_START_RADIUS) {
            return false;
        }
        double dirX = target.x - getX();
        double dirZ = target.z - getZ();
        double dirLen = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (dirLen <= 1.0E-4D) {
            return false;
        }
        dirX /= dirLen;
        dirZ /= dirLen;
        AABB searchBox = new AABB(
                getX() - AUTOPILOT_DEPARTURE_YIELD_LOOKAHEAD, getY() - 1.5D, getZ() - AUTOPILOT_DEPARTURE_YIELD_LOOKAHEAD,
                getX() + AUTOPILOT_DEPARTURE_YIELD_LOOKAHEAD, getY() + 1.5D, getZ() + AUTOPILOT_DEPARTURE_YIELD_LOOKAHEAD
        );
        double selfTargetDistSq = position().distanceToSqr(target);
        for (SailboatEntity other : level().getEntitiesOfClass(SailboatEntity.class, searchBox, boat -> boat != this && boat.isAlive())) {
            if (!other.isAutopilotActive()) {
                continue;
            }
            double otherMotionSq = other.getDeltaMovement().x * other.getDeltaMovement().x
                    + other.getDeltaMovement().z * other.getDeltaMovement().z;
            if (other.getEngineGear() == EngineGear.STOP && otherMotionSq < 4.0E-4D) {
                continue;
            }
            double offsetX = other.getX() - getX();
            double offsetZ = other.getZ() - getZ();
            double along = offsetX * dirX + offsetZ * dirZ;
            if (along < -0.75D || along > AUTOPILOT_DEPARTURE_YIELD_LOOKAHEAD) {
                continue;
            }
            double lateralX = offsetX - dirX * along;
            double lateralZ = offsetZ - dirZ * along;
            if (lateralX * lateralX + lateralZ * lateralZ > AUTOPILOT_DEPARTURE_YIELD_LATERAL * AUTOPILOT_DEPARTURE_YIELD_LATERAL) {
                continue;
            }
            if (!startDock.isInsideDockZone(other.position())) {
                continue;
            }
            double otherTargetDistSq = other.position().distanceToSqr(target);
            boolean otherHasPriority = otherTargetDistSq + 1.0D < selfTargetDistSq
                    || (Math.abs(otherTargetDistSq - selfTargetDistSq) <= 1.0D && other.getId() < getId());
            if (otherHasPriority) {
                return true;
            }
        }
        return false;
    }

    private boolean hasClearlyPassedCurrentWaypoint(Vec3 target, double dist) {
        if (autopilotTargetIndex <= 0 || autopilotTargetIndex >= autopilotRoute.size()) {
            return false;
        }
        if (dist > AUTOPILOT_STALL_SKIP_RADIUS) {
            return false;
        }
        Vec3 previous = autopilotRoute.get(autopilotTargetIndex - 1);
        double segmentX = target.x - previous.x;
        double segmentZ = target.z - previous.z;
        double segmentLengthSq = segmentX * segmentX + segmentZ * segmentZ;
        if (segmentLengthSq < 1.0E-4D) {
            return false;
        }
        double boatOffsetX = getX() - previous.x;
        double boatOffsetZ = getZ() - previous.z;
        double along = (boatOffsetX * segmentX + boatOffsetZ * segmentZ) / segmentLengthSq;
        if (along <= AUTOPILOT_PASSED_PROGRESS_THRESHOLD) {
            return false;
        }
        double projectedX = segmentX * along;
        double projectedZ = segmentZ * along;
        double lateralX = boatOffsetX - projectedX;
        double lateralZ = boatOffsetZ - projectedZ;
        double lateralDistance = Math.sqrt(lateralX * lateralX + lateralZ * lateralZ);
        return lateralDistance <= AUTOPILOT_PASSED_LATERAL_THRESHOLD;
    }

    protected boolean hasAutopilotRoute() {
        return !autopilotRoute.isEmpty();
    }

    private boolean advanceAutopilotTargetOrStop() {
        autopilotTargetIndex++;
        if (autopilotTargetIndex >= autopilotRoute.size()) {
            finishAutopilotAndUnloadAtDestination();
            return false;
        }
        return true;
    }

    private boolean isReadyToUnloadAtDestination() {
        double distance = getFinalDockApproachDistance();
        if (Double.isNaN(distance)) {
            return false;
        }
        // 已贴近 approach point；或已进入目的 zone 且在最终停泊半径内（避开 3.2~4.5 死区，返航更稳）。
        return distance <= AUTOPILOT_ARRIVAL_RADIUS
                || (isInsideAutopilotDestinationDockZone() && distance <= AUTOPILOT_FINAL_STOP_RADIUS);
    }

    @Nullable
    private DockBlockEntity getAutopilotDestinationDock() {
        BlockPos endDockPos = findAutopilotDestinationDockPos();
        return endDockPos == null ? null : getTransportHub(endDockPos);
    }

    @Nullable
    private DockBlockEntity getAutopilotStartDock() {
        if (routeDockPos != null) {
            DockBlockEntity dock = getTransportHub(routeDockPos);
            if (dock != null) {
                return dock;
            }
        }
        if (!autopilotRoute.isEmpty()) {
            BlockPos startDockPos = findTransportHubZoneContains(autopilotRoute.get(0));
            if (startDockPos != null) {
                DockBlockEntity dock = getTransportHub(startDockPos);
                if (dock != null) {
                    return dock;
                }
            }
        }
        return null;
    }

    @Nullable
    private Vec3 computeDockApproachPoint(@Nullable DockBlockEntity dock) {
        if (dock == null || autopilotRoute.isEmpty()) {
            return null;
        }
        if (autopilotDockingSpot != null && isDockParkingSpotValid(dock, autopilotDockingSpot)) {
            return autopilotDockingSpot;
        }
        int lastIndex = autopilotRoute.size() - 1;
        Vec3 finalWaypoint = autopilotRoute.get(lastIndex);
        Vec3 dockCenter = new Vec3(dock.getBlockPos().getX() + 0.5D, getY(), dock.getBlockPos().getZ() + 0.5D);
        double minX = dockCenter.x + dock.getZoneMinX() + DOCK_PARKING_EDGE_PADDING;
        double maxX = dockCenter.x + dock.getZoneMaxX() - DOCK_PARKING_EDGE_PADDING;
        double minZ = dockCenter.z + dock.getZoneMinZ() + DOCK_PARKING_EDGE_PADDING;
        double maxZ = dockCenter.z + dock.getZoneMaxZ() - DOCK_PARKING_EDGE_PADDING;
        if (minX > maxX) {
            double centerX = dockCenter.x + (dock.getZoneMinX() + dock.getZoneMaxX()) * 0.5D;
            minX = centerX;
            maxX = centerX;
        }
        if (minZ > maxZ) {
            double centerZ = dockCenter.z + (dock.getZoneMinZ() + dock.getZoneMaxZ()) * 0.5D;
            minZ = centerZ;
            maxZ = centerZ;
        }

        Vec3 preferred = new Vec3(
                Mth.clamp(finalWaypoint.x, minX, maxX),
                getY(),
                Mth.clamp(finalWaypoint.z, minZ, maxZ)
        );
        if (preferred.distanceToSqr(dockCenter) < DOCK_PARKING_DOCK_EXCLUSION_RADIUS * DOCK_PARKING_DOCK_EXCLUSION_RADIUS) {
            double awayX = preferred.x - dockCenter.x;
            double awayZ = preferred.z - dockCenter.z;
            if (awayX * awayX + awayZ * awayZ <= 1.0E-4D) {
                awayX = finalWaypoint.x - dockCenter.x;
                awayZ = finalWaypoint.z - dockCenter.z;
            }
            double awayLen = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (awayLen <= 1.0E-4D) {
                awayX = 0.0D;
                awayZ = 1.0D;
                awayLen = 1.0D;
            }
            double safeX = dockCenter.x + awayX / awayLen * DOCK_PARKING_DOCK_EXCLUSION_RADIUS;
            double safeZ = dockCenter.z + awayZ / awayLen * DOCK_PARKING_DOCK_EXCLUSION_RADIUS;
            preferred = new Vec3(Mth.clamp(safeX, minX, maxX), getY(), Mth.clamp(safeZ, minZ, maxZ));
        }
        Vec3 bestFallback = preferred;
        Vec3 bestAvailable = null;
        double bestFallbackScore = Double.MAX_VALUE;
        double bestAvailableScore = Double.MAX_VALUE;
        double maxRadius = Math.max(maxX - minX, maxZ - minZ) + DOCK_PARKING_GRID_STEP;

        for (double radius = 0.0D; radius <= maxRadius; radius += DOCK_PARKING_GRID_STEP) {
            for (int step = 0; step < 16; step++) {
                double angle = (Math.PI * 2.0D * step) / 16.0D;
                double x = Mth.clamp(preferred.x + Math.cos(angle) * radius, minX, maxX);
                double z = Mth.clamp(preferred.z + Math.sin(angle) * radius, minZ, maxZ);
                Vec3 candidate = new Vec3(x, getY(), z);
                if (!dock.isInsideDockZone(candidate)) {
                    continue;
                }
                if (candidate.distanceToSqr(dockCenter) < DOCK_PARKING_DOCK_EXCLUSION_RADIUS * DOCK_PARKING_DOCK_EXCLUSION_RADIUS) {
                    continue;
                }
                double dockDistance = Math.sqrt(candidate.distanceToSqr(dockCenter));
                double score = Math.abs(dockDistance - DOCK_PARKING_PREFERRED_DOCK_DISTANCE) * 8.0D
                        + candidate.distanceToSqr(finalWaypoint) * 0.08D;
                if (score < bestFallbackScore) {
                    bestFallbackScore = score;
                    bestFallback = candidate;
                }
                if (!isDockParkingSpotValid(dock, candidate)) {
                    continue;
                }
                if (score < bestAvailableScore) {
                    bestAvailableScore = score;
                    bestAvailable = candidate;
                }
            }
        }

        if (bestAvailable != null) {
            autopilotDockingSpot = bestAvailable;
            return bestAvailable;
        }
        autopilotDockingSpot = bestFallback;
        return bestFallback;
    }

    private double getFinalDockApproachDistance() {
        if (!hasAutopilotRoute() || autopilotTargetIndex < autopilotRoute.size() - 1) {
            return Double.NaN;
        }
        Vec3 approach = computeDockApproachPoint(getAutopilotDestinationDock());
        if (approach == null) {
            return Double.NaN;
        }
        return position().distanceTo(approach);
    }

    private boolean isDockParkingSpotValid(DockBlockEntity dock, Vec3 spot) {
        if (dock == null) {
            return false;
        }
        Vec3 dockCenter = new Vec3(dock.getBlockPos().getX() + 0.5D, spot.y, dock.getBlockPos().getZ() + 0.5D);
        return dock.isInsideDockZone(spot)
                && spot.distanceToSqr(dockCenter) >= DOCK_PARKING_DOCK_EXCLUSION_RADIUS * DOCK_PARKING_DOCK_EXCLUSION_RADIUS
                && isDockParkingSpotMedium(spot)
                && !isDockApproachOccupied(spot);
    }

    protected boolean isDockParkingSpotMedium(Vec3 spot) {
        if (level() == null) {
            return false;
        }
        BlockPos waterPos = BlockPos.containing(spot.x, Math.max(level().getMinBuildHeight(), Mth.floor(getY() - 0.3D)), spot.z);
        return level().getFluidState(waterPos).is(FluidTags.WATER)
                || level().getFluidState(waterPos.below()).is(FluidTags.WATER);
    }

    private boolean isDockApproachOccupied(Vec3 point) {
        if (level() == null) {
            return false;
        }
        AABB box = new AABB(
                point.x - DOCK_APPROACH_CLEAR_RADIUS, getY() - 1.0D, point.z - DOCK_APPROACH_CLEAR_RADIUS,
                point.x + DOCK_APPROACH_CLEAR_RADIUS, getY() + 1.0D, point.z + DOCK_APPROACH_CLEAR_RADIUS
        );
        return !level().getEntitiesOfClass(SailboatEntity.class, box, boat -> boat != this).isEmpty();
    }

    private boolean isInsideAutopilotDestinationDockZone() {
        BlockPos endDockPos = findAutopilotDestinationDockPos();
        DockBlockEntity dock = endDockPos == null ? null : getTransportHub(endDockPos);
        if (dock == null) {
            return false;
        }
        return dock.isInsideDockZone(position());
    }

    @Nullable
    private BlockPos findAutopilotDestinationDockPos() {
        if (autopilotRoute.isEmpty()) {
            return null;
        }
        if (autopilotDestinationDockHintPos != null
                && getTransportHub(autopilotDestinationDockHintPos) != null) {
            return autopilotDestinationDockHintPos;
        }
        autopilotDestinationDockHintPos = null;
        Vec3 endPoint = autopilotRoute.get(autopilotRoute.size() - 1);
        BlockPos exact = findTransportHubZoneContains(endPoint);
        if (exact != null) {
            autopilotDestinationDockHintPos = exact.immutable();
            return exact;
        }
        BlockPos nearest = findNearestRegisteredTransportHub(endPoint, 256.0D);
        if (nearest != null) {
            autopilotDestinationDockHintPos = nearest.immutable();
        }
        return nearest;
    }

    private void initializeAutopilotShipmentContext(RouteDefinition route) {
        String shipper = pendingShipperName == null ? "" : pendingShipperName.trim();
        if (shipper.isBlank()) {
            shipper = route.authorName();
        }
        if (shipper == null || shipper.isBlank()) {
            shipper = "-";
        }
        autopilotShipmentShipperName = shipper;
        autopilotShipmentDepartureEpochMillis = System.currentTimeMillis();
        autopilotShipmentDistanceMeters = route.routeLengthMeters() > 0.0D ? route.routeLengthMeters() : computeRouteLengthMeters(autopilotRoute);
        Vec3 startWaypoint = route.waypoints().isEmpty() ? null : route.waypoints().get(0);
        Vec3 endWaypoint = route.waypoints().isEmpty() ? null : route.waypoints().get(route.waypoints().size() - 1);
        autopilotDestinationDockHintPos = endWaypoint == null ? null : findTransportHubZoneContains(endWaypoint);
        if (autopilotDestinationDockHintPos == null && endWaypoint != null) {
            autopilotDestinationDockHintPos = findNearestRegisteredTransportHub(endWaypoint, 256.0D);
        }
        autopilotShipmentStartDockName = resolveDockName(route.startDockName(), startWaypoint, null);
        autopilotShipmentEndDockName = resolveDockName(route.endDockName(), endWaypoint, autopilotDestinationDockHintPos);
        pendingShipperName = "";
    }

    private void syncPrimaryShipmentFieldsFromManifest() {
        if (autopilotShipmentManifest.isEmpty()) {
            autopilotShipmentRecipientName = "";
            autopilotShipmentRecipientUuid = "";
            autopilotShipmentPurchaseOrderId = "";
            autopilotShipmentShippingOrderId = "";
            return;
        }
        ShipmentManifestEntry first = autopilotShipmentManifest.get(0);
        autopilotShipmentRecipientName = first.recipientName();
        autopilotShipmentRecipientUuid = first.recipientUuid();
        autopilotShipmentPurchaseOrderId = first.purchaseOrderId();
        autopilotShipmentShippingOrderId = first.shippingOrderId();
    }

    private void clearAutopilotShipmentContext() {
        autopilotShipmentShipperName = "";
        autopilotShipmentStartDockName = "";
        autopilotShipmentEndDockName = "";
        autopilotShipmentRecipientName = "";
        autopilotShipmentRecipientUuid = "";
        autopilotShipmentPurchaseOrderId = "";
        autopilotShipmentShippingOrderId = "";
        autopilotShipmentDepartureEpochMillis = 0L;
        autopilotShipmentDistanceMeters = 0.0D;
        autopilotAllowNonOrderAutoReturn = false;
        autopilotAllowNonOrderAutoUnload = false;
        autopilotReturnTrip = false;
        autopilotShipmentManifest.clear();
        autopilotDestinationDockHintPos = null;
    }

    private String resolveDockName(String preferredName, @Nullable Vec3 waypoint, @Nullable BlockPos dockHintPos) {
        if (preferredName != null && !preferredName.isBlank()) {
            return preferredName;
        }
        if (dockHintPos != null) {
            return getTransportHubDisplayName(dockHintPos);
        }
        if (waypoint == null) {
            return "-";
        }
        BlockPos dockPos = findTransportHubZoneContains(waypoint);
        if (dockPos != null) {
            return getTransportHubDisplayName(dockPos);
        }
        return "-";
    }

    private double computeRouteLengthMeters(List<Vec3> points) {
        if (points == null || points.size() < 2) {
            return 0.0D;
        }
        double total = 0.0D;
        for (int i = 1; i < points.size(); i++) {
            Vec3 a = points.get(i - 1);
            Vec3 b = points.get(i);
            double dx = b.x - a.x;
            double dz = b.z - a.z;
            total += Math.sqrt(dx * dx + dz * dz);
        }
        return total;
    }

    private void finishAutopilotAndUnloadAtDestination() {
        resetUnstickState(); // 到站：清脱困状态，后续轨迹状态由到站/续运流程接管
        BlockPos endDockPos = findAutopilotDestinationDockPos();
        if (endDockPos == null || !(level().getBlockEntity(endDockPos) instanceof DockBlockEntity destinationDock)) {
            // 目的地 dock 方块实体读不到（区块未加载/已拆）：仍播到达反馈 + 音效（destination=null 取航线名），
            // 不再静默 stop —— 否则到港玩家既听不到音效也看不到到达提示（实测帆船到港缺音效根因之一）。
            beginArrivalFeedback(null);
            stopAutopilot();
            return;
        }

        BlockPos previousSourceDockPos = routeDockPos == null ? null : routeDockPos.immutable();
        boolean returnTrip = autopilotReturnTrip;
        List<ShipmentManifestEntry> manifest = List.copyOf(autopilotShipmentManifest);
        boolean hasOrder = hasTransportOrder(manifest);
        boolean allowUnload = hasOrder || autopilotAllowNonOrderAutoUnload;
        boolean allowReturn = hasOrder || autopilotAllowNonOrderAutoReturn;
        if (!allowUnload && !allowReturn) {
            beginArrivalFeedback(destinationDock);
            applyDockHoldState(destinationDock);
            stopAutopilot(false);
            return;
        }
        String returnBuyerUuid = manifest.isEmpty() ? autopilotShipmentRecipientUuid : manifest.get(0).recipientUuid();
        String returnBuyerName = manifest.isEmpty() ? autopilotShipmentRecipientName : manifest.get(0).recipientName();

        List<ShipmentManifestEntry> keepOnboard = manifest;
        if (allowUnload) {
            List<ItemStack> allCargo = drainAllCargo();
            DockBlockEntity.ManifestSplit split = DockBlockEntity.splitManifestByDestination(
                    level(), destinationDock.getBlockPos(), manifest);
            keepOnboard = split.keepOnboard();
            List<ItemStack> pool = new ArrayList<>(allCargo);
            List<ItemStack> deliverCargo = DockBlockEntity.resolveDeliverCargo(pool, split.deliverHere(), !split.keepOnboard().isEmpty());
            if (!pool.isEmpty()) {
                loadCargo(pool); // 留车货物退回库存
            }
            if (!deliverCargo.isEmpty()) {
                long depart = autopilotShipmentDepartureEpochMillis > 0L ? autopilotShipmentDepartureEpochMillis : System.currentTimeMillis();
                long elapsed = Math.max(0L, System.currentTimeMillis() - depart);
                double distance = autopilotShipmentDistanceMeters > 0.0D ? autopilotShipmentDistanceMeters : computeRouteLengthMeters(autopilotRoute);
                String routeName = autopilotRouteName == null || autopilotRouteName.isBlank() ? getSelectedRouteName() : autopilotRouteName;
                destinationDock.receiveShipment(this, routeName, autopilotShipmentShipperName, autopilotShipmentStartDockName,
                        autopilotShipmentEndDockName, depart, elapsed, distance, deliverCargo, split.deliverHere());
            }
            pruneShipmentManifest(split.keepOnboard()); // 剪枝：移除已交付条目（保留中途卸货/返航开关）
        }

        // 仍有未送达运单 → 自动开往下一港，逐港连运（去程才续运，返航不续）。
        if (!returnTrip && !keepOnboard.isEmpty() && tryStartWaterLegToNextPort(destinationDock, keepOnboard)) {
            return;
        }
        if (!returnTrip && allowReturn && keepOnboard.isEmpty()
                && tryStartReturnTrip(destinationDock, previousSourceDockPos, returnBuyerUuid, returnBuyerName)) {
            return;
        }
        beginArrivalFeedback(destinationDock);
        applyDockHoldState(destinationDock);
        // 订单全部送达、航程结束：删掉订单轨迹（实时清理，避免送达后 webmap 残留）。
        // 仅在真正结束（无续程/返航）时删；中途续程已在上面 return，不会走到这里。
        if (!autopilotShipmentShippingOrderId.isBlank()) {
            ShippingTraceService.removeTrace(level(), autopilotShipmentShippingOrderId);
        }
        stopAutopilot(false);
    }

    public int getArrivalNoticeTicks() {
        // 语义=剩余可见 tick（服务端权威递减并同步）。不再用 untilTick-tickCount：多 mod 环境下
        // 客户端实体 tick 可能停滞，tickCount 不前进会导致到站提示永不消失。
        return Math.max(0, entityData.get(DATA_ARRIVAL_NOTICE_UNTIL_TICK));
    }

    public String getArrivalNoticeStationName() {
        return entityData.get(DATA_ARRIVAL_NOTICE_STATION_NAME);
    }

    public String getArrivalNoticeElapsedText() {
        return formatArrivalElapsedSeconds(entityData.get(DATA_ARRIVAL_NOTICE_ELAPSED_SECONDS));
    }

    public String getArrivalNoticeDateText() {
        return entityData.get(DATA_ARRIVAL_NOTICE_DATE_TEXT);
    }

    private void beginArrivalFeedback(@Nullable DockBlockEntity destination) {
        if (level().isClientSide) {
            return;
        }
        entityData.set(DATA_ARRIVAL_NOTICE_STATION_NAME, arrivalStationName(destination));
        entityData.set(DATA_ARRIVAL_NOTICE_ELAPSED_SECONDS, arrivalElapsedSecondsFromDeparture());
        entityData.set(DATA_ARRIVAL_NOTICE_DATE_TEXT,
                formatArrivalDate(System.currentTimeMillis(), ZoneId.systemDefault()));
        setArrivalNoticeTicks(ARRIVAL_NOTICE_TICKS);
        level().playSound(null, blockPosition(), arrivalSoundEvent(), SoundSource.NEUTRAL, 0.85F, 1.0F);
        // 到港收尾：开宽限期 + 强制对范围内所有玩家重发一次 spawn，让到港时仍是幽灵的船立即现身（同马车）。
        if (level() instanceof ServerLevel serverLevel) {
            postArrivalForcedHoldTicks = POST_ARRIVAL_FORCED_HOLD_TICKS;
            spawnedToPlayers.clear();
            com.monpai.sailboatmod.util.EntityRetrackHelper.resendSpawnToNewTrackers(
                    serverLevel, this, spawnedToPlayers, true);
        }
    }

    private void setArrivalNoticeTicks(int ticks) {
        // 存「剩余可见 tick」（服务端权威递减），不再存绝对到期 tick。
        int clamped = Mth.clamp(ticks, 0, ARRIVAL_NOTICE_TICKS);
        entityData.set(DATA_ARRIVAL_NOTICE_UNTIL_TICK, clamped);
    }

    /** 服务端每 tick 递减到站提示剩余可见时长（驱动其按时消失，不依赖客户端 tick）。 */
    private void tickArrivalNoticeCountdown() {
        if (level().isClientSide) {
            return;
        }
        int remaining = entityData.get(DATA_ARRIVAL_NOTICE_UNTIL_TICK);
        if (remaining > 0) {
            entityData.set(DATA_ARRIVAL_NOTICE_UNTIL_TICK, remaining - 1);
        }
    }

    /** 清空到站通知 4 字段。仅在发车入口调用，避免抹掉到站路径刚显示的通知。 */
    private void resetArrivalNotice() {
        if (level().isClientSide) {
            return;
        }
        entityData.set(DATA_ARRIVAL_NOTICE_UNTIL_TICK, 0);
        entityData.set(DATA_ARRIVAL_NOTICE_STATION_NAME, "");
        entityData.set(DATA_ARRIVAL_NOTICE_ELAPSED_SECONDS, 0);
        entityData.set(DATA_ARRIVAL_NOTICE_DATE_TEXT, "");
    }

    private int arrivalElapsedSecondsFromDeparture() {
        if (autopilotShipmentDepartureEpochMillis <= 0L) {
            return 0;
        }
        long elapsedMillis = Math.max(0L, System.currentTimeMillis() - autopilotShipmentDepartureEpochMillis);
        long seconds = elapsedMillis / 1000L;
        return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
    }

    private static String formatArrivalElapsedSeconds(int seconds) {
        int safeSeconds = Math.max(0, seconds);
        int hours = safeSeconds / 3600;
        int minutes = (safeSeconds % 3600) / 60;
        int remainingSeconds = safeSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, remainingSeconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, remainingSeconds);
    }

    private static String formatArrivalDate(long epochMillis, @Nullable ZoneId zoneId) {
        ZoneId safeZone = zoneId == null ? ZoneId.systemDefault() : zoneId;
        return ARRIVAL_DATE_FORMATTER.format(Instant.ofEpochMilli(epochMillis).atZone(safeZone));
    }

    private static String arrivalStationName(@Nullable DockBlockEntity destination) {
        if (destination == null) {
            return "Dock";
        }
        String name = destination.getDockName();
        return name == null || name.isBlank() ? "Dock" : name.trim();
    }

    private static SoundEvent arrivalSoundEvent() {
        return SoundEvents.PLAYER_LEVELUP;
    }

    private void applyDockHoldState(DockBlockEntity dock) {
        Vec3 dockCenter = new Vec3(dock.getBlockPos().getX() + 0.5D, getY(), dock.getBlockPos().getZ() + 0.5D);
        dockHoldTicks = 240;
        dockHoldPos = computeDockApproachPoint(dock);
        if (dockHoldPos == null) {
            dockHoldPos = dockCenter;
        }
        dockHoldYaw = computeDockHoldYaw(dock, dockHoldPos);
        inertialPlanarVelocity = Vec3.ZERO;
        commandedForwardAccel = 0.0D;
        setDeltaMovement(Vec3.ZERO);
    }

    private float computeDockHoldYaw(DockBlockEntity dock, Vec3 holdPos) {
        Vec3 dockCenter = new Vec3(dock.getBlockPos().getX() + 0.5D, holdPos.y, dock.getBlockPos().getZ() + 0.5D);
        double awayX = holdPos.x - dockCenter.x;
        double awayZ = holdPos.z - dockCenter.z;
        if (awayX * awayX + awayZ * awayZ <= 1.0E-4D && autopilotRoute.size() >= 2) {
            Vec3 finalWaypoint = autopilotRoute.get(autopilotRoute.size() - 1);
            Vec3 previous = autopilotRoute.get(autopilotRoute.size() - 2);
            awayX = previous.x - finalWaypoint.x;
            awayZ = previous.z - finalWaypoint.z;
        }
        if (awayX * awayX + awayZ * awayZ <= 1.0E-4D) {
            return getYRot();
        }
        return (float) (Mth.atan2(-awayX, awayZ) * (180.0D / Math.PI));
    }

    private boolean hasTransportOrder(List<ShipmentManifestEntry> manifest) {
        if (manifest == null || manifest.isEmpty()) {
            return false;
        }
        for (ShipmentManifestEntry entry : manifest) {
            if (entry == null) {
                continue;
            }
            String purchaseOrderId = entry.purchaseOrderId();
            String shippingOrderId = entry.shippingOrderId();
            if ((purchaseOrderId != null && !purchaseOrderId.isBlank())
                    || (shippingOrderId != null && !shippingOrderId.isBlank())) {
                return true;
            }
        }
        return false;
    }

    public List<ItemStack> unloadAllCargo() {
        return drainAllCargo();
    }

    private void mergeIntoInventory(ItemStack remaining) {
        mergeIntoInventory(remaining, inventory);
    }

    private void mergeIntoInventory(ItemStack remaining, NonNullList<ItemStack> targetInventory) {
        for (int i = 0; i < targetInventory.size() && !remaining.isEmpty(); i++) {
            ItemStack slot = targetInventory.get(i);
            if (slot.isEmpty()) {
                continue;
            }
            if (!ItemStack.isSameItemSameTags(slot, remaining)) {
                continue;
            }
            int limit = Math.min(slot.getMaxStackSize(), container.getMaxStackSize());
            int canMove = Math.min(limit - slot.getCount(), remaining.getCount());
            if (canMove <= 0) {
                continue;
            }
            slot.grow(canMove);
            remaining.shrink(canMove);
        }
        for (int i = 0; i < targetInventory.size() && !remaining.isEmpty(); i++) {
            ItemStack slot = targetInventory.get(i);
            if (!slot.isEmpty()) {
                continue;
            }
            int toMove = Math.min(remaining.getMaxStackSize(), remaining.getCount());
            ItemStack moved = remaining.copy();
            moved.setCount(toMove);
            targetInventory.set(i, moved);
            remaining.shrink(toMove);
        }
    }

    private void rollbackMarketShipment() {
        if (level().isClientSide || autopilotShipmentManifest.isEmpty()) {
            return;
        }
        MarketSavedData market = MarketSavedData.get(level());
        boolean rolledBackToMarket = canRestoreMarketShipment(market);
        List<ItemStack> cargo = drainAllCargo();
        if (!rolledBackToMarket && !cargo.isEmpty()) {
            loadCargo(cargo);
        }
        for (ShipmentManifestEntry entry : autopilotShipmentManifest) {
            if (!entry.isMarketOrder()) {
                continue;
            }
            PurchaseOrder purchaseOrder = market.getPurchaseOrder(entry.purchaseOrderId());
            if (purchaseOrder != null) {
                String nextStatus = rolledBackToMarket ? "WAITING_SHIPMENT" : "FAILED";
                market.putPurchaseOrder(new PurchaseOrder(
                        purchaseOrder.orderId(),
                        purchaseOrder.listingId(),
                        purchaseOrder.buyerUuid(),
                        purchaseOrder.buyerName(),
                        purchaseOrder.quantity(),
                        purchaseOrder.totalPrice(),
                        purchaseOrder.sourceDockPos(),
                        purchaseOrder.sourceDockName(),
                        purchaseOrder.targetDockPos(),
                        purchaseOrder.targetDockName(),
                        nextStatus,
                        purchaseOrder.fulfillment(),
                        purchaseOrder.targetWarehousePos()
                ));
                MarketListing listing = market.getListing(purchaseOrder.listingId());
                if (listing != null && rolledBackToMarket) {
                    market.putListing(new MarketListing(
                            listing.listingId(),
                            listing.sellerUuid(),
                            listing.sellerName(),
                            listing.itemStack(),
                            listing.unitPrice(),
                            listing.availableCount(),
                            listing.reservedCount() + purchaseOrder.quantity(),
                            listing.sourceDockPos(),
                            listing.sourceDockName(),
                            listing.townId(),
                            listing.nationId(),
                            listing.priceAdjustmentBp(),
                            listing.sellerNote()
                    ));
                }
            }
            ShippingOrder shippingOrder = market.getShippingOrder(entry.shippingOrderId());
            if (shippingOrder != null) {
                market.putShippingOrder(new ShippingOrder(
                        shippingOrder.shippingOrderId(),
                        shippingOrder.purchaseOrderId(),
                        shippingOrder.shipperUuid(),
                        shippingOrder.shipperName(),
                        shippingOrder.boatUuid(),
                        shippingOrder.boatName(),
                        shippingOrder.boatMode(),
                        shippingOrder.transportMode(),
                        shippingOrder.routeName(),
                        shippingOrder.sourceDockPos(),
                        shippingOrder.sourceDockName(),
                        shippingOrder.targetDockPos(),
                        shippingOrder.targetDockName(),
                        shippingOrder.sourceTerminalName(),
                        shippingOrder.targetTerminalName(),
                        shippingOrder.distanceMeters(),
                        shippingOrder.etaSeconds(),
                        shippingOrder.rentalFee(),
                        rolledBackToMarket ? "FAILED_ROLLBACK" : "FAILED"
                ));
                ShippingTraceService.updateStatus(level(), shippingOrder.shippingOrderId(), rolledBackToMarket ? "FAILED_ROLLBACK" : "FAILED");
            }
        }
    }

    private boolean canRestoreMarketShipment(MarketSavedData market) {
        boolean hasMarketOrder = false;
        for (ShipmentManifestEntry entry : autopilotShipmentManifest) {
            if (!entry.isMarketOrder()) {
                continue;
            }
            hasMarketOrder = true;
            PurchaseOrder purchaseOrder = market.getPurchaseOrder(entry.purchaseOrderId());
            if (purchaseOrder == null || market.getListing(purchaseOrder.listingId()) == null) {
                return false;
            }
        }
        return hasMarketOrder;
    }

    private List<ItemStack> drainAllCargo() {
        List<ItemStack> cargo = new ArrayList<>();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            cargo.add(stack.copy());
            inventory.set(i, ItemStack.EMPTY);
        }
        return cargo;
    }

    private boolean tryStartReturnTrip(DockBlockEntity currentDock, @Nullable BlockPos returnDockPos,
                                       @Nullable String buyerUuid, @Nullable String buyerName) {
        if (level().isClientSide || currentDock == null || returnDockPos == null || autopilotRoute.size() < 2) {
            return false;
        }
        List<Vec3> reversedWaypoints = new ArrayList<>(autopilotRoute);
        java.util.Collections.reverse(reversedWaypoints);
        RouteDefinition reverseRoute = new RouteDefinition(
                (autopilotRouteName == null || autopilotRouteName.isBlank() ? "Route" : autopilotRouteName) + " (Return)",
                reversedWaypoints,
                currentDock.getOwnerName(),
                currentDock.getOwnerUuid(),
                System.currentTimeMillis(),
                computeRouteLengthMeters(reversedWaypoints),
                currentDock.getDockName(),
                getTransportHubDisplayName(returnDockPos)
        );

        String preferredBuyerUuid = buyerUuid == null || buyerUuid.isBlank() ? getOwnerUuid() : buyerUuid;
        String preferredBuyerName = buyerName == null || buyerName.isBlank() ? getOwnerName() : buyerName;
        currentDock.tryLoadReturnCargo(this, returnDockPos, preferredBuyerUuid, preferredBuyerName);

        setRouteCatalog(List.of(reverseRoute), 0, endDockPosOrCurrent(currentDock));
        autopilotReturnTrip = true;
        return startAutopilot();
    }

    /**
     * 多站连运：从当前港规划并发往下一个未送达运单的目的港。
     * 优先复用当前港已存的航线；没有则提交自动航线生成并停泊等待（异步），命中后由 {@link #tryResumeAwaitedWaterLeg()} 续运。
     */
    private boolean tryStartWaterLegToNextPort(DockBlockEntity here, List<ShipmentManifestEntry> keepOnboard) {
        if (here == null || level().isClientSide || !(level() instanceof ServerLevel)) {
            return false;
        }
        BlockPos nextPort = DockBlockEntity.nextStationDestination(level(), keepOnboard);
        if (nextPort == null || !(level().getBlockEntity(nextPort) instanceof DockBlockEntity nextDock)
                || nextDock instanceof PostStationBlockEntity) {
            return false;
        }
        RouteDefinition leg = pickRouteToDock(here.getRoutesForMap(), nextPort);
        if (leg != null) {
            beginWaterLeg(here, leg);
            return true;
        }
        // 无现成航线 → 异步生成去下一港的水路，停泊等待。
        WaterAutoRouteService.submitAutoRoute((ServerLevel) level(), here, nextDock, null);
        awaitingNextLegPort = nextPort.immutable();
        awaitingNextLegFromDock = here.getBlockPos().immutable();
        awaitingNextLegTicks = WATER_LEG_GENERATION_TIMEOUT_TICKS;
        applyDockHoldState(here);
        stopAutopilot(false);
        return true;
    }

    /** 停泊等待期间轮询：去下一港的航线已生成则续运，超时则放弃（货留船等人工）。 */
    private void tryResumeAwaitedWaterLeg() {
        if (level().isClientSide || awaitingNextLegPort == null || awaitingNextLegFromDock == null) {
            return;
        }
        if (awaitingNextLegTicks-- <= 0) {
            clearAwaitingWaterLeg();
            return;
        }
        if (!(level().getBlockEntity(awaitingNextLegFromDock) instanceof DockBlockEntity here)) {
            clearAwaitingWaterLeg();
            return;
        }
        RouteDefinition leg = pickRouteToDock(here.getRoutesForMap(), awaitingNextLegPort);
        if (leg == null) {
            return; // 继续等待生成完成
        }
        clearAwaitingWaterLeg();
        beginWaterLeg(here, leg);
    }

    private void beginWaterLeg(DockBlockEntity here, RouteDefinition leg) {
        // 续运：保持去程状态（autopilotReturnTrip=false），剩余运单已在卸货阶段剪枝。
        setAllowNonOrderAutoReturn(autopilotAllowNonOrderAutoReturn);
        setRouteCatalog(List.of(leg), 0, here.getBlockPos());
        autopilotReturnTrip = false;
        dockHoldTicks = 0;
        startAutopilot();
    }

    private void clearAwaitingWaterLeg() {
        awaitingNextLegPort = null;
        awaitingNextLegFromDock = null;
        awaitingNextLegTicks = 0;
    }

    @Nullable
    private RouteDefinition pickRouteToDock(List<RouteDefinition> routes, BlockPos targetDockPos) {
        if (routes == null || targetDockPos == null) {
            return null;
        }
        for (RouteDefinition route : routes) {
            if (route == null || route.waypoints().size() < 2) {
                continue;
            }
            Vec3 end = route.waypoints().get(route.waypoints().size() - 1);
            BlockPos endDock = findTransportHubZoneContains(end);
            if (endDock == null) {
                endDock = findNearestRegisteredTransportHub(end, 64.0D);
            }
            if (targetDockPos.equals(endDock)) {
                return route;
            }
        }
        return null;
    }

    private BlockPos endDockPosOrCurrent(DockBlockEntity dock) {
        return dock.getBlockPos();
    }

    private boolean isInsideRouteStartWaitingZone(RouteDefinition route) {
        if (route.waypoints().isEmpty()) {
            return false;
        }
        BlockPos startDockPos = findTransportHubZoneContains(route.waypoints().get(0));
        DockBlockEntity dock = startDockPos == null ? null : getTransportHub(startDockPos);
        if (dock == null) {
            return false;
        }
        return dock.isInsideDockZone(position());
    }

    @Nullable
    protected DockBlockEntity getTransportHub(BlockPos pos) {
        return level().getBlockEntity(pos) instanceof DockBlockEntity dock ? dock : null;
    }

    @Nullable
    protected BlockPos findTransportHubZoneContains(Vec3 point) {
        return DockBlockEntity.findDockZoneContains(level(), point);
    }

    @Nullable
    protected BlockPos findNearestRegisteredTransportHub(Vec3 point, double maxDistance) {
        return DockBlockEntity.findNearestRegisteredDock(level(), point, maxDistance);
    }

    protected String getTransportHubDisplayName(BlockPos pos) {
        return DockBlockEntity.getDockDisplayName(level(), pos);
    }

    private int findNearestWaypointIndex() {
        if (autopilotRoute.isEmpty()) {
            return 0;
        }
        int nearest = 0;
        double nearestSq = Double.MAX_VALUE;
        for (int i = 0; i < autopilotRoute.size(); i++) {
            Vec3 waypoint = autopilotRoute.get(i);
            double dx = waypoint.x - getX();
            double dz = waypoint.z - getZ();
            double distSq = dx * dx + dz * dz;
            if (distSq < nearestSq) {
                nearestSq = distSq;
                nearest = i;
            }
        }
        return nearest;
    }

    private void updateAutopilotChunkLoading() {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        // 幽灵船修复（同马车）：autopilot 全程 + 到港宽限期内对范围内玩家维持可见。
        // 多 mod 坏掉 EntityTracker 时只发一次/不发 spawn 会导致远行返回看不见（幽灵船）。
        // 每 2tick 发 teleport+motion 维持移动；每 ENROUTE_SPAWN_HEARTBEAT_TICKS(60=3s) 重发整套 spawn 兜底。
        if (isAutopilotActive() || postArrivalForcedHoldTicks > 0) {
            if (tickCount % 2 == 0) {
                boolean spawnHeartbeat = (tickCount % ENROUTE_SPAWN_HEARTBEAT_TICKS == 0);
                com.monpai.sailboatmod.util.EntityRetrackHelper.resendSpawnToNewTrackers(
                        serverLevel, this, spawnedToPlayers, spawnHeartbeat);
            }
        } else {
            spawnedToPlayers.clear();
        }
        if (!isAutopilotActive()) {
            // 宽限期递减；期满才释放票据，给 ChunkMap 几秒重建客户端追踪（同马车）。
            if (postArrivalForcedHoldTicks > 0) {
                postArrivalForcedHoldTicks--;
            } else {
                clearAutopilotForcedChunks(serverLevel);
            }
            return;
        }
        Set<Long> requiredChunks = new HashSet<>();
        int boatChunkX = Mth.floor(getX()) >> 4;
        int boatChunkZ = Mth.floor(getZ()) >> 4;
        addForcedChunkArea(requiredChunks, boatChunkX, boatChunkZ, AUTOPILOT_CHUNK_RADIUS);

        if (!autopilotRoute.isEmpty()) {
            int targetIndex = Mth.clamp(autopilotTargetIndex, 0, autopilotRoute.size() - 1);
            Vec3 target = autopilotRoute.get(targetIndex);
            int targetChunkX = Mth.floor(target.x) >> 4;
            int targetChunkZ = Mth.floor(target.z) >> 4;
            addForcedChunkArea(requiredChunks, targetChunkX, targetChunkZ, AUTOPILOT_TARGET_CHUNK_RADIUS);

            if (targetIndex + 1 < autopilotRoute.size()) {
                Vec3 nextTarget = autopilotRoute.get(targetIndex + 1);
                int nextChunkX = Mth.floor(nextTarget.x) >> 4;
                int nextChunkZ = Mth.floor(nextTarget.z) >> 4;
                addForcedChunkArea(requiredChunks, nextChunkX, nextChunkZ, AUTOPILOT_TARGET_CHUNK_RADIUS);
            }
        }
        BlockPos destDockPos = findAutopilotDestinationDockPos();
        if (destDockPos != null) {
            addForcedChunkArea(
                    requiredChunks,
                    destDockPos.getX() >> 4,
                    destDockPos.getZ() >> 4,
                    AUTOPILOT_DEST_DOCK_CHUNK_RADIUS
            );
        }

        for (long chunkKey : requiredChunks) {
            if (!forcedAutopilotChunks.contains(chunkKey)) {
                setAutopilotChunkForced(serverLevel, chunkKey, true);
            }
        }

        if (!forcedAutopilotChunks.isEmpty()) {
            Set<Long> stale = new HashSet<>(forcedAutopilotChunks);
            stale.removeAll(requiredChunks);
            for (long chunkKey : stale) {
                setAutopilotChunkForced(serverLevel, chunkKey, false);
            }
        }

        forcedAutopilotChunks.clear();
        forcedAutopilotChunks.addAll(requiredChunks);
    }

    /**
     * 强制/取消加载区块。用 ForgeChunkManager.forceChunk(ticking=true) 申请 ENTITY_TICKING 票据。
     * 必须用 ticking 票据：vanilla setChunkForced 只给 FORCED 票据(level 31)，玩家不在附近时
     * ChunkMap 不为区块内实体建 EntityTrackerEntry —— 实体在服务端 tick 却从不向客户端发 spawn 包，
     * 导致载具远行返回后「看不见但有音效」。ENTITY_TICKING 保证实体被客户端追踪可见，
     * 且实体移除即释放(不像 vanilla 会持久化到 level.dat)。
     */
    private void setAutopilotChunkForced(ServerLevel serverLevel, long chunkKey, boolean add) {
        int chunkX = ChunkPos.getX(chunkKey);
        int chunkZ = ChunkPos.getZ(chunkKey);
        BlockPos owner = new BlockPos(chunkX << 4, 0, chunkZ << 4);
        net.minecraftforge.common.world.ForgeChunkManager.forceChunk(
                serverLevel, com.monpai.sailboatmod.SailboatMod.MODID,
                owner, chunkX, chunkZ, add, true);
    }

    private void addForcedChunkArea(Set<Long> out, int centerX, int centerZ, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                out.add(ChunkPos.asLong(centerX + dx, centerZ + dz));
            }
        }
    }

    private void clearAutopilotForcedChunks(ServerLevel serverLevel) {
        for (long chunkKey : forcedAutopilotChunks) {
            setAutopilotChunkForced(serverLevel, chunkKey, false);
        }
        forcedAutopilotChunks.clear();
    }

    protected record AutopilotCommand(boolean active, boolean wantsTurn, float turnInput, float yawStep, EngineGear gear) {
        private static AutopilotCommand inactive() {
            return new AutopilotCommand(false, false, 0.0F, 0.0F, EngineGear.STOP);
        }
    }

    protected record GroundDriveContext(boolean autopilotControl,
                                        boolean hasManualInput,
                                        boolean wantsForward,
                                        boolean wantsReverse,
                                        boolean wantsTurn,
                                        float turnInput,
                                        EngineGear gear) {
    }

    private int seatFromEntityData(int entityId) {
        if (entityData.get(DATA_SEAT_0) == entityId) {
            return 0;
        }
        if (entityData.get(DATA_SEAT_1) == entityId) {
            return 1;
        }
        if (entityData.get(DATA_SEAT_2) == entityId) {
            return 2;
        }
        if (entityData.get(DATA_SEAT_3) == entityId) {
            return 3;
        }
        if (entityData.get(DATA_SEAT_4) == entityId) {
            return 4;
        }
        return -1;
    }

    private void syncSeatEntityData() {
        if (level().isClientSide) {
            return;
        }
        int[] seatIds = new int[] {-1, -1, -1, -1, -1};
        for (Entity passenger : getPassengers()) {
            Integer seat = seatAssignments.get(passenger.getUUID());
            if (seat != null && seat >= 0 && seat < SEAT_COUNT && seatIds[seat] == -1) {
                seatIds[seat] = passenger.getId();
            }
        }
        entityData.set(DATA_SEAT_0, seatIds[0]);
        entityData.set(DATA_SEAT_1, seatIds[1]);
        entityData.set(DATA_SEAT_2, seatIds[2]);
        entityData.set(DATA_SEAT_3, seatIds[3]);
        entityData.set(DATA_SEAT_4, seatIds[4]);
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return ChestMenu.threeRows(containerId, playerInventory, container);
    }

    public enum AutopilotControlAction {
        START,
        PAUSE,
        RESUME,
        STOP,
        NEXT_ROUTE,
        PREV_ROUTE
    }

    public enum HandlingPreset {
        REALISTIC(0, 1.082D, 0.989D, 0.962D, 0.988D, 1.00D, 0.228D, 0.036D, 0.024D),
        BALANCED(1, 1.103D, 0.992D, 0.972D, 0.992D, 1.20D, 0.288D, 0.042D, 0.027D),
        ARCADE(2, 1.128D, 0.995D, 0.980D, 0.996D, 1.42D, 0.360D, 0.048D, 0.030D);

        public final int id;
        public final double forwardAcceleration;
        public final double idleDrag;
        public final double reverseDrag;
        public final double turningDrag;
        public final double maxSpeed;
        public final double sailThrust;
        public final double stowedThrust;
        public final double reverseThrust;

        HandlingPreset(int id, double forwardAcceleration, double idleDrag, double reverseDrag, double turningDrag, double maxSpeed,
                       double sailThrust, double stowedThrust, double reverseThrust) {
            this.id = id;
            this.forwardAcceleration = forwardAcceleration;
            this.idleDrag = idleDrag;
            this.reverseDrag = reverseDrag;
            this.turningDrag = turningDrag;
            this.maxSpeed = maxSpeed;
            this.sailThrust = sailThrust;
            this.stowedThrust = stowedThrust;
            this.reverseThrust = reverseThrust;
        }

        public static HandlingPreset byId(int id) {
            for (HandlingPreset value : values()) {
                if (value.id == id) {
                    return value;
                }
            }
            return BALANCED;
        }

        public HandlingPreset next() {
            int nextIndex = (this.ordinal() + 1) % values().length;
            return values()[nextIndex];
        }
    }

    public enum EngineGear {
        FULL_ASTERN(-2, "FULL ASTERN", "gear.sailboatmod.full_astern", -1.00D, -0.55D, 0.0060D, 0.0035D),
        HALF_ASTERN(-1, "HALF ASTERN", "gear.sailboatmod.half_astern", -0.55D, -0.30D, 0.0045D, 0.0028D),
        STOP(0, "STOP", "gear.sailboatmod.stop", 0.0D, 0.0D, 0.0D, 0.0D),
        ONE_THIRD_AHEAD(1, "1/3 AHEAD", "gear.sailboatmod.one_third_ahead", 0.40D, 0.30D, 0.0100D, 0.0060D),
        TWO_THIRDS_AHEAD(2, "2/3 AHEAD", "gear.sailboatmod.two_thirds_ahead", 0.70D, 0.62D, 0.0070D, 0.0042D),
        FULL_AHEAD(3, "FULL AHEAD", "gear.sailboatmod.full_ahead", 1.00D, 0.50D, 0.0048D, 0.0030D);

        public final int id;
        public final String displayName;
        public final String translationKey;
        private final double deployedScale;
        private final double stowedScale;
        private final double deployedAccel;
        private final double stowedAccel;

        EngineGear(int id, String displayName, String translationKey, double deployedScale, double stowedScale, double deployedAccel, double stowedAccel) {
            this.id = id;
            this.displayName = displayName;
            this.translationKey = translationKey;
            this.deployedScale = deployedScale;
            this.stowedScale = stowedScale;
            this.deployedAccel = deployedAccel;
            this.stowedAccel = stowedAccel;
        }

        public double targetSpeed(double maxForward, double maxReverse, boolean sailDeployed) {
            double scale = sailDeployed ? deployedScale : stowedScale;
            return scale < 0.0D ? maxReverse * scale : maxForward * scale;
        }

        public double maxAllowedSpeed(double maxForward, double maxReverse, boolean sailDeployed) {
            return Math.abs(targetSpeed(maxForward, maxReverse, sailDeployed));
        }

        public double accelTarget(boolean sailDeployed) {
            double scale = sailDeployed ? deployedScale : stowedScale;
            double accel = sailDeployed ? deployedAccel : stowedAccel;
            return Math.signum(scale) * accel;
        }

        public EngineGear shiftUp() {
            return byId(Mth.clamp(this.id + 1, FULL_ASTERN.id, FULL_AHEAD.id));
        }

        public EngineGear shiftDown() {
            return byId(Mth.clamp(this.id - 1, FULL_ASTERN.id, FULL_AHEAD.id));
        }

        public static EngineGear byId(int id) {
            for (EngineGear value : values()) {
                if (value.id == id) {
                    return value;
                }
            }
            return STOP;
        }
    }
}
