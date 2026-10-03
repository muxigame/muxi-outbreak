package net.muxigame.outbreak.qa;
import com.google.gson.*;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.*;
import net.muxigame.outbreak.*;
import net.muxigame.outbreak.equipment.*;
import net.muxigame.minigames.equipment.*;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;
@Mod("outbreak_repro_qa")
public final class EquipmentServerQA {
    static EquipmentServerQA current;int exactReceipts;JsonArray receipts=new JsonArray();
    MinecraftServer server;ServerPlayer a,b;OutbreakGame game;OutbreakSession room;int stage,deadline;boolean done,finale;String pendingRole;int pendingId;int waitUntil;Map<String,Integer> commands=new HashMap<>();JsonArray evidence=new JsonArray();Map<UUID,net.minecraft.nbt.CompoundTag> expected=new HashMap<>();int restored;
    final Path coordinator=Path.of(System.getProperty("qa.outbreak.coordinator"));
    public EquipmentServerQA(){current=this;NeoForge.EVENT_BUS.addListener(this::tick);}
    public static void receipt(ServerPlayer p){var self=current;if(self==null)return;var t=self.expected.get(p.getUUID());if(t==null)return;var row=new JsonObject();row.addProperty("name",p.getGameProfile().getName());row.addProperty("inventoryComponents",p.getInventory().save(new net.minecraft.nbt.ListTag()).equals(t.getList("inventory",10)));row.addProperty("selected",p.getInventory().selected==t.getInt("selected"));row.addProperty("position",Math.abs(p.getX()-t.getDouble("x"))<1e-9&&Math.abs(p.getY()-t.getDouble("y"))<1e-9&&Math.abs(p.getZ()-t.getDouble("z"))<1e-9);row.addProperty("modeHealth",p.gameMode.getGameModeForPlayer().getId()==t.getInt("mode")&&p.getHealth()==t.getFloat("health"));row.addProperty("dimension",p.level().dimension().location().toString().equals(t.getString("dimension")));boolean all=true;for(var entry:row.entrySet())if(!entry.getKey().equals("name"))all&=entry.getValue().getAsBoolean();row.addProperty("passed",all);if(all)self.exactReceipts++;self.receipts.add(row);try{self.write(Path.of("equipment-restore-receipts.json"),self.receipts.toString());}catch(Exception e){throw new IllegalStateException(e);}}
    void write(Path p,String value)throws Exception{Path tmp=p.resolveSibling(p.getFileName()+".tmp");Files.writeString(tmp,value);Files.move(tmp,p,StandardCopyOption.REPLACE_EXISTING);}
    void check(String label,boolean ok)throws Exception{System.out.println("EQUIPMENT_ASSERT "+label+"="+ok);if(!ok)throw new AssertionError(label);var d=new JsonObject();d.addProperty("check",label);d.addProperty("passed",true);d.addProperty("tick",server.getTickCount());evidence.add(d);write(Path.of("equipment-evidence.json"),new GsonBuilder().setPrettyPrinting().create().toJson(evidence));}
    OutbreakSession session()throws Exception{var f=OutbreakGame.class.getDeclaredField("sessions");f.setAccessible(true);return ((List<OutbreakSession>)f.get(game)).stream().filter(s->s.players.contains(a.getUUID())).findFirst().orElse(null);}
    void next(int s,int wait){stage=s;deadline=server.getTickCount()+wait;}
    void command(String role,String type,JsonObject values)throws Exception{if(pendingRole!=null)throw new IllegalStateException("Pending command");int id=commands.merge(role,1,Integer::sum);values.addProperty("id",id);values.addProperty("type",type);write(coordinator.resolve("command-"+role+".json"),values.toString());pendingRole=role;pendingId=id;waitUntil=server.getTickCount()+600;}
    void action(String role,String action,String value)throws Exception{JsonObject d=new JsonObject();d.addProperty("action",action);d.addProperty("value",value);command(role,"action",d);}
    void select(String role,int slot)throws Exception{JsonObject d=new JsonObject();d.addProperty("slot",slot);command(role,"select",d);}
    void drop(String role,boolean all)throws Exception{JsonObject d=new JsonObject();d.addProperty("all",all);command(role,"drop",d);}
    void use(String role,int duration)throws Exception{JsonObject d=new JsonObject();d.addProperty("duration",duration);command(role,"use",d);}
    void aim(String role,Vec3 position)throws Exception{JsonObject d=new JsonObject();d.addProperty("x",position.x);d.addProperty("y",position.y);d.addProperty("z",position.z);command(role,"aim",d);}
    void door(String role,BlockPos p)throws Exception{JsonObject d=new JsonObject();d.addProperty("x",p.getX());d.addProperty("y",p.getY());d.addProperty("z",p.getZ());command(role,"door",d);}
    void move(ServerPlayer p,Vec3 pos){var l=server.getLevel(room.map.dimension());l.setChunkForced(((int)pos.x)>>4,((int)pos.z)>>4,true);p.teleportTo(l,pos.x,pos.y,pos.z,0,0);p.fallDistance=0;}
    void both(Vec3 pos){move(a,pos);move(b,pos.add(1,0,0));}
    void clear(){for(UUID id:Set.copyOf(room.infected)){Entity e=server.getLevel(room.map.dimension()).getEntity(id);if(e!=null)e.hurt(a.damageSources().playerAttack(a),1000);}}
    void inventoryBaseline(ServerPlayer p){p.setGameMode(GameType.SURVIVAL);p.getInventory().clearContent();p.getInventory().setItem(0,new ItemStack(Items.DIAMOND,3));p.getInventory().setItem(17,new ItemStack(Items.EMERALD,7));p.getInventory().setItem(36,new ItemStack(Items.IRON_BOOTS));p.getInventory().setItem(40,new ItemStack(Items.SHIELD));net.minecraft.core.component.DataComponents.CUSTOM_NAME.toString();p.getInventory().getItem(17).set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Outbreak QA external marker"));p.setHealth(19);p.getFoodData().setFoodLevel(18);p.inventoryMenu.broadcastChanges();}
    void capture(){for(var p:List.of(a,b))expected.put(p.getUUID(),p.getPersistentData().getCompound("muxi_outbreak_return_v1").copy());}
    void restoreCheck(String label)throws Exception{for(var p:List.of(a,b)){var t=expected.get(p.getUUID());check(label+"_"+p.getGameProfile().getName()+"_inventory_components",p.getInventory().save(new net.minecraft.nbt.ListTag()).equals(t.getList("inventory",10)));check(label+"_"+p.getGameProfile().getName()+"_mode_health_dimension",p.gameMode.getGameModeForPlayer()==GameType.SURVIVAL&&p.getHealth()>=t.getFloat("health")&&p.level()==server.overworld()&&!p.getPersistentData().contains("muxi_outbreak_return_v1"));}restored++;}
    ItemStack expectedSwapGun;
    int stack(ServerPlayer p,int slot){return p.getInventory().getItem(slot).getCount();}
    Map<String,CampaignSupplies.Node> nodes()throws Exception{var f=CampaignSupplies.class.getDeclaredField("nodes");f.setAccessible(true);return (Map<String,CampaignSupplies.Node>)f.get(room.supplies);}
    CampaignSupplies.Node dropped(String kind)throws Exception{return nodes().values().stream().filter(n->n.kind.equals("swapped")&&SharedItems.kind(n.item).equals(kind)&&n.stock.available()).findFirst().orElseThrow();}
    void pointAt(String role,CampaignSupplies.Node n)throws Exception{var p=role.equals("host")?a:b;var level=server.getLevel(room.map.dimension());Vec3 at=null;for(int[] delta:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){var target=n.pos.offset(delta[0],0,delta[1]);if(level.getBlockState(target.below()).getCollisionShape(level,target.below()).isEmpty()||!level.getBlockState(target).getCollisionShape(level,target).isEmpty()||!level.getBlockState(target.above()).getCollisionShape(level,target.above()).isEmpty())continue;Vec3 candidate=target.getBottomCenter(),start=candidate.add(0,p.getEyeHeight(),0),end=start.add(n.pos.getCenter().subtract(start).normalize().scale(3.5));CampaignSupplies.Node first=null;double distance=Double.MAX_VALUE;for(var viewed:nodes().values()){if(viewed.hitbox==null||viewed.hitbox.isRemoved()||!viewed.stock.available()||viewed.section!=room.section)continue;var hit=viewed.hitbox.getBoundingBox().inflate(.15).clip(start,end);if(hit.isPresent()&&start.distanceToSqr(hit.get())<distance){distance=start.distanceToSqr(hit.get());first=viewed;}}if(first!=n)continue;at=candidate;break;}if(at==null)throw new IllegalStateException("No supported unobstructed QA pickup stance for "+n.id);move(p,at);System.out.println("QA_PICKUP_STANCE "+n.id+" stock="+n.stock.remaining()+" node="+n.pos+" stance="+at);aim(role,n.pos.getCenter());}
    void tick(ServerTickEvent.Post event){if(done||!Boolean.getBoolean("muxi.outbreak.recovery.qa"))return;server=event.getServer();
        try{
            if(server.getTickCount()%100==0)write(Path.of("equipment-progress.json"),"{\"stage\":"+stage+",\"tick\":"+server.getTickCount()+"}");
            for(String role:List.of("host","guest")){Path f=coordinator.resolve("fatal-"+role+".json");if(Files.exists(f))throw new AssertionError(Files.readString(f));}
            if(pendingRole!=null){Path p=coordinator.resolve("result-"+pendingRole+"-"+pendingId+".json");if(!Files.exists(p)){if(server.getTickCount()>waitUntil)throw new AssertionError("Client command timeout "+pendingRole+pendingId);return;}JsonObject result;try{result=JsonParser.parseString(Files.readString(p)).getAsJsonObject();}catch(java.io.IOException busy){return;}if(!result.get("ok").getAsBoolean())throw new AssertionError(result);pendingRole=null;}
            if(finale&&room!=null&&room.phase==OutbreakSession.Phase.RUNNING&&server.getTickCount()%10==0)clear();
            if(server.getTickCount()<deadline)return;
            switch(stage){
                case 0->{a=server.getPlayerList().getPlayerByName("OutbreakHostQA");b=server.getPlayerList().getPlayerByName("OutbreakGuestQA");if(a==null||b==null)return;check("two_actual_network_connections",a.connection.getConnection().getRemoteAddress() instanceof java.net.InetSocketAddress&&b.connection.getConnection().getRemoteAddress() instanceof java.net.InetSocketAddress);game=OutbreakGame.active(a);inventoryBaseline(a);inventoryBaseline(b);server.getPlayerList().op(a.getGameProfile());action("host","createConfigured","{\"map\":\"lostschool\",\"mode\":\"CAMPAIGN\",\"difficulty\":1}");next(1,20);}
                case 1->{room=session();check("create_waiting_without_teleport",room!=null&&room.phase==OutbreakSession.Phase.WAITING&&a.level()==server.overworld()&&a.getInventory().countItem(Items.DIAMOND)==3);action("guest","join",room.shortId());next(2,20);}
                case 2->{check("network_guest_waiting_inventory_intact",room.players.size()==2&&b.level()==server.overworld()&&b.getInventory().countItem(Items.DIAMOND)==3);if(!game.snapshot(a).getAsJsonArray("rooms").get(0).getAsJsonObject().get("mapReady").getAsBoolean()){deadline=server.getTickCount()+20;return;}action("host","start","");next(3,235);}
                case 3->{check("explicit_host_start_prepared_two_real_players",room.phase==OutbreakSession.Phase.START_ROOM&&room.prepared.size()==2&&a.level().dimension().equals(room.map.dimension())&&stack(a,0)==0&&stack(a,1)==0);capture();for(String id:new String[]{"fireaxe","frying_pan","electric_guitar","crowbar","cricket_bat","baseball_bat","katana","machete","tonfa","knife","golfclub","shovel","pitchfork"}){var old=SharedMelee.legacy(id);check("legacy_melee_"+id,net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(old.getItem()).toString().equals("muxi_outbreak:melee_"+id)&&ItemStack.isSameItemSameComponents(old,ItemStack.parse(a.registryAccess(),old.save(a.registryAccess())).orElseThrow()));}a.setInvulnerable(true);b.setInvulnerable(true);check("shared_lr_datapack_bridge_preserves_bat",me.xjqsh.lrtactical.api.item.IMeleeWeapon.of(CampaignInventory.create(a,"melee")).getId(CampaignInventory.create(a,"melee")).toString().equals("lrtactical:baseball_bat"));
                    a.getInventory().setItem(3,SharedItems.create("medkit",2));a.getInventory().setItem(2,SharedItems.create("grenade",3));a.inventoryMenu.broadcastChanges();select("host",3);next(4,15);}
                case 4->{drop("host",true);next(5,30);}
                case 5->{check("ctrl_q_medkit_preserves_entire_finite_stack",stack(a,3)==0&&dropped("medkit").stock.remaining()==2);pointAt("guest",dropped("medkit"));next(6,25);}
                case 6->{action("guest","interact","");next(7,25);}
                case 7->{check("f_network_pickup_transfers_entire_stack_to_teammate",stack(a,3)==0&&stack(b,3)==2&&nodes().values().stream().filter(n->SharedItems.kind(n.item).equals("medkit")&&n.kind.equals("swapped")).mapToInt(n->n.stock.remaining()).sum()==0);action("host","interact","");next(8,15);}
                case 8->{check("repeat_f_cannot_duplicate_depleted_pickup",stack(b,3)==2&&stack(a,3)==0);select("guest",3);next(9,15);}
                case 9->{drop("guest",false);next(10,25);}
                case 10->{check("q_single_drop_preserves_remaining_stack",stack(b,3)==1&&dropped("medkit").stock.remaining()==1);pointAt("host",dropped("medkit"));next(11,20);}
                case 11->{action("host","interact","");next(12,25);}
                case 12->{check("single_medkit_shared_without_duplication",stack(a,3)==1&&stack(b,3)==1);a.setHealth(10);b.setHealth(20);aim("host",a.getEyePosition().add(0,0,3));next(13,15);}
                case 13->{select("host",3);next(14,15);}
                case 14->{use("host",105);next(15,120);}
                case 15->{check("actual_medkit_use_consumes_one_and_heals_80_percent_missing",stack(a,3)==0&&Math.abs(a.getHealth()-18)<.01&&stack(b,3)==1);select("guest",3);next(16,15);}
                case 16->{use("guest",105);next(17,120);}
                case 17->{check("full_health_attempt_preserves_stack",stack(b,3)==1);b.getInventory().getItem(3).grow(1);b.inventoryMenu.broadcastChanges();b.setHealth(10);aim("guest",b.getEyePosition().add(0,0,3));next(18,15);}
                case 18->{use("guest",105);next(19,120);}
                case 19->{check("stacked_medkit_consumes_exactly_one",stack(b,3)==1&&Math.abs(b.getHealth()-18)<.01);use("guest",105);next(20,120);}
                case 20->{check("second_stacked_medkit_consumes_last_one",stack(b,3)==0&&Math.abs(b.getHealth()-19.6)<.01);select("host",2);next(21,15);}
                case 21->{drop("host",true);next(22,25);}
                case 22->{check("ctrl_q_grenades_preserve_stack_and_components",stack(a,2)==0&&dropped("grenade").stock.remaining()==3);pointAt("guest",dropped("grenade"));next(23,20);}
                case 23->{action("guest","interact","");next(24,25);}
                case 24->{check("teammate_grenade_stack_transfer",stack(b,2)==3);select("guest",2);next(25,15);}
                case 25->{aim("guest",b.getEyePosition().add(0,0,4));next(26,15);}
                case 26->{use("guest",1);next(27,25);}
                case 27->{check("actual_grenade_use_consumes_exactly_one",stack(b,2)==2);use("guest",1);next(28,25);}
                case 28->{check("second_grenade_use_preserves_remaining_one",stack(b,2)==1);var source=nodes().get("campaign_start_primary");String old=CampaignInventory.gunId(source.item).equals("tacz:hk_mp5a5")?"tacz:m870":"tacz:hk_mp5a5";expectedSwapGun=CampaignInventory.gun(a,old);var gun=com.tacz.guns.api.item.IGun.getIGunOrNull(expectedSwapGun);gun.setCurrentAmmoCount(expectedSwapGun,3);gun.setBulletInBarrel(expectedSwapGun,false);CampaignInventory.explosiveRounds(expectedSwapGun,7);a.getInventory().setItem(0,expectedSwapGun.copy());a.inventoryMenu.broadcastChanges();pointAt("host",source);next(280,20);}
                case 280->{action("host","interact","");next(281,25);}
                case 281->{var source=nodes().get("campaign_start_primary");check("f_swaps_occupied_primary_without_losing_old_components",CampaignInventory.gunId(a.getInventory().getItem(0)).equals(CampaignInventory.gunId(source.item))&&source.stock.remaining()==1);var old=nodes().values().stream().filter(n->n.kind.equals("swapped")&&n.stock.available()&&ItemStack.isSameItemSameComponents(n.item,expectedSwapGun)).findFirst().orElseThrow();pointAt("guest",old);next(282,20);}
                case 282->{action("guest","interact","");next(283,25);}
                case 283->{check("teammate_receives_swapped_gun_exact_magazine_upgrade_components",ItemStack.isSameItemSameComponents(b.getInventory().getItem(0),expectedSwapGun)&&stack(b,0)==1);action("host","interact","");next(284,25);}
                case 284->{check("repeated_start_cache_pickup_does_not_duplicate_or_refill",nodes().get("campaign_start_primary").stock.remaining()==1);var p=room.map.startRoom(0).doors().getFirst();move(a,new Vec3(-6.5,83,1.5));door("host",p);next(29,20);}
                case 29->{both(new Vec3(-6.5,83,-3.5));next(30,100);}
                case 30->{check("director_real_entities_and_running",room.phase==OutbreakSession.Phase.RUNNING&&!room.infected.isEmpty()&&room.infected.stream().anyMatch(id->{var e=server.getLevel(room.map.dimension()).getEntity(id);return e!=null&&e.isAlive()&&e.tickCount>0;}));clear();both(new Vec3(62.5,53,-13.5));next(31,20);}
                case 31->{door("host",room.map.safeRooms().get(0).doors().getFirst());next(32,35);}
                case 32->{check("first_closed_checkpoint_advances",room.section==1&&room.phase==OutbreakSession.Phase.SAFE_ROOM);next(33,170);}
                case 33->{check("second_chapter_protected_start",room.phase==OutbreakSession.Phase.START_ROOM);both(new Vec3(2006.5,77,-13.5));door("host",room.map.startRoom(1).doors().getFirst());next(34,25);}
                case 34->{both(new Vec3(2002.5,77,-11.5));next(35,100);}
                case 35->{check("second_chapter_director_progress",room.phase==OutbreakSession.Phase.RUNNING&&!room.infected.isEmpty());clear();both(new Vec3(2006.5,57,-13.5));next(350,30);}
                case 350->{door("host",room.map.safeRooms().get(1).doors().getFirst());next(36,200);}
                case 36->{check("third_chapter_protected_start",room.section==2&&room.phase==OutbreakSession.Phase.START_ROOM);both(new Vec3(4136.5,81,14.5));door("host",room.map.startRoom(2).doors().getFirst());next(37,25);}
                case 37->{both(new Vec3(4136.5,81,21.5));next(38,100);}
                case 38->{check("third_chapter_director_progress",!room.infected.isEmpty()&&room.phase==OutbreakSession.Phase.RUNNING);clear();both(room.map.finish().getBottomCenter());next(39,60);}
                case 39->{check("natural_finale_has_wave",room.finaleStarted>=0&&room.finaleWaves>0&&!room.infected.isEmpty());finale=true;next(40,1500);}
                case 40->{check("natural_full_campaign_win",room.phase==OutbreakSession.Phase.FINISHED&&room.finaleTankSpawned&&room.finaleTankDefeated&&session()==null);finale=false;restoreCheck("win");action("host","createConfigured","{\"map\":\"lostschool\",\"mode\":\"CAMPAIGN\",\"difficulty\":1}");next(41,25);}
                case 41->{room=session();action("guest","join",room.shortId());next(42,25);}
                case 42->{action("host","start","");next(43,235);}
                case 43->{check("second_real_round_started",room.phase==OutbreakSession.Phase.START_ROOM&&room.prepared.size()==2);capture();a.setInvulnerable(true);b.setInvulnerable(true);both(new Vec3(-6.5,83,1.5));door("host",room.map.startRoom(0).doors().getFirst());next(44,25);}
                case 44->{both(new Vec3(-6.5,83,-3.5));next(45,70);}
                case 45->{check("failure_test_left_start_protection",room.phase==OutbreakSession.Phase.RUNNING);a.setInvulnerable(false);b.setInvulnerable(false);a.setHealth(1);b.setHealth(1);a.invulnerableTime=0;b.invulnerableTime=0;a.hurt(a.damageSources().generic(),1000);b.hurt(b.damageSources().generic(),1000);next(46,35);}
                case 46->{check("actual_all_down_failure_finished",room.phase==OutbreakSession.Phase.FINISHED&&session()==null);restoreCheck("failure");check("win_and_failure_exact_restore_receipts",restored==2&&exactReceipts==4);write(Path.of("equipment-result.json"),"{\"passed\":true,\"actualNetworkClients\":2,\"fakePlayers\":false,\"assistedRouteAndCombat\":true,\"naturalFinaleClock\":true,\"restores\":2,\"normalStopRequested\":true}");for(String role:List.of("host","guest")){JsonObject d=new JsonObject();d.addProperty("id",commands.merge(role,1,Integer::sum));d.addProperty("type","stop");write(coordinator.resolve("command-"+role+".json"),d.toString());}next(47,100);}
                case 47->{done=true;server.halt(false);}
            }
        }catch(Throwable failure){failure.printStackTrace();try{write(Path.of("failure.txt"),failure.toString());for(String role:List.of("host","guest")){JsonObject d=new JsonObject();d.addProperty("id",commands.merge(role,1,Integer::sum));d.addProperty("type","stop");write(coordinator.resolve("command-"+role+".json"),d.toString());}}catch(Exception ignored){}done=true;server.halt(false);}
    }
}
