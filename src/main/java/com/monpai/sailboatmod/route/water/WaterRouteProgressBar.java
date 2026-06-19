package com.monpai.sailboatmod.route.water;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.server.level.ServerBossEvent;

/**
 * 水路航线计算进度条——原版 {@link ServerBossEvent} 血条式(玩家屏幕顶部),零客户端 UI 代码、零新 packet。
 *
 * <p><b>为什么</b>:航线计算可能跑几十秒~几分钟(中段沿走廊分批加载真实区块精寻)。玩家发起后要实时看到进度,
 * 不然以为卡死。原版 BossBar Forge 自动同步给加进来的玩家,客户端原生渲染,玩家关不关码头界面都看得到。
 *
 * <p><b>线程模型</b>:航线计算在后台 WaterRoute-Worker 线程跑,但 {@link ServerBossEvent} 的增删玩家/改进度
 * <b>必须主线程</b>(否则并发改玩家集合炸)。所以每个方法都 {@code server.execute} 投递主线程执行(fire-and-forget,
 * 后台不等),与 [[marketweb_snapshot_deadlock]] 的死锁红线一致——主线程不回头等后台、后台不阻塞等主线程。
 */
public final class WaterRouteProgressBar {
    private static final long DONE_LINGER_MS = 2500L; // 完成/失败后血条保留时长(让玩家看到结果)

    private final MinecraftServer server;
    private final ServerPlayer player; // 可 null(无玩家发起时不显示)
    private final String sourceName;
    private final String targetName;
    private final ServerBossEvent bar;

    public WaterRouteProgressBar(MinecraftServer server, ServerPlayer player, String sourceName, String targetName) {
        this.server = server;
        this.player = player;
        this.sourceName = sourceName == null ? "" : sourceName;
        this.targetName = targetName == null ? "" : targetName;
        this.bar = new ServerBossEvent(
                title("准备中", 0),
                BossEvent.BossBarColor.BLUE,
                BossEvent.BossBarOverlay.PROGRESS);
        this.bar.setProgress(0.0F);
    }

    /** 显示血条(加发起玩家)。无玩家/无 server 则空操作。 */
    public void show() {
        if (server == null || player == null) {
            return;
        }
        server.execute(() -> bar.addPlayer(player));
    }

    /** 更新进度(0~100)+ 阶段文案。后台调用,内部切主线程。 */
    public void update(int percent, String stageLabel) {
        if (server == null || player == null) {
            return;
        }
        int p = Math.max(0, Math.min(100, percent));
        server.execute(() -> {
            bar.setName(title(stageLabel, p));
            bar.setProgress(p / 100.0F);
        });
    }

    /** 成功收尾:满格绿条短暂保留后移除。 */
    public void done() {
        finish("完成", BossEvent.BossBarColor.GREEN, 100);
    }

    /** 失败收尾:红条短暂保留后移除。 */
    public void fail(String reasonLabel) {
        finish(reasonLabel == null || reasonLabel.isBlank() ? "失败" : reasonLabel,
                BossEvent.BossBarColor.RED, 100);
    }

    private void finish(String label, BossEvent.BossBarColor color, int percent) {
        if (server == null || player == null) {
            return;
        }
        server.execute(() -> {
            bar.setColor(color);
            bar.setName(title(label, percent));
            bar.setProgress(Math.max(0, Math.min(100, percent)) / 100.0F);
            // 短暂保留再移除:用一个延迟 tick 任务不便,这里简单起见同步设状态后由调用方流程自然结束;
            // 为确保血条不永久残留,启一个轻量守护线程 sleep 后切主线程移除(daemon,不阻塞关服)。
            scheduleRemoval();
        });
    }

    private void scheduleRemoval() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(DONE_LINGER_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (server != null) {
                server.execute(bar::removeAllPlayers);
            }
        }, "WaterRouteBar-Linger");
        t.setDaemon(true);
        t.start();
    }

    private Component title(String stage, int percent) {
        String s = stage == null || stage.isBlank() ? "计算中" : stage;
        String route = sourceName.isEmpty() && targetName.isEmpty()
                ? "" : (" " + sourceName + " → " + targetName);
        return Component.literal("航线计算" + route + ":" + s + " " + percent + "%");
    }
}
