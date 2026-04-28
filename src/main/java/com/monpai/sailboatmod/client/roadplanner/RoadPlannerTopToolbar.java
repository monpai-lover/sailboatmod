package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.model.RoadToolType;

import java.util.ArrayList;
import java.util.List;

public record RoadPlannerTopToolbar(List<Item> items, RoadPlannerMapLayout.Rect bounds) {
    public static final String GROUP_TOOLS = "\u5de5\u5177";
    public static final String GROUP_EDIT = "\u7f16\u8f91";
    public static final String GROUP_ROUTE = "\u8def\u7ebf";
    public static final String ACTION_UNDO = "\u64a4\u9500";
    public static final String ACTION_CLEAR = "\u6e05\u9664";
    public static final String ACTION_AUTO_COMPLETE = "\u81ea\u52a8\u8865\u5168";
    public static final String ACTION_CONFIRM_BUILD = "\u786e\u8ba4\u5efa\u9020";
    public static final String ACTION_CANCEL = "\u53d6\u6d88";

    public static RoadPlannerTopToolbar defaultToolbar(int screenWidth) {
        return toolbar(screenWidth, Group.NONE);
    }

    public static RoadPlannerTopToolbar toolbar(int screenWidth, Group expandedGroup) {
        List<Item> items = new ArrayList<>();
        int x = 12;
        int y = 8;
        x = addGroup(items, GROUP_TOOLS, Group.TOOLS, x, y) + 8;
        x = addGroup(items, GROUP_EDIT, Group.EDIT, x, y) + 8;
        x = addGroup(items, GROUP_ROUTE, Group.ROUTE, x, y) + 8;
        if (expandedGroup != null && expandedGroup != Group.NONE) {
            items.addAll(expandedItems(expandedGroup, groupX(expandedGroup), 36));
        }
        int height = expandedGroup == null || expandedGroup == Group.NONE ? 34 : 36 + dropdownHeight(expandedGroup);
        return new RoadPlannerTopToolbar(List.copyOf(items), new RoadPlannerMapLayout.Rect(0, 0, screenWidth, height));
    }

    private static List<Item> expandedItems(Group group, int startX, int y) {
        List<Item> items = new ArrayList<>();
        int row = y;
        if (group == Group.TOOLS) {
            addTool(items, "\u9009\u62e9", RoadToolType.SELECT, startX, row);
            addTool(items, "\u7aef\u70b9", RoadToolType.ENDPOINT, startX, row += 24);
            addTool(items, "\u9053\u8def", RoadToolType.ROAD, startX, row += 24);
            addTool(items, "\u6865\u6881", RoadToolType.BRIDGE, startX, row += 24);
            addTool(items, "\u96a7\u9053", RoadToolType.TUNNEL, startX, row += 24);
            addTool(items, "\u64e6\u9664", RoadToolType.ERASE, startX, row += 24);
            addTool(items, "\u8de8\u6c34", RoadToolType.WATER_CROSSING, startX, row += 24);
            addTool(items, "\u8d1d\u585e\u5c14", RoadToolType.BEZIER, startX, row += 24);
            addTool(items, "\u5f3a\u5236\u6e32\u67d3", RoadToolType.FORCE_RENDER, startX, row += 24);
        } else if (group == Group.EDIT) {
            addAction(items, ACTION_UNDO, startX, row);
            addAction(items, ACTION_CLEAR, startX, row += 24);
        } else if (group == Group.ROUTE) {
            addAction(items, ACTION_AUTO_COMPLETE, startX, row);
            addAction(items, ACTION_CONFIRM_BUILD, startX, row += 24);
            addAction(items, ACTION_CANCEL, startX, row += 24);
        }
        return items;
    }

    private static int groupX(Group group) {
        return switch (group) {
            case TOOLS -> 12;
            case EDIT -> 78;
            case ROUTE -> 144;
            case NONE -> 12;
        };
    }

    private static int dropdownHeight(Group group) {
        return switch (group) {
            case TOOLS -> 9 * 24 + 2;
            case EDIT -> 2 * 24 + 2;
            case ROUTE -> 3 * 24 + 2;
            case NONE -> 0;
        };
    }

    private static int addGroup(List<Item> items, String label, Group group, int x, int y) {
        int width = 58;
        items.add(new Item(label, Kind.GROUP, null, group, new RoadPlannerMapLayout.Rect(x, y, width, 22)));
        return x + width;
    }

    private static int addTool(List<Item> items, String label, RoadToolType toolType, int x, int y) {
        int width = 90;
        items.add(new Item(label, Kind.TOOL, toolType, Group.NONE, new RoadPlannerMapLayout.Rect(x, y, width, 22)));
        return x + width;
    }

    private static int addAction(List<Item> items, String label, int x, int y) {
        int width = 90;
        items.add(new Item(label, Kind.ACTION, null, Group.NONE, new RoadPlannerMapLayout.Rect(x, y, width, 22)));
        return x + width;
    }

    public Item itemAt(double mouseX, double mouseY) {
        for (Item item : items) {
            if (item.bounds().contains(mouseX, mouseY)) {
                return item;
            }
        }
        return null;
    }

    public enum Group { NONE, TOOLS, EDIT, ROUTE }

    public enum Kind { GROUP, TOOL, ACTION }

    public record Item(String label, Kind kind, RoadToolType toolType, Group group, RoadPlannerMapLayout.Rect bounds) {
    }
}
