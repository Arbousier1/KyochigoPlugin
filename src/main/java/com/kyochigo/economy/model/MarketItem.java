package com.kyochigo.economy.model;

import com.google.gson.JsonObject;
import com.kyochigo.economy.utils.CraftEngineHook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Optional;

/**
 * 市场物品模型 (v3.3 补全版)
 * 修复了 getTempNeff 方法未定义的编译错误。
 */
public class MarketItem {

    private static final double PRICE_THRESHOLD = 0.001;
    private static final double DEFAULT_BUY_MULTIPLIER = 1.25;

    public enum ItemType {
        MATERIAL, CRAFTENGINE;
        public static ItemType from(String type) {
            return Arrays.stream(values())
                    .filter(t -> t.name().equalsIgnoreCase(type))
                    .findFirst().orElse(MATERIAL);
        }
    }

    private final String configKey;
    private final ItemType itemType;
    private final String id;
    private final String customName;
    private final String iconMaterial;
    private final String category;
    private final double basePrice;
    private final double lambda;
    private final boolean allowBuy;
    private final boolean allowSell;
    private int n;

    private double tempNeff = 0.0;
    private double tempPrice = 0.0;
    private double tempBuyPrice = 0.0;

    private MarketItem(Builder builder) {
        this.configKey = builder.key;
        this.itemType = ItemType.from(builder.type);
        this.id = builder.id;
        this.customName = builder.customName;
        this.iconMaterial = builder.iconMaterial;
        this.category = builder.category;
        this.basePrice = builder.basePrice;
        this.lambda = builder.lambda;
        this.allowBuy = builder.allowBuy;
        this.allowSell = builder.allowSell;
        this.n = builder.initialN;
    }

    // =========================================================================
    // 逻辑匹配与视觉渲染
    // =========================================================================

    public boolean matches(@Nullable ItemStack item, @Nullable CraftEngineHook hook) {
        if (item == null || item.getType().isAir()) return false;
        return switch (itemType) {
            case CRAFTENGINE -> hook != null && hook.isCraftEngineItem(item, id);
            case MATERIAL -> item.getType().name().equalsIgnoreCase(id);
        };
    }

    public Component getDisplayNameComponent(@Nullable CraftEngineHook hook) {
        return Optional.ofNullable(customName)
                .filter(name -> !name.isEmpty())
                .map(this::parseName)
                .or(() -> getCEDisplayName(hook))
                .or(this::getMaterialDisplayName)
                .orElse(Component.text(id));
    }

    private Component parseName(String name) {
        return (name.contains("&") || name.contains("§"))
                ? LegacyComponentSerializer.legacyAmpersand().deserialize(name)
                : MiniMessage.miniMessage().deserialize(name);
    }

    private Optional<Component> getCEDisplayName(CraftEngineHook hook) {
        if (itemType != ItemType.CRAFTENGINE || hook == null) return Optional.empty();
        return Optional.ofNullable(hook.getItem(id))
                .filter(ItemStack::hasItemMeta)
                .map(item -> item.getItemMeta().displayName())
                .or(() -> Optional.of(Component.text(id)));
    }

    private Optional<Component> getMaterialDisplayName() {
        return Optional.ofNullable(Material.matchMaterial(id))
                .map(mat -> Component.translatable(mat.translationKey()));
    }

    public String getPlainDisplayName() {
        return PlainTextComponentSerializer.plainText().serialize(getDisplayNameComponent(null));
    }

    @NotNull
    public ItemStack getIcon(@Nullable CraftEngineHook hook) {
        return Optional.ofNullable(itemType == ItemType.CRAFTENGINE ? hook : null)
                .map(h -> h.getItem(id))
                .or(() -> {
                    String matName = (iconMaterial != null && !iconMaterial.isEmpty()) ? iconMaterial : id;
                    return Optional.ofNullable(Material.matchMaterial(matName)).map(ItemStack::new);
                })
                .map(ItemStack::clone)
                .orElse(new ItemStack(Material.BARRIER));
    }

    public JsonObject toJsonObject() {
        JsonObject json = new JsonObject();
        json.addProperty("id", configKey);
        json.addProperty("name", getPlainDisplayName());
        json.addProperty("basePrice", basePrice);
        json.addProperty("lambda", lambda);
        json.addProperty("n", (double) n);
        json.addProperty("iota", 0.0);
        return json;
    }

    // =========================================================================
    // 核心修复区域：Standard Getters
    // =========================================================================

    public String getConfigKey() { return configKey; }
    public ItemType getItemType() { return itemType; }
    public String getId() { return id; }
    public String getCategory() { return category; }
    public double getBasePrice() { return basePrice; }
    public double getLambda() { return lambda; }
    public boolean isAllowBuy() { return allowBuy; }
    public boolean isAllowSell() { return allowSell; }
    public int getN() { return n; }
    
    // 行情数据 Getter [修复重点]
    public double getTempNeff() { return tempNeff; }
    public double getRawTempPrice() { return tempPrice; }
    public double getRawTempBuyPrice() { return tempBuyPrice; }

    public double getSellPrice() { return (tempPrice > PRICE_THRESHOLD) ? tempPrice : basePrice; }
    public double getBuyPrice() { return (tempBuyPrice > PRICE_THRESHOLD) ? tempBuyPrice : basePrice * DEFAULT_BUY_MULTIPLIER; }

    // Setters
    public void setN(int n) { this.n = n; }
    public void setTempPrice(double p) { this.tempPrice = p; }
    public void setTempBuyPrice(double p) { this.tempBuyPrice = p; }
    public void setTempNeff(double n) { this.tempNeff = n; }

    public Material getMaterial() { 
        return Optional.ofNullable(Material.matchMaterial(id)).orElse(Material.BARRIER); 
    }

    // Builder
    public static class Builder {
        private String key, type = "MATERIAL", id, customName, iconMaterial, category = "misc";
        private double basePrice, lambda;
        private boolean allowBuy = true, allowSell = true;
        private int initialN = 0;
        public Builder key(String v) { this.key = v; return this; }
        public Builder type(String v) { this.type = v; return this; }
        public Builder id(String v) { this.id = v; return this; }
        public Builder customName(String v) { this.customName = v; return this; }
        public Builder iconMaterial(String v) { this.iconMaterial = v; return this; }
        public Builder category(String v) { this.category = v; return this; }
        public Builder basePrice(double v) { this.basePrice = v; return this; }
        public Builder lambda(double v) { this.lambda = v; return this; }
        public Builder allowBuy(boolean v) { this.allowBuy = v; return this; }
        public Builder allowSell(boolean v) { this.allowSell = v; return this; }
        public Builder initialN(int v) { this.initialN = v; return this; }
        public MarketItem build() { return new MarketItem(this); }
    }
}