package com.kyochigo.economy.model;

import com.kyochigo.economy.utils.CraftEngineHook;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public class MarketItem {
    private final String configKey;
    private final String type;
    private final String id;
    private final String customName;
    private final String iconMaterial;
    private final String category;
    private final double basePrice;
    private final double lambda;
    private final boolean allowBuy;
    private final boolean allowSell;

    public MarketItem(String key, String type, String id, String category, double basePrice, double lambda, boolean allowBuy, boolean allowSell) {
        this(key, type, id, null, null, category, basePrice, lambda, allowBuy, allowSell);
    }

    public MarketItem(String key, String type, String id, String customName, String iconMaterial,
                      String category, double basePrice, double lambda, boolean allowBuy, boolean allowSell) {
        this.configKey = key;
        this.type = type;
        this.id = id;
        this.customName = customName;
        this.iconMaterial = iconMaterial;
        this.category = category;
        this.basePrice = basePrice;
        this.lambda = lambda;
        this.allowBuy = allowBuy;
        this.allowSell = allowSell;
    }

    /**
     * 获取用于展示的图标
     * @param hook CraftEngine 钩子，用于获取自定义物品
     * @return ItemStack
     */
    public ItemStack getIcon(CraftEngineHook hook) {
        // 1. 尝试获取 CraftEngine 物品
        if ("CRAFTENGINE".equalsIgnoreCase(type)) {
            if (hook != null) {
                ItemStack ceItem = hook.getItem(id);
                if (ceItem != null) return ceItem.clone();
            }
        }

        // 2. 尝试获取 iconMaterial 指定的材质
        if (this.iconMaterial != null && !this.iconMaterial.isEmpty()) {
            Material mat = Material.matchMaterial(this.iconMaterial);
            if (mat != null) return new ItemStack(mat);
        }

        // 3. 尝试将 ID 解析为材质 (针对 MATERIAL 类型)
        Material mat = Material.matchMaterial(id);
        if (mat != null) return new ItemStack(mat);

        // 4. 只有万不得已才返回 Barrier，并建议在控制台检查配置
        return new ItemStack(Material.BARRIER);
    }

    /**
     * 兼容性方法：直接获取 Material 类型
     * 用于某些只接受 Material 的 API (防止报错)
     */
    public Material getMaterial() {
        Material mat = null;
        if (this.iconMaterial != null && !this.iconMaterial.isEmpty()) {
            mat = Material.matchMaterial(this.iconMaterial);
        }
        if (mat == null) {
            mat = Material.matchMaterial(this.id);
        }
        return mat != null ? mat : Material.BARRIER;
    }

    public boolean matches(ItemStack item, CraftEngineHook hook) {
        if (item == null || item.getType().isAir()) return false;

        if ("CRAFTENGINE".equalsIgnoreCase(type)) {
            if (hook == null) return false;
            return hook.isCraftEngineItem(item, id);
        }

        if ("MATERIAL".equalsIgnoreCase(type)) {
            // 增强的材质匹配，忽略大小写
            String typeName = item.getType().name();
            String keyName = null;
            try {
                if (item.getType().getKey() != null) {
                    keyName = item.getType().getKey().getKey();
                }
            } catch (Exception ignored) {}

            return typeName.equalsIgnoreCase(id) || (keyName != null && keyName.equalsIgnoreCase(id));
        }

        return false;
    }

    public String getDisplayName() {
        if (this.customName != null && !this.customName.isEmpty()) {
            return this.customName.replace("&", "§");
        }
        return getDisplayNameOrLangKey();
    }

    public String getDisplayNameOrLangKey() {
        if (this.customName != null && !this.customName.isEmpty()) {
            return this.customName.replace("&", "§");
        }
        
        if ("CRAFTENGINE".equalsIgnoreCase(type)) return id;

        Material mat = Material.matchMaterial(id);
        if (mat != null) {
            try {
                // 返回翻译键值，配合 Paper 的 Component.translatable 使用效果更佳
                return "<lang:" + mat.getTranslationKey() + ">";
            } catch (NoSuchMethodError e) {
                return mat.name();
            }
        }
        return "Unknown Item";
    }

    // --- Getters ---

    public String getConfigKey() { return configKey; }
    public String getId() { return id; }
    public String getType() { return type; }
    
    public String getMaterialName() { 
        if (iconMaterial != null && !iconMaterial.isEmpty()) return iconMaterial.toUpperCase();
        if (id != null) return id.toUpperCase();
        return "STONE";
    }

    public double getBasePrice() { return basePrice; }
    public double getLambda() { return lambda; }
    public boolean isAllowBuy() { return allowBuy; }
    public boolean isAllowSell() { return allowSell; }

    // --- 兼容性 Getters (解决 Dialog 报错) ---
    
    public double getBuyPrice() {
        return basePrice;
    }

    public double getSellPrice() {
        // 如果你需要买卖差价，可以在这里修改逻辑
        // 例如：return basePrice * 0.5;
        return basePrice; 
    }
}