package net.muxigame.outbreak.equipment;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.muxigame.outbreak.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

public final class CampaignItems {
    private static final DeferredRegister<Item> ITEMS=DeferredRegister.create(BuiltInRegistries.ITEM,MuxiOutbreak.MOD_ID);
    public static final DeferredHolder<Item,MedicalItem> MEDKIT=ITEMS.register("medkit",()->new MedicalItem("medkit",100));
    public static final DeferredHolder<Item,MedicalItem> PILLS=ITEMS.register("pills",()->new MedicalItem("pills",20));
    public static final DeferredHolder<Item,MedicalItem> ADRENALINE=ITEMS.register("adrenaline",()->new MedicalItem("adrenaline",20));
    public static final DeferredHolder<Item,MedicalItem> DEFIB=ITEMS.register("defibrillator",()->new MedicalItem("defib",60));
    public static final DeferredHolder<Item,MedicalItem> EXPLOSIVE_AMMO=ITEMS.register("explosive_ammo_pack",()->new MedicalItem("explosive_pack",40));
    public static final DeferredHolder<Item,ThrowItem> PIPE=ITEMS.register("pipe_bomb",()->new ThrowItem("pipe_bomb"));
    public static final DeferredHolder<Item,ThrowItem> BILE=ITEMS.register("bile_bomb",()->new ThrowItem("bile_bomb"));
    private CampaignItems(){}
    public static void register(IEventBus bus){ITEMS.register(bus);}

    public static final class MedicalItem extends Item {
        public final String kind;
        private final int duration;
        MedicalItem(String kind,int duration){super(new Properties().stacksTo(1));this.kind=kind;this.duration=duration;}
        @Override public int getUseDuration(ItemStack stack,LivingEntity entity){return duration;}
        @Override public UseAnim getUseAnimation(ItemStack stack){return UseAnim.DRINK;}
        @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand){
            ItemStack stack=player.getItemInHand(hand);
            if(player instanceof ServerPlayer p){
                var game=OutbreakGame.active(p);
                if(game==null||!game.canUseMedical(p,kind))return InteractionResultHolder.fail(stack);
            }
            player.startUsingItem(hand);return InteractionResultHolder.consume(stack);
        }
        @Override public ItemStack finishUsingItem(ItemStack stack,Level level,LivingEntity entity){
            if(entity instanceof ServerPlayer p){
                var game=OutbreakGame.active(p);
                if(game!=null&&game.useMedical(p,kind))stack.shrink(1);
            }
            return stack;
        }
    }
    public static final class ThrowItem extends Item {
        public final String kind;
        ThrowItem(String kind){super(new Properties().stacksTo(1));this.kind=kind;}
        @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand){
            ItemStack stack=player.getItemInHand(hand);
            if(player instanceof ServerPlayer p){
                var game=OutbreakGame.active(p);
                if(game==null||!game.throwEquipment(p,kind,stack))return InteractionResultHolder.fail(stack);
                stack.shrink(1);player.getCooldowns().addCooldown(this,20);
            }
            return InteractionResultHolder.sidedSuccess(stack,level.isClientSide());
        }
    }
}
