package com.monpai.sailboatmod.client.modernui;

import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.TextView;

public final class ModernUiScreenHelper {
    private ModernUiScreenHelper() {
    }

    public static LinearLayout createRoot(LinearLayout layout) {
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackground(new ColorDrawable(0xE11161A));
        int pad = layout.dp(14);
        layout.setPadding(pad, pad, pad, pad);
        return layout;
    }

    public static LinearLayout createCard(LinearLayout layout) {
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackground(new ColorDrawable(0xCC192734));
        int pad = layout.dp(10);
        layout.setPadding(pad, pad, pad, pad);
        return layout;
    }

    public static TextView createHeader(TextView view, String text, int size) {
        view.setText(text);
        view.setTextSize(size);
        return view;
    }

    public static TextView createBody(TextView view) {
        view.setTextSize(12);
        view.setTextIsSelectable(true);
        return view;
    }

    public static Button createButton(Button button, String text) {
        button.setText(text);
        button.setTextSize(12);
        int pad = button.dp(8);
        button.setPadding(pad, pad / 2, pad, pad / 2);
        return button;
    }

    public static EditText createInput(EditText input, String text) {
        input.setText(text);
        input.setTextSize(12);
        return input;
    }

    public static LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
    }

    public static LinearLayout.LayoutParams weighted(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
    }

    public static LinearLayout.LayoutParams weightedWrap(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    public static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams matchWidthWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout row(LinearLayout row) {
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    public static void hide(View view, boolean hide) {
        view.setVisibility(hide ? View.GONE : View.VISIBLE);
    }
}
