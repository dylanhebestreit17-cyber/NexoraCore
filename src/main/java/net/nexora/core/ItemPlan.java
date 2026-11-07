package net.nexora.core;

import org.bukkit.inventory.ItemStack;
import java.util.*;

/** Compute a full delivery before changing inventory; never drop overflow on the ground. */
final class ItemPlan {
    static ItemStack[] fit(ItemStack[] inventory,List<ItemStack> items,int inventoryMax){
        ItemStack[] next=Arrays.stream(inventory).map(x->x==null?null:x.clone()).toArray(ItemStack[]::new);
        for(ItemStack template:items){
            if(template==null || template.getType().isAir() || template.getAmount()<=0)continue;
            int left=template.getAmount();
            int max=Math.min(inventoryMax,template.getMaxStackSize());
            if(max<=0)throw new IllegalArgumentException("Ungültige Stapelgröße");
            for(ItemStack slot:next)if(slot!=null && slot.isSimilar(template)){
                int n=Math.min(left,Math.max(0,max-slot.getAmount()));slot.setAmount(slot.getAmount()+n);left-=n;
            }
            for(int i=0;i<next.length && left>0;i++)if(next[i]==null || next[i].getType().isAir()){
                next[i]=template.clone();int n=Math.min(max,left);next[i].setAmount(n);left-=n;
            }
            if(left>0)throw new IllegalArgumentException("Nicht genug Platz im Inventar");
        }return next;
    }
    static String encode(List<ItemStack> items){
        return String.join(",",items.stream().map(i->Base64.getEncoder().encodeToString(i.serializeAsBytes())).toList());
    }
}
