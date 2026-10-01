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

public final class OutbreakGame {
    private MinecraftServer server;
    private Map<String, OutbreakMap> maps = Map.of();
    private final List<OutbreakSession> sessions = new ArrayList<>();
    private final Random random = new Random();
    private int ticks;
    private GeometryInstaller geometry;

    public void register(IEventBus bus) {
        bus.addListener(this::started);
        bus.addListener(this::stopping);
        bus.addListener(this::tick);
        bus.addListener(this::commands);
        bus.addListener(EventPriority.HIGHEST, this::incomingDamage);
        bus.addListener(EventPriority.LOWEST, this::damageDone);
        bus.addListener(EventPriority.HIGHEST, this::death);
        bus.addListener(this::drops);
        bus.addListener(this::experience);
        bus.addListener(this::logout);
        bus.addListener(this::login);
        bus.addListener(this::entityJoin);
        bus.addListener(EventPriority.HIGHEST, (ItemTossEvent event) -> {
            if (!(event.getPlayer() instanceof ServerPlayer player)) return;
            OutbreakSession session=session(player.getUUID());
            if (session==null || !session.prepared.contains(player.getUUID())) return;
            // Toss is fired after the inventory slot was removed. Return it on cancellation.
            event.setCanceled(true);
            player.getInventory().add(event.getEntity().getItem().copy());
            player.containerMenu.broadcastChanges();
            tell(player,"小游戏临时装备不能丢弃；离开后恢复原背包");
        });
        bus.addListener((BlockEvent.BreakEvent event) -> {
            if (event.getPlayer().level().dimension().location().toString().equals("muxi_outbreak:campaign")) event.setCanceled(true);
        });
        bus.addListener((ExplosionEvent.Start event) -> {
            if (event.getLevel().dimension().location().toString().equals("muxi_outbreak:campaign")) event.setCanceled(true);
        });
    }

    private void started(ServerStartedEvent event) {
        server = event.getServer();
        maps = OutbreakMapLoader.load(server);
        geometry = new GeometryInstaller(server);
        MuxiOutbreak.LOG.info("Loaded {} outbreak maps: {}", maps.size(), maps.keySet());
    }

    private void stopping(ServerStoppingEvent event) {
        if (event.getServer() != server) return;
        for (OutbreakSession session : List.copyOf(sessions)) finish(session, false, "服务器停止，本局已安全结束");
        sessions.clear();
        maps = Map.of();
        server = null;
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
                        json.addProperty("seconds",session.seconds);json.addProperty("panicEvents",session.triggeredPanics.size());
                        json.addProperty("finaleWaves",session.finaleWaves);json.addProperty("finaleTankSpawned",session.finaleTankSpawned);
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
        require(server != null, "服务器尚未准备好");
        require(session(host.getUUID()) == null, "你已经在一局游戏中");
        OutbreakMap map = maps.get(mapId);
        require(map != null, "不存在地图：" + mapId);
        require(sessions.stream().noneMatch(s -> s.map.id().equals(mapId)), "这张地图已有队伍进行中，请加入该房间");
        require(server.getLevel(map.dimension()) != null, "地图维度未加载：" + map.dimension().location());
        require(host.gameMode.getGameModeForPlayer() == GameType.SURVIVAL, "请先切换到生存模式");
        OutbreakSession session = new OutbreakSession(
            host.getUUID(), map, overrideMode == null ? map.mode() : overrideMode, difficulty, server.getTickCount()
        );
        sessions.add(session);
        try {
            geometry.request(map);
            if (geometry.ready(map)) preparePlayer(session,host);
            else session.phase=OutbreakSession.Phase.PREPARING;
            notice(session, "创建房间 " + session.shortId() + " · " + map.title() + "。地图就绪后倒计时10秒；队友 /muxioutbreak join " + session.shortId());
        } catch (RuntimeException error) {
            finish(session,false,"地图准备失败");
            throw error;
        }
    }

