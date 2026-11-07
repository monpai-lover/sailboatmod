package com.monpai.sailboatmod.client.ui.vehicle;

import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import net.minecraft.network.chat.Component;

/** Shared destination rows for the ordinary sailboat and registered vehicles. */
public final class DestinationPicker {
    private static final String KEY = "screen.sailboatmod.destination_picker.";

    private DestinationPicker() {}

    public static Component title() {
        return Component.translatable(KEY + "title");
    }

    public static void add(ScrollerView list, String name, double distance, Component note,
            boolean enabled, Runnable select) {
        var label = Component.literal(name);
        if (Double.isFinite(distance) && distance > 0) {
            label.append(Component.translatable(KEY + "distance", Math.round(distance)));
        }
        label.append(note);
        var button = Ui.button(label, Ui.Tone.PRIMARY, select);
        button.setActive(enabled);
        list.addScrollViewChild(button);
    }
}
