package net.muxigame.outbreak.qa;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.GameType;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;
import net.muxigame.outbreak.*;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
@Mod("outbreak_repro_qa")
public class ReproHarness {
  MinecraftServer server; ServerPlayer a,b; OutbreakGame game; OutbreakSession room; int stage,deadline; JsonArray evidence=new JsonArray(); boolean finale;
  public ReproHarness(){NeoForge.EVENT_BUS.addListener(this::tick);}
  static class Listener extends net.minecraft.server.network.ServerGamePacketListenerImpl {
    Integer teleport;
    Listener(MinecraftServer s,net.minecraft.network.Connection c,ServerPlayer p){super(s,c,p,net.minecraft.server.network.CommonListenerCookie.createInitial(p.getGameProfile(),false));}
    @Override public void send(net.minecraft.network.protocol.Packet<?> p){if(p instanceof net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket t)teleport=t.getId();}
    @Override public void send(net.minecraft.network.protocol.Packet<?> p,net.minecraft.network.PacketSendListener l){send(p);}
    @Override public java.net.SocketAddress getRemoteAddress(){return new java.net.InetSocketAddress("127.0.0.1",24567);}
    void ack(){if(teleport!=null){int id=teleport;teleport=null;handleAcceptTeleportPacket(new net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket(id));}}
  }
  ServerPlayer player(String name,int x)throws Exception{
    var p=new ServerPlayer(server,server.overworld(),new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()),name),ClientInformation.createDefault());
    var c=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);var f=net.minecraft.network.Connection.class.getDeclaredField("channel");f.setAccessible(true);f.set(c,new io.netty.channel.embedded.EmbeddedChannel());p.connection=new Listener(server,c,p);
    var pf=PlayerList.class.getDeclaredField("players");pf.setAccessible(true);((List<ServerPlayer>)pf.get(server.getPlayerList())).add(p);
    var mf=PlayerList.class.getDeclaredField("playersByUUID");mf.setAccessible(true);((Map<UUID,ServerPlayer>)mf.get(server.getPlayerList())).put(p.getUUID(),p);
    p.setPos(x,-60,0);p.setGameMode(GameType.SURVIVAL);p.getInventory().add(new ItemStack(Items.DIAMOND,3));server.overworld().addNewPlayer(p);server.getPlayerList().op(p.getGameProfile());return p;
  }
  OutbreakSession session()throws Exception{var f=OutbreakGame.class.getDeclaredField("sessions");f.setAccessible(true);return ((List<OutbreakSession>)f.get(game)).stream().filter(s->s.players.contains(a.getUUID())).findFirst().orElse(null);}
  void record(String label)throws Exception{var d=game.snapshot(a);d.addProperty("label",label);d.addProperty("tick",server.getTickCount());d.addProperty("worldDifficulty",server.overworld().getDifficulty().name());d.addProperty("playerDimension",a.level().dimension().location().toString());d.addProperty("playerPosition",a.position().toString());if(room!=null){d.addProperty("infectedTracked",room.infected.size());d.addProperty("infectedActuallyAlive",room.infected.stream().map(id->server.getLevel(room.map.dimension()).getEntity(id)).filter(e->e!=null&&e.isAlive()).count());d.addProperty("pace",room.director.pace().name());}evidence.add(d);System.out.println("REPRO "+d);Files.writeString(Path.of("repro.json"),new GsonBuilder().setPrettyPrinting().create().toJson(evidence));}
  void detail()throws Exception{var level=server.getLevel(room.map.dimension());JsonArray rows=new JsonArray();for(UUID id:room.infected){Entity mob=level.getEntity(id);if(mob==null)continue;var d=new JsonObject();d.addProperty("position",mob.position().toString());d.addProperty("ticking",level.isPositionEntityTicking(mob.blockPosition()));d.addProperty("tickCount",mob.tickCount);d.addProperty("distance",mob.distanceTo(a));d.addProperty("floorDifference",Math.abs(mob.getY()-a.getY()));rows.add(d);}System.out.println("SPAWN_DETAIL "+rows);Files.writeString(Path.of("spawn-detail.json"),new GsonBuilder().setPrettyPrinting().create().toJson(rows));}
  void forceNear(){var level=server.getLevel(room.map.dimension());BlockPos p=a.blockPosition();for(int x=(p.getX()>>4)-1;x<=(p.getX()>>4)+1;x++)for(int z=(p.getZ()>>4)-1;z<=(p.getZ()>>4)+1;z++)level.setChunkForced(x,z,true);}
  void check(String name,boolean ok)throws Exception{System.out.println("ASSERT "+name+"="+ok);if(!ok)throw new AssertionError(name);}
  void move(ServerPlayer p,Vec3 v){var level=server.getLevel(room.map.dimension());for(int x=(((int)v.x)>>4)-1;x<=(((int)v.x)>>4)+1;x++)for(int z=(((int)v.z)>>4)-1;z<=(((int)v.z)>>4)+1;z++)level.setChunkForced(x,z,true);p.teleportTo(level,v.x,v.y,v.z,0,0);p.fallDistance=0;}
  void both(Vec3 v){move(a,v);move(b,v);}
  void click(BlockPos pos)throws Exception{var event=new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(b,net.minecraft.world.InteractionHand.MAIN_HAND,pos,new net.minecraft.world.phys.BlockHitResult(pos.getCenter(),net.minecraft.core.Direction.UP,pos,false));NeoForge.EVENT_BUS.post(event);check("native_door_event_handled",event.isCanceled());}
  void clear(){for(UUID id:Set.copyOf(room.infected)){var mob=server.getLevel(room.map.dimension()).getEntity(id);if(mob!=null)mob.kill();}}
  void next(int value,int delay){stage=value;deadline=server.getTickCount()+delay;}
  void tick(ServerTickEvent.Post e){
    try{
      server=e.getServer();if(!server.getLocalIp().equals("127.0.0.1")||!Boolean.getBoolean("muxi.outbreak.recovery.qa"))return;
      if(a!=null){((Listener)a.connection).ack();((Listener)b.connection).ack();a.doTick();b.doTick();}
      if(finale&&room!=null&&room.phase!=OutbreakSession.Phase.FINISHED&&server.getTickCount()%20==0)clear();
      if(server.getTickCount()<deadline)return;
      switch(stage){
        case 0->{if(server.getTickCount()<40)return;a=player("ReproHost",0);b=player("ReproGuest",2);a.setInvulnerable(true);b.setInvulnerable(true);game=OutbreakGame.active(a);game.action(a,"createConfigured","{\"map\":\"lostschool\",\"mode\":\"CAMPAIGN\",\"difficulty\":1}");room=session();game.action(a,"invite",b.getUUID().toString());game.action(b,"join",room.shortId());record("created_waiting");next(1,240);}
        case 1->{if(!game.snapshot(a).getAsJsonArray("rooms").get(0).getAsJsonObject().get("mapReady").getAsBoolean())return;record("waiting_no_teleport");check("waiting_keeps_original_inventory_and_location",room.prepared.isEmpty()&&a.level()==server.overworld()&&a.getInventory().countItem(Items.DIAMOND)==3);try{game.action(b,"start","");throw new AssertionError("guest started room");}catch(IllegalArgumentException expected){}game.action(a,"start","");forceNear();next(2,260);}
        case 2->{record("protected_start_room");check("director_waits_until_departure",room.phase==OutbreakSession.Phase.START_ROOM&&room.infected.isEmpty()&&room.seconds==0);check("source_supplies_exist",room.supplies.inspect().size()>0&&room.map.supplies().size()==133);click(room.map.startRoom(0).doors().get(0));both(new Vec3(-6.5,83,-3.5));next(3,100);}
        case 3->{record("running_real_infected");detail();check("actual_ticking_same_floor_infected",!room.infected.isEmpty()&&room.infected.stream().map(id->a.serverLevel().getEntity(id)).allMatch(m->m!=null&&a.serverLevel().isPositionEntityTicking(m.blockPosition())&&Math.abs(m.getY()-a.getY())<=4));server.setDifficulty(Difficulty.PEACEFUL,true);next(4,80);}
        case 4->{record("peaceful_infected_tick_and_attack");check("peaceful_does_not_clear_room_infected",!room.infected.isEmpty());var mob=(net.minecraft.world.entity.Mob)a.serverLevel().getEntity(room.infected.iterator().next());a.setInvulnerable(false);a.setHealth(20);mob.doHurtTarget(a);check("room_infected_damage_is_nonzero_in_peaceful",a.getHealth()<20);a.setInvulnerable(true);server.setDifficulty(Difficulty.NORMAL,true);game.director(a,"disable");clear();both(new Vec3(-6.5,83,-3.5));a.setDeltaMovement(Vec3.ZERO);b.setDeltaMovement(Vec3.ZERO);a.setInvulnerable(false);a.setHealth(20);a.hurt(a.damageSources().generic(),1000);check("native_death_becomes_incapacitation",room.downed.contains(a.getUUID())&&a.getHealth()==1);b.setShiftKeyDown(true);next(5,110);}
        case 5->{System.out.println("REVIVE_DIAGNOSTIC distance="+a.distanceTo(b)+" crouch="+b.isCrouching()+" shift="+b.isShiftKeyDown()+" pose="+b.getPose()+" progress="+room.reviveProgress);check("teammate_revive_completes",!room.downed.contains(a.getUUID())&&a.getHealth()>1);record("teammate_revived");b.setShiftKeyDown(false);a.hurt(a.damageSources().generic(),1000);check("second_incapacitation",room.downed.contains(a.getUUID()));next(6,620);}
        case 6->{check("bleedout_eliminates_to_spectator",!room.alive.contains(a.getUUID())&&a.gameMode.getGameModeForPlayer()==GameType.SPECTATOR);record("eliminated_after_bleedout");clear();move(b,new Vec3(62.5,53,-20.5));next(7,20);}
        case 7->{check("open_safe_room_door_does_not_advance",room.section==0&&room.phase==OutbreakSession.Phase.RUNNING);click(room.map.safeRooms().get(0).doors().get(0));next(8,20);}
        case 8->{check("closed_safe_room_advances_from_room_corner",room.section==1&&room.phase==OutbreakSession.Phase.SAFE_ROOM);record("first_safe_room_closed");next(9,170);}
        case 9->{check("checkpoint_revives_eliminated_teammate",room.alive.size()==2&&a.gameMode.getGameModeForPlayer()==GameType.ADVENTURE&&room.phase==OutbreakSession.Phase.START_ROOM);a.setInvulnerable(true);record("chapter_two_safe_start");try{game.action(b,"join",room.shortId());throw new AssertionError("late join accepted");}catch(IllegalArgumentException expected){}game.director(a,"enable");click(room.map.startRoom(1).doors().get(0));both(new Vec3(2002.5,77,-11.5));next(10,100);}
        case 10->{check("chapter_two_director_runs",room.phase==OutbreakSession.Phase.RUNNING&&!room.infected.isEmpty());record("chapter_two_infected");clear();both(new Vec3(2007.5,57,-20.5));click(room.map.safeRooms().get(1).doors().get(0));next(11,200);}
        case 11->{check("second_checkpoint_advanced",room.section==2&&room.phase==OutbreakSession.Phase.START_ROOM);record("chapter_three_safe_start");click(room.map.startRoom(2).doors().get(0));both(new Vec3(4136.5,81,21.5));next(12,100);}
        case 12->{check("chapter_three_director_runs",room.phase==OutbreakSession.Phase.RUNNING&&!room.infected.isEmpty());record("chapter_three_infected");clear();both(room.map.finish().getBottomCenter());next(13,40);}
        case 13->{check("source_finale_started_and_wave_exists",room.finaleStarted>=0&&room.finaleWaves>=1&&!room.infected.isEmpty());record("source_finale_wave_one");finale=true;next(14,500);}
        case 14->{check("source_finale_wave_two_and_tank",room.finaleWaves>=2&&room.finaleTankSpawned);record("source_finale_wave_two_tank");next(15,800);}
        case 15->{check("natural_finale_win_and_return",session()==null&&room.phase==OutbreakSession.Phase.FINISHED&&room.finaleTankDefeated&&a.level()==server.overworld()&&a.getInventory().countItem(Items.DIAMOND)==3&&b.getInventory().countItem(Items.DIAMOND)==3);record("natural_campaign_finished_and_restored");finale=false;var stalePos=room.map.startRoom(0).doors().get(0);var staleLevel=server.getLevel(room.map.dimension());staleLevel.setBlock(stalePos,net.minecraft.world.level.block.Blocks.IRON_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.HALF,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER),3);staleLevel.setBlock(stalePos.above(),net.minecraft.world.level.block.Blocks.IRON_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.HALF,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER),3);game.action(a,"create","lostschool");room=session();game.action(b,"join",room.shortId());game.action(a,"start","");next(16,230);}
        case 16->{check("persisted_native_door_reentry_recovers",room.phase==OutbreakSession.Phase.START_ROOM&&room.prepared.size()==2);game.action(b,"leave","");check("leaving_member_not_prepared",!room.prepared.contains(b.getUUID()));next(17,40);}
        case 17->{check("leaving_member_inventory_stays_restored_after_ticks",b.level()==server.overworld()&&b.getInventory().countItem(Items.DIAMOND)==3);game.action(a,"leave","");game.action(a,"create","lostschool");room=session();game.action(a,"leave","");check("waiting_cancel_restores_without_settlement",session()==null&&a.level()==server.overworld()&&a.getInventory().countItem(Items.DIAMOND)==3);record("waiting_cancel_clean");for(var level:server.getAllLevels())for(long key:level.getForcedChunks().toLongArray())level.setChunkForced(net.minecraft.world.level.ChunkPos.getX(key),net.minecraft.world.level.ChunkPos.getZ(key),false);stage=16;server.halt(false);}
      }
    }catch(Throwable error){error.printStackTrace();try{Files.writeString(Path.of("failure.txt"),error.toString());}catch(Exception ignored){}stage=16;server.halt(false);}
  }
}
