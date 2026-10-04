package net.muxigame.outbreak.interactionqa;
import com.google.gson.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.*;
import net.muxigame.outbreak.*;
import net.muxigame.outbreak.equipment.*;
import net.muxigame.minigames.equipment.SharedItems;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/** Assisted private fixtures around real players; gameplay requests still come from the native client. */
@Mod(value="outbreak_interaction_qa",dist=Dist.DEDICATED_SERVER)
public final class InteractionServerQA {
    public static int packets;int last=-1;MinecraftServer server;ServerPlayer host;OutbreakSession room;BlockPos target;String nodeId="";
    public InteractionServerQA(){NeoForge.EVENT_BUS.addListener(this::tick);}
    OutbreakSession session()throws Exception{var f=OutbreakGame.class.getDeclaredField("sessions");f.setAccessible(true);return ((List<OutbreakSession>)f.get(OutbreakGame.active(host))).stream().filter(s->s.contains(host.getUUID())).findFirst().orElse(null);}
    Map<String,CampaignSupplies.Node> nodes()throws Exception{var f=CampaignSupplies.class.getDeclaredField("nodes");f.setAccessible(true);return (Map<String,CampaignSupplies.Node>)f.get(room.supplies);}
    void move(Vec3 pos){var level=server.getLevel(room.map.dimension());level.setChunkForced(((int)pos.x)>>4,((int)pos.z)>>4,true);host.teleportTo(level,pos.x,pos.y,pos.z,0,0);host.fallDistance=0;}
    void platform(BlockPos center){var l=server.getLevel(room.map.dimension());l.setChunkForced(center.getX()>>4,center.getZ()>>4,true);for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++){l.setBlock(center.offset(x,-1,z),Blocks.STONE.defaultBlockState(),3);for(int y=0;y<3;y++)l.setBlock(center.offset(x,y,z),Blocks.AIR.defaultBlockState(),3);}}
    Vec3 aim(){var l=server.getLevel(room.map.dimension());if(!nodeId.isBlank()){var n=nodesUnchecked().get(nodeId);return n.hitbox==null?n.pos.getCenter():n.hitbox.getBoundingBox().getCenter();}var shape=l.getBlockState(target).getShape(l,target);return shape.isEmpty()?target.getCenter():shape.bounds().getCenter().add(Vec3.atLowerCornerOf(target));}
    Map<String,CampaignSupplies.Node> nodesUnchecked(){try{return nodes();}catch(Exception e){throw new IllegalStateException(e);}}
    void fixture(String type)throws Exception{
        host.setInvulnerable(true);room.directorEnabled=false;room.downed.remove(host.getUUID());room.alive.add(host.getUUID());nodeId="";
        nodes().entrySet().removeIf(e->{if(!e.getKey().startsWith("interaction_qa_"))return false;var n=e.getValue();if(n.display!=null)n.display.discard();if(n.hitbox!=null)n.hitbox.discard();return true;});
        var l=server.getLevel(room.map.dimension());target=new BlockPos(500,100,500);
        if(type.startsWith("checkpoint-")){
            var group=type.equals("checkpoint-start")?room.map.startRoom(0):room.map.safeRooms().get(type.equals("checkpoint-future")?1:0);
            target=group.doors().getFirst();l.setChunkForced(target.getX()>>4,target.getZ()>>4,true);
            var stand=target.offset(0,0,-2);for(int z=-3;z<0;z++){l.setBlock(target.offset(0,-1,z),Blocks.STONE.defaultBlockState(),3);for(int y=0;y<3;y++)l.setBlock(target.offset(0,y,z),Blocks.AIR.defaultBlockState(),3);}move(stand.getBottomCenter());
        }else{
            platform(target);move(target.getBottomCenter().add(0,0,-2));
            if(type.startsWith("supply-")){
                String kind=type.substring(7);var item=kind.equals("upgrade_station")?CampaignInventory.create(host,"explosive_pack"):kind.equals("grenade")?SharedItems.create("grenade",1):CampaignInventory.create(host,kind);
                int slot=CampaignInventory.slot(CampaignInventory.category(item));
                if(kind.equals("ammo")||kind.equals("upgrade_station")){
                    host.getInventory().clearContent();host.getInventory().setItem(0,CampaignInventory.create(host,"gun:tacz:hk_mp5a5"));
                    var gun=com.tacz.guns.api.item.IGun.getIGunOrNull(host.getInventory().getItem(0));gun.setCurrentAmmoCount(host.getInventory().getItem(0),0);
                }else if(kind.startsWith("gun:")){host.getInventory().setItem(0,CampaignInventory.create(host,"gun:tacz:m870"));}
                else host.getInventory().setItem(slot,ItemStack.EMPTY);
                nodeId="interaction_qa_"+UUID.randomUUID();var ctor=CampaignSupplies.Node.class.getDeclaredConstructor(String.class,String.class,int.class,BlockPos.class,int.class,boolean.class,ItemStack.class);ctor.setAccessible(true);
                var n=ctor.newInstance(nodeId,kind,room.section,target,2,false,item);nodes().put(nodeId,n);
                var show=CampaignSupplies.class.getDeclaredMethod("show",ServerLevel.class,CampaignSupplies.Node.class);show.setAccessible(true);show.invoke(room.supplies,l,n);
            }else{
                Block block=switch(type){case "wood"->Blocks.OAK_DOOR;case "iron"->Blocks.IRON_DOOR;case "trapdoor"->Blocks.OAK_TRAPDOOR;case "gate"->Blocks.OAK_FENCE_GATE;case "button"->Blocks.OAK_BUTTON;case "lever"->Blocks.LEVER;case "chest"->Blocks.CHEST;case "bed"->Blocks.RED_BED;default->throw new IllegalArgumentException(type);};
                var state=block.defaultBlockState();if(block instanceof DoorBlock){state=state.setValue(DoorBlock.FACING,Direction.SOUTH);l.setBlock(target,state.setValue(DoorBlock.HALF,DoubleBlockHalf.LOWER),3);l.setBlock(target.above(),state.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER),3);}
                else if(block instanceof ButtonBlock||block instanceof LeverBlock)l.setBlock(target,state.setValue(BlockStateProperties.ATTACH_FACE,AttachFace.FLOOR),3);
                else if(block instanceof BedBlock){l.setBlock(target,state.setValue(BedBlock.PART,BedPart.FOOT),3);l.setBlock(target.relative(state.getValue(BedBlock.FACING)),state.setValue(BedBlock.PART,BedPart.HEAD),3);}
                else l.setBlock(target,state,3);
            }
        }
        host.getInventory().selected=0;host.inventoryMenu.broadcastChanges();
    }
    JsonObject inspect()throws Exception{
        var result=new JsonObject();result.addProperty("packets",packets);result.addProperty("phase",room==null?"ABSENT":room.phase.name());
        if(room==null)return result;
        result.addProperty("prepared",room.prepared.size());result.addProperty("directorEnabled",room.directorEnabled);
        result.addProperty("hostDimension",host.level().dimension().location().toString());
        if(target!=null){var l=server.getLevel(room.map.dimension());var state=l.getBlockState(target);result.addProperty("block",BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());result.addProperty("state",state.toString());if(state.hasProperty(BlockStateProperties.OPEN))result.addProperty("open",state.getValue(BlockStateProperties.OPEN));if(state.hasProperty(BlockStateProperties.POWERED))result.addProperty("powered",state.getValue(BlockStateProperties.POWERED));
            var hit=aim();result.addProperty("aimX",hit.x);result.addProperty("aimY",hit.y);result.addProperty("aimZ",hit.z);
            var front=l.getBlockState(target.offset(0,0,-1));if(front.getBlock() instanceof DoorBlock)result.addProperty("occludingDoorOpen",front.getValue(DoorBlock.OPEN));
            var pick=host.level().clip(new ClipContext(host.getEyePosition(),host.getEyePosition().add(host.getLookAngle().scale(3.5)),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,host));result.addProperty("serverRay",pick.getType().name());result.addProperty("serverRayPos",pick.getBlockPos().toShortString());
        }
        var inventory=new JsonObject();for(int i=0;i<host.getInventory().getContainerSize();i++){var stack=host.getInventory().getItem(i);if(stack.isEmpty())continue;var row=new JsonObject();row.addProperty("count",stack.getCount());row.addProperty("item",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());row.addProperty("gun",CampaignInventory.gunId(stack));row.addProperty("kind",SharedItems.kind(stack));row.addProperty("components",stack.save(host.registryAccess()).toString());inventory.add(Integer.toString(i),row);}result.add("inventory",inventory);
        int remaining=0;for(var n:nodes().values())if(n.id.equals(nodeId))remaining=n.stock.remaining();result.addProperty("nodeRemaining",remaining);
        result.addProperty("supplyNodes",nodes().size());result.addProperty("menu",host.containerMenu.getClass().getSimpleName());
        var gun=com.tacz.guns.api.item.IGun.getIGunOrNull(host.getInventory().getItem(0));if(gun!=null){result.addProperty("gunMagazine",gun.getCurrentAmmoCount(host.getInventory().getItem(0)));result.addProperty("looseAmmo",CampaignInventory.ammoCount(host,CampaignInventory.ammoId(host.getInventory().getItem(0))));}
        result.addProperty("explosiveRounds",room.explosiveRounds.getOrDefault(host.getUUID(),0));return result;
    }
    void tick(ServerTickEvent.Post e){server=e.getServer();host=server.getPlayerList().getPlayerByName("DebugHost");if(host==null)return;
        var command=InteractionFiles.read("interaction-command-server.json");if(command==null||command.get("id").getAsInt()<=last)return;last=command.get("id").getAsInt();var result=new JsonObject();result.addProperty("id",last);
        try{
            room=session();String type=command.get("type").getAsString();
            switch(type){
                case "observe"->{}
                case "fixture"->{if(room==null)throw new IllegalStateException("Room absent");fixture(command.get("kind").getAsString());}
                case "conditions"->{String condition=command.get("condition").getAsString();if(condition.equals("downed"))room.downed.add(host.getUUID());else if(condition.equals("normal"))room.downed.remove(host.getUUID());else if(condition.equals("far"))move(host.position().add(0,0,-5));else if(condition.equals("wall")){var wall=target.offset(0,0,-1);host.serverLevel().setBlock(wall,Blocks.STONE.defaultBlockState(),3);host.serverLevel().setBlock(wall.above(),Blocks.STONE.defaultBlockState(),3);}else if(condition.equals("door")){var at=target.offset(0,0,-1);var state=Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.SOUTH);host.serverLevel().setBlock(at,state.setValue(DoorBlock.HALF,DoubleBlockHalf.LOWER),3);host.serverLevel().setBlock(at.above(),state.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER),3);}else if(condition.equals("wrong-dimension"))host.teleportTo(server.overworld(),0,100,0,0,0);else throw new IllegalArgumentException(condition);}
                case "duplicate-direct"->{boolean a=OutbreakGame.active(host).interact(host),b=OutbreakGame.active(host).interact(host);result.addProperty("handledFirst",a);result.addProperty("handledSecond",b);}
                default->throw new IllegalArgumentException(type);
            }
            result.addProperty("ok",true);result.add("status",inspect());
        }catch(Throwable failure){result.addProperty("ok",false);result.addProperty("error",failure.toString());failure.printStackTrace();}
        try{InteractionFiles.write("interaction-result-server-"+last+".json",result);}catch(Exception busy){throw new IllegalStateException(busy);}
    }
}
