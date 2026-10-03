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
import net.muxigame.outbreak.equipment.*;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
@Mod("outbreak_repro_qa")
public class DamageServerQA {
  MinecraftServer server; ServerPlayer a,b; OutbreakGame game; OutbreakSession room; int stage,deadline; JsonArray evidence=new JsonArray(); boolean finale;
  public DamageServerQA(){NeoForge.EVENT_BUS.addListener(this::tick);}
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

  net.minecraft.world.entity.Mob mob; int index=-1; JsonObject row; JsonArray rows=new JsonArray();
  void tick(ServerTickEvent.Post event){
    if(!Boolean.getBoolean("muxi.outbreak.recovery.qa"))return;
    try{
      server=event.getServer();
      if(a==null){if(server.getTickCount()<40)return;a=player("DamageProbe",0);game=OutbreakGame.active(a);if(game==null)return;
        game.action(a,"create","lostschool");room=session();room.directorEnabled=false;room.phase=OutbreakSession.Phase.WAITING;
        var level=server.getLevel(room.map.dimension());level.setChunkForced(0,0,true);a.teleportTo(level,.5,100,.5,0,0);((Listener)a.connection).ack();
        room.prepared.add(a.getUUID());room.alive.add(a.getUUID());a.setNoGravity(true);deadline=server.getTickCount()+100;return;}
      ((Listener)a.connection).ack();a.doTick();a.removeAllEffects();
      if(server.getTickCount()<deadline)return;
      if(mob!=null){
        double after=mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)==null?0:mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        row.addProperty("attributeAfterJoin",after);row.addProperty("hpAfterJoin",mob.getMaxHealth());row.addProperty("removed",mob.isRemoved());
        try{Class<?> api=Class.forName("top.theillusivec4.champions.api.ChampionsApi");Object instance=api.getMethod("get").invoke(null);row.addProperty("champion",(Boolean)api.getMethod("isChampion",net.minecraft.world.entity.LivingEntity.class).invoke(instance,mob));}catch(ClassNotFoundException absent){row.addProperty("champion",false);}
        a.setHealth(20);a.invulnerableTime=0;a.setAbsorptionAmount(0);room.downed.clear();room.incapCount.clear();room.temporaryHealth.clear();room.phase=OutbreakSession.Phase.RUNNING;
        boolean hurt=a.hurt(a.damageSources().mobAttack(mob),(float)(after>0?after:4));
        row.addProperty("healthLost",20-a.getHealth());row.addProperty("hurtAccepted",hurt);row.addProperty("downed",room.downed.contains(a.getUUID()));
        rows.add(row);System.out.println("DAMAGE_PROBE "+row);mob.discard();room.infected.remove(mob.getUUID());room.infectedKinds.remove(mob.getUUID());mob=null;room.phase=OutbreakSession.Phase.WAITING;
      }
      index++;
      if(index>=144){Files.writeString(Path.of("damage-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(rows));Files.writeString(Path.of("probe-result.json"),"{\"completed\":true,\"rows\":144,\"fixturePlayers\":true}");room.prepared.clear();game.action(a,"leave","");server.halt(false);deadline=Integer.MAX_VALUE;return;}
      int difficulty=index%4;var kind=net.muxigame.outbreak.infected.InfectedKind.values()[(index/4)%9];var world=Difficulty.values()[index/36];room.difficulty=difficulty;server.setDifficulty(world,true);
      var level=server.getLevel(room.map.dimension());mob=net.muxigame.outbreak.infected.InfectedFactory.create(level,kind,room.id,1,difficulty);mob.setPos(2,100,2);mob.setNoAi(true);mob.setNoGravity(true);room.infected.add(mob.getUUID());room.infectedKinds.put(mob.getUUID(),kind);
      row=new JsonObject();row.addProperty("worldDifficulty",world.name());row.addProperty("roomDifficulty",difficulty);row.addProperty("kind",kind.name());row.addProperty("attributeBeforeJoin",mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)==null?0:mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE));level.addFreshEntity(mob);deadline=server.getTickCount()+3;
    }catch(Throwable error){error.printStackTrace();try{Files.writeString(Path.of("failure.txt"),error.toString());}catch(Exception ignored){}deadline=Integer.MAX_VALUE;server.halt(false);}
  }
}
