package dev.mingle;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

/** One lockable room around the edge of the arena. All coordinates are block coordinates. */
public final class Room {

    public enum DoorState { CLOSED, OPEN, LOCKED }

    final int id;
    final int minX, maxX, minZ, maxZ;   // interior bounds (inclusive)
    final int doorX, doorZ;             // door column (2 blocks tall)
    final int outX, outZ;               // the arena block right in front of the door
    final BlockFace inward;             // direction from the room towards the arena
    final int height;                   // interior height

    DoorState door = DoorState.CLOSED;

    Room(int id, int minX, int maxX, int minZ, int maxZ, int doorX, int doorZ, int outX, int outZ,
         BlockFace inward, int height) {
        this.id = id;
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.doorX = doorX;
        this.doorZ = doorZ;
        this.outX = outX;
        this.outZ = outZ;
        this.inward = inward;
        this.height = height;
    }

    public int id() { return id; }

    public DoorState door() { return door; }

    boolean contains(Location loc) {
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ
                && y > Arena.FLOOR_Y && y <= Arena.FLOOR_Y + height;
    }

    boolean inDoorway(Location loc) {
        int y = loc.getBlockY();
        return loc.getBlockX() == doorX && loc.getBlockZ() == doorZ
                && y > Arena.FLOOR_Y && y <= Arena.FLOOR_Y + 2;
    }

    Location outside(World world) {
        Location l = new Location(world, outX + 0.5, Arena.FLOOR_Y + 1, outZ + 0.5);
        l.setDirection(inward.getDirection());
        return l;
    }
}
