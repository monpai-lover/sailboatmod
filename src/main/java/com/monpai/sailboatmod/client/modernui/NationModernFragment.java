package com.monpai.sailboatmod.client.modernui;

import com.monpai.sailboatmod.client.NationOverviewConsumer;
import com.monpai.sailboatmod.client.screen.nation.NationHomeScreen;
import com.monpai.sailboatmod.nation.menu.NationOverviewData;
import com.monpai.sailboatmod.nation.menu.NationOverviewDiplomacyEntry;
import com.monpai.sailboatmod.nation.menu.NationOverviewDiplomacyRequest;
import com.monpai.sailboatmod.nation.menu.NationOverviewMember;
import com.monpai.sailboatmod.nation.menu.NationOverviewNationEntry;
import com.monpai.sailboatmod.nation.menu.NationOverviewTown;
import com.monpai.sailboatmod.nation.model.NationOfficeIds;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.NationGuiActionPacket;
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
import java.util.Locale;

public class NationModernFragment extends Fragment implements ScreenCallback, NationOverviewConsumer {
    private NationOverviewData data;

    private int selectedMemberIndex;
    private int selectedNationIndex;

    private TextView titleView;
    private TextView statusView;
    private TextView summaryPopulationBody;
    private TextView summaryEconomyBody;
    private TextView summaryTerritoryBody;
    private TextView overviewBody;
    private TextView membersBody;
    private TextView claimsBody;
    private TextView diplomacyBody;
    private TextView treasuryBody;

    private EditText nationNameInput;
    private EditText shortNameInput;
    private EditText joinNationInput;
    private EditText officerTitleInput;
    private EditText claimChunkXInput;
    private EditText claimChunkZInput;
    private EditText claimAreaXInput;
    private EditText claimAreaZInput;

    private Button overviewPrimaryButton;
    private Button joinButton;
    private Button leaveButton;
    private Button memberPrevButton;
    private Button memberNextButton;
    private Button appointOfficerButton;
    private Button removeOfficerButton;
    private Button appointMayorButton;
    private Button kickMemberButton;
    private Button renameOfficerTitleButton;
    private Button claimButton;
    private Button unclaimButton;
    private Button claimAreaButton;
    private Button unclaimAreaButton;
    private Button nationPrevButton;
    private Button nationNextButton;
    private Button allyButton;
    private Button tradeButton;
    private Button neutralButton;
    private Button enemyButton;
    private Button declareWarButton;
    private Button acceptButton;
    private Button rejectButton;
    private Button openTradeButton;
    private Button salesTaxDownButton;
    private Button salesTaxUpButton;
    private Button tariffDownButton;
    private Button tariffUpButton;

    public NationModernFragment(NationOverviewData data) {
        this.data = data == null ? NationOverviewData.empty() : data;
    }