    public void join(ServerPlayer player, String shortId) {
        require(session(player.getUUID()) == null, "你已经在一局游戏中");
        OutbreakSession session = sessions.stream()
            .filter(s -> s.shortId().equalsIgnoreCase(shortId))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("房间不存在"));
        require(session.phase == OutbreakSession.Phase.COUNTDOWN || session.phase == OutbreakSession.Phase.PREPARING, "游戏已经开始");
        require(session.players.size() < 4, "房间已满");
        require(player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL, "请先切换到生存模式");
        session.players.add(player.getUUID());
        session.alive.add(player.getUUID());
        if (session.phase != OutbreakSession.Phase.PREPARING) preparePlayer(session, player);
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
        if (session.phase == OutbreakSession.Phase.PREPARING) {
            if (geometry.error(session.map)!=null) { finish(session,false,"地图安装失败："+geometry.error(session.map)); return; }
            if (geometry.ready(session.map)) {
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
        if (session.phase == OutbreakSession.Phase.COUNTDOWN) {
            if (now >= session.timer) {
                session.phase = OutbreakSession.Phase.RUNNING;
                notice(session, session.mode == OutbreakMap.Mode.SURVIVAL ? "生存模式开始" : "战役开始");
            }
            return;
        }
        if (session.phase == OutbreakSession.Phase.SAFE_ROOM) {
            reviveTick(session, now);
            if (now >= session.timer) {
                for (UUID id : session.players) {
                    ServerPlayer player=server.getPlayerList().getPlayer(id);
                    if (player==null) continue;
                    session.alive.add(id);
                    player.setGameMode(GameType.ADVENTURE);
                    player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    player.removeEffect(MobEffects.WEAKNESS);
                    player.setHealth(player.getMaxHealth());
                    player.getFoodData().setFoodLevel(20);
                    teleportToSection(session,player);
                }
                session.downed.clear();session.downedSince.clear();session.reviveProgress.clear();session.incapCount.clear();
                session.phase = OutbreakSession.Phase.RUNNING;
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
            if (safeRoom != null && allAliveInside(session, safeRoom)) {
                session.section = safeRoom.nextSection();
                session.phase = OutbreakSession.Phase.SAFE_ROOM;
                session.timer = now + 160;
                session.panicWaves=0;session.panicDelay=0;session.director.forcePanic(false);
                discardInfected(session, level);
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
        double progress = session.mode == OutbreakMap.Mode.SURVIVAL
            ? Math.min(1.0, session.seconds / 600.0)
            : Math.min(1.0, session.section / (double)Math.max(1, session.map.safeRooms().size() + 1));
        return new Director.Sample(
            players.size(), avgHealth, damage, separation, progress, session.infected.size(), session.downed.size()
        );
    }

    private boolean spawn(OutbreakSession session, InfectedKind kind, boolean horde) {
        ServerLevel level = server.getLevel(session.map.dimension());
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
            if (distance<36 || distance>80*80) continue;
            for (int attempt=0;attempt<5;attempt++) {
                BlockPos p=attempt==0?point.pos():point.pos().offset(random.nextInt(3)-1,0,random.nextInt(3)-1);
                mob.moveTo(p.getX()+.5,p.getY(),p.getZ()+.5,random.nextFloat()*360,0);
                if (level.getBlockState(p.below()).getCollisionShape(level,p.below()).isEmpty()) continue;
                if (!level.getFluidState(p).isEmpty() || !level.noCollision(mob,mob.getBoundingBox())) continue;
                position=p;break;
            }
            if (position!=null) break;
        }
        if (position==null) return false;
        ServerPlayer target = nearest(session, mob.position());
        if (target != null) mob.setTarget(target);
        if (level.addFreshEntity(mob)) {
            session.infected.add(mob.getUUID());
            session.infectedKinds.put(mob.getUUID(), kind);
            if (kind.special()) MuxiOutbreak.LOG.info("OUTBREAK_SPAWN session={} kind={} pos={}",session.shortId(),kind,position);
            return true;
        }
        return false;
    }

    private void controlSpecials(OutbreakSession session, ServerLevel level, int now) {
        for (UUID id : Set.copyOf(session.infected)) {
            Entity entity = level.getEntity(id);
            if (!(entity instanceof Mob mob) || !mob.isAlive()) continue;
            InfectedKind kind = session.infectedKinds.getOrDefault(id, InfectedKind.COMMON);
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
            if (entity == null || !entity.isAlive()) {
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
        session.originalModes.putIfAbsent(player.getUUID(), player.gameMode.getGameModeForPlayer());
        session.returnPoints.putIfAbsent(player.getUUID(), new OutbreakSession.ReturnPoint(
            player.level().dimension(), player.position(), player.getYRot(), player.getXRot()
        ));
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
        for (int i=0;i<session.map.itemSpawns().size();i++) {
            var item=session.map.itemSpawns().get(i);
            if (item.section()!=session.section) continue;
            for (ServerPlayer player:alivePlayers(session)) {
                String key=player.getUUID()+":"+i;
                if (player.distanceToSqr(item.pos().getCenter())>9 || !session.claimedSupplies.add(key)) continue;
                player.getInventory().add(new ItemStack(Items.ARROW,32));
                player.getInventory().add(new ItemStack(Items.GOLDEN_APPLE));
                player.getInventory().add(new ItemStack(Items.COOKED_BEEF,4));
                tell(player,"已领取本处弹药和医疗补给");
            }
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
        if (elapsed>=finale.holdSeconds()*20 && session.finaleTankSpawned && !bossAlive && allAliveNear(session,session.map.finish(),finale.radius())) {
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
            if (progress % 20 == 0) {
                reviver.displayClientMessage(Component.literal("救援 " + (progress / 20) + "/3"), true);
            }
            if (progress >= 60) {
                session.downed.remove(downedId);
                session.downedSince.remove(downedId);
                session.reviveProgress.remove(downedId);
                downed.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                downed.removeEffect(MobEffects.WEAKNESS);
                downed.setHealth(Math.max(6, downed.getMaxHealth() * 0.35f));
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
        session.downed.add(player.getUUID());
        session.downedSince.put(player.getUUID(), server.getTickCount());
        session.reviveProgress.put(player.getUUID(), 0);
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 40, 9));
        player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 20 * 40, 4));
        notice(session, player.getDisplayName().getString() + " 倒地；队友蹲下靠近 3 秒可救援");
    }

    private void incomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            OutbreakSession session = session(player.getUUID());
            if (session != null && session.prepared.contains(player.getUUID()) &&
                (session.downed.contains(player.getUUID()) || session.phase == OutbreakSession.Phase.COUNTDOWN || session.phase == OutbreakSession.Phase.SAFE_ROOM)) {
                event.setCanceled(true);
                return;
            }
        }
        String victimSession = event.getEntity().getPersistentData().getString(InfectedFactory.TAG_SESSION);
        if (!victimSession.isBlank() && event.getSource().getEntity() instanceof Entity attacker) {
            String attackerSession = attacker.getPersistentData().getString(InfectedFactory.TAG_SESSION);
            if (victimSession.equals(attackerSession)) event.setCanceled(true);
        }
    }

    private void damageDone(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        OutbreakSession session = session(player.getUUID());
        if (session == null) return;
        session.recentDamage.merge(player.getUUID(), (double)Math.max(0, event.getNewDamage()), Double::sum);
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
        PlayerSnapshot.restore(player);
        session.players.remove(player.getUUID());session.alive.remove(player.getUUID());
        session.downed.remove(player.getUUID());session.downedSince.remove(player.getUUID());session.reviveProgress.remove(player.getUUID());
        notice(session,player.getScoreboardName()+" 离开了房间");
    }

    private void entityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel)) return;
        String tag=event.getEntity().getPersistentData().getString(InfectedFactory.TAG_SESSION);
        if (!tag.isBlank() && sessions.stream().noneMatch(s->s.id.toString().equals(tag))) {
            event.setCanceled(true);event.getEntity().discard();
        } else if (tag.isBlank() && event.getEntity() instanceof Mob &&
            event.getLevel().dimension().location().toString().equals("muxi_outbreak:campaign")) {
            event.setCanceled(true);event.getEntity().discard();
        }
    }

    private void eliminate(OutbreakSession session, ServerPlayer player, String reason) {
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
        session.phase = OutbreakSession.Phase.FINISHED;
        MuxiOutbreak.LOG.info("OUTBREAK_RESULT map={} session={} win={} section={} seconds={} reason={}",session.map.id(),session.shortId(),win,session.section,session.seconds,reason);
        ServerLevel mapLevel = server == null ? null : server.getLevel(session.map.dimension());
        if (mapLevel != null) discardInfected(session, mapLevel);
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
