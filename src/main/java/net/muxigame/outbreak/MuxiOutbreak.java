package net.muxigame.outbreak;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(MuxiOutbreak.MOD_ID)
public final class MuxiOutbreak {
    public static final String MOD_ID = "muxi_outbreak";
    public static final Logger LOG = LoggerFactory.getLogger("muxi-outbreak");

    public MuxiOutbreak(IEventBus modBus) {
        net.muxigame.outbreak.equipment.CampaignItems.register(modBus);
        net.muxigame.minigames.GameAreas.register(MOD_ID,net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.ResourceLocation.parse("muxi_outbreak:campaign")));
        OutbreakGame game = new OutbreakGame();
        game.register(NeoForge.EVENT_BUS);
        LOG.info("muxi Outbreak loaded");
    }
}