    @Override
    public void updateData(NationOverviewData data) {
        this.data = data == null ? NationOverviewData.empty() : data;
        this.selectedMemberIndex = clampSelection(this.selectedMemberIndex, this.data.members().size());
        this.selectedNationIndex = clampSelection(this.selectedNationIndex, this.data.allNations().size());
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
        refreshButton.setOnClickListener(v -> ModNetwork.CHANNEL.sendToServer(new NationGuiActionPacket(NationGuiActionPacket.Action.REFRESH)));
        Button classicButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Classic Controls");
        classicButton.setOnClickListener(v -> Minecraft.getInstance().setScreen(new NationHomeScreen(data)));
        row.addView(refreshButton, ModernUiScreenHelper.wrap());
        row.addView(classicButton, ModernUiScreenHelper.wrap());
        root.addView(row, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout summaryRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        summaryRow.addView(createPopulationSummaryCard(), ModernUiScreenHelper.weightedWrap(1f));
        summaryRow.addView(createEconomySummaryCard(), ModernUiScreenHelper.weightedWrap(1f));
        summaryRow.addView(createTerritorySummaryCard(), ModernUiScreenHelper.weightedWrap(1f));
        root.addView(summaryRow, ModernUiScreenHelper.matchWidthWrap());

        buildOverviewCard(root);
        buildMembersCard(root);
        buildClaimsCard(root);
        buildDiplomacyCard(root);
        buildTreasuryCard(root);
        refreshViews();
        return scrollView;
    }

    private void buildOverviewCard(LinearLayout root) {
        LinearLayout card = addCard(root, "Nation Overview");
        overviewBody = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        card.addView(overviewBody, ModernUiScreenHelper.matchWidthWrap());

        nationNameInput = ModernUiScreenHelper.createInput(new EditText(requireContext()), "");
        shortNameInput = ModernUiScreenHelper.createInput(new EditText(requireContext()), "");
        joinNationInput = ModernUiScreenHelper.createInput(new EditText(requireContext()), "");
        card.addView(label("Nation Name"), ModernUiScreenHelper.matchWidthWrap());
        card.addView(nationNameInput, ModernUiScreenHelper.matchWidthWrap());
        card.addView(label("Short Name"), ModernUiScreenHelper.matchWidthWrap());
        card.addView(shortNameInput, ModernUiScreenHelper.matchWidthWrap());
        card.addView(label("Join By Name"), ModernUiScreenHelper.matchWidthWrap());
        card.addView(joinNationInput, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout actionRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        overviewPrimaryButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "");
        overviewPrimaryButton.setOnClickListener(v -> handleOverviewPrimary());
        joinButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Join Nation");
        joinButton.setOnClickListener(v -> joinNation());
        leaveButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Leave Nation");
        leaveButton.setOnClickListener(v -> send(new NationGuiActionPacket(NationGuiActionPacket.Action.LEAVE_NATION)));
        actionRow.addView(overviewPrimaryButton, ModernUiScreenHelper.wrap());
        actionRow.addView(joinButton, ModernUiScreenHelper.wrap());
        actionRow.addView(leaveButton, ModernUiScreenHelper.wrap());
        card.addView(actionRow, ModernUiScreenHelper.matchWidthWrap());
    }

    private void buildMembersCard(LinearLayout root) {
        LinearLayout card = addCard(root, "Members And Offices");
        membersBody = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        card.addView(membersBody, ModernUiScreenHelper.matchWidthWrap());

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

        officerTitleInput = ModernUiScreenHelper.createInput(new EditText(requireContext()), "");
        card.addView(label("Officer Title"), ModernUiScreenHelper.matchWidthWrap());
        card.addView(officerTitleInput, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout actionRowA = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        appointOfficerButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Appoint Officer");
        appointOfficerButton.setOnClickListener(v -> appointOfficer());
        removeOfficerButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Remove Officer");
        removeOfficerButton.setOnClickListener(v -> removeOfficer());
        appointMayorButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Assign Capital Mayor");
        appointMayorButton.setOnClickListener(v -> appointMayor());
        actionRowA.addView(appointOfficerButton, ModernUiScreenHelper.wrap());
        actionRowA.addView(removeOfficerButton, ModernUiScreenHelper.wrap());
        actionRowA.addView(appointMayorButton, ModernUiScreenHelper.wrap());
        card.addView(actionRowA, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout actionRowB = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        renameOfficerTitleButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Rename Officer Title");
        renameOfficerTitleButton.setOnClickListener(v -> renameOfficerTitle());
        kickMemberButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Kick Member");
        kickMemberButton.setOnClickListener(v -> kickSelectedMember());
        actionRowB.addView(renameOfficerTitleButton, ModernUiScreenHelper.wrap());
        actionRowB.addView(kickMemberButton, ModernUiScreenHelper.wrap());
        card.addView(actionRowB, ModernUiScreenHelper.matchWidthWrap());
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

    private void buildDiplomacyCard(LinearLayout root) {
        LinearLayout card = addCard(root, "Diplomacy");
        diplomacyBody = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        card.addView(diplomacyBody, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout navRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        nationPrevButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "< Nation");
        nationPrevButton.setOnClickListener(v -> {
            selectedNationIndex = moveSelection(selectedNationIndex, data.allNations().size(), -1);
            refreshViews();
        });
        nationNextButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Nation >");
        nationNextButton.setOnClickListener(v -> {
            selectedNationIndex = moveSelection(selectedNationIndex, data.allNations().size(), 1);
            refreshViews();
        });
        navRow.addView(nationPrevButton, ModernUiScreenHelper.wrap());
        navRow.addView(nationNextButton, ModernUiScreenHelper.wrap());
        card.addView(navRow, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout rowA = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        allyButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Ally");
        allyButton.setOnClickListener(v -> submitDiplomacyAction(NationGuiActionPacket.Action.DIPLOMACY_ALLY));
        tradeButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Trade");
        tradeButton.setOnClickListener(v -> submitDiplomacyAction(NationGuiActionPacket.Action.DIPLOMACY_TRADE));
        neutralButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Neutral");
        neutralButton.setOnClickListener(v -> submitDiplomacyAction(NationGuiActionPacket.Action.DIPLOMACY_NEUTRAL));
        enemyButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Enemy");
        enemyButton.setOnClickListener(v -> submitDiplomacyAction(NationGuiActionPacket.Action.DIPLOMACY_ENEMY));
        rowA.addView(allyButton, ModernUiScreenHelper.wrap());
        rowA.addView(tradeButton, ModernUiScreenHelper.wrap());
        rowA.addView(neutralButton, ModernUiScreenHelper.wrap());
        rowA.addView(enemyButton, ModernUiScreenHelper.wrap());
        card.addView(rowA, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout rowB = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        declareWarButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Declare War");
        declareWarButton.setOnClickListener(v -> submitDiplomacyAction(NationGuiActionPacket.Action.DECLARE_WAR));
        acceptButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Accept Request");
        acceptButton.setOnClickListener(v -> submitDiplomacyAction(NationGuiActionPacket.Action.DIPLOMACY_ACCEPT));
        rejectButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Reject Request");
        rejectButton.setOnClickListener(v -> submitDiplomacyAction(NationGuiActionPacket.Action.DIPLOMACY_REJECT));
        openTradeButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Open Trade");
        openTradeButton.setOnClickListener(v -> openTradeScreen());
        rowB.addView(declareWarButton, ModernUiScreenHelper.wrap());
        rowB.addView(acceptButton, ModernUiScreenHelper.wrap());
        rowB.addView(rejectButton, ModernUiScreenHelper.wrap());
        rowB.addView(openTradeButton, ModernUiScreenHelper.wrap());
        card.addView(rowB, ModernUiScreenHelper.matchWidthWrap());
    }

    private void buildTreasuryCard(LinearLayout root) {
        LinearLayout card = addCard(root, "Treasury");
        treasuryBody = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        card.addView(treasuryBody, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout rowA = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        salesTaxDownButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Sales Tax -");
        salesTaxDownButton.setOnClickListener(v -> adjustSalesTax(-100));
        salesTaxUpButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Sales Tax +");
        salesTaxUpButton.setOnClickListener(v -> adjustSalesTax(100));
        rowA.addView(salesTaxDownButton, ModernUiScreenHelper.wrap());
        rowA.addView(salesTaxUpButton, ModernUiScreenHelper.wrap());
        card.addView(rowA, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout rowB = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        tariffDownButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Import Tariff -");
        tariffDownButton.setOnClickListener(v -> adjustImportTariff(-100));
        tariffUpButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Import Tariff +");
        tariffUpButton.setOnClickListener(v -> adjustImportTariff(100));
        rowB.addView(tariffDownButton, ModernUiScreenHelper.wrap());
        rowB.addView(tariffUpButton, ModernUiScreenHelper.wrap());
        card.addView(rowB, ModernUiScreenHelper.matchWidthWrap());
    }

    private LinearLayout addCard(LinearLayout root, String title) {
        LinearLayout card = ModernUiScreenHelper.createCard(new LinearLayout(requireContext()));
        card.addView(ModernUiScreenHelper.createHeader(new TextView(requireContext()), title, 14), ModernUiScreenHelper.matchWidthWrap());
        root.addView(card, ModernUiScreenHelper.matchWidthWrap());
        return card;
    }

    private LinearLayout createPopulationSummaryCard() {
        LinearLayout card = ModernUiScreenHelper.createCard(new LinearLayout(requireContext()));
        card.addView(ModernUiScreenHelper.createHeader(new TextView(requireContext()), "Population", 13), ModernUiScreenHelper.matchWidthWrap());
        summaryPopulationBody = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        card.addView(summaryPopulationBody, ModernUiScreenHelper.matchWidthWrap());
        return card;
    }

    private LinearLayout createEconomySummaryCard() {
        LinearLayout card = ModernUiScreenHelper.createCard(new LinearLayout(requireContext()));
        card.addView(ModernUiScreenHelper.createHeader(new TextView(requireContext()), "Economy", 13), ModernUiScreenHelper.matchWidthWrap());
        summaryEconomyBody = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        card.addView(summaryEconomyBody, ModernUiScreenHelper.matchWidthWrap());
        return card;
    }

    private LinearLayout createTerritorySummaryCard() {
        LinearLayout card = ModernUiScreenHelper.createCard(new LinearLayout(requireContext()));
        card.addView(ModernUiScreenHelper.createHeader(new TextView(requireContext()), "Territory", 13), ModernUiScreenHelper.matchWidthWrap());
        summaryTerritoryBody = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        card.addView(summaryTerritoryBody, ModernUiScreenHelper.matchWidthWrap());
        return card;
    }

    private TextView label(String text) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextSize(11);
        return view;
    }

    private void refreshViews() {
        titleView.setText(data.hasNation() ? data.nationName() + " [" + safe(data.shortName()) + "]" : "Nation Command");
        statusView.setText(buildStatusText());
        summaryPopulationBody.setText(buildPopulationSummary());
        summaryEconomyBody.setText(buildEconomySummary());
        summaryTerritoryBody.setText(buildTerritorySummary());
        overviewBody.setText(buildOverviewText());
        membersBody.setText(buildMembersText());
        claimsBody.setText(buildClaimsText());
        diplomacyBody.setText(buildDiplomacyText());
        treasuryBody.setText(buildTreasuryText());

        syncInput(nationNameInput, data.nationName());
        syncInput(shortNameInput, data.shortName());
        syncInput(officerTitleInput, data.officerTitle());
        syncClaimInputs();

        boolean hasNation = data.hasNation();
        NationOverviewDiplomacyRequest request = selectedRequest();
        NationOverviewNationEntry selectedNation = selectedNation();
        boolean validNationTarget = selectedNation != null && !selectedNation.nationId().equals(data.nationId());

        overviewPrimaryButton.setText(hasNation ? "Save Nation Info" : "Create Nation");
        overviewPrimaryButton.setEnabled(hasNation ? data.canManageInfo() : true);
        joinButton.setEnabled(!hasNation);
        leaveButton.setEnabled(hasNation);

        memberPrevButton.setEnabled(data.members().size() > 1);
        memberNextButton.setEnabled(data.members().size() > 1);
        appointOfficerButton.setEnabled(data.canManageOffices() && canAppointSelectedMember());
        removeOfficerButton.setEnabled(data.canManageOffices() && canRemoveSelectedOfficer());
        appointMayorButton.setEnabled(data.isLeader() && canAssignSelectedMemberAsMayor());
        renameOfficerTitleButton.setEnabled(data.isLeader() && hasNation);
        kickMemberButton.setEnabled(data.isLeader() && canKickSelectedMember());

        claimButton.setEnabled(hasNation && data.canManageClaims());
        unclaimButton.setEnabled(hasNation && data.canManageClaims());
        claimAreaButton.setEnabled(hasNation && data.canManageClaims());
        unclaimAreaButton.setEnabled(hasNation && data.canManageClaims());

        nationPrevButton.setEnabled(data.allNations().size() > 1);
        nationNextButton.setEnabled(data.allNations().size() > 1);
        allyButton.setEnabled(hasNation && validNationTarget);
        tradeButton.setEnabled(hasNation && validNationTarget);
        neutralButton.setEnabled(hasNation && validNationTarget);
        enemyButton.setEnabled(hasNation && validNationTarget);
        declareWarButton.setEnabled(hasNation && data.canDeclareWar() && validNationTarget);
        acceptButton.setEnabled(hasNation && request != null);
        rejectButton.setEnabled(hasNation && request != null);
        openTradeButton.setEnabled(hasNation && validNationTarget);

        salesTaxDownButton.setEnabled(hasNation && data.canManageTreasury() && data.salesTaxBasisPoints() > 0);
        salesTaxUpButton.setEnabled(hasNation && data.canManageTreasury() && data.salesTaxBasisPoints() < 3000);
        tariffDownButton.setEnabled(hasNation && data.canManageTreasury() && data.importTariffBasisPoints() > 0);
        tariffUpButton.setEnabled(hasNation && data.canManageTreasury() && data.importTariffBasisPoints() < 5000);
    }

    private String buildStatusText() {
        if (!data.hasNation()) {
            return "Create or join a nation here. Classic screen remains available for flags and the terrain map.";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Leader: ").append(safe(data.leaderName()))
                .append(" | Office: ").append(safe(data.officeName()))
                .append(" | Claims: ").append(data.totalClaims())
                .append(" | Members: ").append(data.memberCount());
        if (data.hasActiveWar()) {
            builder.append("\nWar: ").append(safe(data.warOpponentName()))
                    .append(" | Score ").append(data.warScoreSelf()).append(" - ").append(data.warScoreOpponent())
                    .append(" | Status ").append(safe(data.warStatus()));
        }
        return builder.toString();
    }

    private String buildPopulationSummary() {
        int total = Math.max(1, data.memberCount());
        int online = countOnlineMembers();
        int officers = countOfficeMembers(NationOfficeIds.OFFICER);
        return "Members " + data.memberCount()
                + "\nOnline  " + bar(online, total, 10) + " " + online
                + "\nOfficer " + bar(officers, total, 10) + " " + officers
                + "\nLeader  " + bar(data.isLeader() ? 1 : 0, 1, 10) + " " + (data.isLeader() ? "You" : safe(data.leaderName()));
    }

    private String buildEconomySummary() {
        return "Balance " + data.treasuryBalance()
                + "\nSales   " + bar(data.salesTaxBasisPoints(), 3000, 10) + " " + percent(data.salesTaxBasisPoints())
                + "\nImport  " + bar(data.importTariffBasisPoints(), 5000, 10) + " " + percent(data.importTariffBasisPoints())
                + "\nTrades  " + bar(data.recentTradeCount(), Math.max(10, data.recentTradeCount()), 10) + " " + data.recentTradeCount();
    }

    private String buildTerritorySummary() {
        int towns = data.towns().size();
        int claims = data.totalClaims();
        return "Towns   " + bar(towns, Math.max(1, towns), 10) + " " + towns
                + "\nClaims  " + bar(claims, Math.max(1, claims), 10) + " " + claims
                + "\nCore    " + bar(data.hasCore() ? 1 : 0, 1, 10) + " " + (data.hasCore() ? "Placed" : "Missing")
                + "\nWar     " + bar(data.hasActiveWar() ? 1 : 0, 1, 10) + " " + (data.hasActiveWar() ? "Active" : "Idle");
    }

    private String buildOverviewText() {
        if (!data.hasNation()) {
            return "No nation joined yet.\nCreate a new nation or type a target name to join one.\nClassic controls still cover flag upload and the full claim map.";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Capital: ").append(safe(data.capitalTownName()))
                .append("\nCurrent chunk owner: ").append(safe(data.currentChunkOwnerName()))
                .append("\nCore placed: ").append(data.hasCore());
        if (data.hasCore()) {
            builder.append("\nCore dimension: ").append(safe(data.coreDimension()));
        }
        if (!data.towns().isEmpty()) {
            builder.append("\nTowns:");
            int limit = Math.min(5, data.towns().size());
            for (int i = 0; i < limit; i++) {
                NationOverviewTown town = data.towns().get(i);
                builder.append("\n").append(i == 0 ? ">" : "-")
                        .append(' ')
                        .append(town.townName())
                        .append(" | Mayor ").append(safe(town.mayorName()))
                        .append(" | Claims ").append(town.claimCount());
                if (town.capital()) {
                    builder.append(" | Capital");
                }
            }
        }
        return builder.toString();
    }

    private String buildMembersText() {
        if (data.members().isEmpty()) {
            return "No member roster available.";
        }
        NationOverviewMember selected = selectedMember();
        int total = Math.max(1, data.members().size());
        int online = countOnlineMembers();
        int officers = countOfficeMembers(NationOfficeIds.OFFICER);
        int members = Math.max(0, data.members().size() - officers - 1);
        StringBuilder builder = new StringBuilder();
        builder.append("Selected: ").append(selected == null ? "-" : safe(selected.playerName()))
                .append(selected != null && selected.online() ? " | Online" : " | Offline")
                .append("\nRole: ").append(selected == null ? "-" : safe(selected.officeName()))
                .append("\nOfficer title: ").append(safe(data.officerTitle()))
                .append("\n\nStats:")
                .append("\nOnline  ").append(bar(online, total, 18)).append(" ").append(online)
                .append("\nOfficer ").append(bar(officers, total, 18)).append(" ").append(officers)
                .append("\nMember  ").append(bar(members, total, 18)).append(" ").append(Math.max(0, members))
                .append("\n\nRoster:");
        appendWindow(builder, data.members(), selectedMemberIndex, 5, member ->
                member.playerName() + " | " + safe(member.officeName()) + (member.online() ? " | Online" : " | Offline"));
        return builder.toString();
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
                + "\nEnter start and end chunk coordinates to claim or unclaim ranges.";
    }

    private String buildDiplomacyText() {
        if (!data.hasNation()) {
            return "Join a nation first to manage relations.";
        }
        NationOverviewNationEntry selected = selectedNation();
        NationOverviewDiplomacyRequest request = selectedRequest();
        NationOverviewDiplomacyEntry relation = selectedRelation();
        StringBuilder builder = new StringBuilder();
        builder.append("Selected: ").append(selected == null ? "-" : selected.nationName())
                .append(selected == null || safe(selected.shortName()).equals("-") ? "" : " [" + selected.shortName() + "]")
                .append("\nRelation: ").append(relation == null ? safe(selected == null ? "" : selected.diplomacyStatusId()) : safe(relation.statusId()))
                .append("\nPending request: ").append(request == null ? "-" : safe(request.statusId()))
                .append("\nIncoming requests: ").append(data.incomingDiplomacyRequests().size())
                .append("\nAll nations:");
        appendWindow(builder, data.allNations(), selectedNationIndex, 6, nation ->
                nation.nationName() + " | " + safe(nation.shortName()) + " | " + safe(nation.diplomacyStatusId()));
        return builder.toString();
    }

    private String buildTreasuryText() {
        StringBuilder builder = new StringBuilder();
        builder.append("Balance: ").append(data.treasuryBalance())
                .append("\nSales tax: ").append(percent(data.salesTaxBasisPoints()))
                .append("\nImport tariff: ").append(percent(data.importTariffBasisPoints()))
                .append("\nRecent trades: ").append(data.recentTradeCount())
                .append("\n\nAnalytics:")
                .append("\nSales  ").append(bar(data.salesTaxBasisPoints(), 3000, 18)).append(" ").append(percent(data.salesTaxBasisPoints()))
                .append("\nImport ").append(bar(data.importTariffBasisPoints(), 5000, 18)).append(" ").append(percent(data.importTariffBasisPoints()))
                .append("\nTrade  ").append(bar(data.recentTradeCount(), Math.max(10, data.recentTradeCount()), 18)).append(" ").append(data.recentTradeCount());
        if (data.hasTradeProposal()) {
            builder.append("\nTrade proposal: ").append(safe(data.tradeProposerNationName()))
                    .append(" | Offer ").append(data.tradeOfferCurrency())
                    .append(" | Request ").append(data.tradeRequestCurrency());
        }
        if (data.hasPeaceProposal()) {
            builder.append("\nPeace proposal: ").append(safe(data.peaceProposalType()))
                    .append(" | Cede ").append(data.peaceProposalCede())
                    .append(" | Amount ").append(data.peaceProposalAmount());
        }
        return builder.toString();
    }

    private void handleOverviewPrimary() {
        if (data.hasNation()) {
            String nationName = textOf(nationNameInput);
            String shortName = textOf(shortNameInput);
            if (data.canManageInfo() && !nationName.isBlank() && !nationName.equals(data.nationName())) {
                send(new NationGuiActionPacket(NationGuiActionPacket.Action.RENAME_NATION, nationName, true));
            }
            if (data.canManageInfo() && !shortName.equals(data.shortName())) {
                send(new NationGuiActionPacket(NationGuiActionPacket.Action.SET_SHORT_NAME, shortName, true));
            }
            return;
        }
        String nationName = textOf(nationNameInput);
        if (!nationName.isBlank()) {
            send(new NationGuiActionPacket(NationGuiActionPacket.Action.CREATE_NATION, nationName, true));
        }
    }

    private void joinNation() {
        String target = textOf(joinNationInput);
        if (!target.isBlank()) {
            send(new NationGuiActionPacket(NationGuiActionPacket.Action.JOIN_NATION, target, true));
        }
    }

    private void appointOfficer() {
        NationOverviewMember selected = selectedMember();
        if (selected != null) {
            send(new NationGuiActionPacket(NationGuiActionPacket.Action.APPOINT_OFFICER, selected.playerUuid()));
        }
    }

    private void removeOfficer() {
        NationOverviewMember selected = selectedMember();
        if (selected != null) {
            send(new NationGuiActionPacket(NationGuiActionPacket.Action.REMOVE_OFFICER, selected.playerUuid()));
        }
    }

    private void appointMayor() {
        NationOverviewMember selected = selectedMember();
        if (selected != null && !data.capitalTownId().isBlank()) {
            send(new NationGuiActionPacket(NationGuiActionPacket.Action.APPOINT_MAYOR, selected.playerUuid(), data.capitalTownId()));
        }
    }

    private void renameOfficerTitle() {
        String title = textOf(officerTitleInput);
        if (!title.isBlank()) {
            send(new NationGuiActionPacket(NationGuiActionPacket.Action.RENAME_OFFICER_TITLE, title, true));
        }
    }

    private void kickSelectedMember() {
        NationOverviewMember selected = selectedMember();
        if (selected != null) {
            send(new NationGuiActionPacket(NationGuiActionPacket.Action.KICK_MEMBER, selected.playerUuid()));
        }
    }

    private void submitClaim(boolean unclaim) {
        int x = parseInt(textOf(claimChunkXInput), data.currentChunkX());
        int z = parseInt(textOf(claimChunkZInput), data.currentChunkZ());
        send(new NationGuiActionPacket(unclaim ? NationGuiActionPacket.Action.UNCLAIM_CHUNK : NationGuiActionPacket.Action.CLAIM_CHUNK, x, z));
    }

    private void submitClaimArea(boolean unclaim) {
        int x1 = parseInt(textOf(claimChunkXInput), data.currentChunkX());
        int z1 = parseInt(textOf(claimChunkZInput), data.currentChunkZ());
        int x2 = parseInt(textOf(claimAreaXInput), x1);
        int z2 = parseInt(textOf(claimAreaZInput), z1);
        send(new NationGuiActionPacket(unclaim ? NationGuiActionPacket.Action.UNCLAIM_AREA : NationGuiActionPacket.Action.CLAIM_AREA, x1, z1, "", x2 + "," + z2));
    }

    private void submitDiplomacyAction(NationGuiActionPacket.Action action) {
        NationOverviewNationEntry selected = selectedNation();
        if (selected != null) {
            send(new NationGuiActionPacket(action, selected.nationName(), true));
        }
    }

    private void openTradeScreen() {
        NationOverviewNationEntry selected = selectedNation();
        if (selected != null) {
            send(new NationGuiActionPacket(NationGuiActionPacket.Action.OPEN_TRADE_SCREEN, selected.nationId(), true));
        }
    }

    private void adjustSalesTax(int delta) {
        int next = Math.max(0, Math.min(3000, data.salesTaxBasisPoints() + delta));
        send(new NationGuiActionPacket(NationGuiActionPacket.Action.SET_SALES_TAX, String.valueOf(next), true));
    }

    private void adjustImportTariff(int delta) {
        int next = Math.max(0, Math.min(5000, data.importTariffBasisPoints() + delta));
        send(new NationGuiActionPacket(NationGuiActionPacket.Action.SET_IMPORT_TARIFF, String.valueOf(next), true));
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

    private NationOverviewNationEntry selectedNation() {
        List<NationOverviewNationEntry> nations = data.allNations();
        if (nations.isEmpty()) {
            return null;
        }
        return nations.get(clampSelection(selectedNationIndex, nations.size()));
    }

    private NationOverviewDiplomacyEntry selectedRelation() {
        NationOverviewNationEntry selected = selectedNation();
        if (selected == null) {
            return null;
        }
        for (NationOverviewDiplomacyEntry relation : data.diplomacyRelations()) {
            if (relation.nationId().equals(selected.nationId())) {
                return relation;
            }
        }
        return null;
    }

    private NationOverviewDiplomacyRequest selectedRequest() {
        NationOverviewNationEntry selected = selectedNation();
        if (selected == null) {
            return null;
        }
        for (NationOverviewDiplomacyRequest request : data.incomingDiplomacyRequests()) {
            if (request.nationId().equals(selected.nationId())) {
                return request;
            }
        }
        return null;
    }

    private boolean canAppointSelectedMember() {
        NationOverviewMember selected = selectedMember();
        return selected != null
                && !NationOfficeIds.LEADER.equals(selected.officeId())
                && !NationOfficeIds.OFFICER.equals(selected.officeId());
    }

    private boolean canRemoveSelectedOfficer() {
        NationOverviewMember selected = selectedMember();
        return selected != null && NationOfficeIds.OFFICER.equals(selected.officeId());
    }

    private boolean canAssignSelectedMemberAsMayor() {
        return selectedMember() != null && !data.capitalTownId().isBlank();
    }

    private boolean canKickSelectedMember() {
        NationOverviewMember selected = selectedMember();
        return selected != null && !NationOfficeIds.LEADER.equals(selected.officeId());
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

    private void send(NationGuiActionPacket packet) {
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

    private int countOnlineMembers() {
        int count = 0;
        for (NationOverviewMember member : data.members()) {
            if (member.online()) {
                count++;
            }
        }
        return count;
    }

    private int countOfficeMembers(String officeId) {
        int count = 0;
        for (NationOverviewMember member : data.members()) {
            if (officeId.equals(member.officeId())) {
                count++;
            }
        }
        return count;
    }

    private String bar(int value, int max, int width) {
        int safeMax = Math.max(1, max);
        int safeWidth = Math.max(1, width);
        int filled = Math.max(0, Math.min(safeWidth, (int) Math.round((value / (double) safeMax) * safeWidth)));
        return "[" + "#".repeat(filled) + ".".repeat(Math.max(0, safeWidth - filled)) + "]";
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

    private String percent(int basisPoints) {
        return String.format(Locale.ROOT, "%.1f%%", basisPoints / 100.0f);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
