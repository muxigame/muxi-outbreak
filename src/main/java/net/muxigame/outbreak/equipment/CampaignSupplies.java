package net.muxigame.outbreak.equipment;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.muxigame.outbreak.*;
import java.util.*;

/** Server-owned shared finite stock. Visual entities are views, never the source of truth. */
public final class CampaignSupplies {
    public static final String TAG="muxi_outbreak_supply",SESSION="muxi_outbreak_supply_session";
    public static final class Node {
        public final String id,kind;public final int section;public final BlockPos pos;
        public final SupplyRules.Stock stock;public final ItemStack item;
        public Entity display,hitbox;
        public final Set<UUID> upgradesClaimed=new HashSet<>();
        public final Set<UUID> startClaims=new HashSet<>();
        Node(String id,String kind,int section,BlockPos pos,int count,boolean infinite,ItemStack item){
            this.id=id;this.kind=kind;this.section=section;this.pos=pos;stock=new SupplyRules.Stock(count,infinite);this.item=item;
        }
    }
    private final OutbreakSession session;
    private final Map<String,Node> nodes=new LinkedHashMap<>();
    private final Set<String> decided=new HashSet<>();
    private final Map<UUID,Integer> usedAt=new HashMap<>();
    private int generated;
    public CampaignSupplies(OutbreakSession session){this.session=session;}

