package net.muxigame.outbreak.map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import java.util.*;
/** Native checkpoint doors fitted to the converted map's existing openings. */
public final class CheckpointDoors {
    private final Map<BlockPos,BlockState> original=new LinkedHashMap<>();
    private final List<OutbreakMap.SafeRoom> groups=new ArrayList<>();
    public void install(ServerLevel level,OutbreakMap map) {
        if(!original.isEmpty())return;
        groups.addAll(map.startRooms());groups.addAll(map.safeRooms());
        for(var room:groups)for(var p:room.doors()) {
            if(level.getBlockState(p.below()).getCollisionShape(level,p.below()).isEmpty())throw new IllegalStateException("Checkpoint door has no floor: "+p);
            var lower=level.getBlockState(p);var upper=level.getBlockState(p.above());
            // A saved authored door may survive a crash after player snapshots were saved.
            // Only the configured native iron-door cells are repairable; other obstructions fail.
            if((!lower.is(Blocks.IRON_DOOR)&&!lower.getCollisionShape(level,p).isEmpty())||(!upper.is(Blocks.IRON_DOOR)&&!upper.getCollisionShape(level,p.above()).isEmpty()))throw new IllegalStateException("Checkpoint door opening is obstructed: "+p);
            original.put(p,lower.is(Blocks.IRON_DOOR)?Blocks.AIR.defaultBlockState():lower);
            original.put(p.above(),upper.is(Blocks.IRON_DOOR)?Blocks.AIR.defaultBlockState():upper);
            var facing=room.doors().size()>1&&room.doors().get(0).getX()==room.doors().get(1).getX()?Direction.EAST:Direction.SOUTH;
            var state=Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING,facing).setValue(DoorBlock.OPEN,map.safeRooms().contains(room));
            level.setBlock(p,state.setValue(DoorBlock.HALF,DoubleBlockHalf.LOWER),3);
            level.setBlock(p.above(),state.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER),3);
        }
    }
    public boolean interact(ServerLevel level,BlockPos pos,OutbreakMap map,int section,boolean enabled) {
        for(var room:groups)if(room.doors().stream().anyMatch(p->p.equals(pos)||p.above().equals(pos))) {
            boolean current=map.startRoom(section)==room||room.nextSection()==section+1&&map.safeRooms().contains(room);
            if(!enabled||!current)return true;
            boolean open=closed(level,room);
            for(var p:room.doors())for(var at:List.of(p,p.above())) {
                var state=level.getBlockState(at);
                if(state.getBlock() instanceof DoorBlock)level.setBlock(at,state.setValue(DoorBlock.OPEN,open),3);
            }
            level.levelEvent(open?1005:1011,room.doors().get(0),0);
            return true;
        }
        return false;
    }
    public boolean closed(ServerLevel level,OutbreakMap.SafeRoom room) {
        for(var p:room.doors()) {
            var state=level.getBlockState(p);
            if(!(state.getBlock() instanceof DoorBlock)||state.getValue(DoorBlock.OPEN))return false;
        }
        return true;
    }
    public void restore(ServerLevel level) {original.forEach((p,state)->level.setBlock(p,state,3));original.clear();groups.clear();}
}
