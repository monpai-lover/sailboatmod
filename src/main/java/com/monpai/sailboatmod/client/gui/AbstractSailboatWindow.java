package com.monpai.sailboatmod.client.gui;

import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.ButtonHandler;
import com.ldtteam.blockui.views.BOWindow;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Base window skeleton for all sailboat mod UIs (inspired by MineColonies)
 */
public abstract class AbstractSailboatWindow extends BOWindow implements ButtonHandler {
    private final Map<String, Consumer<Button>> buttons = new HashMap<>();

    public AbstractSailboatWindow(final ResourceLocation resource) {
        super(resource);
    }

    protected final void registerButton(final String id, final Runnable action) {
        buttons.put(id, b -> action.run());
    }

    protected final void registerButton(final String id, final Consumer<Button> action) {
        buttons.put(id, action);
    }

    @Override
    public void onButtonClicked(@NotNull final Button button) {
        Consumer<Button> handler = buttons.get(button.getID());
        if (handler != null) {
            handler.accept(button);
        }
    }
}
