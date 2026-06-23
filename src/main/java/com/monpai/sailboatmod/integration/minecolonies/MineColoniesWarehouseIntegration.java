package com.monpai.sailboatmod.integration.minecolonies;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandlerModifiable;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 殖民地(MineColonies)仓库的<b>读取 + 扣货</b>反射桥,供市场直接上架殖民地仓库物品。
 *
 * <p>全程<b>反射、无编译期依赖</b>(MineColonies 是可选 mod),范式与 {@link MineColoniesIntegration} 一致:
 * {@code ModList.isLoaded("minecolonies")} 守门 + 懒初始化 + 任一反射失败即 {@code disabled} 永久降级(返回空/false,
 * 绝不抛到调用方)。底层 Rack 的 inventory 是标准 Forge {@link IItemHandlerModifiable}(非 MineColonies 专有),
 * 故 extractItem/getSlots 直接调,只有定位 colony/warehouse/rack 的链路走反射。</p>
 *
 * <p>反射链:{@code IColonyManager.getInstance().getColonyByWorld(id, level)} → {@code IColony.getServerBuildingManager()}
 * → {@code IRegisteredStructureManager.getClosestWarehouseInColony(BlockPos)} → {@code IWareHouse.getTileEntity()}
 * → {@code AbstractTileEntityWareHouse.getMatchingItemStacksInWarehouse(Predicate)} 返回 {@code List<Tuple<ItemStack,BlockPos(rack)>>};
 * 扣货定位 rack TE → {@code AbstractTileEntityRack.getInventory()}(IItemHandlerModifiable)→ extractItem。
 * 权限:{@code IColony.getPermissions().hasPermission(Player, Action)}。</p>
 */
public final class MineColoniesWarehouseIntegration {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile Bridge bridge;
    private static volatile boolean initAttempted;
    private static volatile boolean disabled;

    private MineColoniesWarehouseIntegration() {
    }

    public static boolean isAvailable() {
        return bridge() != null;
    }

    /** 殖民地仓库内物品快照(去重合并同类,count 为总量;rackPos 仅记一处供调试)。供市场 UI/web 列出可上架货。 */
    public record WarehouseItem(ItemStack stack, int count, BlockPos rackPos) {
    }

    /**
     * 读某殖民地仓库的全部物品(合并同类堆叠为总量)。colony/仓库未加载或无 MineColonies 时返回空列表。
     * {@code anchorPos} 用于 getClosestWarehouseInColony 定位仓库(传殖民地核心或任一已知 claim 中心)。
     */
    public static List<WarehouseItem> readWarehouse(ServerLevel level, int colonyId, BlockPos anchorPos) {
        Bridge api = bridge();
        if (api == null || level == null) {
            return List.of();
        }
        try {
            return api.readWarehouse(level, colonyId, anchorPos);
        } catch (Throwable t) {
            disabled = true;
            LOGGER.error("Disabling MineColonies warehouse integration after read failure", t);
            return List.of();
        }
    }

    /**
     * 从某殖民地仓库抽取最多 {@code quantity} 个匹配 {@code sample} 的物品,返回实际抽出的 ItemStack 列表(已从仓库扣除)。
     * 抽不到(无货/未加载/无 MineColonies)返回空列表。<b>原子性</b>:本方法只负责扣货,调用方须保证"扣到的货确实入了
     * 市场发货池"再建 listing;若入库失败应把返回的货还回(insertCargo)。
     */
    public static List<ItemStack> extractFromWarehouse(ServerLevel level, int colonyId, BlockPos anchorPos,
                                                       ItemStack sample, int quantity) {
        Bridge api = bridge();
        if (api == null || level == null || sample == null || sample.isEmpty() || quantity <= 0) {
            return List.of();
        }
        try {
            return api.extractFromWarehouse(level, colonyId, anchorPos, sample, quantity);
        } catch (Throwable t) {
            disabled = true;
            LOGGER.error("Disabling MineColonies warehouse integration after extract failure", t);
            return List.of();
        }
    }

    /** 该玩家是否有权操作此殖民地(仓库)。无 MineColonies/查不到返回 false(保守拒绝)。 */
    public static boolean canManageWarehouse(ServerLevel level, int colonyId, Player player) {
        Bridge api = bridge();
        if (api == null || level == null || player == null) {
            return false;
        }
        try {
            return api.canManageWarehouse(level, colonyId, player);
        } catch (Throwable t) {
            disabled = true;
            LOGGER.error("Disabling MineColonies warehouse integration after permission check failure", t);
            return false;
        }
    }

    public static void onServerStopped() {
        bridge = null;
        initAttempted = false;
        disabled = false;
    }

