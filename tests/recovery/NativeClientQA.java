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
public final class NativeClientQA {
  int ticks,stage,deadline,frame;boolean starting,done,pending;volatile JsonObject observed;volatile Throwable failed;volatile boolean tankRendered,tankDefeatedSeen;int tankFocusFrames;JsonArray evidence=new JsonArray();String request="";
  public NativeClientQA(){NeoForge.EVENT_BUS.addListener(this::tick);}
  static OutbreakSession room(OutbreakGame game)throws Exception{var f=OutbreakGame.class.getDeclaredField("sessions");f.setAccessible(true);return ((List<OutbreakSession>)f.get(game)).stream().findFirst().orElse(null);}
  void next(int value,int wait){stage=value;deadline=ticks+wait;frame=0;}
  void send(String action,String value){request=UUID.randomUUID().toString();PacketDistributor.sendToServer(new GameNetwork.TerminalAction(request,"outbreak",action,value));}
  void observe(Minecraft mc){var server=mc.getSingleplayerServer();if(server==null||mc.player==null||pending)return;pending=true;var id=mc.player.getUUID();server.execute(()->{try{var p=server.getPlayerList().getPlayer(id);var game=OutbreakGame.active(p);var r=room(game);var d=game.snapshot(p);d.addProperty("roomPhase",r==null?"NONE":r.phase.name());d.addProperty("section",r==null?-1:r.section);d.addProperty("infected",r==null?0:r.infected.size());d.addProperty("seconds",r==null?0:r.seconds);d.addProperty("waves",r==null?0:r.finaleWaves);d.addProperty("tank",r!=null&&r.finaleTankSpawned);d.addProperty("tankDefeated",r!=null&&r.finaleTankDefeated);if(r!=null&&r.finaleTankDefeated)tankDefeatedSeen=true;if(r!=null&&r.finaleTankId!=null){var tank=p.serverLevel().getEntity(r.finaleTankId);if(tank!=null)d.addProperty("tankEntityId",tank.getId());}d.addProperty("dimension",p.level().dimension().location().toString());d.addProperty("worldDifficulty",p.level().getDifficulty().name());d.addProperty("diamonds",p.getInventory().countItem(Items.DIAMOND));d.addProperty("players",server.getPlayerList().getPlayerCount());d.addProperty("networkTransport",p.connection.getClass().getName());d.add("operation",GameRuntime.get(server).snapshot(p,"").get("operation"));if(r!=null&&!r.infected.isEmpty()){Entity m=p.serverLevel().getEntity(r.infected.iterator().next());if(m!=null){d.addProperty("entityId",m.getId());d.addProperty("entityTickCount",m.tickCount);d.addProperty("entityTicking",p.serverLevel().isPositionEntityTicking(m.blockPosition()));}}observed=d;}catch(Throwable e){failed=e;}finally{pending=false;}});}
  void serverAction(Minecraft mc,java.util.function.Consumer<ServerPlayer> action){var server=mc.getSingleplayerServer();var id=mc.player.getUUID();server.execute(()->{try{action.accept(server.getPlayerList().getPlayer(id));}catch(Throwable e){failed=e;}});}
  void move(Minecraft mc,Vec3 at){serverAction(mc,p->{var r=getRoom(p);p.teleportTo(p.server.getLevel(r.map.dimension()),at.x,at.y,at.z,0,0);p.fallDistance=0;});}
  static OutbreakSession getRoom(ServerPlayer p){try{return room(OutbreakGame.active(p));}catch(Exception e){throw new RuntimeException(e);}}
  void kill(Minecraft mc){serverAction(mc,p->{var r=getRoom(p);if(r!=null)for(UUID id:Set.copyOf(r.infected)){var mob=p.serverLevel().getEntity(id);if(mob!=null&&(!id.equals(r.finaleTankId)||tankRendered))mob.hurt(p.damageSources().playerAttack(p),1000);}});}
  void click(Minecraft mc,BlockPos pos){mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(pos.getCenter(),Direction.UP,pos,false));}
  void record(String label)throws Exception{var d=observed.deepCopy();d.addProperty("label",label);d.addProperty("clientTick",ticks);evidence.add(d);Files.writeString(Path.of("native-evidence.json"),new GsonBuilder().setPrettyPrinting().create().toJson(evidence));System.out.println("NATIVE_QA "+d);}
  void screenshot(Minecraft mc,String name)throws Exception{try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(Path.of(name+".png"));}}
  void check(boolean value,String label){if(!value)throw new AssertionError(label);System.out.println("NATIVE_ASSERT "+label+"=true");}
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
    if(ticks%100==0)Files.writeString(Path.of("native-progress.json"),"{\"stage\":"+stage+",\"ticks\":"+ticks+",\"observation\":"+observed+"}");
    if(ticks<deadline||observed==null&&stage>0)return;
    switch(stage){
      case 0->{serverAction(mc,p->{p.setInvulnerable(true);p.getInventory().clearContent();p.getInventory().add(new ItemStack(Items.DIAMOND,3));});send("createConfigured","{\"map\":\"lostschool\",\"mode\":\"CAMPAIGN\",\"difficulty\":1}");next(1,50);}
      case 1->{if(!observed.get("roomPhase").getAsString().equals("WAITING"))return;if(!observed.getAsJsonArray("rooms").get(0).getAsJsonObject().get("mapReady").getAsBoolean())return;check(observed.get("dimension").getAsString().equals("minecraft:overworld")&&observed.get("diamonds").getAsInt()==3,"network_create_waits_without_teleport_or_inventory_change");record("network_waiting_room");send("start","");next(2,240);}
      case 2->{check(observed.get("roomPhase").getAsString().equals("START_ROOM")&&observed.get("infected").getAsInt()==0,"network_host_start_enters_protected_room");record("native_start_room");move(mc,new Vec3(-6.5,83,1.5));next(3,30);}
      case 3->{click(mc,new BlockPos(-7,83,0));next(4,20);}
      case 4->{serverAction(mc,p->{var r=getRoom(p);check(!r.checkpointDoors.closed(p.serverLevel(),r.map.startRoom(0)),"native_start_door_opened_via_player_packet");});move(mc,new Vec3(-6.5,83,-3.5));next(5,100);}
      case 5->{check(org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(mc.getWindow().getWindow(),org.lwjgl.glfw.GLFW.GLFW_VISIBLE)==org.lwjgl.glfw.GLFW.GLFW_FALSE,"window_hidden_without_focus");if(observed.get("infected").getAsInt()==0)return;check(observed.get("entityTicking").getAsBoolean()&&observed.get("entityTickCount").getAsInt()>0,"native_network_infected_visible_and_ticking_in_peaceful");var mob=mc.level.getEntity(observed.get("entityId").getAsInt());if(mob==null)return;Vec3 delta=mob.getEyePosition().subtract(mc.player.getEyePosition());mc.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x,delta.z)));mc.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y,Math.sqrt(delta.x*delta.x+delta.z*delta.z))));if(++frame<30)return;screenshot(mc,"native-infected");record("native_rendered_infected");move(mc,new Vec3(-98.5,63,-3.5));next(50,100);}
      case 50->{check(observed.get("infected").getAsInt()>0,"native_director_refills_after_floor_transition");serverAction(mc,p->{var r=getRoom(p);check(r.infected.stream().map(id->p.serverLevel().getEntity(id)).allMatch(m->m!=null&&Math.abs(m.getY()-p.getY())<=6),"old_floor_common_budget_released");});record("native_lower_floor_refill");kill(mc);move(mc,new Vec3(62.5,53,-20.5));next(6,30);}
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
      case 19->{if(!tankRendered&&observed.has("tankEntityId")){var tank=mc.level.getEntity(observed.get("tankEntityId").getAsInt());if(tank!=null){Vec3 delta=tank.getEyePosition().subtract(mc.player.getEyePosition());mc.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x,delta.z)));mc.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y,Math.sqrt(delta.x*delta.x+delta.z*delta.z))));mc.player.yRotO=mc.player.getYRot();mc.player.xRotO=mc.player.getXRot();boolean visible=mc.level.clip(new net.minecraft.world.level.ClipContext(mc.player.getEyePosition(),tank.getEyePosition(),net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,mc.player)).getType()==HitResult.Type.MISS;if(visible&&++tankFocusFrames>=30){screenshot(mc,"native-tank");tankRendered=true;record("native_tank_rendered");}else if(!visible)tankFocusFrames=0;}}if(!observed.get("roomPhase").getAsString().equals("NONE")){if(ticks%20==0)kill(mc);return;}check(tankRendered&&tankDefeatedSeen&&observed.get("dimension").getAsString().equals("minecraft:overworld")&&observed.get("diamonds").getAsInt()==3,"native_natural_victory_inventory_and_location_restored");screenshot(mc,"native-restored");record("native_natural_victory");Files.writeString(Path.of("native-result.json"),"{\"passed\":true,\"realMinecraftClient\":true,\"realLocalNetworkPlayer\":true,\"fakePlayers\":false,\"windowVisible\":false,\"assistedRouteTeleportsAndCombat\":true,\"naturalFinaleClock\":true}");done=true;mc.stop();}
    }
  }catch(Throwable e){done=true;e.printStackTrace();try{Files.writeString(Path.of("native-result.json"),"{\"passed\":false,\"error\":"+new Gson().toJson(e.toString())+"}");}catch(Exception ignored){}mc.stop();}}
}
