package net.muxigame.outbreak.qa;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.*;
import me.xjqsh.lrtactical.entity.GrenadeEntity;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.muxigame.outbreak.*;
import net.muxigame.outbreak.equipment.*;
import net.muxigame.outbreak.infected.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

/** TEST-ONLY mod. Real ServerPlayer/item/world tick logic with a no-op transport.
 * This is not a network-client test. Reflection ONLY enrolls fixture players and observes sessions.
 * Never compiled into the production Outbreak jar, and refuses non-loopback/non-fixture servers.
 */
@Mod("muxi_outbreak_qa")
public final class EquipmentEngineHarness {
    private final JsonArray checks=new JsonArray();
    private final Deque<Step> steps=new ArrayDeque<>();
    private record Step(String label,int waitTicks,Runnable action){}
    private static final class NoSocketListener extends net.minecraft.server.network.ServerGamePacketListenerImpl{
        private Integer pendingTeleport;
        NoSocketListener(MinecraftServer server,net.minecraft.network.Connection connection,ServerPlayer player){
            super(server,connection,player,net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(),false));
        }
        @Override public void send(net.minecraft.network.protocol.Packet<?> packet){
            if(packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket position)pendingTeleport=position.getId();
        }
        @Override public void send(net.minecraft.network.protocol.Packet<?> packet,net.minecraft.network.PacketSendListener listener){send(packet);}
        @Override public java.net.SocketAddress getRemoteAddress(){return new java.net.InetSocketAddress("127.0.0.1",23457);}
        void acknowledgeTeleport(){
            if(pendingTeleport!=null){int id=pendingTeleport;pendingTeleport=null;handleAcceptTeleportPacket(new net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket(id));}
        }
    }
    private MinecraftServer server;
    private ServerPlayer a,b;
    private OutbreakGame game;
    private OutbreakSession session;
    private int waitUntil,startedAt;
    private boolean running,passed;
    private String error="",lastAction="";
    private Path output;
    private final Map<ServerLevel,Set<Long>> fixtureChunkTickets=new IdentityHashMap<>();
    private String medNode,secondMed,ammoNode;
    private int expectedReserve,remainingBefore,magazineBefore;
    private float observedHealth;
    private Vec3 ammoPosition;
    private Mob target;
    private GrenadeEntity testGrenade;
    private int observedDetonations;
    private BlockPos protectedBlock;
    private net.minecraft.world.level.block.state.BlockState protectedState;

    public EquipmentEngineHarness(IEventBus modBus){
        if(!Boolean.getBoolean("muxi.outbreak.qa"))throw new IllegalStateException("QA-only mod refuses normal startup");
        NeoForge.EVENT_BUS.addListener(this::commands);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ExplosionEvent.Detonate event)->{
            if(running&&event.getLevel() instanceof ServerLevel){observedDetonations++;System.out.println("QA_NATIVE_DETONATION entities="+event.getAffectedEntities().size()+" targetPresent="+(target!=null&&event.getAffectedEntities().contains(target)));}
        });
    }
    private void commands(RegisterCommandsEvent event){
        event.getDispatcher().register(Commands.literal("outbreakqa").requires(s->s.hasPermission(2))
            .then(Commands.literal("run").executes(ctx->{begin(ctx.getSource().getServer());ctx.getSource().sendSuccess(()->Component.literal("Engine integration suite started; no network players are impersonated"),false);return 1;}))
            .then(Commands.literal("status").executes(ctx->{ctx.getSource().sendSuccess(()->Component.literal(summary().toString()),false);return 1;})));
    }
    private void check(String name,boolean condition,Object details){
        JsonObject row=new JsonObject();row.addProperty("name",name);row.addProperty("passed",condition);row.addProperty("details",String.valueOf(details));row.addProperty("tick",server.getTickCount()-startedAt);checks.add(row);
        System.out.println("OUTBREAK_QA_ASSERT "+row);
        if(!condition)throw new AssertionError(name+": "+details);
    }
    private void terminal(ServerPlayer player,String action,String value){
        String request=UUID.randomUUID().toString();var runtime=net.muxigame.minigames.GameRuntime.get(server);runtime.terminalRequest(player,request,"outbreak",action,value);
        var receipt=runtime.snapshot(player,"").getAsJsonObject("operation");
        check("terminal_"+action,receipt.get("request").getAsString().equals(request)&&receipt.get("status").getAsString().equals("completed"),receipt);
        runtime.terminalRequest(player,request,"outbreak",action,value);check("terminal_replay_"+action,runtime.snapshot(player,"").getAsJsonObject("operation").equals(receipt),"cached receipt");
    }
    private void step(String label,int wait,Runnable action){steps.add(new Step(label,wait,action));}
    @SuppressWarnings("unchecked") private List<ServerPlayer> players() throws Exception{
        Field field=PlayerList.class.getDeclaredField("players");field.setAccessible(true);return (List<ServerPlayer>)field.get(server.getPlayerList());
    }
    @SuppressWarnings("unchecked") private Map<UUID,ServerPlayer> playerMap() throws Exception{
        Field field=PlayerList.class.getDeclaredField("playersByUUID");field.setAccessible(true);return (Map<UUID,ServerPlayer>)field.get(server.getPlayerList());
    }
    @SuppressWarnings("unchecked") private OutbreakSession observeSession(){
        try{Field f=OutbreakGame.class.getDeclaredField("sessions");f.setAccessible(true);return ((List<OutbreakSession>)f.get(game)).stream().filter(s->s.players.contains(a.getUUID())).findFirst().orElse(null);}
        catch(Exception e){throw new RuntimeException(e);}
    }
    private ServerPlayer fixture(String name,int x) throws Exception{
        var profile=new GameProfile(UUID.nameUUIDFromBytes(("OutbreakEngine:"+name).getBytes(java.nio.charset.StandardCharsets.UTF_8)),name);
        ServerPlayer p=new ServerPlayer(server,server.overworld(),profile,ClientInformation.createDefault());
        var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        Field channel=net.minecraft.network.Connection.class.getDeclaredField("channel");channel.setAccessible(true);
        channel.set(connection,new io.netty.channel.embedded.EmbeddedChannel());
        p.connection=new NoSocketListener(server,connection,p);
        p.setPos(x+.5,-60,.5);p.setGameMode(GameType.SURVIVAL);p.getInventory().add(new ItemStack(Items.DIAMOND,3));
        players().add(p);playerMap().put(p.getUUID(),p);server.overworld().addNewPlayer(p);
        server.getPlayerList().op(profile);return p;
    }
    private void tp(ServerPlayer p,Vec3 at){
        ServerLevel level=server.getLevel(session.map.dimension());
        int cx=((int)Math.floor(at.x))>>4,cz=((int)Math.floor(at.z))>>4;
        // No socket exists to acknowledge chunk batches. Explicit fixture tickets let the
        // real server tick projectiles/entities; never manually tick test grenades.
        Set<Long> forced=fixtureChunkTickets.computeIfAbsent(level,k->new HashSet<>());
        for(int x=cx-1;x<=cx+1;x++)for(int z=cz-1;z<=cz+1;z++){
            long key=net.minecraft.world.level.ChunkPos.asLong(x,z);
            if(!level.getForcedChunks().contains(key)&&forced.add(key))level.setChunkForced(x,z,true);
        }
        p.teleportTo(level,at.x,at.y,at.z,0,0);p.fallDistance=0;
    }
    private void both(Vec3 at){tp(a,at);tp(b,at);}
    private JsonObject node(String id){
        for(var value:session.supplies.inspect())if(value.getAsJsonObject().get("id").getAsString().equals(id))return value.getAsJsonObject();
        throw new IllegalStateException("node not initialized: "+id);
    }
    private String source(String kind,Set<String> excluded){
        return session.map.supplies().stream().filter(n->n.section()==session.section&&n.choices().equals(List.of(kind))&&!n.directorChoice()&&!n.infinite()&&n.count()==1&&!excluded.contains(n.id()))
            .min(Comparator.comparingDouble(n->a.distanceToSqr(n.pos().getCenter()))).orElseThrow().id();
    }
    private Vec3 sourcePos(String id){return session.map.supplies().stream().filter(n->n.id().equals(id)).findFirst().orElseThrow().pos().getBottomCenter();}
    private int total(SupplyRules.Slot category,ServerPlayer player){
        int count=0;for(int i=0;i<player.getInventory().getContainerSize();i++){var stack=player.getInventory().getItem(i);if(CampaignInventory.category(stack)==category)count+=stack.getCount();}
        if(CampaignInventory.category(player.containerMenu.getCarried())==category)count+=player.containerMenu.getCarried().getCount();return count;
    }
    private void give(ServerPlayer p,int slot,ItemStack item){p.stopUsingItem();p.getInventory().setItem(slot,item);p.getInventory().selected=slot;}
    private void use(ServerPlayer p){p.gameMode.useItem(p,p.level(),p.getMainHandItem(),InteractionHand.MAIN_HAND);}
    private void healthy(ServerPlayer p,float health){p.stopUsingItem();p.invulnerableTime=0;p.setHealth(health);session.temporaryHealth.remove(p.getUUID());p.removeAllEffects();}
    private void protect(ServerPlayer p){p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE,10000,255,false,false));}
    private void killInfected(){
        for(UUID id:Set.copyOf(session.infected)){Entity e=server.getLevel(session.map.dimension()).getEntity(id);if(e!=null)e.kill();}
    }

    private void begin(MinecraftServer input){
        if(running)throw new IllegalStateException("suite already running");
        Path cwd=Path.of("").toAbsolutePath().normalize();
        if(!input.getLocalIp().equals("127.0.0.1")||!cwd.endsWith(Path.of("muxi-outbreak","build","qa-server")))throw new IllegalStateException("Not an isolated loopback QA fixture");
        server=input;output=cwd.getParent().resolve("equipment-engine-report.json");checks.asList().clear();steps.clear();error="";passed=false;startedAt=server.getTickCount();
        try{a=fixture("EngineQA",0);b=fixture("EngineQB",2);game=OutbreakGame.active(a);if(game==null)throw new IllegalStateException("Outbreak not started");}
        catch(Exception e){cleanup();throw new IllegalStateException(e);}
        running=true;waitUntil=server.getTickCount();
        step("create room",1,()->{
            check("local_connection_without_platform_binding",net.muxigame.minigames.GameRuntime.get(server).snapshot(a,"").get("allowed").getAsBoolean(),"fresh isolated config");
            try{net.muxigame.minigames.TrustedAccounts.uid(a);throw new AssertionError("Local name became platform UID");}catch(IllegalArgumentException expected){check("local_name_not_platform_uid",true,"unbound");}
            terminal(a,"createConfigured","{\"map\":\"lostschool\",\"difficulty\":\"1\",\"mode\":\"CAMPAIGN\"}");session=observeSession();game.director(a,"disable");
            check("terminal_single_player_room",session.players.size()==1&&session.lobbyWaiting,session.phase);
        });
        step("invite teammate",5,()->terminal(a,"invite",b.getUUID().toString()));
        step("join teammate",5,()->{terminal(b,"join",session.shortId());check("two_server_players_joined",session.players.size()==2,session.phase);});
        step("terminal lobby waits for host",230,()->{check("does_not_autostart_before_invites",session.lobbyWaiting&&session.phase==OutbreakSession.Phase.WAITING,session.phase);terminal(a,"start","");});
        step("native loadout",230,()->{
            check("countdown_completed",session.phase==OutbreakSession.Phase.START_ROOM,session.phase);
            check("actual_spawn_coordinates",a.position().distanceTo(session.map.start().getBottomCenter())<2,a.position());
            check("real_source_supplies_initialized",session.map.supplies().size()==133&&session.supplies.inspect().size()>0,session.supplies.inspect().size());
            check("same_primary_as_zombie_game",CampaignInventory.gunId(a.getInventory().getItem(0)).equals(CampaignInventory.PRIMARY),CampaignInventory.gunId(a.getInventory().getItem(0)));
            check("same_sidearm_as_zombie_game",CampaignInventory.gunId(a.getInventory().getItem(1)).equals(CampaignInventory.SECONDARY),CampaignInventory.gunId(a.getInventory().getItem(1)));
            expectedReserve=SupplyRules.challengeReserve(CampaignInventory.capacity(a.getInventory().getItem(0))+CampaignInventory.capacity(a.getInventory().getItem(1)),false);
            check("matching_real_ammo_reserve",CampaignInventory.ammoCount(a,"tacz:9mm")==expectedReserve,expectedReserve);
            check("no_free_medical_or_throwables",total(SupplyRules.Slot.LARGE_MEDICAL,a)==0&&total(SupplyRules.Slot.SMALL_MEDICAL,a)==0&&total(SupplyRules.Slot.THROWABLE,a)==0,a.getInventory().items);
            medNode=source("medkit",Set.of());secondMed=source("medkit",Set.of(medNode));both(sourcePos(medNode));
        });
        step("shared finite kit",20,()->{
            String first=session.supplies.take(a,medNode,false);String second=session.supplies.take(b,medNode,false);
            check("medkit_stock_shared_not_per_player",a.getInventory().getItem(3).is(CampaignItems.MEDKIT.get())&&b.getInventory().getItem(3).isEmpty()&&node(medNode).get("remaining").getAsInt()==0,first+" / "+second);
            both(sourcePos(secondMed));
        });
        step("full slot does not burn world stock",20,()->{
            remainingBefore=node(secondMed).get("remaining").getAsInt();
            String result=session.supplies.take(a,secondMed,false);
            check("one_large_medical_and_no_stock_loss",total(SupplyRules.Slot.LARGE_MEDICAL,a)==1&&node(secondMed).get("remaining").getAsInt()==remainingBefore,result);
            session.supplies.take(b,secondMed,false);check("teammate_can_take_refused_item",b.getInventory().getItem(3).is(CampaignItems.MEDKIT.get()),node(secondMed));
            healthy(a,4);a.getInventory().selected=3;use(a);
        });
        step("medical needs five seconds",60,()->check("medkit_not_instant",a.isUsingItem()&&Math.abs(a.getHealth()-4)<.1&&!a.getInventory().getItem(3).isEmpty(),a.getHealth()));
        step("medkit finish",48,()->{
            check("medkit_heals_80_percent_missing",Math.abs(a.getHealth()-16.8)<.1&&a.getInventory().getItem(3).isEmpty(),a.getHealth());
            healthy(a,4);give(a,4,new ItemStack(CampaignItems.PILLS.get()));use(a);
        });
        step("temporary pills",25,()->{
            check("pills_temporary_50_percent",a.getHealth()>13.8&&a.getHealth()<=14&&session.temporaryHealth.getOrDefault(a.getUUID(),0f)>9.8,a.getHealth());
            observedHealth=a.getHealth();
        });
        step("temporary decay",100,()->{
            check("temporary_health_decays",a.getHealth()<observedHealth-.2&&a.getHealth()>observedHealth-.4,a.getHealth());
            healthy(a,4);give(a,3,new ItemStack(CampaignItems.MEDKIT.get()));use(a);
        });
        step("damage interrupts treatment",30,()->a.hurt(a.damageSources().generic(),2));
        step("interrupted kit retained",100,()->{
            check("damage_cancels_and_does_not_consume_kit",!a.isUsingItem()&&a.getInventory().getItem(3).is(CampaignItems.MEDKIT.get())&&a.getHealth()<4,a.getHealth());
            healthy(a,4);a.heal(16);check("no_automatic_or_vanilla_healing",Math.abs(a.getHealth()-4)<.05,a.getHealth());
            give(a,4,new ItemStack(CampaignItems.ADRENALINE.get()));use(a);
        });
        step("adrenaline",25,()->{
            check("adrenaline_quarter_temporary_health",a.getHealth()>8.8&&a.getHealth()<=9&&session.adrenalineUntil.getOrDefault(a.getUUID(),0)>server.getTickCount(),a.getHealth());
            a.getInventory().setItem(8,new ItemStack(CampaignItems.PILLS.get(),3));a.getInventory().setItem(40,CampaignInventory.throwable("lrtactical:m67"));
            a.getInventory().setItem(2,new ItemStack(CampaignItems.PIPE.get(),2));a.containerMenu.setCarried(new ItemStack(CampaignItems.DEFIB.get()));
        });
        step("all slot enforcement",3,()->{
            check("single_throwable_including_offhand",total(SupplyRules.Slot.THROWABLE,a)==1&&a.getInventory().getItem(40).isEmpty(),total(SupplyRules.Slot.THROWABLE,a));
            check("one_big_and_one_small_medical",total(SupplyRules.Slot.LARGE_MEDICAL,a)==1&&total(SupplyRules.Slot.SMALL_MEDICAL,a)==1&&a.containerMenu.getCarried().isEmpty(),a.getInventory().items);
            check("extra_items_return_as_shared_pickups",session.supplies.inspect().asList().stream().anyMatch(v->v.getAsJsonObject().get("id").getAsString().startsWith("swap_")),session.supplies.inspect().size());
            ammoNode=session.map.supplies().stream().filter(n->n.section()==0&&n.choices().equals(List.of("ammo"))).findFirst().orElseThrow().id();ammoPosition=sourcePos(ammoNode);both(ammoPosition);
            for(int i=9;i<36;i++)if(IAmmo.getIAmmoOrNull(a.getInventory().getItem(i))!=null)a.getInventory().setItem(i,ItemStack.EMPTY);
            IGun gun=IGun.getIGunOrNull(a.getInventory().getItem(0));gun.setCurrentAmmoCount(a.getInventory().getItem(0),2);gun.setBulletInBarrel(a.getInventory().getItem(0),false);
        });
        step("real ammo pile",20,()->{
            String result=session.supplies.take(a,ammoNode,false);
            check("ammo_pile_refills_matching_reserve",CampaignInventory.ammoCount(a,"tacz:9mm")==expectedReserve,result);
            check("pile_does_not_magically_reload",IGun.getIGunOrNull(a.getInventory().getItem(0)).getCurrentAmmoCount(a.getInventory().getItem(0))==2,"magazine retained 2");
            check("ammo_pile_is_not_consumed",node(ammoNode).get("infinite").getAsBoolean(),node(ammoNode));
            int meds=total(SupplyRules.Slot.LARGE_MEDICAL,a),grenades=total(SupplyRules.Slot.THROWABLE,a);
            for(int i=0;i<10;i++)session.supplies.take(a,ammoNode,false);
            check("repeat_refill_no_overflow_or_med_grenade_gifts",CampaignInventory.ammoCount(a,"tacz:9mm")==expectedReserve&&total(SupplyRules.Slot.LARGE_MEDICAL,a)==meds&&total(SupplyRules.Slot.THROWABLE,a)==grenades,expectedReserve);
            give(a,0,CampaignInventory.gun(a,"tacz:rpg7"));
        });
        step("RPG restriction",3,()->{
            String rocket=CampaignInventory.ammoId(a.getInventory().getItem(0));int before=CampaignInventory.ammoCount(a,rocket);
            session.supplies.take(a,ammoNode,false);
            check("ordinary_pile_does_not_refill_RPG",CampaignInventory.ammoCount(a,rocket)==before,rocket);
            give(a,0,CampaignInventory.gun(a,CampaignInventory.PRIMARY));CampaignInventory.refill(a,false);
            IGunOperator.fromLivingEntity(a).initialData();IGunOperator.fromLivingEntity(a).draw(()->a.getMainHandItem());
        });
        step("native gun shoots",40,()->{
            magazineBefore=IGun.getIGunOrNull(a.getMainHandItem()).getCurrentAmmoCount(a.getMainHandItem());
            Object result=IGunOperator.fromLivingEntity(a).shoot(()->a.getXRot(),()->a.getYRot());
            check("tacz_shoot_operation",result.toString().equals("SUCCESS"),result);
        });
        step("native ammunition consumption",8,()->{
            int after=IGun.getIGunOrNull(a.getMainHandItem()).getCurrentAmmoCount(a.getMainHandItem());
            check("real_gun_spent_one_round",after<magazineBefore,magazineBefore+" -> "+after);
            healthy(a,20);healthy(b,20);both(ammoPosition);protect(b);a.hurt(a.damageSources().generic(),100);b.setShiftKeyDown(true);
        });
        step("incap and regular revive timing",70,()->check("regular_revive_not_before_five_seconds",session.downed.contains(a.getUUID()),session.reviveProgress));
        step("revive completes",40,()->{
            b.setShiftKeyDown(false);check("regular_revive_completed",!session.downed.contains(a.getUUID())&&a.getHealth()<=6&&session.temporaryHealth.getOrDefault(a.getUUID(),0f)>4,"health="+a.getHealth());
            healthy(a,20);a.hurt(a.damageSources().generic(),100);
        });
        step("bleed out with surviving teammate",610,()->{
            check("downed_player_bleeds_out",!session.alive.contains(a.getUUID())&&session.eliminatedAt.containsKey(a.getUUID()),session.alive);
            give(b,3,new ItemStack(CampaignItems.DEFIB.get()));use(b);
        });
        step("defibrillator revives",70,()->{
            check("defib_resurrects_and_consumes_large_slot",session.alive.contains(a.getUUID())&&a.gameMode.getGameModeForPlayer()==GameType.ADVENTURE&&Math.abs(a.getHealth()-10)<.1&&b.getInventory().getItem(3).isEmpty(),a.getHealth());
            give(a,3,new ItemStack(CampaignItems.EXPLOSIVE_AMMO.get()));use(a);
        });
        step("deploy upgrade",45,()->{
            var nodes=session.supplies.inspect().asList().stream().map(JsonElement::getAsJsonObject).filter(v->v.get("kind").getAsString().equals("upgrade_station")).toList();
            check("upgrade_pack_deploys_shared_station",nodes.size()==1&&a.getInventory().getItem(3).isEmpty(),nodes);
            String id=nodes.getFirst().get("id").getAsString();session.supplies.take(a,id,false);
            check("one_magazine_explosive_upgrade",session.explosiveRounds.getOrDefault(a.getUUID(),0)==CampaignInventory.capacity(a.getInventory().getItem(0)),session.explosiveRounds);
            give(a,2,CampaignInventory.throwable("lrtactical:m67"));healthy(a,20);protect(a);protect(b);
            use(a);
        });
        step("native LR grenade release",20,()->a.releaseUsingItem());
        step("native LR grenade created and inventory spent",2,()->{
            long count=server.getLevel(session.map.dimension()).getEntities().getAll().spliterator().getExactSizeIfKnown();
            check("LR_grenade_consumed",a.getInventory().getItem(2).isEmpty(),count);
            var level=a.serverLevel();
            target=InfectedFactory.create(level,InfectedKind.COMMON,session.id,2,1);target.setNoAi(true);
            target.moveTo(a.getX(),a.getY(),a.getZ(),0,0);target.setNoGravity(true);level.addFreshEntity(target);session.infected.add(target.getUUID());session.infectedKinds.put(target.getUUID(),InfectedKind.COMMON);
            observedHealth=target.getHealth();protectedBlock=a.blockPosition().below();protectedState=level.getBlockState(protectedBlock);
            testGrenade=new GrenadeEntity(a,level,2);testGrenade.setPos(a.getX(),a.getY()+.8,a.getZ());testGrenade.setNoGravity(true);testGrenade.setItem(CampaignInventory.throwable("lrtactical:m67"));testGrenade.setDamage(28);testGrenade.setRadius(5.5f);testGrenade.setDestroyBlocks(true);
            check("native_grenade_added_to_world",level.addFreshEntity(testGrenade),testGrenade.getUUID());
        });
        step("real addon detonation without terrain loss",12,()->{
            check("LR_native_detonation_executed",testGrenade.isRemoved()&&observedDetonations>0,"removed="+testGrenade.isRemoved()+" tickCount="+testGrenade.tickCount+" life="+testGrenade.getLife()+" detonations="+observedDetonations+" grenade="+testGrenade.position()+" target="+target.position());
            check("grenade_damage_not_suppressed",!target.isAlive()||target.getHealth()<observedHealth,target.getHealth());
            check("grenade_cannot_break_campaign_geometry",a.serverLevel().getBlockState(protectedBlock).equals(protectedState),protectedBlock);
            give(a,2,new ItemStack(CampaignItems.PIPE.get()));use(a);
        });
        step("pipe bomb use",125,()->{
            check("pipe_bomb_spent_and_detonated",a.getInventory().getItem(2).isEmpty(),a.getInventory().getItem(2));
            give(a,2,new ItemStack(CampaignItems.BILE.get()));use(a);
        });
        step("bile bomb use",20,()->{
            check("bile_bomb_spent",a.getInventory().getItem(2).isEmpty(),a.getInventory().getItem(2));
            game.manualSpawn(a,"horde");
        });
        step("horde and special coexist with guns",10,()->{
            check("native_horde_with_equipment_runtime",session.infected.size()>2,session.infected.size());killInfected();game.manualSpawn(a,"hunter");
        });
        step("special",5,()->{
            check("special_with_equipment_runtime",session.infectedKinds.containsValue(InfectedKind.HUNTER),session.infectedKinds);killInfected();
            healthy(a,10);healthy(b,12);protect(a);protect(b);both(session.map.chapters().get(0).end().getBottomCenter());session.checkpointDoors.interact(a.serverLevel(),session.map.safeRooms().get(0).doors().get(0),session.map,0,true);
        });
        step("safe room does not heal or duplicate items",170,()->{
            check("safe_room_advanced",session.section==1&&session.phase==OutbreakSession.Phase.START_ROOM,session.phase);
            check("safe_room_preserves_wounds",Math.abs(a.getHealth()-10)<.1&&Math.abs(b.getHealth()-12)<.1,a.getHealth()+" / "+b.getHealth());
            check("finite_old_stock_did_not_regrow",node(medNode).get("remaining").getAsInt()==0,node(medNode));
            both(session.map.chapters().get(1).end().getBottomCenter());session.checkpointDoors.interact(a.serverLevel(),session.map.safeRooms().get(1).doors().get(0),session.map,1,true);
        });
        step("third chapter",170,()->{
            check("third_chapter_entry",session.section==2&&session.phase==OutbreakSession.Phase.START_ROOM,session.section);
            both(session.map.finish().getBottomCenter());
        });
        // Native 60-second finale is advanced only by real server ticks, never by force-win/clock manipulation.
        for(int i=0;i<65;i++){
            final int second=i;
            step("finale second "+i,20,()->{
                if(observeSession()!=null){killInfected();if(second==5)check("no_instant_finale_win",session.phase==OutbreakSession.Phase.RUNNING,session.phase);}
            });
        }
        step("victory and restore",5,()->{
            check("untrusted_campaign_no_platform_points",net.muxigame.minigames.GamePlatform.pendingCount(a)==0&&net.muxigame.minigames.GamePlatform.pendingCount(b)==0,"no platform outbox");
            check("natural_campaign_victory",observeSession()==null&&session.section==2&&session.finaleTankSpawned,session.phase);
            check("original_inventory_restored",a.getInventory().countItem(Items.DIAMOND)==3&&b.getInventory().countItem(Items.DIAMOND)==3&&CampaignInventory.gunId(a.getInventory().getItem(0)).isEmpty(),a.getInventory().items);
            check("original_location_and_mode_restored",a.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD)&&a.gameMode.getGameModeForPlayer()==GameType.SURVIVAL,a.position());
        });
    }

    private void tick(ServerTickEvent.Post event){
        if(!running||event.getServer()!=server)return;
        try{
            // No socket is attached; run the same vanilla doTick normally driven by a connection.
            if(a!=null&&!a.isRemoved()){((NoSocketListener)a.connection).acknowledgeTeleport();a.doTick();}
            if(b!=null&&!b.isRemoved()){((NoSocketListener)b.connection).acknowledgeTeleport();b.doTick();}
            if(server.getTickCount()<waitUntil)return;
            if(steps.isEmpty()){passed=true;running=false;persist();cleanup();return;}
            if(steps.peekFirst().label().equals("terminal lobby waits for host")&&!game.snapshot(a).getAsJsonArray("rooms").get(0).getAsJsonObject().get("mapReady").getAsBoolean()){
                if(server.getTickCount()-startedAt>6000)throw new AssertionError("fresh campaign preparation timed out before host start");
                return;
            }
            // A fresh fixture world has no cached campaign geometry. Observe the real
            // preparation/countdown instead of assuming a warmed world's 230 ticks.
            if(steps.peekFirst().label().equals("native loadout")&&(session.phase==OutbreakSession.Phase.PREPARING||session.phase==OutbreakSession.Phase.COUNTDOWN)){
                if(server.getTickCount()-startedAt>6000)throw new AssertionError("fresh campaign preparation timed out");
                return;
            }
            Step step=steps.removeFirst();lastAction=step.label();step.action().run();
            waitUntil=server.getTickCount()+(steps.isEmpty()?1:steps.peekFirst().waitTicks());
        }catch(Throwable failure){error=lastAction+": "+failure;failure.printStackTrace();running=false;passed=false;persist();cleanup();}
    }
    private JsonObject summary(){
        JsonObject result=new JsonObject();result.addProperty("passed",passed);result.addProperty("running",running);result.addProperty("error",error);result.addProperty("currentStep",lastAction);
        result.addProperty("kind","real server-engine integration using two ServerPlayer fixtures with no-op network transport; NOT two network clients");
        result.addProperty("elapsedTicks",server==null?0:server.getTickCount()-startedAt);result.add("checks",checks);return result;
    }
    private void persist(){
        try{Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(summary())+"\n");}
        catch(Exception e){e.printStackTrace();}
    }
    private void cleanup(){
        try{
            if(game!=null&&a!=null&&observeSession()!=null)game.stop(a,"QA fixture cleanup");
            for(ServerPlayer p:new ServerPlayer[]{a,b})if(p!=null){players().remove(p);playerMap().remove(p.getUUID());p.discard();}
            for(var entry:fixtureChunkTickets.entrySet())for(long packed:entry.getValue()){
                var pos=new net.minecraft.world.level.ChunkPos(packed);entry.getKey().setChunkForced(pos.x,pos.z,false);
            }
            fixtureChunkTickets.clear();
        }catch(Throwable e){e.printStackTrace();}
        a=null;b=null;
    }
}
