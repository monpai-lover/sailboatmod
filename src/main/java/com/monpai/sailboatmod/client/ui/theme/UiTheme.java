package com.monpai.sailboatmod.client.ui.theme;

import java.util.Locale;

/**
 * The two looks the player can switch between from any SailboatMod screen.
 *
 * <p>Both themes style the same selectors; only colours and textures differ (see
 * {@code assets/sailboatmod/lss/<id>.lss}). Layout is identical, so switching never moves anything on screen.</p>
 */
public enum UiTheme {
    /** Night-hall panels, rarity-lit inventory slots, emerald for money and buying. Matches the web market. */
    TRADING_HALL("trading_hall", "screen.sailboatmod.ui.theme.trading_hall"),
    /** Map-paper panels, ink text, wax-seal red for buying and warnings. */
    LEDGER("ledger", "screen.sailboatmod.ui.theme.ledger");

    private final String id;
    private final String labelKey;

    UiTheme(String id, String labelKey) {
        this.id = id;
        this.labelKey = labelKey;
    }

    public String id() {
        return id;
    }

    public String labelKey() {
        return labelKey;
    }

    /** Resource path of this theme's stylesheet, relative to the mod namespace. */
    public String stylesheetPath() {
        return "lss/" + id + ".lss";
    }

    public UiTheme next() {
        UiTheme[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /** Parses a stored ID; unknown or missing values fall back to {@link #TRADING_HALL}. */
    public static UiTheme fromId(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (UiTheme theme : values()) {
                if (theme.id.equals(normalized)) {
                    return theme;
                }
            }
        }
        return TRADING_HALL;
    }
}
