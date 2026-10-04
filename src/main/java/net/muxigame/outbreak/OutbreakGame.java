package net.muxigame.outbreak;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.muxigame.outbreak.equipment.*;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.common.util.TriState;
import com.tacz.guns.entity.EntityKineticBullet;
import net.muxigame.outbreak.compat.Left2MineCompatCommands;
import net.muxigame.outbreak.director.Director;
import net.muxigame.outbreak.infected.InfectedFactory;
import net.muxigame.outbreak.infected.InfectedKind;
import net.muxigame.outbreak.infected.SpecialInfectedController;
import net.muxigame.outbreak.map.OutbreakMap;
import net.muxigame.outbreak.map.OutbreakMapLoader;
import net.muxigame.outbreak.map.GeometryInstaller;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;

public final class OutbreakGame implements net.muxigame.minigames.GameModule {
    private static final Map<MinecraftServer,OutbreakGame> ACTIVE=new WeakHashMap<>();
    public static OutbreakGame active(ServerPlayer player){return ACTIVE.get(player.server);}
    private MinecraftServer server;
    private Map<String, OutbreakMap> maps = Map.of();
    private final List<OutbreakSession> sessions = new ArrayList<>();
    private final Random random = new Random();
    private int ticks;
    private GeometryInstaller geometry;

    public Collection<net.muxigame.minigames.RoomTeam> roomTeams(){return sessions.stream().map(s->s.team).toList();}
    public boolean roomWaiting(net.muxigame.minigames.RoomTeam team){return sessions.stream().anyMatch(s->s.team==team&&s.lobbyWaiting&&s.phase==OutbreakSession.Phase.WAITING);}
    public String gameId(){return "outbreak";}
    public String title(){return "求援之路";}
    private net.muxigame.minigames.GameRuntime runtime(){return net.muxigame.minigames.GameRuntime.get(server);}
    public com.google.gson.JsonObject snapshot(ServerPlayer p){
        var data=new com.google.gson.JsonObject();data.addProperty("self",p.getUUID().toString());var catalog=new com.google.gson.JsonArray();
        for(var map:maps.values()){var row=new com.google.gson.JsonObject();row.addProperty("id",map.id());row.addProperty("title",map.title());row.addProperty("mode",map.mode().name());catalog.add(row);}data.add("maps",catalog);data.addProperty("recovering",p.getPersistentData().contains(net.muxigame.minigames.PlayerReturns.OUTBREAK,net.minecraft.nbt.Tag.TAG_COMPOUND));
        var rooms=new com.google.gson.JsonArray();for(var session:sessions){var row=new com.google.gson.JsonObject();row.addProperty("id",session.shortId());row.addProperty("session",session.team.session.toString());row.addProperty("socialManaged",session.team.socialManaged);row.addProperty("host",session.host.toString());row.addProperty("mine",session.contains(p.getUUID()));row.addProperty("title",session.map.title());row.addProperty("phase",session.phase.name());row.addProperty("mode",session.mode.name());row.addProperty("difficulty",session.difficulty);row.addProperty("lobbyWaiting",session.lobbyWaiting);row.addProperty("mapReady",geometry.ready(session.map));row.addProperty("invited",session.team.invites.getOrDefault(p.getUUID(),0L)>server.getTickCount());row.addProperty("count",session.players.size());row.addProperty("section",session.section);row.addProperty("seconds",session.seconds);var members=new com.google.gson.JsonArray();for(var id:session.players){var member=server.getPlayerList().getPlayer(id);if(member!=null)members.add(member.getDisplayName().getString());}row.add("members",members);rooms.add(row);}data.add("rooms",rooms);
        var online=new com.google.gson.JsonArray();for(var other:server.getPlayerList().getPlayers())if(other!=p&&runtime().memberships.owner(other.getUUID())==null&&online.size()<64){var row=new com.google.gson.JsonObject();row.addProperty("id",other.getUUID().toString());row.addProperty("name",other.getDisplayName().getString());online.add(row);}data.add("players",online);return data;
    }
    public com.google.gson.JsonObject terminalUi(ServerPlayer p,com.google.gson.JsonObject snapshot){return OutbreakTerminalUi.describe(snapshot);}
    public void action(ServerPlayer p,String action,String value){
        switch(action){
            case "create"->createRoom(p,value,null,1,true);
            case "createConfigured"->{
                var choice=com.google.gson.JsonParser.parseString(value).getAsJsonObject();
                require(choice.keySet().equals(java.util.Set.of("map","difficulty"))||choice.keySet().equals(java.util.Set.of("map","difficulty","mode")),"无效房间选项");
                String level=choice.get("difficulty").getAsString();require(level.matches("[0-3]"),"无效难度");
                OutbreakMap.Mode mode=choice.has("mode")?OutbreakMap.Mode.valueOf(choice.get("mode").getAsString()):null;
                createRoom(p,choice.get("map").getAsString(),mode,Integer.parseInt(level),true);
            }
            case "interact"->{require(value.isBlank(),"无效交互参数");interact(p);}
            case "invite"->invite(p,UUID.fromString(value));
            case "start"->startWaiting(p);
            case "join"->join(p,value);
            case "leave"->{var room=session(p.getUUID());if(room==null){PlayerSnapshot.restore(p);return;}if(room.host.equals(p.getUUID()))finish(room,false,"房主退出");else leavePlayer(room,p);}
            default->throw new IllegalArgumentException("未知小游戏操作");
        }
    }
    private void invite(ServerPlayer host,UUID target){
        var room=requireSession(host);require(room.host.equals(host.getUUID()),"只有房主可以邀请队友");
        require(!room.team.socialManaged||!runtime().social.enabled(),"Use terminal room invitations");
        require(room.phase==OutbreakSession.Phase.WAITING&&room.lobbyWaiting,"游戏已经开始");
        require(room.players.size()<4,"房间已满");var guest=server.getPlayerList().getPlayer(target);
        require(guest!=null&&guest!=host,"请选择在线队友");require(runtime().memberships.owner(target)==null&&!net.muxigame.minigames.PlayerReturns.pending(guest),"队友已在其他房间或正在恢复");
        runtime().requireParticipation(guest);room.team.invites.put(target,(long)server.getTickCount()+6000);
        tell(guest,host.getDisplayName().getString()+" 邀请你加入求援之路房间 "+room.shortId()+"，请在终端小游戏大厅加入");
    }
    public void register(IEventBus bus) {
        net.muxigame.minigames.equipment.GameEquipment.register(new net.muxigame.minigames.equipment.EquipmentContext(){
            public String gameId(){return "outbreak";}
            public boolean active(ServerPlayer p){var g=OutbreakGame.active(p);return g!=null&&g.inCampaign(p);}
            public boolean canUseMedical(ServerPlayer p,String kind){var g=OutbreakGame.active(p);return g!=null&&g.canUseMedical(p,kind);}
            public boolean useMedical(ServerPlayer p,String kind){var g=OutbreakGame.active(p);return g!=null&&g.useMedical(p,kind);}
            public boolean throwEquipment(ServerPlayer p,String kind,ItemStack stack){var g=OutbreakGame.active(p);return g!=null&&g.throwEquipment(p,kind,stack);}
            public boolean interact(ServerPlayer p){var g=OutbreakGame.active(p);return g!=null&&g.interact(p);}
        });
        bus.addListener(this::started);
        bus.addListener(this::stopping);
        bus.addListener(this::tick);
        bus.addListener(this::commands);
        bus.addListener(EventPriority.HIGHEST, this::incomingDamage);
        bus.addListener(EventPriority.LOWEST, this::roomIncomingDamage);
        bus.addListener(EventPriority.LOWEST, this::roomDamageLimit);
        bus.addListener(EventPriority.LOWEST, this::damageDone);
        bus.addListener(EventPriority.HIGHEST, this::death);
        bus.addListener(EventPriority.LOWEST,(LivingDeathEvent event)->{
            for(var s:sessions)if(event.getEntity().getUUID().equals(s.finaleTankId))s.finaleTankDefeated=true;
        });
        bus.addListener(this::drops);
        bus.addListener(this::experience);
        bus.addListener(this::logout);
        bus.addListener(this::login);
        bus.addListener(this::entityJoin);
        bus.addListener((PlayerInteractEvent.RightClickBlock event)->{
            if(!(event.getEntity() instanceof ServerPlayer p))return;
            // Beds are campaign scenery. This dimension cannot sleep: vanilla use
            // would explode the bed, damage players and destroy the restored map.
            if(inCampaign(p)&&p.level().getBlockState(event.getPos()).getBlock() instanceof net.minecraft.world.level.block.BedBlock){
                event.setCanceled(true);event.setCancellationResult(InteractionResult.SUCCESS);return;
            }
            if(event.getHand()!=net.minecraft.world.InteractionHand.MAIN_HAND)return;
            var s=session(p.getUUID());
            if(s==null||!s.prepared.contains(p.getUUID())||!p.level().dimension().equals(s.map.dimension()))return;
            if(s.checkpointDoors.interact(p.serverLevel(),event.getPos(),s.map,s.section,s.phase==OutbreakSession.Phase.START_ROOM||s.phase==OutbreakSession.Phase.RUNNING)){
                event.setCanceled(true);event.setCancellationResult(InteractionResult.SUCCESS);
                if(s.phase==OutbreakSession.Phase.RUNNING)tell(p,"安全屋需要所有存活队友进入、救起倒地队友后关门。");
            }
        });
        bus.addListener((PlayerInteractEvent.EntityInteract event)->{
            if(event.getEntity() instanceof ServerPlayer p&&interactSupply(p,event.getTarget())){
                event.setCanceled(true);event.setCancellationResult(InteractionResult.SUCCESS);
            }
        });
        bus.addListener((PlayerInteractEvent.EntityInteractSpecific event)->{
            if(event.getEntity() instanceof ServerPlayer p&&interactSupply(p,event.getTarget())){
                event.setCanceled(true);event.setCancellationResult(InteractionResult.SUCCESS);
            }
        });
        bus.addListener((ItemEntityPickupEvent.Pre event)->{
            if(event.getPlayer() instanceof ServerPlayer p&&inCampaign(p))event.setCanPickup(TriState.FALSE);
        });
        bus.addListener((LivingHealEvent event)->{
            if(event.getEntity() instanceof ServerPlayer p&&inCampaign(p))event.setCanceled(true);
        });
        bus.addListener((ProjectileImpactEvent event)->{
            if(event.getProjectile().level().isClientSide())return;
            for(var session:sessions)if(session.throwables.impact(event.getProjectile().getUUID(),event.getRayTraceResult(),server.getTickCount())){
                event.setCanceled(true);break;
            }
        });
        bus.addListener((com.tacz.guns.api.event.common.EntityHurtByGunEvent.Post event)->{
            if(event.getLogicalSide()==net.neoforged.fml.LogicalSide.SERVER)explosiveImpact(event.getBullet(),event.getHurtEntity().position());
        });
        bus.addListener((com.tacz.guns.api.event.common.EntityKillByGunEvent event)->{
            if(event.getLogicalSide()==net.neoforged.fml.LogicalSide.SERVER)explosiveImpact(event.getBullet(),event.getKilledEntity().position());
        });
        bus.addListener((com.tacz.guns.api.event.server.AmmoHitBlockEvent event)->explosiveImpact(event.getAmmo(),event.getHitResult().getLocation()));
        bus.addListener(EventPriority.HIGHEST, (ItemTossEvent event) -> {
            if (!(event.getPlayer() instanceof ServerPlayer player)) return;
            OutbreakSession session=session(player.getUUID());
            if (session==null || !session.prepared.contains(player.getUUID())) return;
            // Vanilla has already removed exactly this count. Convert it once to session-owned finite stock.
            event.setCanceled(true);ItemStack removed=event.getEntity().getItem().copy();
            if(!session.alive.contains(player.getUUID())||session.downed.contains(player.getUUID())||!player.level().dimension().equals(session.map.dimension())||!session.supplies.drop(player,removed)){
                player.getInventory().add(removed);tell(player,"当前不能丢下装备");
            }
            player.containerMenu.broadcastChanges();
        });
        bus.addListener((BlockEvent.BreakEvent event) -> {
            if (event.getPlayer().level().dimension().location().toString().equals("muxi_outbreak:campaign")) event.setCanceled(true);
        });
        bus.addListener(EventPriority.LOWEST,(ExplosionEvent.Detonate event) -> {
            // Preserve damage/knockback from native TaCZ and LR explosives. Only terrain is protected.
            if (event.getLevel().dimension().location().toString().equals("muxi_outbreak:campaign")) event.getAffectedBlocks().clear();
        });
    }