    public void tick(ServerLevel level,List<ServerPlayer> players,int now){
        if(players.isEmpty())return;
        ensureStartCache(level,players);
        if(now%10==0)for(var source:session.map.supplies()){
            if(source.section()!=session.section||decided.contains(source.id()))continue;
            if(players.stream().noneMatch(p->p.distanceToSqr(source.pos().getCenter())<48*48))continue;
            // Optional Director choices belong to the team's approaching floor/area.
            // Fixed BSP caches remain preplaced; do not choose downstairs loot at upstairs spawn.
            if(source.directorChoice()&&players.stream().noneMatch(p->Math.abs(p.getY()-source.pos().getY())<=6&&p.distanceToSqr(source.pos().getCenter())<24*24))continue;
            if(!level.areEntitiesLoaded(new net.minecraft.world.level.ChunkPos(source.pos()).toLong())||!level.isPositionEntityTicking(source.pos()))continue;
            decided.add(source.id());
            double health=players.stream().mapToDouble(p->p.getHealth()/p.getMaxHealth()).average().orElse(1);
            int meds=(int)players.stream().filter(p->!p.getInventory().getItem(3).isEmpty()).count();
            String kind=SupplyRules.choose(source.choices(),source.mustExist(),source.directorChoice(),health,meds,players.size(),
                new Random(session.id.getMostSignificantBits()^session.id.getLeastSignificantBits()^source.id().hashCode()));
            if(kind.isBlank())continue;
            Node node=new Node(source.id(),kind,source.section(),source.pos(),source.count(),source.infinite(),CampaignInventory.create(players.getFirst(),kind));
            nodes.put(node.id,node);
        }
        nodes.entrySet().removeIf(entry->{Node node=entry.getValue();if(node.kind.equals("swapped")&&!node.stock.available()){removeVisuals(node);return true;}return false;});
        for(Node node:nodes.values()){
            if(node.section!=session.section||!node.stock.available()){removeVisuals(node);continue;}
            boolean near=players.stream().anyMatch(p->p.distanceToSqr(node.pos.getCenter())<48*48);
            if(!near){removeVisuals(node);continue;}
            if(node.hitbox==null||node.hitbox.isRemoved())show(level,node);
        }
    }
    /** Explicit native starting-cache adaptation; source BSP pickups stay unchanged. */
    private void ensureStartCache(ServerLevel level,List<ServerPlayer> players){
        if(session.section!=0||decided.contains("campaign_start_cache"))return;
        BlockPos start=session.map.sectionStart(0);var room=session.map.startRoom(0);
        List<BlockPos> positions=new ArrayList<>();
        for(int radius=1;radius<=3&&positions.size()<3;radius++)for(int dx=-radius;dx<=radius&&positions.size()<3;dx++)for(int dz=-radius;dz<=radius&&positions.size()<3;dz++){
            if(Math.max(Math.abs(dx),Math.abs(dz))!=radius)continue;
            BlockPos at=start.offset(dx,0,dz);
            if(room!=null&&!room.contains(at.getBottomCenter()))continue;
            if(!level.getBlockState(at).getCollisionShape(level,at).isEmpty()||!level.getBlockState(at.above()).getCollisionShape(level,at.above()).isEmpty()||level.getBlockState(at.below()).getCollisionShape(level,at.below()).isEmpty())continue;
            positions.add(at);
        }
        if(positions.size()<3)throw new IllegalStateException("No supported starting weapon cache positions in "+session.map.id());
        Random seeded=new Random(session.id.getMostSignificantBits()^session.id.getLeastSignificantBits());
        String primary=seeded.nextBoolean()?"gun:tacz:hk_mp5a5":"gun:tacz:m870";
        String[] ids={"campaign_start_primary","campaign_start_pistol","campaign_start_melee"};
        String[] kinds={primary,"gun:tacz:glock_17","melee"};
        int count=Math.max(1,session.players.size());
        for(int i=0;i<3;i++)nodes.put(ids[i],new Node(ids[i],kinds[i],0,positions.get(i),count,false,CampaignInventory.create(players.getFirst(),kinds[i])));
        decided.add("campaign_start_cache");
    }
    private void show(ServerLevel level,Node node){
        removeVisuals(node);
        var display=EntityType.ITEM_DISPLAY.create(level);
        var interaction=EntityType.INTERACTION.create(level);
        if(display==null||interaction==null)return;
        CompoundTag tag=new CompoundTag();display.saveWithoutId(tag);
        tag.put("item",node.item.save(level.registryAccess()));tag.putString("item_display","ground");tag.putString("billboard","vertical");display.load(tag);
        CompoundTag hit=new CompoundTag();interaction.saveWithoutId(hit);hit.putFloat("width",.7f);hit.putFloat("height",.7f);hit.putBoolean("response",true);interaction.load(hit);
        for(Entity entity:List.of(display,interaction)){
            entity.setPos(node.pos.getX()+.5,node.pos.getY()+.25,node.pos.getZ()+.5);
            entity.getPersistentData().putString(TAG,node.id);entity.getPersistentData().putString(SESSION,session.id.toString());
            entity.setInvulnerable(true);entity.setNoGravity(true);entity.addTag("muxi_outbreak_supply");
            entity.setCustomName(label(node));entity.setCustomNameVisible(entity==interaction);
            level.addFreshEntity(entity);
        }
        node.display=display;node.hitbox=interaction;
    }
    public static Component label(Node node){
        // Preserve the translatable component until it reaches the player's client.
        // Resolving getString() on the server baked English names into a Chinese UI.
        Component itemName=node.kind.equals("ammo")?Component.translatable("supply.muxi_outbreak.ammo"):
            node.kind.equals("upgrade_station")?Component.translatable("item.muxi_outbreak.explosive_ammo_pack"):CampaignInventory.displayName(node.item);
        return Component.empty().append(itemName).append(node.stock.infinite()?" ∞":" ×"+node.stock.remaining());
    }
    public boolean interactLook(ServerPlayer player){return interactLook(player,Double.POSITIVE_INFINITY);}
    /** A nearer block wins over supplies behind it, so one F press cannot act through a door. */
    public boolean interactLook(ServerPlayer player,double blockDistance){
        Vec3 start=player.getEyePosition(),end=start.add(player.getLookAngle().scale(3.5));
        Node closest=null;double distance=Double.MAX_VALUE;
        for(Node n:nodes.values()){
            if(n.hitbox==null||n.hitbox.isRemoved()||!n.stock.available()||n.section!=session.section)continue;
            var hit=n.hitbox.getBoundingBox().inflate(.15).clip(start,end);if(hit.isEmpty())continue;
            double d=start.distanceToSqr(hit.get());if(d<blockDistance&&d<distance){distance=d;closest=n;}
        }
        if(closest==null)return false;String message=take(player,closest.id,true);if(!message.isBlank())player.sendSystemMessage(Component.literal("[Outbreak] "+message));return true;
    }
    public String take(ServerPlayer player,String id,boolean swap){
        Node node=nodes.get(id);int now=player.server.getTickCount();
        if(node==null||node.section!=session.section||!node.stock.available())return "补给不存在或已被取走";
        if(!session.alive.contains(player.getUUID())||session.downed.contains(player.getUUID()))return "当前不能拾取补给";
        if(!player.level().dimension().equals(session.map.dimension())||player.distanceToSqr(node.pos.getCenter())>12.25)return "离补给太远";
        var hit=player.level().clip(new ClipContext(player.getEyePosition(),node.pos.getCenter(),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player));
        if(hit.getType()!=HitResult.Type.MISS&&hit.getLocation().distanceTo(node.pos.getCenter())>.8)return "补给被墙体遮挡";
        if(now-usedAt.getOrDefault(player.getUUID(),-100)<2)return ""; // duplicate main/offhand packet, not a gameplay refill cooldown
        usedAt.put(player.getUUID(),now);
        CampaignInventory.normalize(player,stack->drop(player,stack));
        if(node.kind.equals("ammo")){
            boolean changed=CampaignInventory.refill(player,true);
            return changed?"备用弹药已补满；仍需正常换弹":"备用弹药已满，或当前主武器不接受普通弹药堆";
        }
        if(node.kind.equals("upgrade_station")){
            if(node.upgradesClaimed.contains(player.getUUID()))return "你已领取这份高爆弹药";
            int rounds=CampaignInventory.capacity(player.getInventory().getItem(0));
            if(rounds<1||CampaignInventory.excludedFromAmmoPile(player.getInventory().getItem(0)))return "当前主武器不能装填高爆弹药";
            if(!node.stock.take(true))return "升级包已耗尽";
            node.upgradesClaimed.add(player.getUUID());session.explosiveRounds.put(player.getUUID(),rounds);
            CampaignInventory.explosiveRounds(player.getInventory().getItem(0),rounds);
            if(!node.stock.available())removeVisuals(node);return "已装备一弹匣高爆弹药："+rounds;
        }
        if(CampaignInventory.category(node.item)==SupplyRules.Slot.AMMO){
            int count=Math.min(node.stock.remaining(),CampaignInventory.looseAmmoCapacity(player,node.item));
            if(count<1)return "需先携带对应枪械，或备用弹药已满";
            if(!node.stock.take(count))return "补给已被队友取走";
            CampaignInventory.receiveLooseAmmo(player,node.item,count);if(!node.stock.available())removeVisuals(node);else if(node.hitbox!=null)node.hitbox.setCustomName(label(node));return "已拾取备用弹药："+count;
        }
        int slot=CampaignInventory.slot(CampaignInventory.category(node.item));
        if(slot<0)return "该物品不能携带";
        ItemStack previous=player.getInventory().getItem(slot);
        if(!previous.isEmpty()&&!swap)return "按F拾取或交换；旧装备留给队友";
        if(node.id.startsWith("campaign_start_")&&node.startClaims.contains(player.getUUID()))return "本轮已从这个起始武器点取过装备";
        boolean merge=!previous.isEmpty()&&ItemStack.isSameItemSameComponents(previous,node.item)&&CampaignInventory.slotLimit(CampaignInventory.category(node.item),node.item)>1;
        if(!previous.isEmpty()&&ItemStack.isSameItemSameComponents(previous,node.item)&&!merge)return "已持有同款物品";
        if(merge&&previous.getCount()>=previous.getMaxStackSize())return "这一类装备已堆叠至上限";
        if(!merge&&!previous.isEmpty()&&(nodes.size()>=1024||dropPosition(player)==null))return "附近没有可放置交换装备的空位";
        int capacity=CampaignInventory.slotLimit(CampaignInventory.category(node.item),node.item)-(merge?previous.getCount():0);
        int quantity=node.kind.equals("swapped")?Math.min(capacity,node.stock.remaining()):1;
        // All operations run on the server thread; quantity is decremented only after all preconditions pass.
        if(!node.stock.take(quantity))return "补给已被队友取走";
        if(slot<=1)com.tacz.guns.api.entity.IGunOperator.fromLivingEntity(player).cancelReload();
        if(merge)previous.grow(quantity);else player.getInventory().setItem(slot,node.item.copyWithCount(quantity));
        if(node.id.startsWith("campaign_start_")){node.startClaims.add(player.getUUID());if(slot<=1&&!CampaignInventory.gunId(node.item).isEmpty())CampaignInventory.refill(player,false);}
        if(!merge&&!previous.isEmpty())drop(player,previous);
        player.inventoryMenu.broadcastChanges();
        if(!node.stock.available())removeVisuals(node);
        else if(node.hitbox!=null)node.hitbox.setCustomName(label(node));
        player.sendSystemMessage(Component.literal("[Outbreak] 已拾取 ").append(CampaignInventory.displayName(node.item)));
        return "";
    }
    private BlockPos dropPosition(ServerPlayer player){
        List<BlockPos> candidates=new ArrayList<>();BlockPos origin=player.blockPosition();
        for(int dy=-1;dy<=1;dy++)for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)candidates.add(origin.offset(dx,dy,dz));
        candidates.sort(Comparator.comparingDouble(at->at.getBottomCenter().distanceToSqr(player.position())));
        for(BlockPos at:candidates){
            if(at.getBottomCenter().distanceToSqr(player.position())>9)continue;
            if(player.level().getBlockState(at.below()).getCollisionShape(player.level(),at.below()).isEmpty())continue;
            var box=new AABB(at.getX()+.15,at.getY()+.25,at.getZ()+.15,at.getX()+.85,at.getY()+.95,at.getZ()+.85);
            if(player.level().getBlockCollisions(player,box).iterator().hasNext())continue;
            if(nodes.values().stream().anyMatch(n->n.section==session.section&&n.stock.available()&&n.pos.equals(at)))continue;
            return at;
        }
        return null;
    }
    public boolean drop(ServerPlayer player,ItemStack item){
        if(item.isEmpty()||nodes.size()>=1024)return false;BlockPos position=dropPosition(player);if(position==null)return false;
        String id="swap_"+(++generated);
        Node node=new Node(id,"swapped",session.section,position,item.getCount(),false,item.copyWithCount(1));
        nodes.put(id,node);return true;
    }
    public void deployUpgrade(ServerPlayer player){
        String id="upgrade_"+(++generated);
        Node node=new Node(id,"upgrade_station",session.section,player.blockPosition(),4,false,new ItemStack(CampaignItems.EXPLOSIVE_AMMO.get()));nodes.put(id,node);
    }
    public JsonArray inspect(){
        JsonArray result=new JsonArray();for(Node node:nodes.values()){
            JsonObject row=new JsonObject();row.addProperty("id",node.id);row.addProperty("kind",node.kind);row.addProperty("section",node.section);
            row.addProperty("remaining",node.stock.remaining());row.addProperty("infinite",node.stock.infinite());row.addProperty("active",node.stock.available());
            JsonArray pos=new JsonArray();pos.add(node.pos.getX());pos.add(node.pos.getY());pos.add(node.pos.getZ());row.add("pos",pos);result.add(row);
        }return result;
    }
    public void cleanup(){for(Node node:nodes.values())removeVisuals(node);nodes.clear();decided.clear();}
    private void removeVisuals(Node node){if(node.display!=null)node.display.discard();if(node.hitbox!=null)node.hitbox.discard();node.display=null;node.hitbox=null;}
}
