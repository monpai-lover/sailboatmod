package com.monpai.sailboatmod.client.modernui;

import com.monpai.sailboatmod.client.TownOverviewConsumer;
import com.monpai.sailboatmod.client.screen.town.TownHomeScreen;
import com.monpai.sailboatmod.nation.menu.NationOverviewMember;
import com.monpai.sailboatmod.nation.menu.TownOverviewData;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.TownGuiActionPacket;
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
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;
import net.minecraft.client.Minecraft;

import java.util.List;

public class TownModernFragment extends Fragment implements ScreenCallback, TownOverviewConsumer {
    private TownOverviewData data;

    private int selectedMemberIndex;

    private TextView titleView;
    private TextView statusView;
    private TextView overviewBody;
    private TextView residentsBody;
    private TextView cultureBody;
    private TextView claimsBody;

    private EditText townNameInput;
    private EditText claimChunkXInput;
    private EditText claimChunkZInput;
    private EditText claimAreaXInput;
    private EditText claimAreaZInput;

    private Button renameTownButton;
    private Button abandonTownButton;
    private Button removeCoreButton;
    private Button memberPrevButton;
    private Button memberNextButton;
    private Button appointMayorButton;
    private Button claimButton;
    private Button unclaimButton;
    private Button claimAreaButton;
    private Button unclaimAreaButton;
    private Button toggleMirrorButton;

    public TownModernFragment(TownOverviewData data) {
        this.data = data == null ? TownOverviewData.empty() : data;
    }

