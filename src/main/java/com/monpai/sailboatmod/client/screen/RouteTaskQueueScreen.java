package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.monpai.sailboatmod.client.ui.kit.MeterBar;
import com.monpai.sailboatmod.client.ui.kit.ThemedScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.CancelPortWorkPacket;
import com.monpai.sailboatmod.network.packet.RequestRouteTasksPacket;
import com.monpai.sailboatmod.network.packet.SyncRouteTasksPacket;
import com.monpai.sailboatmod.route.water.RouteTaskView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Task controls query the server ledger, not the lifetime of a destination selection window. */
public final class RouteTaskQueueScreen extends ThemedScreen {
    private final Screen parent;
    private final Set<UUID> cancelling = new HashSet<>();
    private UUID query;
    private List<RouteTaskView> tasks = List.of();
    private ScrollerView list;
    private Label summary;
    private long nextRefresh;

    public RouteTaskQueueScreen(Screen parent) {
        super(Component.literal("寻路任务队列"));
        this.parent = parent;
    }
    @Override protected float windowWidth() { return 360; }
    @Override protected UIElement buildBody() {
        UIElement body = Ui.column();
        summary = Ui.text("正在读取服务端任务…", "sm-muted");
        list = Ui.scroller();
        list.layout(l -> l.height(190).flexGrow(0));
        body.addChildren(summary, list,
                Ui.paragraph(Component.literal("关闭界面不丢失任务。每人最多4个运行任务，保留最近16项。"), "sm-muted"));
        UIElement actions = Ui.row();
        actions.addChildren(Ui.button(Component.literal("返回"), Ui.Tone.SECONDARY, this::onClose),
                Ui.button(Component.literal("刷新任务"), Ui.Tone.SECONDARY, this::request));
        body.addChild(actions);
        renderTasks();
        return body;
    }
    @Override protected void afterInit() { request(); }
    private void request() {
        query = UUID.randomUUID();
        nextRefresh = ticks() + 100; // Do not supersede a response on a high-latency connection.
        ModNetwork.CHANNEL.sendToServer(new RequestRouteTasksPacket(query));
    }
    public void receive(SyncRouteTasksPacket packet) {
        if (!packet.query().equals(query)) return;
        tasks = packet.tasks();
        nextRefresh = ticks() + 20;
        cancelling.removeIf(id -> tasks.stream().noneMatch(t -> t.id().equals(id) && t.active()));
        renderTasks();
    }
    private void renderTasks() {
        if (list == null) return;
        list.clearAllScrollViewChildren();
        long active = tasks.stream().filter(RouteTaskView::active).count();
        summary.setValue(Component.literal("运行 / 排队：" + active + "　最近任务：" + tasks.size()));
        if (tasks.isEmpty()) list.addScrollViewChild(Ui.emptyState(Component.literal("暂无寻路任务")));
        for (RouteTaskView task : tasks) {
            UIElement row = Ui.column();
            row.addClass("sm-row");
            row.layout(l -> l.widthPercent(100).paddingAll(4).gapAll(2).flexShrink(0));
            String endpoints = task.source().getX() + "," + task.source().getZ() + " → "
                    + task.target().getX() + "," + task.target().getZ();
            row.addChild(Ui.clippedText(Component.literal(endpoints)));
            row.addChild(Ui.clippedText(Component.literal(task.stage() + " · " + task.percent() + "% · "
                    + task.elapsedMillis(System.currentTimeMillis()) / 1000 + "秒"), "sm-muted"));
            row.style(s -> s.tooltips(Component.literal(task.dimension() + "\n任务 " + task.id())));
            MeterBar bar = new MeterBar();
            bar.layout(l -> l.widthPercent(100).height(6));
            bar.set(task.percent() / 100F, task.active() ? 0xFF7AA2C6 : task.state() == 1 ? 0xFF58B58D : 0xFFE09A59);
            row.addChild(bar);
            if (task.active()) {
                var cancel = Ui.button(Component.literal(cancelling.contains(task.id()) ? "正在取消…" : "取消此任务"),
                        Ui.Tone.DANGER, () -> {
                            cancelling.add(task.id());
                            ModNetwork.CHANNEL.sendToServer(new CancelPortWorkPacket(task.id(), true));
                            request();
                            renderTasks();
                        });
                cancel.setActive(!cancelling.contains(task.id()));
                row.addChild(cancel);
            }
            list.addScrollViewChild(row);
        }
    }
    @Override protected void onUiTick() { if (ticks() >= nextRefresh) request(); }
    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }
}
