package net.muxigame.outbreak.equipment;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.muxigame.outbreak.OutbreakSession;

/** Block half of campaign F: checkpoint rules first, then native block-only use. */
public final class CampaignInteractions {
    private CampaignInteractions() {}
    public static boolean interactBlock(ServerPlayer player,OutbreakSession session,BlockHitResult hit){
        var level=player.serverLevel();var pos=hit.getBlockPos();
        if(!level.mayInteract(player,pos))return false;
        if(session.checkpointDoors.interact(level,pos,session.map,session.section,
            session.phase==OutbreakSession.Phase.START_ROOM||session.phase==OutbreakSession.Phase.RUNNING))return true;
        var state=level.getBlockState(pos);
        // Converted beds are scenery; this dimension cannot sleep and vanilla use explodes.
        if(state.getBlock() instanceof BedBlock)return true;
        if(state.getBlock() instanceof DoorBlock door){
            door.setOpen(player,level,state,pos,!state.getValue(DoorBlock.OPEN));
            return true;
        }
        // Campaign inventory remains sealed; scenery containers are not supply nodes.
        var block=state.getBlock();
        if(!(block instanceof ButtonBlock||block instanceof LeverBlock||block instanceof TrapDoorBlock||block instanceof FenceGateBlock))return false;
        // Buttons, levers, trapdoors and gates retain their native behavior.
        // No useItem/useItemOn: F must never reload/fire/use medicine or place a held item.
        return state.useWithoutItem(level,player,hit).consumesAction();
    }
}
