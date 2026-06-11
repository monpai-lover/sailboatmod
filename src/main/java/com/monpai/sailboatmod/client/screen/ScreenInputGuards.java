package com.monpai.sailboatmod.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;

public final class ScreenInputGuards {
    private ScreenInputGuards() {
    }

    public static boolean shouldConsumeInventoryKeyWhenEditing(Minecraft minecraft,
                                                               int keyCode,
                                                               int scanCode,
                                                               EditBox... editBoxes) {
        boolean inventoryKey = minecraft != null
                && minecraft.options != null
                && minecraft.options.keyInventory.matches(keyCode, scanCode);
        return shouldConsumeInventoryKeyForTest(inventoryKey, hasFocusedEditBox(editBoxes));
    }

    static boolean shouldConsumeInventoryKeyForTest(boolean inventoryKey, boolean editingText) {
        return inventoryKey && editingText;
    }

    private static boolean hasFocusedEditBox(EditBox... editBoxes) {
        if (editBoxes == null) {
            return false;
        }
        for (EditBox editBox : editBoxes) {
            if (editBox != null && editBox.isFocused()) {
                return true;
            }
        }
        return false;
    }
}
