package net.nexora.core;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Loaded only if LuckPerms is present. No console command interpolation. */
final class RankBridge {
    private final LuckPerms lp;
    RankBridge(LuckPerms lp){this.lp=lp;}
    String rank(UUID uuid){
        User user=lp.getUserManager().getUser(uuid);
        return user==null?null:user.getPrimaryGroup();
    }
    CompletableFuture<Void> change(UUID uuid,String group,boolean add){
        if(lp.getGroupManager().getGroup(group)==null)throw new IllegalArgumentException("LuckPerms-Gruppe existiert nicht");
        return lp.getUserManager().modifyUser(uuid,user->{
            InheritanceNode node=InheritanceNode.builder(group).build();
            if(add)user.data().add(node);else user.data().remove(node);
        });
    }
    boolean hasGroup(String group){return lp.getGroupManager().getGroup(group)!=null;}
    CompletableFuture<Void> cosmetic(UUID uuid,String node){
        return lp.getUserManager().modifyUser(uuid,user->user.data().add(net.luckperms.api.node.Node.builder(node).build()));
    }

}
