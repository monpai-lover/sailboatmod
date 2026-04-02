package com.monpai.sailboatmod.client.modernui;

import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.FinalizeRouteNamePacket;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.mc.ScreenCallback;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.TextView;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

public class RouteBookNameModernFragment extends Fragment implements ScreenCallback {
    private final InteractionHand hand;
    private final String suggestedName;
    private EditText nameInput;

    public RouteBookNameModernFragment(InteractionHand hand, String suggestedName) {
        this.hand = hand;
        this.suggestedName = suggestedName == null ? "" : suggestedName;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable DataSet savedInstanceState) {
        LinearLayout root = ModernUiScreenHelper.createRoot(new LinearLayout(requireContext()));
        root.addView(ModernUiScreenHelper.createHeader(new TextView(requireContext()), "Finalize Route Name", 20), ModernUiScreenHelper.matchWidthWrap());
        TextView body = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        body.setText("Set a final name for the active route before it is saved into the route book.");
        root.addView(body, ModernUiScreenHelper.matchWidthWrap());
        nameInput = ModernUiScreenHelper.createInput(new EditText(requireContext()), suggestedName);
        root.addView(nameInput, ModernUiScreenHelper.matchWidthWrap());
        LinearLayout row = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        Button confirmButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Confirm");
        confirmButton.setOnClickListener(v -> submit());
        Button cancelButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Cancel");
        cancelButton.setOnClickListener(v -> Minecraft.getInstance().setScreen(null));
        row.addView(confirmButton, ModernUiScreenHelper.wrap());
        row.addView(cancelButton, ModernUiScreenHelper.wrap());
        root.addView(row, ModernUiScreenHelper.matchWidthWrap());
        return root;
    }

    private void submit() {
        String name = nameInput == null ? suggestedName : nameInput.getText().toString();
        ModNetwork.CHANNEL.sendToServer(new FinalizeRouteNamePacket(hand, name));
        Minecraft.getInstance().setScreen(null);
    }
}
