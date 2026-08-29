package jp.monakaserver.monakabu.gui;

import java.util.List;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class ItemFactory {
    private static final MiniMessage MM=MiniMessage.miniMessage();private ItemFactory(){}
    public static ItemStack item(Material material,String name,List<String> lore){return item(material,name,lore,0);}
    @SuppressWarnings("deprecation")
    public static ItemStack item(Material material,String name,List<String> lore,int customModelData){ItemStack stack=new ItemStack(material);ItemMeta meta=stack.getItemMeta();meta.displayName(MM.deserialize(name));meta.lore(lore.stream().map(MM::deserialize).toList());if(customModelData>0)meta.setCustomModelData(customModelData);stack.setItemMeta(meta);return stack;}
}
