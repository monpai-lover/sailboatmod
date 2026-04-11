package com.monpai.sailboatmod.client.screen;

public final class ClaimsMapVisibility {
    private ClaimsMapVisibility() {
    }

    public static boolean showMapTools(boolean claimsPage, int claimsSubPage) {
        return claimsPage && claimsSubPage == 0;
    }

    public static boolean allowMapInteraction(boolean claimsPage, int claimsSubPage) {
        return showMapTools(claimsPage, claimsSubPage);
    }
}