    private static Bridge bridge() {
        if (disabled) {
            return null;
        }
        if (!ModList.get().isLoaded("minecolonies")) {
            return null;
        }
        if (!initAttempted) {
            synchronized (MineColoniesWarehouseIntegration.class) {
                if (!initAttempted) {
                    initAttempted = true;
                    try {
                        bridge = new Bridge();
                    } catch (ClassNotFoundException ignored) {
                        return null;
                    } catch (Throwable t) {
                        disabled = true;
                        LOGGER.error("Failed to bootstrap MineColonies warehouse integration", t);
                    }
                }
            }
        }
        return bridge;
    }

    private static final class Bridge {
        private final Object manager;
        private final Method getColonyByWorldMethod;
        private final Method colonyGetServerBuildingManagerMethod;
        private final Method getClosestWarehouseMethod;
        private final Method warehouseGetTileEntityMethod;
        private final Method getMatchingItemStacksMethod;
        private final Method colonyGetPermissionsMethod;
        private final Method permissionsHasPermissionMethod;
        private final Object actionManageHuts;
        private final Method rackGetInventoryMethod;

        private Bridge() throws Exception {
            Class<?> managerClass = Class.forName("com.minecolonies.api.colony.IColonyManager");
            Class<?> colonyClass = Class.forName("com.minecolonies.api.colony.IColony");
            Class<?> buildingManagerClass = Class.forName("com.minecolonies.api.colony.managers.interfaces.IRegisteredStructureManager");
            Class<?> wareHouseClass = Class.forName("com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse");
            Class<?> tileWareHouseClass = Class.forName("com.minecolonies.api.tileentities.AbstractTileEntityWareHouse");
            Class<?> permissionsClass = Class.forName("com.minecolonies.api.colony.permissions.IPermissions");
            Class<?> actionClass = Class.forName("com.minecolonies.api.colony.permissions.Action");
            Class<?> rackClass = Class.forName("com.minecolonies.api.tileentities.AbstractTileEntityRack");

            this.manager = managerClass.getMethod("getInstance").invoke(null);
            this.getColonyByWorldMethod = managerClass.getMethod("getColonyByWorld", int.class, Level.class);
            this.colonyGetServerBuildingManagerMethod = colonyClass.getMethod("getServerBuildingManager");
            this.getClosestWarehouseMethod = buildingManagerClass.getMethod("getClosestWarehouseInColony", BlockPos.class);
            this.warehouseGetTileEntityMethod = wareHouseClass.getMethod("getTileEntity");
            this.getMatchingItemStacksMethod = tileWareHouseClass.getMethod("getMatchingItemStacksInWarehouse", Predicate.class);
            this.colonyGetPermissionsMethod = colonyClass.getMethod("getPermissions");
            this.permissionsHasPermissionMethod = permissionsClass.getMethod("hasPermission", Player.class, actionClass);
            this.actionManageHuts = resolveAction(actionClass);
            this.rackGetInventoryMethod = rackClass.getMethod("getInventory");
        }

        @SuppressWarnings("unchecked")
        private List<WarehouseItem> readWarehouse(ServerLevel level, int colonyId, BlockPos anchorPos) throws Exception {
            Object tileWarehouse = resolveTileWarehouse(level, colonyId, anchorPos);
            if (tileWarehouse == null) {
                return List.of();
            }
            Predicate<ItemStack> all = stack -> stack != null && !stack.isEmpty();
            List<?> matches = (List<?>) this.getMatchingItemStacksMethod.invoke(tileWarehouse, all);
            // 合并同类(忽略 rackPos 差异):同 item+nbt 累加总量,供 UI 列出"有多少可上架"。
            List<WarehouseItem> out = new ArrayList<>();
            for (Object raw : matches) {
                Tuple2 tuple = readTuple(raw);
                if (tuple == null || tuple.stack == null || tuple.stack.isEmpty()) {
                    continue;
                }
                WarehouseItem merged = null;
                for (WarehouseItem existing : out) {
                    if (ItemStack.isSameItemSameTags(existing.stack(), tuple.stack)) {
                        merged = existing;
                        break;
                    }
                }
                if (merged == null) {
                    out.add(new WarehouseItem(tuple.stack.copy(), tuple.stack.getCount(), tuple.pos));
                } else {
                    int idx = out.indexOf(merged);
                    out.set(idx, new WarehouseItem(merged.stack(), merged.count() + tuple.stack.getCount(), merged.rackPos()));
                }
            }
            return out;
        }