    @Override
    public void updateData(TownOverviewData data) {
        this.data = data == null ? TownOverviewData.empty() : data;
        this.selectedMemberIndex = clampSelection(this.selectedMemberIndex, this.data.members().size());
        if (titleView != null) {
            titleView.post(this::refreshViews);
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable DataSet savedInstanceState) {
        ScrollView scrollView = new ScrollView(requireContext());
        LinearLayout root = ModernUiScreenHelper.createRoot(new LinearLayout(requireContext()));
        scrollView.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        titleView = ModernUiScreenHelper.createHeader(new TextView(requireContext()), "", 20);
        statusView = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        root.addView(titleView, ModernUiScreenHelper.matchWidthWrap());
        root.addView(statusView, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout row = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        Button refreshButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Refresh");
        refreshButton.setOnClickListener(v -> ModNetwork.CHANNEL.sendToServer(new TownGuiActionPacket(TownGuiActionPacket.Action.REFRESH, data.townId())));
        Button classicButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Classic Controls");
        classicButton.setOnClickListener(v -> Minecraft.getInstance().setScreen(new TownHomeScreen(data)));
        row.addView(refreshButton, ModernUiScreenHelper.wrap());
        row.addView(classicButton, ModernUiScreenHelper.wrap());
        root.addView(row, ModernUiScreenHelper.matchWidthWrap());

        buildOverviewCard(root);
        buildResidentsCard(root);
        buildClaimsCard(root);
        buildCultureCard(root);
        refreshViews();
        return scrollView;
    }

    private void buildOverviewCard(LinearLayout root) {
        LinearLayout card = addCard(root, "Town Overview");
        overviewBody = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        card.addView(overviewBody, ModernUiScreenHelper.matchWidthWrap());

        townNameInput = ModernUiScreenHelper.createInput(new EditText(requireContext()), "");
        card.addView(label("Town Name"), ModernUiScreenHelper.matchWidthWrap());
        card.addView(townNameInput, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout rowA = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        renameTownButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Rename Town");
        renameTownButton.setOnClickListener(v -> renameTown());
        abandonTownButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Abandon Town");
        abandonTownButton.setOnClickListener(v -> send(new TownGuiActionPacket(TownGuiActionPacket.Action.ABANDON_TOWN, data.townId())));
        removeCoreButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Remove Core");
        removeCoreButton.setOnClickListener(v -> send(new TownGuiActionPacket(TownGuiActionPacket.Action.REMOVE_CORE, data.townId())));
        rowA.addView(renameTownButton, ModernUiScreenHelper.wrap());
        rowA.addView(abandonTownButton, ModernUiScreenHelper.wrap());
        rowA.addView(removeCoreButton, ModernUiScreenHelper.wrap());
        card.addView(rowA, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout rowB = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        toggleMirrorButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Toggle Flag Mirror");
        toggleMirrorButton.setOnClickListener(v -> send(new TownGuiActionPacket(TownGuiActionPacket.Action.TOGGLE_FLAG_MIRROR, data.townId())));
        rowB.addView(toggleMirrorButton, ModernUiScreenHelper.wrap());
        card.addView(rowB, ModernUiScreenHelper.matchWidthWrap());
    }

    private void buildResidentsCard(LinearLayout root) {
        LinearLayout card = addCard(root, "Residents");
        residentsBody = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        card.addView(residentsBody, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout navRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        memberPrevButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "< Member");
        memberPrevButton.setOnClickListener(v -> {
            selectedMemberIndex = moveSelection(selectedMemberIndex, data.members().size(), -1);
            refreshViews();
        });
        memberNextButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Member >");
        memberNextButton.setOnClickListener(v -> {
            selectedMemberIndex = moveSelection(selectedMemberIndex, data.members().size(), 1);
            refreshViews();
        });
        navRow.addView(memberPrevButton, ModernUiScreenHelper.wrap());
        navRow.addView(memberNextButton, ModernUiScreenHelper.wrap());
        card.addView(navRow, ModernUiScreenHelper.matchWidthWrap());

        appointMayorButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Appoint Mayor");
        appointMayorButton.setOnClickListener(v -> appointMayor());
        card.addView(appointMayorButton, ModernUiScreenHelper.matchWidthWrap());
    }

    private void buildClaimsCard(LinearLayout root) {
        LinearLayout card = addCard(root, "Claims");
        claimsBody = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        card.addView(claimsBody, ModernUiScreenHelper.matchWidthWrap());

        claimChunkXInput = ModernUiScreenHelper.createInput(new EditText(requireContext()), "");
        claimChunkZInput = ModernUiScreenHelper.createInput(new EditText(requireContext()), "");
        claimAreaXInput = ModernUiScreenHelper.createInput(new EditText(requireContext()), "");
        claimAreaZInput = ModernUiScreenHelper.createInput(new EditText(requireContext()), "");
        card.addView(label("Start Chunk X"), ModernUiScreenHelper.matchWidthWrap());
        card.addView(claimChunkXInput, ModernUiScreenHelper.matchWidthWrap());
        card.addView(label("Start Chunk Z"), ModernUiScreenHelper.matchWidthWrap());
        card.addView(claimChunkZInput, ModernUiScreenHelper.matchWidthWrap());
        card.addView(label("Area End Chunk X"), ModernUiScreenHelper.matchWidthWrap());
        card.addView(claimAreaXInput, ModernUiScreenHelper.matchWidthWrap());
        card.addView(label("Area End Chunk Z"), ModernUiScreenHelper.matchWidthWrap());
        card.addView(claimAreaZInput, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout rowA = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        claimButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Claim Chunk");
        claimButton.setOnClickListener(v -> submitClaim(false));
        unclaimButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Unclaim Chunk");
        unclaimButton.setOnClickListener(v -> submitClaim(true));
        rowA.addView(claimButton, ModernUiScreenHelper.wrap());
        rowA.addView(unclaimButton, ModernUiScreenHelper.wrap());
        card.addView(rowA, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout rowB = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        claimAreaButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Claim Area");
        claimAreaButton.setOnClickListener(v -> submitClaimArea(false));
        unclaimAreaButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Unclaim Area");
        unclaimAreaButton.setOnClickListener(v -> submitClaimArea(true));
        rowB.addView(claimAreaButton, ModernUiScreenHelper.wrap());
        rowB.addView(unclaimAreaButton, ModernUiScreenHelper.wrap());
        card.addView(rowB, ModernUiScreenHelper.matchWidthWrap());
    }

    private void buildCultureCard(LinearLayout root) {
        LinearLayout card = addCard(root, "Culture And Access");
        cultureBody = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        card.addView(cultureBody, ModernUiScreenHelper.matchWidthWrap());
    }

    private LinearLayout addCard(LinearLayout root, String title) {
        LinearLayout card = ModernUiScreenHelper.createCard(new LinearLayout(requireContext()));
        card.addView(ModernUiScreenHelper.createHeader(new TextView(requireContext()), title, 14), ModernUiScreenHelper.matchWidthWrap());
        root.addView(card, ModernUiScreenHelper.matchWidthWrap());
        return card;
    }

    private TextView label(String text) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextSize(11);
        return view;
    }

    private void refreshViews() {
        titleView.setText(data.hasTown() ? data.townName() : "Town Command");
        statusView.setText(buildStatusText());
        overviewBody.setText(buildOverviewText());
        residentsBody.setText(buildResidentsText());
        cultureBody.setText(buildCultureText());
        claimsBody.setText(buildClaimsText());

        syncInput(townNameInput, data.townName());
        syncClaimInputs();

        boolean hasTown = data.hasTown();
        renameTownButton.setEnabled(hasTown && data.canManageTown());
        abandonTownButton.setEnabled(hasTown && data.isMayor());
        removeCoreButton.setEnabled(hasTown && data.canManageTown() && data.hasCore());
        toggleMirrorButton.setEnabled(hasTown && data.canUploadFlag() && !data.flagId().isBlank());
        memberPrevButton.setEnabled(data.members().size() > 1);
        memberNextButton.setEnabled(data.members().size() > 1);
        appointMayorButton.setEnabled(hasTown && data.canAssignMayor() && canAssignSelectedMemberAsMayor());
        claimButton.setEnabled(hasTown && data.canManageClaims());
        unclaimButton.setEnabled(hasTown && data.canManageClaims());
        claimAreaButton.setEnabled(hasTown && data.canManageClaims());
        unclaimAreaButton.setEnabled(hasTown && data.canManageClaims());
    }

    private String buildStatusText() {
        if (!data.hasTown()) {
            return "No town selected. Classic controls still cover the full claim preview map and flag upload flow.";
        }
        return "Nation: " + safe(data.nationName())
                + " | Mayor: " + safe(data.mayorName())
                + " | Residents: " + data.residentCount()
                + " | Claims: " + data.totalClaims();
    }

    private String buildOverviewText() {
        if (!data.hasTown()) {
            return "No town selected.\nUse the classic screen to create a town, then this page can manage it.";
        }
        return "Capital town: " + data.capitalTown()
                + "\nCore placed: " + data.hasCore()
                + "\nCurrent chunk owner: " + safe(data.currentChunkOwnerName())
                + "\nFlag: " + (data.flagId().isBlank() ? "-" : data.flagId())
                + "\nMirror: " + data.flagMirrored();
    }

    private String buildResidentsText() {
        if (data.members().isEmpty()) {
            return "No member roster available.";
        }
        StringBuilder builder = new StringBuilder();
        NationOverviewMember selected = selectedMember();
        builder.append("Selected: ").append(selected == null ? "-" : safe(selected.playerName()))
                .append(selected != null && selected.online() ? " | Online" : " | Offline")
                .append("\nRole: ").append(selected == null ? "-" : safe(selected.officeName()))
                .append("\nMayor: ").append(safe(data.mayorName()))
                .append("\n\nRoster:");
        appendWindow(builder, data.members(), selectedMemberIndex, 6, member ->
                member.playerName() + " | " + safe(member.officeName()) + (member.online() ? " | Online" : " | Offline"));
        return builder.toString();
    }

    private String buildCultureText() {
        return "Culture: " + safe(data.cultureId())
                + "\nAverage literacy: " + Math.round(data.averageLiteracy() * 100.0f) + "%"
                + "\nCulture groups: " + data.cultureDistribution().size()
                + "\nEducation tiers: " + data.educationLevelDistribution().size();
    }

    private String buildClaimsText() {
        return "Current chunk: " + data.currentChunkX() + ", " + data.currentChunkZ()
                + "\nPreview center: " + data.previewCenterChunkX() + ", " + data.previewCenterChunkZ()
                + "\nOwner: " + safe(data.currentChunkOwnerName())
                + "\nBreak: " + safe(data.breakAccessLevel())
                + " | Place: " + safe(data.placeAccessLevel())
                + "\nUse: " + safe(data.useAccessLevel())
                + " | Container: " + safe(data.containerAccessLevel())
                + "\nRedstone: " + safe(data.redstoneAccessLevel())
                + " | Entity Use: " + safe(data.entityUseAccessLevel())
                + "\nEntity Damage: " + safe(data.entityDamageAccessLevel())
                + "\nUse the coordinate fields below for chunk and rectangle claim operations.";
    }

    private void renameTown() {
        String townName = textOf(townNameInput);
        if (!townName.isBlank()) {
            send(new TownGuiActionPacket(TownGuiActionPacket.Action.RENAME_TOWN, data.townId(), townName));
        }
    }

    private void appointMayor() {
        NationOverviewMember selected = selectedMember();
        if (selected != null) {
            send(new TownGuiActionPacket(TownGuiActionPacket.Action.APPOINT_MAYOR, data.townId(), selected.playerUuid()));
        }
    }

    private void submitClaim(boolean unclaim) {
        int x = parseInt(textOf(claimChunkXInput), data.currentChunkX());
        int z = parseInt(textOf(claimChunkZInput), data.currentChunkZ());
        send(new TownGuiActionPacket(unclaim ? TownGuiActionPacket.Action.UNCLAIM_CHUNK : TownGuiActionPacket.Action.CLAIM_CHUNK, data.townId(), x, z));
    }

    private void submitClaimArea(boolean unclaim) {
        int x1 = parseInt(textOf(claimChunkXInput), data.currentChunkX());
        int z1 = parseInt(textOf(claimChunkZInput), data.currentChunkZ());
        int x2 = parseInt(textOf(claimAreaXInput), x1);
        int z2 = parseInt(textOf(claimAreaZInput), z1);
        send(new TownGuiActionPacket(unclaim ? TownGuiActionPacket.Action.UNCLAIM_AREA : TownGuiActionPacket.Action.CLAIM_AREA, data.townId(), x1, z1, x2 + "," + z2));
    }

    private void syncClaimInputs() {
        syncBlankNumericInput(claimChunkXInput, data.currentChunkX());
        syncBlankNumericInput(claimChunkZInput, data.currentChunkZ());
        syncBlankNumericInput(claimAreaXInput, data.currentChunkX());
        syncBlankNumericInput(claimAreaZInput, data.currentChunkZ());
    }

    private NationOverviewMember selectedMember() {
        return data.members().isEmpty() ? null : data.members().get(clampSelection(selectedMemberIndex, data.members().size()));
    }

    private boolean canAssignSelectedMemberAsMayor() {
        NationOverviewMember selected = selectedMember();
        return selected != null && !selected.playerUuid().equals(data.mayorUuid());
    }

    private <T> void appendWindow(StringBuilder builder, List<T> values, int selectedIndex, int windowSize, java.util.function.Function<T, String> formatter) {
        if (values.isEmpty()) {
            builder.append("\n- None");
            return;
        }
        int safeIndex = clampSelection(selectedIndex, values.size());
        int start = Math.max(0, safeIndex - Math.max(1, windowSize / 2));
        int end = Math.min(values.size(), start + windowSize);
        if (end - start < windowSize) {
            start = Math.max(0, end - windowSize);
        }
        for (int i = start; i < end; i++) {
            builder.append('\n')
                    .append(i == safeIndex ? "> " : "- ")
                    .append(formatter.apply(values.get(i)));
        }
    }

    private void send(TownGuiActionPacket packet) {
        ModNetwork.CHANNEL.sendToServer(packet);
    }

    private int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int clampSelection(int index, int size) {
        if (size <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(index, size - 1));
    }

    private int moveSelection(int current, int size, int delta) {
        if (size <= 0) {
            return 0;
        }
        return Math.floorMod(current + delta, size);
    }

    private String textOf(EditText input) {
        return input == null ? "" : input.getText().toString().trim();
    }

    private void syncInput(EditText input, String value) {
        if (input == null || input.isFocused()) {
            return;
        }
        String next = value == null ? "" : value;
        String current = input.getText().toString();
        if (!next.equals(current)) {
            input.setText(next);
        }
    }

    private void syncBlankNumericInput(EditText input, int value) {
        if (input == null || input.isFocused()) {
            return;
        }
        if (textOf(input).isBlank()) {
            input.setText(String.valueOf(value));
        }
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
