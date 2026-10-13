package com.monpai.sailboatmod.client.screen.resident;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.monpai.sailboatmod.client.ui.kit.ThemedScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.resident.model.FamilyData;
import com.monpai.sailboatmod.resident.model.ResidentRecord;
import com.monpai.sailboatmod.resident.service.FamilyService;
import dev.vfyjxf.taffy.style.AlignItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** A resident's family: parents above, partner beside, children and siblings below. Back returns to the resident. */
public class FamilyTreeScreen extends ThemedScreen {
    private final ResidentRecord resident;
    private final Screen parent;

    public FamilyTreeScreen(ResidentRecord resident, Screen parent) {
        super(Component.translatable("screen.sailboatmod.ui.resident.family_of", resident.name()));
        this.resident = resident;
        this.parent = parent;
    }

    @Override
    protected float windowWidth() {
        return 260;
    }

    @Override
    protected UIElement buildBody() {
        FamilyData family = FamilyService.getFamily(resident.residentId());
        UIElement body = Ui.column();
        body.layout(l -> l.alignItems(AlignItems.STRETCH));
        UIElement parents = Ui.row();
        parents.layout(l -> l.gapAll(Ui.GAP_S));
        parents.addChildren(person(orUnknown(family.getParentA())), Ui.text("&", "sm-muted"), person(orUnknown(family.getParentB())));
        UIElement self = Ui.row();
        self.layout(l -> l.gapAll(Ui.GAP_S));
        UIElement selfCard = person(Component.literal(resident.name()));
        selfCard.addClass("selected");
        self.addChildren(selfCard, Ui.text("♥", "sm-down"),
                person(family.getPartnerId().isEmpty() ? Component.translatable("screen.sailboatmod.ui.resident.none") : Component.literal(family.getPartnerId())));

        ScrollerView children = Ui.scroller();
        children.layout(l -> l.height(60).flexGrow(0));
        List<String> kids = family.getChildren();
        if (kids.isEmpty()) {
            children.addScrollViewChild(Ui.emptyState(Component.translatable("screen.sailboatmod.ui.resident.none")));
        }
        for (String child : kids) {
            children.addScrollViewChild(Ui.text(child));
        }
        List<String> siblings = family.getSiblings();
        body.addChildren(
                Ui.muted(Component.translatable("screen.sailboatmod.ui.resident.parents")), parents,
                Ui.muted(Component.translatable("screen.sailboatmod.ui.resident.partner")), self,
                Ui.muted(Component.translatable("screen.sailboatmod.ui.resident.children")), children,
                Ui.muted(Component.translatable("screen.sailboatmod.ui.resident.siblings")),
                Ui.paragraph(siblings.isEmpty() ? Component.translatable("screen.sailboatmod.ui.resident.none") : Component.literal(String.join(", ", siblings))),
                row(Ui.spacer(), Ui.button(Component.translatable("gui.back"), Ui.Tone.SECONDARY, this::onClose)));
        return body;
    }

    private static UIElement person(Component name) {
        UIElement card = Ui.row("sm-row");
        card.layout(l -> l.flexGrow(1).flexBasis(0).minWidth(0).height(18).paddingHorizontal(Ui.GAP_S));
        card.addChild(Ui.text(name));
        return card;
    }

    private static UIElement row(UIElement... children) {
        UIElement row = Ui.row();
        row.layout(l -> l.gapAll(Ui.GAP_S).widthPercent(100));
        row.addChildren(children);
        return row;
    }

    private static Component orUnknown(String value) {
        return value == null || value.isEmpty() ? Component.translatable("screen.sailboatmod.ui.resident.unknown") : Component.literal(value);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
