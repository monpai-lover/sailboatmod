package com.monpai.sailboatmod.client.modernui;

import com.monpai.sailboatmod.client.screen.SailboatInfoScreen;
import com.monpai.sailboatmod.entity.SailboatEntity;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.ControlAutopilotPacket;
import com.monpai.sailboatmod.network.packet.OpenSailboatStoragePacket;
import com.monpai.sailboatmod.network.packet.RenameSailboatPacket;
import com.monpai.sailboatmod.network.packet.SelectSailboatSeatPacket;
import com.monpai.sailboatmod.network.packet.SetHandlingPresetPacket;
import com.monpai.sailboatmod.network.packet.SetSailboatRentalPricePacket;
import com.monpai.sailboatmod.network.packet.ToggleSailPacket;
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
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

public class SailboatInfoModernFragment extends Fragment implements ScreenCallback {
    private final SailboatEntity sailboat;
    private int selectedSeat;

    private TextView titleView;
    private TextView statusView;
    private TextView routeBody;
    private TextView seatsBody;
    private EditText nameInput;
    private EditText rentalInput;

    public SailboatInfoModernFragment(SailboatEntity sailboat) {
        this.sailboat = sailboat;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable DataSet savedInstanceState) {
        ScrollView scrollView = new ScrollView(requireContext());
        LinearLayout root = ModernUiScreenHelper.createRoot(new LinearLayout(requireContext()));
        scrollView.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        titleView = ModernUiScreenHelper.createHeader(new TextView(requireContext()), "Sailboat Control", 20);
        statusView = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        root.addView(titleView, ModernUiScreenHelper.matchWidthWrap());
        root.addView(statusView, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout topRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        Button refreshButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Refresh");
        refreshButton.setOnClickListener(v -> refreshViews());
        Button classicButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Classic Controls");
        classicButton.setOnClickListener(v -> Minecraft.getInstance().setScreen(new SailboatInfoScreen(sailboat)));
        topRow.addView(refreshButton, ModernUiScreenHelper.wrap());
        topRow.addView(classicButton, ModernUiScreenHelper.wrap());
        root.addView(topRow, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout infoCard = ModernUiScreenHelper.createCard(new LinearLayout(requireContext()));
        infoCard.addView(ModernUiScreenHelper.createHeader(new TextView(requireContext()), "Identity", 14), ModernUiScreenHelper.matchWidthWrap());
        nameInput = ModernUiScreenHelper.createInput(new EditText(requireContext()), currentName());
        rentalInput = ModernUiScreenHelper.createInput(new EditText(requireContext()), formatRentalPriceInput());
        infoCard.addView(label("Boat Name"), ModernUiScreenHelper.matchWidthWrap());
        infoCard.addView(nameInput, ModernUiScreenHelper.matchWidthWrap());
        infoCard.addView(label("Rental Price"), ModernUiScreenHelper.matchWidthWrap());
        infoCard.addView(rentalInput, ModernUiScreenHelper.matchWidthWrap());
        LinearLayout infoRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        Button saveButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Save");
        saveButton.setOnClickListener(v -> saveInfo());
        Button storageButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Open Storage");
        storageButton.setOnClickListener(v -> ModNetwork.CHANNEL.sendToServer(new OpenSailboatStoragePacket()));
        infoRow.addView(saveButton, ModernUiScreenHelper.wrap());
        infoRow.addView(storageButton, ModernUiScreenHelper.wrap());
        infoCard.addView(infoRow, ModernUiScreenHelper.matchWidthWrap());
        root.addView(infoCard, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout routeCard = ModernUiScreenHelper.createCard(new LinearLayout(requireContext()));
        routeCard.addView(ModernUiScreenHelper.createHeader(new TextView(requireContext()), "Sailing And Autopilot", 14), ModernUiScreenHelper.matchWidthWrap());
        routeBody = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        routeCard.addView(routeBody, ModernUiScreenHelper.matchWidthWrap());
        LinearLayout sailRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        Button sailButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Toggle Sail");
        sailButton.setOnClickListener(v -> {
            ModNetwork.CHANNEL.sendToServer(new ToggleSailPacket());
            refreshViews();
        });
        Button handlingButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Next Handling");
        handlingButton.setOnClickListener(v -> {
            ModNetwork.CHANNEL.sendToServer(new SetHandlingPresetPacket(sailboat.getHandlingPreset().next().id));
            refreshViews();
        });
        sailRow.addView(sailButton, ModernUiScreenHelper.wrap());
        sailRow.addView(handlingButton, ModernUiScreenHelper.wrap());
        routeCard.addView(sailRow, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout routeNavRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        Button prevRouteButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "< Route");
        prevRouteButton.setOnClickListener(v -> autopilot(SailboatEntity.AutopilotControlAction.PREV_ROUTE));
        Button nextRouteButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Route >");
        nextRouteButton.setOnClickListener(v -> autopilot(SailboatEntity.AutopilotControlAction.NEXT_ROUTE));
        routeNavRow.addView(prevRouteButton, ModernUiScreenHelper.wrap());
        routeNavRow.addView(nextRouteButton, ModernUiScreenHelper.wrap());
        routeCard.addView(routeNavRow, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout autopilotRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        autopilotRow.addView(controlButton("Start", SailboatEntity.AutopilotControlAction.START), ModernUiScreenHelper.wrap());
        autopilotRow.addView(controlButton("Pause", SailboatEntity.AutopilotControlAction.PAUSE), ModernUiScreenHelper.wrap());
        autopilotRow.addView(controlButton("Resume", SailboatEntity.AutopilotControlAction.RESUME), ModernUiScreenHelper.wrap());
        autopilotRow.addView(controlButton("Stop", SailboatEntity.AutopilotControlAction.STOP), ModernUiScreenHelper.wrap());
        routeCard.addView(autopilotRow, ModernUiScreenHelper.matchWidthWrap());
        root.addView(routeCard, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout seatCard = ModernUiScreenHelper.createCard(new LinearLayout(requireContext()));
        seatCard.addView(ModernUiScreenHelper.createHeader(new TextView(requireContext()), "Crew Seats", 14), ModernUiScreenHelper.matchWidthWrap());
        seatsBody = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        seatCard.addView(seatsBody, ModernUiScreenHelper.matchWidthWrap());
        LinearLayout seatNavRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        Button prevSeatButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "< Seat");
        prevSeatButton.setOnClickListener(v -> {
            selectedSeat = Math.floorMod(selectedSeat - 1, 5);
            refreshViews();
        });
        Button nextSeatButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Seat >");
        nextSeatButton.setOnClickListener(v -> {
            selectedSeat = Math.floorMod(selectedSeat + 1, 5);
            refreshViews();
        });
        Button chooseSeatButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Use Selected Seat");
        chooseSeatButton.setOnClickListener(v -> {
            ModNetwork.CHANNEL.sendToServer(new SelectSailboatSeatPacket(sailboat.getId(), selectedSeat));
            refreshViews();
        });
        seatNavRow.addView(prevSeatButton, ModernUiScreenHelper.wrap());
        seatNavRow.addView(nextSeatButton, ModernUiScreenHelper.wrap());
        seatNavRow.addView(chooseSeatButton, ModernUiScreenHelper.wrap());
        seatCard.addView(seatNavRow, ModernUiScreenHelper.matchWidthWrap());
        root.addView(seatCard, ModernUiScreenHelper.matchWidthWrap());

        refreshViews();
        return scrollView;
    }

    private TextView label(String text) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextSize(11);
        return view;
    }

    private Button controlButton(String text, SailboatEntity.AutopilotControlAction action) {
        Button button = ModernUiScreenHelper.createButton(new Button(requireContext()), text);
        button.setOnClickListener(v -> autopilot(action));
        return button;
    }

    private void saveInfo() {
        ModNetwork.CHANNEL.sendToServer(new RenameSailboatPacket(sailboat.getId(), nameInput.getText().toString()));
        ModNetwork.CHANNEL.sendToServer(new SetSailboatRentalPricePacket(sailboat.getId(), parseRentalPriceInput()));
        refreshViews();
    }

    private void autopilot(SailboatEntity.AutopilotControlAction action) {
        ModNetwork.CHANNEL.sendToServer(new ControlAutopilotPacket(sailboat.getId(), action));
        refreshViews();
    }

    private void refreshViews() {
        titleView.setText(currentName());
        statusView.setText("Owner: " + sailboat.getOwnerName()
                + "\nHandling: " + Component.translatable("screen.sailboatmod.preset." + sailboat.getHandlingPreset().name().toLowerCase()).getString()
                + "\nRental: " + (sailboat.isAvailableForRent() ? sailboat.getRentalPrice() : "Disabled"));
        if (nameInput != null && !nameInput.isFocused()) {
            nameInput.setText(currentName());
        }
        if (rentalInput != null && !rentalInput.isFocused()) {
            rentalInput.setText(formatRentalPriceInput());
        }
        routeBody.setText("Current route: " + sailboat.getSelectedRouteName()
                + "\nRoute index: " + (sailboat.getSelectedRouteIndex() + 1) + "/" + Math.max(1, sailboat.getRouteCount())
                + "\nState: " + autopilotState()
                + "\nSail: " + (sailboat.isSailDeployed() ? "Deployed" : "Stowed"));
        seatsBody.setText(buildSeatText());
    }

    private String buildSeatText() {
        StringBuilder builder = new StringBuilder();
        builder.append("Selected seat: ").append(selectedSeat + 1);
        for (int seat = 0; seat < 5; seat++) {
            Entity occupant = getSeatOccupant(seat);
            builder.append('\n')
                    .append(seat == selectedSeat ? "> " : "- ")
                    .append(seat + 1)
                    .append(": ")
                    .append(occupant == null ? (seat == 0 ? "Captain" : "Empty") : shorten(occupant.getName().getString()));
        }
        return builder.toString();
    }

    private Entity getSeatOccupant(int seat) {
        for (Entity passenger : sailboat.getPassengers()) {
            if (sailboat.getSeatFor(passenger) == seat) {
                return passenger;
            }
        }
        return null;
    }

    private String autopilotState() {
        if (!sailboat.isAutopilotActive()) {
            return "Stopped";
        }
        return sailboat.isAutopilotPaused() ? "Paused" : "Running";
    }

    private int parseRentalPriceInput() {
        try {
            int parsed = Integer.parseInt(rentalInput.getText().toString().trim());
            return Math.max(SailboatEntity.MIN_RENTAL_PRICE, Math.min(SailboatEntity.MAX_RENTAL_PRICE, parsed));
        } catch (Exception ignored) {
            return sailboat.getRentalPrice();
        }
    }

    private String formatRentalPriceInput() {
        return Integer.toString(sailboat.getRentalPrice());
    }

    private String currentName() {
        return sailboat.hasCustomName() ? sailboat.getCustomName().getString() : Component.translatable("screen.sailboatmod.unnamed").getString();
    }

    private String shorten(String text) {
        return text.length() > 18 ? text.substring(0, 18) + "..." : text;
    }
}
