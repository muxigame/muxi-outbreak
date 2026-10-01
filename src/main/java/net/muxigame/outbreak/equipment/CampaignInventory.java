package net.muxigame.outbreak.equipment;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.*;
import com.tacz.guns.api.item.builder.*;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.resource.pojo.data.gun.Bolt;
import com.tacz.guns.util.AttachmentDataUtils;
import me.xjqsh.lrtactical.api.LrTacticalAPI;
import me.xjqsh.lrtactical.api.item.*;
import me.xjqsh.lrtactical.init.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import java.util.*;
import java.util.function.Consumer;

public final class CampaignInventory {
    public static final String PRIMARY="tacz:hk_mp5a5",SECONDARY="tacz:glock_17";
    private CampaignInventory(){}
    public static ItemStack gun(ServerPlayer player,String id){
        var key=ResourceLocation.parse(id);
        var data=TimelessAPI.getCommonGunIndex(key).orElseThrow(()->new IllegalArgumentException("枪包缺少 "+id)).getGunData();
        var modes=data.getFireModeSet();
        ItemStack stack=GunItemBuilder.create().setId(key).setCount(1).setAmmoCount(0).setAmmoInBarrel(false)
            .setFireMode(modes.contains(FireMode.AUTO)?FireMode.AUTO:modes.getFirst()).build(player.registryAccess());
        fillMagazine(stack);return stack;
    }
    public static int capacity(ItemStack stack){
        IGun gun=IGun.getIGunOrNull(stack);
        if(gun==null)return 0;
        return TimelessAPI.getCommonGunIndex(gun.getGunId(stack)).map(i->AttachmentDataUtils.getAmmoCountWithAttachment(stack,i.getGunData())).orElse(0);
    }
    public static void fillMagazine(ItemStack stack){
        IGun gun=IGun.getIGunOrNull(stack);if(gun==null)return;
        var data=TimelessAPI.getCommonGunIndex(gun.getGunId(stack)).orElseThrow().getGunData();
        gun.setCurrentAmmoCount(stack,capacity(stack));gun.setBulletInBarrel(stack,data.getBolt()!=Bolt.OPEN_BOLT);
    }
    public static String gunId(ItemStack stack){IGun gun=IGun.getIGunOrNull(stack);return gun==null?"":gun.getGunId(stack).toString();}
    public static String gunType(ItemStack stack){
        String id=gunId(stack);return id.isEmpty()?"":TimelessAPI.getCommonGunIndex(ResourceLocation.parse(id)).map(i->i.getType().toLowerCase(Locale.ROOT)).orElse("");
    }
    public static String ammoId(ItemStack stack){IGun gun=IGun.getIGunOrNull(stack);return gun==null?"":TimelessAPI.getCommonGunIndex(gun.getGunId(stack)).orElseThrow().getGunData().getAmmoId().toString();}
    public static ItemStack ammo(String id,int count){
        if(TimelessAPI.getCommonAmmoIndex(ResourceLocation.parse(id)).isEmpty())throw new IllegalArgumentException("弹药不存在 "+id);
        return AmmoItemBuilder.create().setId(ResourceLocation.parse(id)).setCount(count).build();
    }
    public static ItemStack throwable(String id){
        ItemStack stack=new ItemStack(ModItems.THROWABLE.get());
        IThrowable.of(stack).setId(stack,ResourceLocation.parse(id));
        if(LrTacticalAPI.getThrowableIndex(stack).isEmpty())throw new IllegalArgumentException("投掷物资源未加载 "+id);
        stack.set(DataComponents.MAX_STACK_SIZE,1);return stack;
    }
    public static ItemStack create(ServerPlayer player,String kind){
        if(kind.startsWith("gun:"))return gun(player,kind.substring(4));
        if(kind.startsWith("lr:"))return throwable(kind.substring(3));
        return switch(kind){
            case "ammo"->new ItemStack(Items.CHEST);
            case "medkit"->new ItemStack(CampaignItems.MEDKIT.get());
            case "pills"->new ItemStack(CampaignItems.PILLS.get());
            case "adrenaline"->new ItemStack(CampaignItems.ADRENALINE.get());
            case "defib"->new ItemStack(CampaignItems.DEFIB.get());
            case "explosive_pack"->new ItemStack(CampaignItems.EXPLOSIVE_AMMO.get());
            case "pipe_bomb"->new ItemStack(CampaignItems.PIPE.get());
            case "bile_bomb"->new ItemStack(CampaignItems.BILE.get());
            case "melee"->{ItemStack stack=new ItemStack(ModItems.MELEE.get());IMeleeWeapon.of(stack).setId(stack,ResourceLocation.parse("lrtactical:baseball_bat"));yield stack;}
            default->throw new IllegalArgumentException("未实现的补给种类 "+kind);
        };
    }
    public static SupplyRules.Slot category(ItemStack stack){
        if(stack.isEmpty())return SupplyRules.Slot.NONE;
        if(IGun.getIGunOrNull(stack)!=null)return gunType(stack).equals("pistol")?SupplyRules.Slot.SECONDARY:SupplyRules.Slot.PRIMARY;
        if(IAmmo.getIAmmoOrNull(stack)!=null)return SupplyRules.Slot.AMMO;
        if(stack.getItem() instanceof IThrowable||stack.getItem() instanceof CampaignItems.ThrowItem||stack.is(ModItems.DETONATOR.get()))return SupplyRules.Slot.THROWABLE;
        if(stack.getItem() instanceof IMeleeWeapon)return SupplyRules.Slot.SECONDARY;
        if(stack.getItem() instanceof CampaignItems.MedicalItem item)
            return item.kind.equals("pills")||item.kind.equals("adrenaline")?SupplyRules.Slot.SMALL_MEDICAL:SupplyRules.Slot.LARGE_MEDICAL;
        if(stack.getItem() instanceof IConsumable)return SupplyRules.Slot.LARGE_MEDICAL;
        return SupplyRules.Slot.NONE;
    }
    public static int slot(SupplyRules.Slot category){return switch(category){
        case PRIMARY->0;case SECONDARY->1;case THROWABLE->2;case LARGE_MEDICAL->3;case SMALL_MEDICAL->4;default->-1;};}
    public static void validate(ServerPlayer player){gun(player,PRIMARY);gun(player,SECONDARY);throwable("lrtactical:m67");}
    public static void kit(ServerPlayer p){
        validate(p);p.closeContainer();p.getInventory().clearContent();p.removeAllEffects();
        p.getInventory().setItem(0,gun(p,PRIMARY));p.getInventory().setItem(1,gun(p,SECONDARY));
        // Medkits/throwables are world supplies, not duplicated into every player's inventory.
        refill(p,false);p.getInventory().selected=0;p.setGameMode(GameType.ADVENTURE);
        p.setHealth(p.getMaxHealth());p.getFoodData().setFoodLevel(20);p.getFoodData().setSaturation(0);
        p.inventoryMenu.broadcastChanges();
    }
    public static boolean excludedFromAmmoPile(ItemStack gun){
        String type=gunType(gun),id=gunId(gun);
        return type.equals("rpg")||type.equals("launcher")||id.equals("tacz:m249"); // M249 maps source M60.
    }
    public static Map<String,Integer> reserveCaps(ServerPlayer p,boolean ammoPile){
        Map<String,Integer> caps=new LinkedHashMap<>();Set<String> rockets=new HashSet<>();
        for(int slot=0;slot<=1;slot++){
            ItemStack stack=p.getInventory().getItem(slot);if(IGun.getIGunOrNull(stack)==null)continue;
            if(ammoPile&&excludedFromAmmoPile(stack))continue;
            String id=ammoId(stack);caps.merge(id,capacity(stack),Integer::sum);
            if(gunType(stack).equals("rpg")||gunType(stack).equals("launcher"))rockets.add(id);
        }
        caps.replaceAll((id,count)->SupplyRules.challengeReserve(count,rockets.contains(id)));return caps;
    }
    /** Ammunition piles fill reserve only, never magically reload a magazine. No gameplay cooldown. */
    public static boolean refill(ServerPlayer p,boolean ammoPile){
        Map<String,Integer> caps=reserveCaps(p,ammoPile);boolean changed=false;
        for(var entry:caps.entrySet()){
            int current=ammoCount(p,entry.getKey());int needed=Math.max(0,entry.getValue()-current);
            for(int i=SupplyRules.AMMO_FIRST_SLOT;i<36&&needed>0;i++){
                ItemStack old=p.getInventory().getItem(i);IAmmo ammo=IAmmo.getIAmmoOrNull(old);
                if(ammo!=null&&ammo.getAmmoId(old).toString().equals(entry.getKey())&&old.getCount()<Math.min(60,old.getMaxStackSize())){
                    int n=Math.min(needed,Math.min(60,old.getMaxStackSize())-old.getCount());old.grow(n);needed-=n;changed|=n>0;
                }
            }
            for(int i=SupplyRules.AMMO_FIRST_SLOT;i<36&&needed>0;i++)if(p.getInventory().getItem(i).isEmpty()){
                int n=Math.min(60,needed);p.getInventory().setItem(i,ammo(entry.getKey(),n));needed-=n;changed=true;
            }
        }
        if(changed)p.inventoryMenu.broadcastChanges();return changed;
    }
    public static int ammoCount(ServerPlayer p,String id){
        int count=0;for(int i=0;i<p.getInventory().getContainerSize();i++){
            ItemStack stack=p.getInventory().getItem(i);IAmmo ammo=IAmmo.getIAmmoOrNull(stack);
            if(ammo!=null&&ammo.getAmmoId(stack).toString().equals(id))count+=stack.getCount();
        }return count;
    }
    public static int explosiveRounds(ItemStack gun){
        return gun.getOrDefault(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.EMPTY).copyTag().getInt("muxi_outbreak_explosive_rounds");
    }
    public static void explosiveRounds(ItemStack gun,int count){
        net.minecraft.world.item.component.CustomData.update(DataComponents.CUSTOM_DATA,gun,tag->tag.putInt("muxi_outbreak_explosive_rounds",Math.max(0,count)));
    }
    /** Invariant includes offhand, armor and cursor; no hidden second medical/grenade stack. */
    public static void normalize(ServerPlayer p,Consumer<ItemStack> overflow){
        List<ItemStack> all=new ArrayList<>();boolean bad=false;
        Set<SupplyRules.Slot> seen=EnumSet.noneOf(SupplyRules.Slot.class);
        for(int i=0;i<p.getInventory().getContainerSize();i++){
            ItemStack stack=p.getInventory().getItem(i);if(stack.isEmpty())continue;
            var cat=category(stack);int target=slot(cat);
            if(cat==SupplyRules.Slot.NONE||target>=0&&(i!=target||stack.getCount()!=1||!seen.add(cat))||cat==SupplyRules.Slot.AMMO&&(i<9||i>=36))bad=true;
            all.add(stack);
        }
        ItemStack carried=p.containerMenu.getCarried();
        if(!carried.isEmpty()){bad=true;all.add(carried);}
        if(!bad)return;
        p.containerMenu.setCarried(ItemStack.EMPTY);p.getInventory().clearContent();
        for(ItemStack stack:all){
            var cat=category(stack);int target=slot(cat);
            if(target>=0){
                if(p.getInventory().getItem(target).isEmpty()){
                    ItemStack keep=stack.copyWithCount(1);
                    if(cat==SupplyRules.Slot.THROWABLE)keep.set(DataComponents.MAX_STACK_SIZE,1);
                    p.getInventory().setItem(target,keep);stack=stack.copy();stack.shrink(1);
                }
                while(!stack.isEmpty()){overflow.accept(stack.copyWithCount(1));stack.shrink(1);}
            }else if(cat==SupplyRules.Slot.AMMO){
                for(int i=9;i<36&&!stack.isEmpty();i++)if(p.getInventory().getItem(i).isEmpty()){
                    int n=Math.min(stack.getMaxStackSize(),stack.getCount());p.getInventory().setItem(i,stack.copyWithCount(n));stack.shrink(n);
                }
                // Temporary ammunition has no external owner; cap overflow cannot escape the minigame.
            }
        }
        p.inventoryMenu.broadcastChanges();
    }
}