        private List<ItemStack> extractFromWarehouse(ServerLevel level, int colonyId, BlockPos anchorPos,
                                                     ItemStack sample, int quantity) throws Exception {
            Object tileWarehouse = resolveTileWarehouse(level, colonyId, anchorPos);
            if (tileWarehouse == null) {
                return List.of();
            }
            Predicate<ItemStack> matcher = stack -> stack != null && !stack.isEmpty()
                    && ItemStack.isSameItemSameTags(stack, sample);
            List<?> matches = (List<?>) this.getMatchingItemStacksMethod.invoke(tileWarehouse, matcher);

            List<ItemStack> extracted = new ArrayList<>();
            int remaining = quantity;
            for (Object raw : matches) {
                if (remaining <= 0) {
                    break;
                }
                Tuple2 tuple = readTuple(raw);
                if (tuple == null || tuple.pos == null) {
                    continue;
                }
                Object rackTe = level.getBlockEntity(tuple.pos);
                if (rackTe == null) {
                    continue;
                }
                Object inventoryObj;
                try {
                    inventoryObj = this.rackGetInventoryMethod.invoke(rackTe);
                } catch (Throwable t) {
                    continue; // 该坐标不是 Rack(理论不会,容错跳过)
                }
                if (!(inventoryObj instanceof IItemHandlerModifiable inventory)) {
                    continue;
                }
                remaining -= drainHandler(inventory, sample, remaining, extracted);
            }
            return extracted;
        }

        /** 从一个 Rack inventory 抽最多 want 个匹配 sample 的货,累加进 out,返回本次抽出的数量。 */
        private int drainHandler(IItemHandlerModifiable inventory, ItemStack sample, int want, List<ItemStack> out) {
            int drained = 0;
            for (int slot = 0; slot < inventory.getSlots() && drained < want; slot++) {
                ItemStack inSlot = inventory.getStackInSlot(slot);
                if (inSlot.isEmpty() || !ItemStack.isSameItemSameTags(inSlot, sample)) {
                    continue;
                }
                int take = Math.min(want - drained, inSlot.getCount());
                ItemStack got = inventory.extractItem(slot, take, false);
                if (!got.isEmpty()) {
                    out.add(got);
                    drained += got.getCount();
                }
            }
            return drained;
        }

        private boolean canManageWarehouse(ServerLevel level, int colonyId, Player player) throws Exception {
            Object colony = this.getColonyByWorldMethod.invoke(this.manager, colonyId, level);
            if (colony == null) {
                return false;
            }
            Object permissions = this.colonyGetPermissionsMethod.invoke(colony);
            if (permissions == null || this.actionManageHuts == null) {
                return false;
            }
            Object result = this.permissionsHasPermissionMethod.invoke(permissions, player, this.actionManageHuts);
            return result instanceof Boolean bool && bool;
        }

        private Object resolveTileWarehouse(ServerLevel level, int colonyId, BlockPos anchorPos) throws Exception {
            Object colony = this.getColonyByWorldMethod.invoke(this.manager, colonyId, level);
            if (colony == null) {
                return null;
            }
            Object buildingManager = this.colonyGetServerBuildingManagerMethod.invoke(colony);
            if (buildingManager == null) {
                return null;
            }
            BlockPos anchor = anchorPos == null ? BlockPos.ZERO : anchorPos;
            Object warehouse = this.getClosestWarehouseMethod.invoke(buildingManager, anchor);
            if (warehouse == null) {
                return null;
            }
            return this.warehouseGetTileEntityMethod.invoke(warehouse);
        }

        /** Tuple<ItemStack, BlockPos> 反射读两个分量(MineColonies 用 net.minecraft.world.item.crafting? 否,是 net.minecraft.util.Tuple)。 */
        private Tuple2 readTuple(Object tuple) {
            if (tuple == null) {
                return null;
            }
            try {
                Method getA = tuple.getClass().getMethod("getA");
                Method getB = tuple.getClass().getMethod("getB");
                Object a = getA.invoke(tuple);
                Object b = getB.invoke(tuple);
                ItemStack stack = a instanceof ItemStack s ? s : null;
                BlockPos pos = b instanceof BlockPos p ? p : null;
                return new Tuple2(stack, pos);
            } catch (Throwable t) {
                return null;
            }
        }

        private record Tuple2(ItemStack stack, BlockPos pos) {
        }

        /** 优先 MANAGE_HUTS(仓库属 hut),回退 OPEN_CONTAINER;都无则 null(canManage 返回 false 保守拒绝)。 */
        private static Object resolveAction(Class<?> actionClass) {
            for (String name : new String[]{"MANAGE_HUTS", "OPEN_CONTAINER", "ACCESS_HUTS"}) {
                try {
                    return Enum.valueOf(actionClass.asSubclass(Enum.class), name);
                } catch (IllegalArgumentException ignored) {
                    // 该版本无此动作,试下一个
                }
            }
            return null;
        }
    }
}