    private void started(ServerStartedEvent event) {
        server = event.getServer();
        ACTIVE.put(server,this);runtime().register(this);
        maps = OutbreakMapLoader.load(server);
        geometry = new GeometryInstaller(server);
        MuxiOutbreak.LOG.info("Loaded {} outbreak maps: {}", maps.size(), maps.keySet());
    }

    private void stopping(ServerStoppingEvent event) {
        if (event.getServer() != server) return;
        for (OutbreakSession session : List.copyOf(sessions)) finish(session, false, "服务器停止，本局已安全结束");
        sessions.clear();
        maps = Map.of();
        ACTIVE.remove(server);server = null;
        geometry = null;
    }

    private void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("muxioutbreak")
            .then(Commands.literal("list").executes(context -> {
                context.getSource().sendSuccess(() -> Component.literal(maps.isEmpty() ? "没有可用地图" : "地图：" + String.join(", ", maps.keySet())), false);
                return maps.size();
            }))
            .then(Commands.literal("prepare").requires(source -> source.hasPermission(2))
                .then(Commands.argument("map", StringArgumentType.word()).executes(context -> {
                    OutbreakMap map = maps.get(StringArgumentType.getString(context,"map"));
                    require(map != null, "地图不存在");
                    geometry.request(map);
                    context.getSource().sendSuccess(() -> Component.literal(geometry.progress(map)),false);
                    return geometry.ready(map) ? 1 : 0;
                })))
            .then(Commands.literal("inspect").requires(source -> source.hasPermission(2))
                .then(Commands.argument("map", StringArgumentType.word()).executes(context -> {
                    String id=StringArgumentType.getString(context,"map");
                    OutbreakMap map=maps.get(id);
                    require(map!=null,"地图不存在");
                    var json=new com.google.gson.JsonObject();
                    json.addProperty("map",id);json.addProperty("geometryReady",geometry.ready(map));
                    json.addProperty("geometry",geometry.progress(map));
                    var session=sessions.stream().filter(s->s.map.id().equals(id)).findFirst().orElse(null);
                    if(session!=null) {
                        json.addProperty("session",session.shortId());json.addProperty("phase",session.phase.name());
                        json.addProperty("section",session.section);json.addProperty("players",session.players.size());
                        json.addProperty("alive",session.alive.size());json.addProperty("downed",session.downed.size());
                        json.addProperty("seconds",session.seconds);
                        json.addProperty("directorEnabled",session.directorEnabled);json.addProperty("directorPace",session.director.pace().name());
                        json.addProperty("worldDifficulty",server.overworld().getDifficulty().name());
                        json.addProperty("spawnAttempts",session.spawnAttempts);json.addProperty("spawnSuccesses",session.spawnSuccesses);
                        json.addProperty("spawnInactive",session.spawnInactive);json.addProperty("spawnDistance",session.spawnDistance);json.addProperty("spawnCollision",session.spawnCollision);
                        json.addProperty("tickingInfected",session.infected.stream().map(levelId->server.getLevel(session.map.dimension()).getEntity(levelId)).filter(e->e!=null&&e.isAlive()&&server.getLevel(session.map.dimension()).isPositionEntityTicking(e.blockPosition())).count());
                        json.addProperty("panicEvents",session.triggeredPanics.size());
                        json.addProperty("finaleWaves",session.finaleWaves);json.addProperty("finaleTankSpawned",session.finaleTankSpawned);json.addProperty("finaleTankDefeated",session.finaleTankDefeated);
                        var infected=new com.google.gson.JsonObject();
                        for(var kind:InfectedKind.values()) infected.addProperty(kind.name(),session.infectedKinds.values().stream().filter(k->k==kind).count());
                        json.add("infected",infected);
                    }
                    context.getSource().sendSuccess(()->Component.literal(json.toString()),false);
                    return 1;
                })))
            .then(Commands.literal("leave").executes(context -> {
                ServerPlayer player=context.getSource().getPlayerOrException();
                OutbreakSession session=requireSession(player);
                if (session.host.equals(player.getUUID())) finish(session,false,"房主离开了游戏");
                else leavePlayer(session,player);
                return 1;
            }))
            .then(Commands.literal("supplies").executes(context->{
                ServerPlayer p=context.getSource().getPlayerOrException();
                var rows=requireSession(p).supplies.inspect();
                for(var row:rows)context.getSource().sendSuccess(()->Component.literal(row.toString()),false);
                return rows.size();
            }))
            .then(Commands.literal("supply").then(Commands.argument("id",StringArgumentType.word()).executes(context->{
                ServerPlayer p=context.getSource().getPlayerOrException();
                tell(p,requireSession(p).supplies.take(p,StringArgumentType.getString(context,"id"),p.isCrouching()));return 1;
            })))
            .then(Commands.literal("status").executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                status(player);
                return 1;
            }))
            .then(Commands.literal("start")
                .then(Commands.argument("map", StringArgumentType.word()).executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    start(player, StringArgumentType.getString(context, "map"), null, 1);
                    return 1;
                })))
            .then(Commands.literal("join")
                .then(Commands.argument("session", StringArgumentType.word()).executes(context -> {
                    join(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "session"));
                    return 1;
                })))
            .then(Commands.literal("stop").executes(context -> {
                stop(context.getSource().getPlayerOrException(), "房主结束了游戏");
                return 1;
            }))
            .then(Commands.literal("director").requires(source -> source.hasPermission(2))
                .then(Commands.argument("action", StringArgumentType.word()).executes(context -> {
                    director(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "action"));
                    return 1;
                })))
            .then(Commands.literal("spawn").requires(source -> source.hasPermission(2))
                .then(Commands.argument("kind", StringArgumentType.word()).executes(context -> {
                    manualSpawn(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "kind"));
                    return 1;
                })));
        event.getDispatcher().register(root);
        Left2MineCompatCommands.register(event.getDispatcher(), this);
    }

    public void start(ServerPlayer host, String mapId, OutbreakMap.Mode overrideMode, int difficulty) {
        createRoom(host,mapId,overrideMode,difficulty,false);
    }

    private void createRoom(ServerPlayer host,String mapId,OutbreakMap.Mode overrideMode,int difficulty,boolean waiting) {
        require(server != null, "服务器尚未准备好");
        require(session(host.getUUID()) == null, "你已经在一局游戏中");
        PlayerSnapshot.checkEligible(host);
        OutbreakMap map = maps.get(mapId);
        require(map != null, "不存在地图：" + mapId);
        require(sessions.stream().noneMatch(s -> s.map.id().equals(mapId)), "这张地图已有队伍进行中，请加入该房间");
        require(server.getLevel(map.dimension()) != null, "地图维度未加载：" + map.dimension().location());
        require(host.gameMode.getGameModeForPlayer() == GameType.SURVIVAL, "请先切换到生存模式");
        OutbreakSession session = new OutbreakSession(
            host.getUUID(), map, overrideMode == null ? map.mode() : overrideMode, difficulty, server.getTickCount()
        );
        session.lobbyWaiting=waiting;
        if(waiting)session.phase=OutbreakSession.Phase.WAITING;
        runtime().join(gameId(),session.team,host,4);session.alive.add(host.getUUID());
        sessions.add(session);
        try {
            geometry.request(map);
            if (!waiting) {
                if (geometry.ready(map)) {session.checkpointDoors.install(server.getLevel(map.dimension()),map);preparePlayer(session,host);}
                else session.phase=OutbreakSession.Phase.PREPARING;
            }
            notice(session, waiting?"已创建等待房间 "+session.shortId()+"；邀请队友后由房主点击开始。":"房间 "+session.shortId()+"；地图准备完成后倒计时10秒。");
        } catch (RuntimeException error) {
            finish(session,false,"地图准备失败");
            throw error;
        }
    }

    private void startWaiting(ServerPlayer host) {
        OutbreakSession room=requireSession(host);
        require(room.host.equals(host.getUUID()),"Only the host can start this room");
        require(room.lobbyWaiting&&room.phase==OutbreakSession.Phase.WAITING,"Room is not waiting");
        require(geometry.ready(room.map),"Map is still preparing; wait and try again");
        require(!room.players.isEmpty()&&room.players.size()<=4,"Invalid team size");
        List<ServerPlayer> members=new ArrayList<>();
        for(UUID id:room.players) {
            ServerPlayer member=server.getPlayerList().getPlayer(id);
            require(member!=null&&member.connection!=null&&member.connection.isAcceptingMessages(),"A member is offline");
            runtime().requireParticipation(member);
            PlayerSnapshot.checkEligible(member);
            CampaignInventory.validate(member);
            require(member.gameMode.getGameModeForPlayer()==GameType.SURVIVAL,"All members must be in survival mode");
            members.add(member);
        }
        try {
            room.checkpointDoors.install(server.getLevel(room.map.dimension()),room.map);
            for(ServerPlayer member:members)preparePlayer(room,member);
            room.lobbyWaiting=false;
            room.phase=OutbreakSession.Phase.COUNTDOWN;
            room.timer=server.getTickCount()+200;
            notice(room,"Team ready; game starts in 10 seconds");
        } catch(RuntimeException failure) {
            finish(room,false,"Start failed; original player states restored");
            throw failure;
        }
    }

    public void join(ServerPlayer player, String shortId) {
        require(session(player.getUUID()) == null, "你已经在一局游戏中");
        PlayerSnapshot.checkEligible(player);
        CampaignInventory.validate(player);
        OutbreakSession session = sessions.stream()
            .filter(s -> s.shortId().equalsIgnoreCase(shortId))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("房间不存在"));
        runtime().social.requireRoomJoin(session.team,player);
        require(session.phase == OutbreakSession.Phase.WAITING || (!session.lobbyWaiting&&(session.phase == OutbreakSession.Phase.COUNTDOWN || session.phase == OutbreakSession.Phase.PREPARING)), "游戏已经开始");
        require(session.players.size() < 4, "房间已满");
        require(player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL, "请先切换到生存模式");
        runtime().join(gameId(),session.team,player,4);
        session.alive.add(player.getUUID());session.team.invites.remove(player.getUUID());
        try {if (!session.lobbyWaiting&&session.phase != OutbreakSession.Phase.PREPARING) preparePlayer(session, player);}
        catch(RuntimeException failure){PlayerSnapshot.restore(player);runtime().memberships.leave(session.team,player.getUUID());session.prepared.remove(player.getUUID());session.originalModes.remove(player.getUUID());session.returnPoints.remove(player.getUUID());throw failure;}
        notice(session, player.getDisplayName().getString() + " 加入了房间");
    }

    public void stop(ServerPlayer requester, String reason) {
        OutbreakSession session = requireSession(requester);
        require(session.host.equals(requester.getUUID()) || requester.hasPermissions(2), "只有房主或管理员可以结束本局");
        finish(session, false, reason);
    }

    public void win(ServerPlayer requester) {
        require(requester.hasPermissions(2), "只有管理员可跳过战役验收条件");
        OutbreakSession session = requireSession(requester);
        require(session.host.equals(requester.getUUID()) || requester.hasPermissions(2), "只有房主或管理员可以结束本局");
        finish(session, true, "战役完成");
    }

    public void panicStart(ServerPlayer requester, int waves, int delayTicks) {
        require(requester.hasPermissions(2), "只有管理员可以手动触发事件");
        OutbreakSession session = requireSession(requester);
        session.panicWaves = waves == 0 ? 1 : waves;
        session.panicDelay = Math.max(0, delayTicks);
        if (session.panicDelay == 0) session.director.forcePanic(true);
        notice(session, "Panic Event 已启动" + (waves < 0 ? "（持续）" : "，波数 " + session.panicWaves));
    }

    public void panicStop(ServerPlayer requester) {
        require(requester.hasPermissions(2), "只有管理员可以手动停止事件");
        OutbreakSession session = requireSession(requester);
        session.panicWaves = 0;
        session.panicDelay = 0;
        session.director.forcePanic(false);
        notice(session, "Panic Event 已停止");
    }

    public void director(ServerPlayer requester, String action) {
        require(requester.hasPermissions(2), "只有管理员可以控制 Director");
        OutbreakSession session = requireSession(requester);
        switch (action.toLowerCase(Locale.ROOT)) {
            case "enable" -> {
                session.directorEnabled = true;
                notice(session, "AI Director 已启用");
            }
            case "disable" -> {
                session.directorEnabled = false;
                session.director.forcePanic(false);
                notice(session, "AI Director 已停用；手动 spawn 仍可用");
            }
            default -> throw new IllegalArgumentException("director 仅支持 enable / disable");
        }
    }

    public void legacyDirector(ServerPlayer requester, String action, String kind) {
        if ("spawn".equalsIgnoreCase(action)) {
            manualSpawn(requester, kind == null || kind.isBlank() ? "common" : kind);
        } else {
            director(requester, action);
        }
    }

    public void manualSpawn(ServerPlayer requester, String rawKind) {
        require(requester.hasPermissions(2), "只有管理员可以手动刷怪");
        OutbreakSession session = requireSession(requester);
        String normalized = rawKind.toLowerCase(Locale.ROOT);
        if (normalized.equals("horde")) {
            for (int i = 0; i < 8 + session.alive.size() * 3; i++) spawn(session, InfectedKind.COMMON, true);
            return;
        }
        if (normalized.equals("special")) {
            spawn(session, randomSpecial(), true);
            return;
        }
        if (normalized.equals("boss")) {
            spawn(session, random.nextBoolean() ? InfectedKind.TANK : InfectedKind.WITCH, true);
            return;
        }
        spawn(session, InfectedKind.parse(normalized), true);
    }

    public String defaultMapId() {
        if (maps.containsKey("lostschool")) return "lostschool";
        if (maps.containsKey("metro_escape")) return "metro_escape";
        return maps.keySet().stream().findFirst().orElseThrow(() -> new IllegalArgumentException("没有可用地图"));
    }

    public static int parseDifficulty(String value) {
        if (value == null || value.isBlank()) return 1;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "0", "easy" -> 0;
            case "1", "normal" -> 1;
            case "2", "hard" -> 2;
            case "3", "expert" -> 3;
            default -> throw new IllegalArgumentException("难度应为 Easy/Normal/Hard/Expert 或 0-3");
        };
    }

    private void tick(ServerTickEvent.Post event) {
        if (server == null || event.getServer() != server) return;
        ticks++;
        geometry.tick();
        int now = server.getTickCount();
        for (OutbreakSession session : List.copyOf(sessions)) {
            try {
                tickSession(session, now);
            } catch (RuntimeException error) {
                MuxiOutbreak.LOG.error("Outbreak session {} failed", session.shortId(), error);
                finish(session, false, "本局发生异常，已安全结束");
            }
        }
    }

    private void tickSession(OutbreakSession session, int now) {
        ServerLevel level = server.getLevel(session.map.dimension());
        if (level == null) {
            finish(session, false, "地图维度已卸载");
            return;
        }
        session.alive.removeIf(id -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            return player == null;
        });
        if (session.alive.isEmpty()) {
            finish(session, false, "队伍全灭");
            return;
        }
        if (session.phase == OutbreakSession.Phase.WAITING) {
            if (geometry.error(session.map)!=null)finish(session,false,"Map preparation failed: "+geometry.error(session.map));
            return; // No loadout, teleport, timer, supplies or director while the APP room waits.
        }
        if (session.phase == OutbreakSession.Phase.PREPARING) {
            if (geometry.error(session.map)!=null) { finish(session,false,"地图安装失败："+geometry.error(session.map)); return; }
            if (geometry.ready(session.map)) {
                session.checkpointDoors.install(level,session.map);
                for (ServerPlayer player : alivePlayers(session)) preparePlayer(session,player);
                session.phase=OutbreakSession.Phase.COUNTDOWN;
                session.timer=now+200;
                notice(session,"真实战役地图已就绪，10秒后开始");
            } else if (ticks%100==0) notice(session,"准备地图："+geometry.progress(session.map));
            return;
        }
        if (session.alive.stream().allMatch(session.downed::contains)) {
            finish(session,false,"所有幸存者已倒地，队伍全灭");
            return;
        }
        if (alivePlayers(session).stream().anyMatch(p -> !p.level().dimension().equals(session.map.dimension()))) {
            finish(session,false,"玩家离开战役维度，已恢复原有状态");
            return;
        }
        for(UUID id:session.prepared){
            ServerPlayer p=server.getPlayerList().getPlayer(id);if(p==null)continue;
            if(p.containerMenu!=p.inventoryMenu)p.closeContainer();
            CampaignInventory.normalize(p,stack->session.supplies.drop(p,stack));
            session.explosiveRounds.put(id,CampaignInventory.explosiveRounds(p.getInventory().getItem(0)));
            p.getFoodData().setFoodLevel(20);p.getFoodData().setSaturation(0);
            float temporary=session.temporaryHealth.getOrDefault(id,0f);
            if(temporary>0&&!session.downed.contains(id)&&session.alive.contains(id)){
                float loss=Math.min(temporary,Math.max(0,p.getHealth()-1));
                loss=Math.min(loss,p.getMaxHealth()*.0027f/20f);
                p.setHealth(p.getHealth()-loss);session.temporaryHealth.put(id,Math.max(0,temporary-loss));
            }
        }
        session.supplies.tick(level,alivePlayers(session),now);
        session.throwables.tick(level,now);
        if(now%20==0)session.equipmentEntities.removeIf(id->level.getEntity(id)==null||level.getEntity(id).isRemoved());
        if (session.phase == OutbreakSession.Phase.COUNTDOWN) {
            if(session.lobbyWaiting)return;
            if (now >= session.timer) {
                session.phase = session.mode==OutbreakMap.Mode.CAMPAIGN&&session.map.startRoom(session.section)!=null?OutbreakSession.Phase.START_ROOM:OutbreakSession.Phase.RUNNING;
                notice(session,"开门离开起点安全屋后开始推进；终点全员进入后关门过关。");
                notice(session, session.mode == OutbreakMap.Mode.SURVIVAL ? "生存模式开始" : "战役开始");
            }
            return;
        }
        if(session.phase==OutbreakSession.Phase.START_ROOM) {
            var startRoom=session.map.startRoom(session.section);
            if(startRoom==null||alivePlayers(session).stream().anyMatch(p->!startRoom.contains(p.position()))) {
                session.phase=OutbreakSession.Phase.RUNNING;session.director.reset();
                notice(session,"已离开起点安全屋，导演开始运行。");
            }
            return;
        }
        if (session.phase == OutbreakSession.Phase.SAFE_ROOM) {
            reviveTick(session, now);
            if (now >= session.timer) {
                for (UUID id : session.players) {
                    ServerPlayer player=server.getPlayerList().getPlayer(id);
                    if (player==null) continue;
                    boolean wasDead=!session.alive.contains(id);
                    session.alive.add(id);
                    player.setGameMode(GameType.ADVENTURE);
                    player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    player.removeEffect(MobEffects.WEAKNESS);
                    if(wasDead){
                        player.setHealth(player.getMaxHealth()*.5f);session.temporaryHealth.remove(id);
                        session.incapCount.remove(id);session.eliminatedAt.remove(id);
                    }
                    player.getFoodData().setFoodLevel(20);
                    teleportToSection(session,player);
                }
                session.downed.clear();session.downedSince.clear();session.reviveProgress.clear();
                session.phase = session.map.startRoom(session.section)!=null?OutbreakSession.Phase.START_ROOM:OutbreakSession.Phase.RUNNING;
                session.director.reset();
                notice(session, "安全屋开启，继续推进");
            }
            return;
        }
        if (session.phase != OutbreakSession.Phase.RUNNING) return;

        reviveTick(session, now);
        cleanupInfected(session, level);
        if (ticks % 5 == 0) controlSpecials(session, level, now);

        if (session.mode == OutbreakMap.Mode.CAMPAIGN) {
            OutbreakMap.SafeRoom safeRoom = session.map.safeRooms().stream()
                .filter(room -> room.nextSection() == session.section + 1)
                .findFirst().orElse(null);
            if (safeRoom != null && allAliveInside(session, safeRoom) && session.checkpointDoors.closed(level,safeRoom)) {
                session.section = safeRoom.nextSection();
                session.phase = OutbreakSession.Phase.SAFE_ROOM;
                session.timer = now + 160;
                session.panicWaves=0;session.panicDelay=0;session.director.forcePanic(false);
                discardInfected(session, level);
                session.throwables.cleanup();
                discardEquipment(session,level);
                notice(session, "全员进入安全屋 · 8 秒后继续");
                return;
            }
            if (safeRoom == null && session.map.finale()!=null) {
                if (finaleTick(session,now)) return;
            } else if (safeRoom == null && allAliveNear(session, session.map.finish(), 5.5)) {
                finish(session, true, "全员抵达撤离点");
                return;
            }
        }

        if (ticks % 20 != 0) return;
        session.seconds++;
        campaignEvents(session);
        for (ServerPlayer player:alivePlayers(session)) player.displayClientMessage(Component.literal(
            session.map.sectionTitle(session.section)+" · 幸存者 "+session.alive.size()+" · 感染者 "+session.infected.size()
            +(session.finaleStarted>=0 ? " · 撤离坚守 "+Math.max(0,session.map.finale().holdSeconds()-(now-session.finaleStarted)/20)+"秒" : "")),true);
        if (session.panicDelay > 0) {
            session.panicDelay -= 20;
            if (session.panicDelay <= 0 && session.panicWaves != 0) session.director.forcePanic(true);
        }
        if (!session.directorEnabled) return;

        Director.Sample sample = sample(session, level);
        Director.Decision decision = session.director.tick(sample, random);
        int commonAlive = (int) session.infectedKinds.values().stream().filter(k -> k == InfectedKind.COMMON).count();
        int needed = Math.min(4, Math.max(0, decision.desiredCommon() - commonAlive));
        for (int i = 0; i < needed; i++) spawn(session, InfectedKind.COMMON, false);
        if (decision.hordePulse()) {
            int count = 6 + session.alive.size() * 4;
            for (int i = 0; i < count; i++) spawn(session, InfectedKind.COMMON, true);
            if (session.panicWaves > 0 && --session.panicWaves == 0) session.director.forcePanic(false);
        }
        if (decision.special()) spawn(session, randomSpecial(), false);
        if (decision.boss()) spawn(session, random.nextBoolean() ? InfectedKind.TANK : InfectedKind.WITCH, false);
        session.recentDamage.replaceAll((id, damage) -> damage * 0.35);
    }

    private Director.Sample sample(OutbreakSession session, ServerLevel level) {
        List<ServerPlayer> players = alivePlayers(session);
        double avgHealth = players.stream()
            .mapToDouble(p -> p.getHealth() / Math.max(1.0, p.getMaxHealth()))
            .average().orElse(0);
        double damage = players.stream()
            .mapToDouble(p -> session.recentDamage.getOrDefault(p.getUUID(), 0d) / Math.max(1.0, p.getMaxHealth()))
            .average().orElse(0);
        double maxDistance = 0;
        for (int i = 0; i < players.size(); i++) for (int j = i + 1; j < players.size(); j++) {
            maxDistance = Math.max(maxDistance, Math.sqrt(players.get(i).distanceToSqr(players.get(j))));
        }
        double separation = Math.min(1.0, maxDistance / 32.0);
        double sectionProgress=0;
        if(session.section<session.map.chapters().size()){
            var route=session.map.chapters().get(session.section).route();
            if(route.size()>1&&!players.isEmpty()){
                double sum=0;
                for(var player:players){
                    int closest=0;double distance=Double.MAX_VALUE;
                    for(int i=0;i<route.size();i++){
                        double d=player.distanceToSqr(route.get(i).getCenter());if(d<distance){distance=d;closest=i;}
                    }
                    sum+=closest/(double)(route.size()-1);
                }sectionProgress=sum/players.size();
            }
        }
        double progress = session.mode == OutbreakMap.Mode.SURVIVAL
            ? Math.min(1.0, session.seconds / 600.0)
            : Math.min(1.0, (session.section+sectionProgress) / Math.max(1,session.map.safeRooms().size()+1));
        return new Director.Sample(
            players.size(), avgHealth, damage, separation, progress, session.infected.size(), session.downed.size()
        );
    }

    private boolean spawn(OutbreakSession session, InfectedKind kind, boolean horde) {
        ServerLevel level = server.getLevel(session.map.dimension());
        session.spawnAttempts++;
        if (level == null || session.infected.size() >= 96) return false;
        List<OutbreakMap.Spawn> points = kind.boss()
            ? session.map.bossFor(session.section)
            : horde ? session.map.hordeFor(session.section) : session.map.commonFor(session.section);
        if (points.isEmpty()) points = session.map.hordeFor(session.section);
        if (points.isEmpty()) return false; // Never invent unvalidated coordinates in a converted map.
        Mob mob = InfectedFactory.create(level, kind, session.id, session.alive.size(), session.difficulty);
        List<OutbreakMap.Spawn> candidates=new ArrayList<>(points);
        Collections.shuffle(candidates,random);
        BlockPos position=null;
        for (var point:candidates) {
            double distance=alivePlayers(session).stream().mapToDouble(p -> p.distanceToSqr(point.pos().getCenter())).min().orElse(Double.MAX_VALUE);
            if (distance<36 || distance>40*40 || alivePlayers(session).stream().noneMatch(p->p.distanceToSqr(point.pos().getCenter())<=40*40&&Math.abs(p.getY()-point.pos().getY())<=4)) {session.spawnDistance++;continue;}
            if(!level.isPositionEntityTicking(point.pos())) {session.spawnInactive++;continue;}
            if(session.map.safeRooms().stream().anyMatch(r->r.contains(point.pos().getBottomCenter()))||session.map.startRooms().stream().anyMatch(r->r.contains(point.pos().getBottomCenter())))continue;
            for (int attempt=0;attempt<5;attempt++) {
                BlockPos p=attempt==0?point.pos():point.pos().offset(random.nextInt(3)-1,0,random.nextInt(3)-1);
                if(!level.isPositionEntityTicking(p))continue;
                mob.moveTo(p.getX()+.5,p.getY(),p.getZ()+.5,random.nextFloat()*360,0);
                if (level.getBlockState(p.below()).getCollisionShape(level,p.below()).isEmpty()) continue;
                if (!level.getFluidState(p).isEmpty() || !level.noCollision(mob,mob.getBoundingBox())) continue;
                position=p;break;
            }
            if (position!=null) break;
            session.spawnCollision++;
        }
        if (position==null) return false;
        ServerPlayer target = nearest(session, mob.position());
        if (target != null) mob.setTarget(target);
        session.infected.add(mob.getUUID());session.infectedKinds.put(mob.getUUID(),kind);
        if (level.addFreshEntity(mob)) {
            session.spawnSuccesses++;
            if(kind==InfectedKind.TANK&&session.finaleStarted>=0&&!session.finaleTankSpawned){session.finaleTankId=mob.getUUID();session.finaleTankDefeated=false;}
            if (kind.special()) MuxiOutbreak.LOG.info("OUTBREAK_SPAWN session={} kind={} pos={}",session.shortId(),kind,position);
            return true;
        }
        session.infected.remove(mob.getUUID());session.infectedKinds.remove(mob.getUUID());
        return false;
    }

    private void controlSpecials(OutbreakSession session, ServerLevel level, int now) {
        for (UUID id : Set.copyOf(session.infected)) {
            Entity entity = level.getEntity(id);
            if (!(entity instanceof Mob mob) || !mob.isAlive()) continue;
            InfectedKind kind = session.infectedKinds.getOrDefault(id, InfectedKind.COMMON);
            if(kind==InfectedKind.COMMON&&session.throwables.isDistracted(mob,now))continue;
            ServerPlayer target = nearest(session, mob.position());
            if (target != null) mob.setTarget(target);
            if (SpecialInfectedController.tick(mob, kind, target, now)) {
                for (int i = 0; i < 5; i++) spawn(session, InfectedKind.COMMON, true);
                mob.discard();
            }
        }
    }

    private void cleanupInfected(OutbreakSession session, ServerLevel level) {
        for (UUID id : Set.copyOf(session.infected)) {
            Entity entity = level.getEntity(id);
            boolean abandonedCommon=entity!=null&&session.infectedKinds.get(id)==InfectedKind.COMMON&&alivePlayers(session).stream().noneMatch(p->p.distanceToSqr(entity)<=48*48&&Math.abs(p.getY()-entity.getY())<=6);
            if (entity == null || !entity.isAlive() || !level.isPositionEntityTicking(entity.blockPosition()) || abandonedCommon) {
                if(id.equals(session.finaleTankId)&&!session.finaleTankDefeated){session.finaleTankSpawned=false;session.finaleTankId=null;}
                if(entity!=null)entity.discard();
                session.infected.remove(id);
                session.infectedKinds.remove(id);
            }
        }
    }

    private void discardInfected(OutbreakSession session, ServerLevel level) {
        for (UUID id : Set.copyOf(session.infected)) {
            Entity entity = level.getEntity(id);
            if (entity != null) entity.discard();
        }
        session.infected.clear();
        session.infectedKinds.clear();
    }

    private void preparePlayer(OutbreakSession session, ServerPlayer player) {
        if (session.prepared.contains(player.getUUID())) return;
        CampaignInventory.validate(player);
        session.originalModes.putIfAbsent(player.getUUID(), player.gameMode.getGameModeForPlayer());
        session.returnPoints.putIfAbsent(player.getUUID(), new OutbreakSession.ReturnPoint(
            player.level().dimension(), player.position(), player.getYRot(), player.getXRot()
        ));
        runtime().requireParticipation(player);
        PlayerSnapshot.capture(player);
        session.prepared.add(player.getUUID());
        PlayerSnapshot.kit(player);
        teleportToSection(session,player);
    }

    private void teleportToSection(OutbreakSession session,ServerPlayer player) {
        ServerLevel level=server.getLevel(session.map.dimension());
        if (level==null) throw new IllegalArgumentException("地图维度未加载");
        BlockPos start=session.map.sectionStart(session.section);
        var box=player.getBoundingBox().move(start.getX()+.5-player.getX(),start.getY()-player.getY(),start.getZ()+.5-player.getZ());
        if (!level.noCollision(player,box)) throw new IllegalStateException("战役出生点被阻挡："+start);
        player.teleportTo(level,start.getX()+.5,start.getY(),start.getZ()+.5,0,0);
        player.fallDistance=0;
    }

    private void campaignEvents(OutbreakSession session) {
        for (int i=0;i<session.map.panicEvents().size();i++) {
            var event=session.map.panicEvents().get(i);
            if (event.section()!=session.section || session.triggeredPanics.contains(i)) continue;
            if (alivePlayers(session).stream().noneMatch(p->p.distanceToSqr(event.pos().getCenter())<36)) continue;
            session.triggeredPanics.add(i);session.panicWaves=event.waves();session.director.forcePanic(true);
            notice(session,"尸潮事件触发：守住路线，继续向安全屋推进");
        }
    }

    private boolean finaleTick(OutbreakSession session,int now) {
        var finale=session.map.finale();
        if (session.finaleStarted<0) {
            if (!allAliveNear(session,finale.pos(),finale.radius())) return false;
            session.finaleStarted=now;session.director.forcePanic(true);
            notice(session,"已呼叫救援，坚守 "+finale.holdSeconds()+" 秒并击败坦克");
        }
        int elapsed=now-session.finaleStarted;
        int due=Math.min(finale.waves(),1+elapsed/Math.max(20,finale.holdSeconds()*20/finale.waves()));
        while (session.finaleWaves<due) {
            session.finaleWaves++;
            for (int i=0;i<8+session.alive.size()*3;i++) spawn(session,InfectedKind.COMMON,true);
            spawn(session,randomSpecial(),true);
            notice(session,"撤离尸潮 "+session.finaleWaves+"/"+finale.waves());
        }
        if (!session.finaleTankSpawned && (session.finaleWaves>=2 || finale.waves()==1) && ticks%20==0)
            session.finaleTankSpawned=spawn(session,InfectedKind.TANK,true);
        boolean bossAlive=session.infectedKinds.values().stream().anyMatch(InfectedKind::boss);
        if (elapsed>=finale.holdSeconds()*20 && session.finaleTankSpawned && session.finaleTankDefeated && !bossAlive && allAliveNear(session,session.map.finish(),finale.radius())) {
            finish(session,true,"三章战役完成，全员成功撤离");return true;
        }
        return false;
    }

    private void reviveTick(OutbreakSession session, int now) {
        for (UUID downedId : Set.copyOf(session.downed)) {
            ServerPlayer downed = server.getPlayerList().getPlayer(downedId);
            if (downed == null) continue;
            if (now - session.downedSince.getOrDefault(downedId, now) >= 600) {
                eliminate(session, downed, "失血过多");
                continue;
            }
            ServerPlayer reviver = alivePlayers(session).stream()
                .filter(p -> !p.getUUID().equals(downedId))
                .filter(p -> !session.downed.contains(p.getUUID()))
                .filter(ServerPlayer::isCrouching)
                .filter(p -> p.distanceToSqr(downed) <= 7.0)
                .findFirst().orElse(null);
            if (reviver == null) {
                session.reviveProgress.put(downedId, 0);
                continue;
            }
            int progress = session.reviveProgress.merge(downedId, 1, Integer::sum);
            int needed=session.adrenalineUntil.getOrDefault(reviver.getUUID(),0)>now?60:100;
            if (progress % 20 == 0) {
                reviver.displayClientMessage(Component.literal("救援 " + (progress / 20) + "/"+needed/20), true);
            }
            if (progress >= needed) {
                session.downed.remove(downedId);
                session.downedSince.remove(downedId);
                session.reviveProgress.remove(downedId);
                downed.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                downed.removeEffect(MobEffects.WEAKNESS);
                float health=Math.max(1,downed.getMaxHealth()*.3f);
                downed.setHealth(health);session.temporaryHealth.put(downedId,Math.max(0,health-1));
                notice(session, reviver.getDisplayName().getString() + " 救起了 " + downed.getDisplayName().getString());
            }
        }
    }

    private void death(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        OutbreakSession session = session(player.getUUID());
        if (session == null || !session.prepared.contains(player.getUUID()) || session.phase == OutbreakSession.Phase.FINISHED) return;
        event.setCanceled(true);
        player.setHealth(1);
        if (session.downed.contains(player.getUUID()) || session.incapCount.getOrDefault(player.getUUID(), 0) >= 2) {
            eliminate(session, player, "无法继续战斗");
            return;
        }
        session.incapCount.merge(player.getUUID(), 1, Integer::sum);
        session.temporaryHealth.remove(player.getUUID());
        session.downed.add(player.getUUID());
        session.downedSince.put(player.getUUID(), server.getTickCount());
        session.reviveProgress.put(player.getUUID(), 0);
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 40, 9));
        player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 20 * 40, 4));
        notice(session, player.getDisplayName().getString() + " 倒地；队友蹲下靠近 5 秒可救援，肾上腺素加速至3秒");
    }

    private void incomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            OutbreakSession session = session(player.getUUID());
            if (session != null && session.prepared.contains(player.getUUID()) &&
                (session.downed.contains(player.getUUID()) || session.phase == OutbreakSession.Phase.COUNTDOWN || session.phase == OutbreakSession.Phase.START_ROOM || session.phase == OutbreakSession.Phase.SAFE_ROOM)) {
                event.setCanceled(true);
                return;
            }
        }
        String victimSession = event.getEntity().getPersistentData().getString(InfectedFactory.TAG_SESSION);
        if (!victimSession.isBlank() && event.getSource().getEntity() instanceof Entity attacker) {
            String attackerSession = attacker.getPersistentData().getString(InfectedFactory.TAG_SESSION);
            if (victimSession.equals(attackerSession) && !(attacker instanceof Mob mob&&sessions.stream().anyMatch(s->s.throwables.isDistracted(mob,server.getTickCount())))) event.setCanceled(true);
        }
    }

    private OutbreakSession combatRoom(ServerPlayer player){
        var s=session(player.getUUID());return s!=null&&s.prepared.contains(player.getUUID())&&s.alive.contains(player.getUUID())&&s.phase==OutbreakSession.Phase.RUNNING&&player.level().dimension().equals(s.map.dimension())?s:null;
    }
    private double roomDamage(OutbreakSession s,ServerPlayer target,net.minecraft.world.damagesource.DamageSource source,float original){
        Entity attacker=source.getEntity();
        if(attacker instanceof Mob mob&&mob.getPersistentData().contains(InfectedFactory.TAG_SESSION)){
            if(!s.id.toString().equals(mob.getPersistentData().getString(InfectedFactory.TAG_SESSION)))return 0;
            var kind=s.infectedKinds.get(mob.getUUID());if(kind==null)return 0;
            double amount=net.muxigame.outbreak.infected.OutbreakCombatRules.melee(kind,s.difficulty);
            if(kind==InfectedKind.COMMON&&target.getLookAngle().multiply(1,0,1).dot(mob.position().subtract(target.position()).multiply(1,0,1))<0)amount*=.5;
            return amount;
        }
        if(attacker instanceof ServerPlayer teammate){
            if(!s.players.contains(teammate.getUUID()))return 0;
            if(teammate!=target)return original*net.muxigame.outbreak.infected.OutbreakCombatRules.friendly(s.difficulty);
        }
        return -1; // environment and self damage retain their existing rules
    }
    private void roomIncomingDamage(LivingIncomingDamageEvent event){
        if(!(event.getEntity() instanceof ServerPlayer target))return;var s=combatRoom(target);if(s==null)return;
        double amount=roomDamage(s,target,event.getSource(),event.getOriginalAmount());if(amount<0)return;
        if(amount==0)event.setCanceled(true);else event.setAmount((float)amount);
    }
    private void roomDamageLimit(LivingDamageEvent.Pre event){
        if(!(event.getEntity() instanceof ServerPlayer target))return;var s=combatRoom(target);if(s==null)return;
        double amount=roomDamage(s,target,event.getSource(),event.getOriginalDamage());if(amount>=0)event.setNewDamage(Math.min(event.getNewDamage(),(float)amount));
    }

    private void damageDone(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        OutbreakSession session = session(player.getUUID());
        if (session == null) return;
        session.recentDamage.merge(player.getUUID(), (double)Math.max(0, event.getNewDamage()), Double::sum);
        float remaining=Math.max(0,session.temporaryHealth.getOrDefault(player.getUUID(),0f)-event.getNewDamage());
        session.temporaryHealth.put(player.getUUID(),Math.min(remaining,Math.max(0,player.getHealth()-1)));
        if(player.isUsingItem()&&player.getUseItem().getItem() instanceof net.muxigame.minigames.equipment.SharedItems.MedicalItem)player.stopUsingItem();
    }

    private void drops(LivingDropsEvent event) {
        if (!event.getEntity().getPersistentData().getString(InfectedFactory.TAG_SESSION).isBlank()) event.setCanceled(true);
    }

    private void experience(LivingExperienceDropEvent event) {
        if (!event.getEntity().getPersistentData().getString(InfectedFactory.TAG_SESSION).isBlank()) event.setDroppedExperience(0);
    }

    private void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        OutbreakSession session = session(player.getUUID());
        if (session == null) return;
        if (session.host.equals(player.getUUID())) finish(session,false,"房主断线，已恢复队伍状态");
        else leavePlayer(session,player);
    }

    private void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && PlayerSnapshot.restore(player))
            tell(player,"已恢复上次小游戏结束前的背包、位置和状态");
    }

    private void leavePlayer(OutbreakSession session,ServerPlayer player) {
        if(session.prepared.contains(player.getUUID()))runtime().complete(gameId(),session.team,player,false,0,session.difficulty,session.seconds);
        PlayerSnapshot.restore(player);
        runtime().memberships.leave(session.team,player.getUUID());
        UUID id=player.getUUID();
        session.prepared.remove(id);session.originalModes.remove(id);session.returnPoints.remove(id);
        session.downed.remove(id);session.downedSince.remove(id);session.reviveProgress.remove(id);
        session.incapCount.remove(id);session.temporaryHealth.remove(id);session.adrenalineUntil.remove(id);
        session.eliminatedAt.remove(id);session.explosiveRounds.remove(id);session.explosiveShotTick.remove(id);session.recentDamage.remove(id);
        notice(session,player.getScoreboardName()+" 离开了房间");
    }

    private void entityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel)) return;
        Entity entity=event.getEntity();
        if(OutbreakEntityAdmission.rejectForeignSession(entity.getPersistentData()::getString,
            id->sessions.stream().anyMatch(s->s.id.toString().equals(id)))){
            event.setCanceled(true);entity.discard();return;
        }
        if(entity instanceof EntityKineticBullet bullet&&bullet.getOwner() instanceof ServerPlayer p){
            OutbreakSession s=session(p.getUUID());
            if(s!=null&&s.alive.contains(p.getUUID())&&!s.downed.contains(p.getUUID())&&
                CampaignInventory.gunId(p.getInventory().getItem(0)).equals(bullet.getGunId().toString())){
                int now=server.getTickCount();
                ItemStack weapon=p.getInventory().getItem(0);int rounds=CampaignInventory.explosiveRounds(weapon);
                if(rounds>0&&s.explosiveShotTick.getOrDefault(p.getUUID(),-1)!=now){
                    CampaignInventory.explosiveRounds(weapon,rounds-1);s.explosiveRounds.put(p.getUUID(),rounds-1);s.explosiveShotTick.put(p.getUUID(),now);
                }
                if(s.explosiveShotTick.getOrDefault(p.getUUID(),-1)==now)bullet.getPersistentData().putBoolean("muxi_outbreak_explosive",true);
            }
        }
        if(entity instanceof me.xjqsh.lrtactical.entity.GrenadeEntity grenade&&entity.level().dimension().location().toString().equals("muxi_outbreak:campaign"))grenade.setDestroyBlocks(false);
        Entity owner=entity instanceof net.minecraft.world.entity.projectile.Projectile projectile?projectile.getOwner():
            entity instanceof net.minecraft.world.entity.AreaEffectCloud cloud?cloud.getOwner():null;
        if(owner instanceof ServerPlayer p){
            OutbreakSession s=session(p.getUUID());
            if(s!=null&&entity.level().dimension().equals(s.map.dimension())){
                entity.getPersistentData().putString("muxi_outbreak_equipment_session",s.id.toString());s.equipmentEntities.add(entity.getUUID());
            }
        }
        String tag=event.getEntity().getPersistentData().getString(InfectedFactory.TAG_SESSION);
        if (!tag.isBlank() && sessions.stream().noneMatch(s->s.id.toString().equals(tag)&&s.infected.contains(entity.getUUID()))) {
            event.setCanceled(true);event.getEntity().discard();
        } else if (tag.isBlank() && event.getEntity() instanceof Mob &&
            event.getLevel().dimension().location().toString().equals("muxi_outbreak:campaign")) {
            event.setCanceled(true);event.getEntity().discard();
        }
    }

    private void eliminate(OutbreakSession session, ServerPlayer player, String reason) {
        session.eliminatedAt.put(player.getUUID(),player.position());
        session.alive.remove(player.getUUID());
        session.downed.remove(player.getUUID());
        session.downedSince.remove(player.getUUID());
        session.reviveProgress.remove(player.getUUID());
        player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        player.removeEffect(MobEffects.WEAKNESS);
        player.setGameMode(GameType.SPECTATOR);
        notice(session, player.getDisplayName().getString() + " 被淘汰：" + reason);
    }

    private void finish(OutbreakSession session, boolean win, String reason) {
        if (session.phase == OutbreakSession.Phase.FINISHED) return;
        for(UUID id:session.players){var p=server.getPlayerList().getPlayer(id);if(p!=null&&session.prepared.contains(id))runtime().complete(gameId(),session.team,p,win,0,session.difficulty,session.seconds);}
        session.phase = OutbreakSession.Phase.FINISHED;
        session.supplies.cleanup();session.throwables.cleanup();
        MuxiOutbreak.LOG.info("OUTBREAK_RESULT map={} session={} win={} section={} seconds={} reason={}",session.map.id(),session.shortId(),win,session.section,session.seconds,reason);
        ServerLevel mapLevel = server == null ? null : server.getLevel(session.map.dimension());
        if (mapLevel != null) discardInfected(session, mapLevel);
        if (mapLevel != null) discardEquipment(session,mapLevel);
        if (mapLevel != null) session.checkpointDoors.restore(mapLevel);
        if (server != null) for (UUID id : session.players) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) continue;
            if (PlayerSnapshot.restore(player)) {
                tell(player,(win?"胜利 · ":"结束 · ")+reason+" · 生存 "+session.seconds+" 秒");
                continue;
            }
            if (!session.prepared.contains(id)) continue;
            player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
            player.removeEffect(MobEffects.WEAKNESS);
            player.setGameMode(session.originalModes.getOrDefault(id, GameType.SURVIVAL));
            OutbreakSession.ReturnPoint back = session.returnPoints.get(id);
            if (back != null) {
                ServerLevel returnLevel = server.getLevel(back.dimension());
                if (returnLevel != null) {
                    Vec3 point = back.position();
                    player.teleportTo(returnLevel, point.x, point.y, point.z, back.yaw(), back.pitch());
                }
            }
            tell(player, (win ? "胜利 · " : "结束 · ") + reason + " · 生存 " + session.seconds + " 秒");
        }
        runtime().memberships.close(session.team);
        sessions.remove(session);
    }

    private void status(ServerPlayer player) {
        OutbreakSession session = session(player.getUUID());
        if (session == null) {
            tell(player, "当前不在 Outbreak 房间");
            return;
        }
        tell(player, "房间 " + session.shortId() + " · " + session.map.title()
            + " · " + session.phase + " · Section " + session.section
            + " · 感染者 " + session.infected.size()
            + " · Director " + session.director.pace());
    }
    private void discardEquipment(OutbreakSession session,ServerLevel level){
        for(UUID id:session.equipmentEntities){Entity entity=level.getEntity(id);if(entity!=null)entity.discard();}
        session.equipmentEntities.clear();
    }

    private boolean allAliveInside(OutbreakSession session, OutbreakMap.SafeRoom room) {
        if (!session.downed.isEmpty()) return false;
        for (ServerPlayer player : alivePlayers(session)) {
            if (!player.level().dimension().equals(session.map.dimension()) || !room.contains(player.position())) return false;
        }
        return !session.alive.isEmpty();
    }

    private boolean allAliveNear(OutbreakSession session, BlockPos position, double radius) {
        if (!session.downed.isEmpty()) return false;
        double max = radius * radius;
        for (ServerPlayer player : alivePlayers(session)) {
            if (!player.level().dimension().equals(session.map.dimension())) return false;
            if (player.distanceToSqr(position.getCenter()) > max) return false;
        }
        return !session.alive.isEmpty();
    }

    private ServerPlayer nearest(OutbreakSession session, Vec3 position) {
        return alivePlayers(session).stream()
            .filter(p -> !session.downed.contains(p.getUUID()))
            .min(Comparator.comparingDouble(p -> p.position().distanceToSqr(position)))
            .orElseGet(() -> alivePlayers(session).stream().findFirst().orElse(null));
    }

    private List<ServerPlayer> alivePlayers(OutbreakSession session) {
        return session.alive.stream()
            .map(id -> server.getPlayerList().getPlayer(id))
            .filter(Objects::nonNull)
            .toList();
    }

    private InfectedKind randomSpecial() {
        InfectedKind[] choices = {
            InfectedKind.HUNTER, InfectedKind.SMOKER, InfectedKind.BOOMER,
            InfectedKind.SPITTER, InfectedKind.CHARGER, InfectedKind.JOCKEY
        };
        return choices[random.nextInt(choices.length)];
    }

    private OutbreakSession requireSession(ServerPlayer player) {
        OutbreakSession session = session(player.getUUID());
        if (session == null) throw new IllegalArgumentException("你当前不在 Outbreak 游戏中");
        return session;
    }

    private boolean inCampaign(ServerPlayer player){
        OutbreakSession s=session(player.getUUID());
        return s!=null&&s.prepared.contains(player.getUUID())&&s.phase!=OutbreakSession.Phase.FINISHED;
    }

    private boolean interactSupply(ServerPlayer player,Entity target){
        String id=target.getPersistentData().getString(CampaignSupplies.TAG);
        if(id.isBlank())return false;
        OutbreakSession s=session(player.getUUID());
        if(s==null||!s.id.toString().equals(target.getPersistentData().getString(CampaignSupplies.SESSION)))return true;
        tell(player,"按F拾取或交换地上装备");return true;
    }

    /** One server-authoritative target for the contextual F request; never uses the held item. */
    public boolean interact(ServerPlayer player){
        var s=session(player.getUUID());
        if(s==null||!inCampaign(player)||!s.alive.contains(player.getUUID())||s.downed.contains(player.getUUID())
            ||player.isSpectator()||!player.level().dimension().equals(s.map.dimension())
            ||s.phase==OutbreakSession.Phase.WAITING||s.phase==OutbreakSession.Phase.PREPARING
            ||s.phase==OutbreakSession.Phase.COUNTDOWN||s.phase==OutbreakSession.Phase.FINISHED)return false;
        int now=server.getTickCount();
        if(now-s.interactionAt.getOrDefault(player.getUUID(),-100)<2)return true;
        s.interactionAt.put(player.getUUID(),now);
        Vec3 eye=player.getEyePosition();
        var hit=player.level().clip(new ClipContext(eye,eye.add(player.getLookAngle().scale(3.5)),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player));
        double blockDistance=hit.getType()==HitResult.Type.MISS?Double.POSITIVE_INFINITY:eye.distanceToSqr(hit.getLocation());
        if(s.supplies.interactLook(player,blockDistance))return true;
        return hit.getType()==HitResult.Type.BLOCK&&CampaignInteractions.interactBlock(player,s,hit);
    }

    private ServerPlayer medicalTarget(OutbreakSession s,ServerPlayer user,String kind){
        if(kind.equals("defib"))return s.eliminatedAt.entrySet().stream()
            .filter(e->user.position().distanceToSqr(e.getValue())<=16&&visible(user,e.getValue().add(0,.4,0)))
            .map(e->server.getPlayerList().getPlayer(e.getKey())).filter(Objects::nonNull).findFirst().orElse(null);
        if(kind.equals("medkit")&&user.isCrouching())return alivePlayers(s).stream()
            .filter(p->p!=user&&!s.downed.contains(p.getUUID())&&p.distanceToSqr(user)<=16&&user.hasLineOfSight(p))
            .min(Comparator.comparingDouble(user::distanceToSqr)).orElse(user);
        return user;
    }
    private boolean visible(ServerPlayer user,Vec3 destination){
        var hit=user.level().clip(new ClipContext(user.getEyePosition(),destination,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,user));
        return hit.getType()==HitResult.Type.MISS||hit.getLocation().distanceTo(destination)<.5;
    }
    public boolean canUseMedical(ServerPlayer user,String kind){
        OutbreakSession s=session(user.getUUID());
        if(s==null||!s.prepared.contains(user.getUUID())||!s.alive.contains(user.getUUID())||s.downed.contains(user.getUUID()))return false;
        if(!user.level().dimension().equals(s.map.dimension())||s.phase==OutbreakSession.Phase.PREPARING||s.phase==OutbreakSession.Phase.FINISHED)return false;
        ServerPlayer target=medicalTarget(s,user,kind);if(target==null)return false;
        return switch(kind){
            case "medkit"->target.getHealth()-s.temporaryHealth.getOrDefault(target.getUUID(),0f)<target.getMaxHealth()-.01;
            case "pills","adrenaline"->target.getHealth()<target.getMaxHealth()-.01;
            case "defib"->s.eliminatedAt.containsKey(target.getUUID())&&!s.alive.contains(target.getUUID());
            case "explosive_pack"->true;
            default->false;
        };
    }
    public boolean useMedical(ServerPlayer user,String kind){
        if(!canUseMedical(user,kind))return false;
        OutbreakSession s=requireSession(user);ServerPlayer target=medicalTarget(s,user,kind);
        UUID id=target.getUUID();float before=target.getHealth();
        switch(kind){
            case "medkit"->{
                float temp=s.temporaryHealth.getOrDefault(id,0f);
                float permanent=Math.max(1,target.getHealth()-temp);
                float healed=(float)SupplyRules.firstAidPermanent(permanent,target.getMaxHealth());
                target.setHealth(Math.min(target.getMaxHealth(),healed+temp));
                s.temporaryHealth.put(id,Math.max(0,target.getHealth()-healed));s.incapCount.remove(id);
            }
            case "pills","adrenaline"->{
                float added=(float)SupplyRules.addTemporary(target.getHealth(),target.getMaxHealth(),kind.equals("adrenaline"));
                target.setHealth(target.getHealth()+added);s.temporaryHealth.merge(id,added,Float::sum);
                if(kind.equals("adrenaline")){
                    s.adrenalineUntil.put(id,server.getTickCount()+300);
                    target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED,300,0,false,true));
                }
            }
            case "defib"->{
                Vec3 at=s.eliminatedAt.remove(id);if(at==null)return false;
                target.teleportTo(user.serverLevel(),at.x,at.y,at.z,target.getYRot(),target.getXRot());
                target.setGameMode(GameType.ADVENTURE);target.setHealth(target.getMaxHealth()*.5f);
                s.temporaryHealth.remove(id);s.incapCount.remove(id);s.alive.add(id);
            }
            case "explosive_pack"->s.supplies.deployUpgrade(user);
            default->{return false;}
        }
        MuxiOutbreak.LOG.info("OUTBREAK_MEDICAL session={} kind={} user={} target={} healthBefore={} healthAfter={} temporary={}",
            s.shortId(),kind,user.getScoreboardName(),target.getScoreboardName(),before,target.getHealth(),s.temporaryHealth.getOrDefault(id,0f));
        return true;
    }
    public boolean throwEquipment(ServerPlayer player,String kind,ItemStack stack){
        OutbreakSession s=session(player.getUUID());
        return s!=null&&inCampaign(player)&&s.throwables.launch(player,kind,stack);
    }

    private void explosiveImpact(Entity bullet,Vec3 position){
        if(!(bullet.level() instanceof ServerLevel level)||!bullet.getPersistentData().getBoolean("muxi_outbreak_explosive")||bullet.getPersistentData().getBoolean("muxi_outbreak_explosive_used"))return;
        bullet.getPersistentData().putBoolean("muxi_outbreak_explosive_used",true);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION,position.x,position.y+.5,position.z,1,0,0,0,0);
        Entity owner=bullet instanceof net.minecraft.world.entity.projectile.Projectile projectile?projectile.getOwner():null;
        // Native enhancement splashes entities only; never runs block-destruction logic.
        for(LivingEntity victim:level.getEntitiesOfClass(LivingEntity.class,new AABB(position,position).inflate(2.5))){
            double distance=victim.position().distanceTo(position);if(distance>2.5)continue;
            if(level.clip(new ClipContext(position.add(0,.3,0),victim.getEyePosition(),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,victim)).getType()!=HitResult.Type.MISS)continue;
            victim.hurt(level.damageSources().explosion(bullet,owner instanceof LivingEntity living?living:null),(float)(6*(1-distance/2.5)));
        }
    }

    private OutbreakSession session(UUID player) {
        return sessions.stream().filter(s -> s.contains(player)).findFirst().orElse(null);
    }

    private void notice(OutbreakSession session, String message) {
        MuxiOutbreak.LOG.info("OUTBREAK_EVENT session={} section={} phase={} message={}",session.shortId(),session.section,session.phase,message);
        for (UUID id : session.players) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) tell(player, message);
        }
    }

    private static void tell(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal("[Outbreak] " + message));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
