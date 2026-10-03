package net.muxigame.outbreak.qa;
import com.google.gson.*;
import net.minecraft.client.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.*;
import net.minecraft.server.level.*;
import net.muxigame.outbreak.*;
import net.muxigame.outbreak.equipment.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.muxigame.minigames.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
@Mod(value="outbreak_native_qa",dist=Dist.CLIENT)
public final class NativePlayfeelQA {
  static NativePlayfeelQA instance;static final Map<UUID,net.minecraft.nbt.CompoundTag> expectedReturns=new java.util.concurrent.ConcurrentHashMap<>();volatile int restoresVerified;JsonArray restoreReceipts=new JsonArray();volatile String originalInventory;volatile Vec3 originalPosition;volatile int bulletCount;int magazineBefore;volatile int targetId;String pickupId="";int initialStock;boolean baseline=Files.exists(Path.of("baseline-input.json"));boolean focused=Files.exists(Path.of("focused-input.json"));int ticks,stage,deadline,frame;boolean starting,done,pending;volatile JsonObject observed;volatile Throwable failed;volatile boolean tankRendered,tankDefeatedSeen;int tankFocusFrames;JsonArray evidence=new JsonArray();String request="";
  public NativePlayfeelQA(){instance=this;NeoForge.EVENT_BUS.addListener(this::tick);NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e)->{if(!e.getLevel().isClientSide()&&e.getEntity() instanceof com.tacz.guns.entity.EntityKineticBullet bullet&&bullet.getOwner() instanceof ServerPlayer)bulletCount++;});}
  static OutbreakSession room(OutbreakGame game)throws Exception{var f=OutbreakGame.class.getDeclaredField("sessions");f.setAccessible(true);return ((List<OutbreakSession>)f.get(game)).stream().findFirst().orElse(null);}
  void next(int value,int wait){stage=value;deadline=ticks+wait;frame=0;}
  void send(String action,String value){request=UUID.randomUUID().toString();PacketDistributor.sendToServer(new GameNetwork.TerminalAction(request,"outbreak",action,value));}
  void observe(Minecraft mc){var server=mc.getSingleplayerServer();if(server==null||mc.player==null||pending)return;pending=true;var id=mc.player.getUUID();server.execute(()->{try{var p=server.getPlayerList().getPlayer(id);var game=OutbreakGame.active(p);var r=room(game);var d=game.snapshot(p);if(p.getPersistentData().contains("muxi_outbreak_return_v1",10))expectedReturns.put(id,p.getPersistentData().getCompound("muxi_outbreak_return_v1").copy());d.addProperty("roomPhase",r==null?"NONE":r.phase.name());d.addProperty("section",r==null?-1:r.section);d.addProperty("infected",r==null?0:r.infected.size());d.addProperty("seconds",r==null?0:r.seconds);d.addProperty("waves",r==null?0:r.finaleWaves);d.addProperty("tank",r!=null&&r.finaleTankSpawned);d.addProperty("tankDefeated",r!=null&&r.finaleTankDefeated);if(r!=null&&r.finaleTankDefeated)tankDefeatedSeen=true;if(r!=null&&r.finaleTankId!=null){var tank=p.serverLevel().getEntity(r.finaleTankId);if(tank!=null)d.addProperty("tankEntityId",tank.getId());}d.addProperty("dimension",p.level().dimension().location().toString());d.addProperty("worldDifficulty",p.level().getDifficulty().name());d.addProperty("diamonds",p.getInventory().countItem(Items.DIAMOND));d.addProperty("players",server.getPlayerList().getPlayerCount());d.addProperty("networkTransport",p.connection.getClass().getName());d.add("operation",GameRuntime.get(server).snapshot(p,"").get("operation"));if(r!=null&&!r.infected.isEmpty()){Entity m=p.serverLevel().getEntity(r.infected.iterator().next());if(m!=null){d.addProperty("entityId",m.getId());d.addProperty("entityTickCount",m.tickCount);d.addProperty("entityTicking",p.serverLevel().isPositionEntityTicking(m.blockPosition()));}}JsonArray inventory=new JsonArray();for(int slot=0;slot<5;slot++){ItemStack item=p.getInventory().getItem(slot);JsonObject a=new JsonObject();a.addProperty("slot",slot);a.addProperty("id",BuiltInRegistries.ITEM.getKey(item.getItem()).toString());a.addProperty("gun",CampaignInventory.gunId(item));a.addProperty("count",item.getCount());var gun=com.tacz.guns.api.item.IGun.getIGunOrNull(item);if(gun!=null)a.addProperty("magazine",gun.getCurrentAmmoCount(item));a.addProperty("name",item.getHoverName().getString());inventory.add(a);}d.add("inventory",inventory);d.addProperty("health",p.getHealth());d.addProperty("serverBullets",bulletCount);if(r!=null){d.add("supplies",r.supplies.inspect());d.addProperty("pace",r.director.pace().name());try{Object shared=r.throwables;try{var owner=CampaignThrowables.class.getDeclaredField("shared");owner.setAccessible(true);shared=owner.get(r.throwables);}catch(NoSuchFieldException oldBaseline){}var f=shared.getClass().getDeclaredField("active");f.setAccessible(true);var active=(Map<?,?>)f.get(shared);d.addProperty("throws",active.size());JsonObject kinds=new JsonObject();for(Object t:active.values()){var k=t.getClass().getDeclaredField("kind");k.setAccessible(true);String name=(String)k.get(t);kinds.addProperty(name,kinds.has(name)?kinds.get(name).getAsInt()+1:1);}d.add("throwsByKind",kinds);}catch(Exception e){throw new RuntimeException(e);}}observed=d;}catch(Throwable e){failed=e;}finally{pending=false;}});}
  void serverAction(Minecraft mc,java.util.function.Consumer<ServerPlayer> action){var server=mc.getSingleplayerServer();var id=mc.player.getUUID();server.execute(()->{try{action.accept(server.getPlayerList().getPlayer(id));}catch(Throwable e){failed=e;}});}
  void move(Minecraft mc,Vec3 at){serverAction(mc,p->{var r=getRoom(p);p.teleportTo(p.server.getLevel(r.map.dimension()),at.x,at.y,at.z,0,0);p.fallDistance=0;});}
  static OutbreakSession getRoom(ServerPlayer p){try{return room(OutbreakGame.active(p));}catch(Exception e){throw new RuntimeException(e);}}
  void kill(Minecraft mc){serverAction(mc,p->{var r=getRoom(p);if(r!=null)for(UUID id:Set.copyOf(r.infected)){var mob=p.serverLevel().getEntity(id);if(mob!=null&&(!id.equals(r.finaleTankId)||tankRendered))mob.hurt(p.damageSources().playerAttack(p),1000);}});}
  void click(Minecraft mc,BlockPos pos){mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(pos.getCenter(),Direction.UP,pos,false));}
  void record(String label)throws Exception{var d=observed.deepCopy();d.addProperty("label",label);d.addProperty("clientTick",ticks);evidence.add(d);Files.writeString(Path.of("native-evidence.json"),new GsonBuilder().setPrettyPrinting().create().toJson(evidence));System.out.println("NATIVE_QA "+d);}
  void screenshot(Minecraft mc,String name)throws Exception{try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(Path.of(name+".png"));}}
  void check(boolean value,String label){if(!value)throw new AssertionError(label);System.out.println("NATIVE_ASSERT "+label+"=true");}


  public static void restored(ServerPlayer p){
    var self=instance;var t=expectedReturns.remove(p.getUUID());if(self==null||t==null)return;
    JsonObject row=new JsonObject();
    row.addProperty("inventoryComponents",p.getInventory().save(new net.minecraft.nbt.ListTag()).equals(t.getList("inventory",10)));
    row.addProperty("originalMarkers",p.getInventory().countItem(Items.DIAMOND)==3&&p.getInventory().countItem(Items.EMERALD)==7&&p.getInventory().getItem(17).has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)&&p.getInventory().getItem(39).is(Items.IRON_HELMET)&&p.getInventory().getItem(40).is(Items.SHIELD));
    row.addProperty("dimension",p.level().dimension().location().toString().equals(t.getString("dimension")));
    row.addProperty("position",p.position().distanceTo(new Vec3(t.getDouble("x"),t.getDouble("y"),t.getDouble("z")))<.00001);
    row.addProperty("mode",p.gameMode.getGameModeForPlayer().getId()==t.getInt("mode"));
    row.addProperty("health",Math.abs(p.getHealth()-t.getFloat("health"))<.00001);
    var food=new net.minecraft.nbt.CompoundTag();p.getFoodData().addAdditionalSaveData(food);row.addProperty("food",food.equals(t.getCompound("food")));
    row.addProperty("selectedSlot",p.getInventory().selected==t.getInt("selected"));row.addProperty("pendingRemoved",!p.getPersistentData().contains("muxi_outbreak_return_v1"));
    row.addProperty("savedHealth",t.getFloat("health"));row.addProperty("restoredHealth",p.getHealth());row.addProperty("transactionTime",p.server.getTickCount());
    try{for(String key:List.of("inventoryComponents","originalMarkers","dimension","position","mode","health","food","selectedSlot","pendingRemoved"))self.check(row.get(key).getAsBoolean(),"restore_transaction_"+key);self.restoresVerified++;row.addProperty("verified",true);}catch(Throwable e){self.failed=e;row.addProperty("verified",false);}
    self.restoreReceipts.add(row);try{atomic(Path.of("restore-receipts.json"),new GsonBuilder().setPrettyPrinting().create().toJson(self.restoreReceipts));}catch(Exception e){self.failed=e;}
  }
  static void atomic(Path path,String value)throws Exception{Path tmp=path.resolveSibling(path.getFileName()+".tmp");Files.writeString(tmp,value);Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
  static Map<String,CampaignSupplies.Node> nodes(OutbreakSession r){try{var f=CampaignSupplies.class.getDeclaredField("nodes");f.setAccessible(true);return (Map<String,CampaignSupplies.Node>)f.get(r.supplies);}catch(Exception e){throw new RuntimeException(e);}}
  void preparePickup(Minecraft mc,String id){pickupId=id;serverAction(mc,p->{var r=getRoom(p);var n=nodes(r).get(id);check(n!=null&&n.hitbox!=null,"source_pickup_loaded_"+id);targetId=n.hitbox.getId();initialStock=n.stock.remaining();Vec3 at=n.pos.getCenter().add(0,-.5,-1.5);p.teleportTo(p.serverLevel(),at.x,at.y,at.z,0,0);p.fallDistance=0;});}
  void pickup(Minecraft mc){var e=mc.level.getEntity(targetId);if(e==null)throw new IllegalStateException("Client pickup entity absent "+targetId);System.out.println("PLAYFEEL_CLIENT_LABEL "+e.getCustomName());if(baseline){mc.gameMode.interact(mc.player,e,InteractionHand.MAIN_HAND);}else{Vec3 delta=e.getBoundingBox().getCenter().subtract(mc.player.getEyePosition());mc.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x,delta.z)));mc.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y,Math.sqrt(delta.x*delta.x+delta.z*delta.z))));mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(mc.player.getYRot(),mc.player.getXRot(),mc.player.onGround()));PacketDistributor.sendToServer(new GameNetwork.Action("outbreak","interact",""));}}
  void select(Minecraft mc,int slot){mc.player.getInventory().selected=slot;}
  void equip(Minecraft mc,String kind,int slot,float health){serverAction(mc,p->{p.getInventory().setItem(slot,CampaignInventory.create(p,kind));p.setHealth(health);p.inventoryMenu.broadcastChanges();});}
  void use(Minecraft mc,int slot){mc.options.keyUse.setDown(true);select(mc,slot);mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);}
  void tick(ClientTickEvent.Post event){if(done)return;Minecraft mc=Minecraft.getInstance();ticks++;try{
    if(failed!=null)throw new RuntimeException(failed);
    if(ticks>9000)throw new IllegalStateException("Native QA deadline reached");
    if(!starting){if(ticks<30||mc.getOverlay()!=null||mc.screen==null)return;starting=true;if(mc.getLevelSource().levelExists("outbreak-native-qa")){mc.createWorldOpenFlows().openWorld("outbreak-native-qa",()->{});return;}mc.createWorldOpenFlows().createFreshLevel("outbreak-native-qa",new LevelSettings("Outbreak isolated real client QA",GameType.SURVIVAL,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(817131L,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),mc.screen);return;}
    if(mc.player==null||mc.getSingleplayerServer()==null){
      if(ticks%100==0){Files.writeString(Path.of("native-loading-state.json"),new Gson().toJson(Map.of("ticks",ticks,"screen",mc.screen==null?"none":mc.screen.getClass().getName(),"title",mc.screen==null?"none":mc.screen.getTitle().getString())));}
      if(Files.exists(Path.of("upgrade-input.json"))&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen backup){
        var f=net.minecraft.client.gui.screens.BackupConfirmScreen.class.getDeclaredField("onProceed");f.setAccessible(true);
        Files.writeString(Path.of("upgrade-load-confirmation.json"),new Gson().toJson(Map.of("qaCopyOnly",true,"screen",backup.getClass().getName(),"title",backup.getTitle().getString(),"backupRequested",true)));
        ((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(backup)).proceed(true,false);
      }else if(Files.exists(Path.of("upgrade-input.json"))&&mc.screen instanceof net.minecraft.client.gui.screens.ConfirmScreen confirm){
        var f=net.minecraft.client.gui.screens.ConfirmScreen.class.getDeclaredField("callback");f.setAccessible(true);
        Files.writeString(Path.of("upgrade-load-confirmation.json"),new Gson().toJson(Map.of("qaCopyOnly",true,"screen",confirm.getClass().getName(),"title",confirm.getTitle().getString())));
        ((it.unimi.dsi.fastutil.booleans.BooleanConsumer)f.get(confirm)).accept(true);
      }
      return;
    }
    if(mc.screen!=null&&stage>0)mc.setScreen(null);
    if(ticks%10==0)observe(mc);
    if(ticks%100==0)atomic(Path.of("native-progress.json"),"{\"stage\":"+stage+",\"ticks\":"+ticks+",\"observation\":"+observed+"}");
    if(stage==32&&ticks%100==0&&observed!=null){kill(mc);record("director_natural_sample");}
    if(ticks<deadline||observed==null&&stage>0)return;
    switch(stage){
      case 0->{serverAction(mc,p->{p.setInvulnerable(true);p.getInventory().clearContent();p.getInventory().add(new ItemStack(Items.DIAMOND,3));ItemStack marker=new ItemStack(Items.EMERALD,7);marker.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Original inventory marker"));p.getInventory().setItem(17,marker);p.getInventory().setItem(39,new ItemStack(Items.IRON_HELMET));p.getInventory().setItem(40,new ItemStack(Items.SHIELD));p.setHealth(17);p.getFoodData().setFoodLevel(13);p.getFoodData().setSaturation(2.5f);originalInventory=p.getInventory().save(new net.minecraft.nbt.ListTag()).toString();originalPosition=p.position();});send("createConfigured","{\"map\":\"lostschool\",\"mode\":\"CAMPAIGN\",\"difficulty\":1}");next(1,50);}
      case 1->{if(!observed.get("roomPhase").getAsString().equals("WAITING"))return;if(!observed.getAsJsonArray("rooms").get(0).getAsJsonObject().get("mapReady").getAsBoolean())return;check(observed.get("dimension").getAsString().equals("minecraft:overworld")&&observed.get("diamonds").getAsInt()==3,"network_create_waits_without_teleport_or_inventory_change");record("network_waiting_room");send("start","");next(2,240);}
      case 2->{check(observed.get("roomPhase").getAsString().equals("START_ROOM")&&observed.get("infected").getAsInt()==0,"network_host_start_enters_protected_room");record("native_start_room");if(!baseline)serverAction(mc,p->{var r=getRoom(p);var decided=nodes(r);for(var source:r.map.supplies())if(source.directorChoice()&&decided.containsKey(source.id()))check(Math.abs(p.getY()-source.pos().getY())<=6&&p.distanceToSqr(source.pos().getCenter())<24*24,"director_optional_loot_waits_for_approaching_floor_"+source.id());});if(!baseline)check(observed.getAsJsonArray("inventory").get(0).getAsJsonObject().get("count").getAsInt()==0&&observed.getAsJsonArray("inventory").get(1).getAsJsonObject().get("count").getAsInt()==0,"start_without_gifted_guns_or_melee");preparePickup(mc,"lost_131123");next(20,35);}

      case 20->{pickup(mc);next(21,30);}
      case 21->{check(!mc.player.getInventory().getItem(2).isEmpty(),"actual_source_vomitjar_picked_up");System.out.println("PLAYFEEL_THROW_ID "+BuiltInRegistries.ITEM.getKey(mc.player.getInventory().getItem(2).getItem()));use(mc,2);next(22,30);}
      case 22->{mc.options.keyUse.setDown(false);record("bile_in_start_room");if(!baseline)check(observed.getAsJsonArray("inventory").get(2).getAsJsonObject().get("count").getAsInt()==0&&observed.get("throws").getAsInt()>0,"bile_launches_in_start_room_via_use_packet");equip(mc,"medkit",3,20);next(23,25);}
      case 23->{use(mc,3);next(24,115);}
      case 24->{mc.options.keyUse.setDown(false);record("full_health_medkit");check(observed.get("health").getAsFloat()==20&&observed.getAsJsonArray("inventory").get(3).getAsJsonObject().get("count").getAsInt()==1,"full_health_medkit_not_consumed");equip(mc,"medkit",3,10);next(25,25);}
      case 25->{use(mc,3);next(26,115);}
      case 26->{mc.options.keyUse.setDown(false);record("injured_medkit");check(Math.abs(observed.get("health").getAsFloat()-18)<.1&&observed.getAsJsonArray("inventory").get(3).getAsJsonObject().get("count").getAsInt()==0,"medkit_heals_eighty_percent_missing_health_after_hold");serverAction(mc,p->{p.getInventory().setItem(0,ItemStack.EMPTY);p.getInventory().setItem(1,ItemStack.EMPTY);p.inventoryMenu.broadcastChanges();});preparePickup(mc,baseline?"lost_195790":"campaign_start_primary");next(27,40);}
      case 27->{var target=mc.level.getEntity(targetId);if(target==null)return;Vec3 delta=target.position().add(0,.3,0).subtract(mc.player.getEyePosition());mc.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x,delta.z)));mc.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y,Math.sqrt(delta.x*delta.x+delta.z*delta.z))));mc.player.yRotO=mc.player.getYRot();mc.player.xRotO=mc.player.getXRot();if(++frame<20)return;screenshot(mc,"scene-starting-weapons");pickup(mc);next(28,35);}
      case 28->{record("source_gun_pickup");check(!mc.player.getInventory().getItem(0).isEmpty(),"scene_gun_pickup_via_real_interaction_packet");System.out.println("PLAYFEEL_GUN_HOVER "+mc.player.getInventory().getItem(0).getHoverName().getString());if(!baseline)check(!mc.player.getInventory().getItem(0).getHoverName().getString().contains("item.")&&!mc.player.getInventory().getItem(0).getHoverName().getString().contains("tacz.gun."),"gun_display_has_translated_name");magazineBefore=observed.getAsJsonArray("inventory").get(0).getAsJsonObject().get("magazine").getAsInt();select(mc,0);next(280,70);}
      case 280->{select(mc,0);var result=com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator.fromLocalPlayer(mc.player).shoot();System.out.println("PLAYFEEL_REAL_CLIENT_SHOOT "+result);if(!result.toString().equals("SUCCESS"))return;next(281,40);}
      case 281->{record("picked_scene_gun_fired");check(observed.get("serverBullets").getAsInt()>0&&observed.getAsJsonArray("inventory").get(0).getAsJsonObject().get("magazine").getAsInt()<magazineBefore,"picked_gun_fires_real_tacz_network_bullet_and_consumes_ammo");preparePickup(mc,baseline?"lost_130904":"campaign_start_melee");next(29,35);}
      case 29->{pickup(mc);next(30,35);}
      case 30->{record("scene_melee_pickup");check(CampaignInventory.category(mc.player.getInventory().getItem(1))==SupplyRules.Slot.SECONDARY,"scene_melee_pickup_via_real_interaction_packet");equip(mc,"bile_bomb",2,20);move(mc,new Vec3(-6.5,83,1.5));next(3,30);}
      case 3->{click(mc,new BlockPos(-7,83,0));next(4,20);}
      case 4->{serverAction(mc,p->{var r=getRoom(p);check(!r.checkpointDoors.closed(p.serverLevel(),r.map.startRoom(0)),"native_start_door_opened_via_player_packet");});move(mc,new Vec3(-6.5,83,-3.5));next(5,100);}
      case 5->{check(org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(mc.getWindow().getWindow(),org.lwjgl.glfw.GLFW.GLFW_VISIBLE)==org.lwjgl.glfw.GLFW.GLFW_FALSE,"window_hidden_without_focus");if(observed.get("infected").getAsInt()==0)return;check(observed.get("entityTicking").getAsBoolean()&&observed.get("entityTickCount").getAsInt()>0,"native_network_infected_visible_and_ticking_in_peaceful");var mob=mc.level.getEntity(observed.get("entityId").getAsInt());if(mob==null)return;Vec3 delta=mob.getEyePosition().subtract(mc.player.getEyePosition());mc.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x,delta.z)));mc.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y,Math.sqrt(delta.x*delta.x+delta.z*delta.z))));if(++frame<30)return;screenshot(mc,"native-infected");record("native_rendered_infected");use(mc,2);next(31,40);}
      case 31->{mc.options.keyUse.setDown(false);record("bile_in_running");check(observed.getAsJsonArray("inventory").get(2).getAsJsonObject().get("count").getAsInt()==0&&observed.get("throws").getAsInt()>0,"bile_launches_in_running_via_use_packet");if(baseline){Files.writeString(Path.of("native-result.json"),"{\"passed\":true,\"baselineOnly\":true}");done=true;mc.stop();return;}next(32,focused?20:1600);}
      case 32->{Set<String> paces=new HashSet<>();for(var e:evidence){var d=e.getAsJsonObject();if(d.has("pace"))paces.add(d.get("pace").getAsString());}if(!focused)check(paces.containsAll(Set.of("RELAX","BUILD","PEAK","FADE")),"live_director_natural_four_phase_cycle");equip(mc,"pipe_bomb",2,20);next(33,25);}
      case 33->{use(mc,2);next(34,35);}
      case 34->{mc.options.keyUse.setDown(false);record("pipe_in_running");check(observed.getAsJsonArray("inventory").get(2).getAsJsonObject().get("count").getAsInt()==0&&observed.get("throws").getAsInt()>0,"pipe_launches_via_real_use_packet");next(35,125);}
      case 35->{record("pipe_fuse_completed");check(!observed.getAsJsonObject("throwsByKind").has("pipe_bomb"),"pipe_natural_fuse_completes");equip(mc,"lr:lrtactical:molotov",2,20);next(36,25);}
      case 36->{use(mc,2);next(37,55);}
      case 37->{mc.options.keyUse.setDown(false);mc.gameMode.releaseUsingItem(mc.player);next(38,30);}
      case 38->{record("native_lr_molotov");check(observed.getAsJsonArray("inventory").get(2).getAsJsonObject().get("count").getAsInt()==0,"native_molotov_actual_use_still_works");move(mc,new Vec3(-98.5,63,-3.5));next(50,100);}
      case 40->{if(!observed.get("roomPhase").getAsString().equals("WAITING"))return;record("second_round_waiting");send("start","");next(41,245);}
      case 41->{check(observed.get("roomPhase").getAsString().equals("START_ROOM")&&observed.getAsJsonArray("inventory").get(0).getAsJsonObject().get("count").getAsInt()==0&&observed.getAsJsonArray("inventory").get(1).getAsJsonObject().get("count").getAsInt()==0,"second_round_still_requires_scene_weapon_pickup");record("second_round_scene_start");move(mc,new Vec3(-6.5,83,1.5));next(410,30);}
      case 410->{click(mc,new BlockPos(-7,83,0));next(411,20);}
      case 411->{move(mc,new Vec3(-6.5,83,-3.5));next(412,40);}
      case 412->{check(observed.get("roomPhase").getAsString().equals("RUNNING"),"second_round_departed_protected_start_before_damage_test");serverAction(mc,p->{p.setInvulnerable(false);p.setHealth(1);p.hurt(p.damageSources().generic(),1000);});next(42,35);}
      case 42->{check(observed.get("roomPhase").getAsString().equals("NONE")&&observed.get("diamonds").getAsInt()==3,"actual_minecraft_all_down_failure_returns_safely");serverAction(mc,p->{check(restoresVerified>=2,"failure_restores_exact_original_player_state");});next(43,25);}
      case 43->{record("second_round_failed_and_restored");Files.writeString(Path.of("native-result.json"),"{\"passed\":true,\"realMinecraftClient\":true,\"realLocalNetworkPlayer\":true,\"fakePlayers\":false,\"windowVisible\":false,\"assistedRouteTeleportsAndCombat\":true,\"naturalFinaleClock\":true}");done=true;mc.stop();}
      case 50->{check(observed.get("infected").getAsInt()>0,"native_director_refills_after_floor_transition");serverAction(mc,p->{var r=getRoom(p);check(r.infected.stream().map(id->p.serverLevel().getEntity(id)).allMatch(m->m!=null&&Math.abs(m.getY()-p.getY())<=6),"old_floor_common_budget_released");});record("native_lower_floor_refill");move(mc,new Vec3(-25.5,64,3.5));next(501,50);}
      case 501->{serverAction(mc,p->{try{var r=getRoom(p);var f=CampaignSupplies.class.getDeclaredField("decided");f.setAccessible(true);check(((Set<String>)f.get(r.supplies)).contains("lost_195747"),"director_optional_source_is_decided_when_actual_new_floor_is_approached");}catch(Exception e){throw new RuntimeException(e);}});record("new_floor_director_supply_decision");kill(mc);move(mc,new Vec3(62.5,53,-20.5));next(6,30);}
      case 6->{check(observed.get("section").getAsInt()==0,"native_open_checkpoint_does_not_complete");move(mc,new Vec3(62.5,53,-13.5));next(7,30);}
      case 7->{click(mc,new BlockPos(61,53,-14));next(8,35);}
      case 8->{check(observed.get("roomPhase").getAsString().equals("SAFE_ROOM")&&observed.get("section").getAsInt()==1,"native_all_inside_and_close_door_advances");screenshot(mc,"native-safe-room");record("native_first_checkpoint");next(9,170);}
      case 9->{check(observed.get("roomPhase").getAsString().equals("START_ROOM"),"native_next_chapter_waits_for_departure");move(mc,new Vec3(2006.5,77,-13.5));next(10,30);}
      case 10->{click(mc,new BlockPos(2005,77,-14));next(11,20);}
      case 11->{move(mc,new Vec3(2002.5,77,-11.5));next(12,100);}
      case 12->{check(observed.get("section").getAsInt()==1&&observed.get("infected").getAsInt()>0,"native_chapter_two_infected");record("native_chapter_two");kill(mc);move(mc,new Vec3(2006.5,57,-13.5));next(13,30);}
      case 13->{click(mc,new BlockPos(2005,57,-14));next(14,200);}
      case 14->{check(observed.get("section").getAsInt()==2&&observed.get("roomPhase").getAsString().equals("START_ROOM"),"native_second_checkpoint");move(mc,new Vec3(4136.5,81,14.5));next(15,30);}
      case 15->{click(mc,new BlockPos(4136,81,15));next(16,20);}
      case 16->{move(mc,new Vec3(4136.5,81,21.5));next(17,100);}
      case 17->{check(observed.get("infected").getAsInt()>0,"native_chapter_three_infected");record("native_chapter_three");kill(mc);move(mc,new Vec3(4159.5,92,224.5));next(18,70);}
      case 18->{check(observed.get("waves").getAsInt()>0&&observed.get("infected").getAsInt()>0,"native_source_finale_wave");screenshot(mc,"native-finale");record("native_finale_wave");next(19,10);}
      case 19->{if(!tankRendered&&observed.has("tankEntityId")){var tank=mc.level.getEntity(observed.get("tankEntityId").getAsInt());if(tank!=null){Vec3 delta=tank.getEyePosition().subtract(mc.player.getEyePosition());mc.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x,delta.z)));mc.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y,Math.sqrt(delta.x*delta.x+delta.z*delta.z))));mc.player.yRotO=mc.player.getYRot();mc.player.xRotO=mc.player.getXRot();boolean visible=mc.level.clip(new net.minecraft.world.level.ClipContext(mc.player.getEyePosition(),tank.getEyePosition(),net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,mc.player)).getType()==HitResult.Type.MISS;if(visible&&++tankFocusFrames>=30){screenshot(mc,"native-tank");tankRendered=true;record("native_tank_rendered");}else if(!visible)tankFocusFrames=0;}}if(!observed.get("roomPhase").getAsString().equals("NONE")){if(ticks%20==0)kill(mc);return;}check(tankRendered&&tankDefeatedSeen&&observed.get("dimension").getAsString().equals("minecraft:overworld")&&observed.get("diamonds").getAsInt()==3,"native_natural_victory_inventory_and_location_restored");screenshot(mc,"native-restored");record("native_natural_victory");serverAction(mc,p->{check(restoresVerified>=1,"natural_win_restores_exact_inventory_components_offhand_armor_position_mode_health");});send("createConfigured","{\"map\":\"lostschool\",\"mode\":\"CAMPAIGN\",\"difficulty\":1}");next(40,45);}
    }
  }catch(Throwable e){done=true;e.printStackTrace();try{Files.writeString(Path.of("native-result.json"),"{\"passed\":false,\"error\":"+new Gson().toJson(e.toString())+"}");}catch(Exception ignored){}mc.stop();}}
}
