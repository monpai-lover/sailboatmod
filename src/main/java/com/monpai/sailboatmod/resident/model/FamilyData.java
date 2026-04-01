package com.monpai.sailboatmod.resident.model;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Family relationships for a resident (inspired by MineColonies)
 */
public class FamilyData {
    private String partnerId = "";
    private String parentA = "";
    private String parentB = "";
    private final List<String> children = new ArrayList<>();
    private final List<String> siblings = new ArrayList<>();

    public String getPartnerId() { return partnerId; }
    public void setPartner(String id) { this.partnerId = id == null ? "" : id; }
    public boolean hasPartner() { return !partnerId.isEmpty(); }

    public String getParentA() { return parentA; }
    public String getParentB() { return parentB; }
    public void setParents(String a, String b) {
        this.parentA = a == null ? "" : a;
        this.parentB = b == null ? "" : b;
    }

    public List<String> getChildren() { return Collections.unmodifiableList(children); }
    public void addChild(String childId) {
        if (childId != null && !childId.isEmpty() && !children.contains(childId)) {
            children.add(childId);
        }
    }

    public List<String> getSiblings() { return Collections.unmodifiableList(siblings); }
    public void addSibling(String siblingId) {
        if (siblingId != null && !siblingId.isEmpty() && !siblings.contains(siblingId)) {
            siblings.add(siblingId);
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Partner", partnerId);
        tag.putString("ParentA", parentA);
        tag.putString("ParentB", parentB);
        tag.put("Children", saveStringList(children));
        tag.put("Siblings", saveStringList(siblings));
        return tag;
    }

    public static FamilyData load(CompoundTag tag) {
        FamilyData data = new FamilyData();
        data.partnerId = tag.getString("Partner");
        data.parentA = tag.getString("ParentA");
        data.parentB = tag.getString("ParentB");
        loadStringList(tag, "Children", data.children);
        loadStringList(tag, "Siblings", data.siblings);
        return data;
    }

    private static ListTag saveStringList(List<String> list) {
        ListTag tags = new ListTag();
        for (String s : list) tags.add(StringTag.valueOf(s));
        return tags;
    }

    private static void loadStringList(CompoundTag tag, String key, List<String> out) {
        if (tag.contains(key)) {
            ListTag list = tag.getList(key, Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) out.add(list.getString(i));
        }
    }
}
