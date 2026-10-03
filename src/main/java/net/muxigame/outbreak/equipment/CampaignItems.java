package net.muxigame.outbreak.equipment;
import net.minecraft.world.item.Item;
import net.muxigame.minigames.equipment.SharedItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
/** Source-compatible façade. The common framework owns these exact historical registry IDs. */
public final class CampaignItems {
    public static final DeferredHolder<Item,SharedItems.MedicalItem> MEDKIT=SharedItems.LEGACY_MEDKIT;
    public static final DeferredHolder<Item,SharedItems.MedicalItem> PILLS=SharedItems.LEGACY_PILLS;
    public static final DeferredHolder<Item,SharedItems.MedicalItem> ADRENALINE=SharedItems.LEGACY_ADRENALINE;
    public static final DeferredHolder<Item,SharedItems.MedicalItem> DEFIB=SharedItems.LEGACY_DEFIB;
    public static final DeferredHolder<Item,SharedItems.MedicalItem> EXPLOSIVE_AMMO=SharedItems.LEGACY_EXPLOSIVE_AMMO;
    public static final DeferredHolder<Item,SharedItems.ThrowItem> PIPE=SharedItems.LEGACY_PIPE;
    public static final DeferredHolder<Item,SharedItems.ThrowItem> BILE=SharedItems.LEGACY_BILE;
    public static void register(IEventBus ignored){} // registered once on the framework's mod bus
    private CampaignItems(){}
}
