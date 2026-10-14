package dev.mingle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds and drives the physical arena:
 *
 * <pre>
 *   +---+---+---+---+---+
 *   | 1 | 2 | 3 | 4 |   |     rooms line all four walls,
 *   +-d-+-d-+-d-+-d-+---+     each with a 1x2 door (d) facing the middle
 *   |                   |
 *   |    .-carousel-.   |     the carousel spins (coloured wedges rotate)
 *   |   (     ()     )  |     while the music plays
 *   |    '----------'   |
 *   |                   |
 *   +-------------------+
 * </pre>
 *
 * The lobby is a glass platform floating above the arena so waiting players can watch.
 */
public final class Arena {

    public static final int FLOOR_Y = 64;
    public static final int LOBBY_Y = FLOOR_Y + 32;
    private static final int LOBBY_RADIUS = 8;

    private static final Material WALL = Material.PINK_CONCRETE;
    private static final Material ROOM_WALL = Material.MAGENTA_CONCRETE;
    private static final Material ROOM_FLOOR = Material.WHITE_WOOL;
    private static final Material TRIM = Material.PURPLE_CONCRETE;
    private static final Material DOOR_CLOSED = Material.LIGHT_GRAY_STAINED_GLASS;
    private static final Material DOOR_LOCKED = Material.RED_STAINED_GLASS;
    private static final Material[] WEDGES = {
            Material.RED_CONCRETE, Material.WHITE_CONCRETE,
            Material.YELLOW_CONCRETE, Material.WHITE_CONCRETE,
            Material.LIME_CONCRETE, Material.WHITE_CONCRETE,
            Material.LIGHT_BLUE_CONCRETE, Material.WHITE_CONCRETE,
    };

    /** The four walls. (u, v) -> (x, z) where u runs along the wall and v points away from the centre. */
    private enum WallSide {
        NORTH(BlockFace.SOUTH) { int x(int u, int v, int h) { return u; }            int z(int u, int v, int h) { return -(h + 1 + v); } },
        EAST(BlockFace.WEST)   { int x(int u, int v, int h) { return h + 1 + v; }    int z(int u, int v, int h) { return u; } },
        SOUTH(BlockFace.NORTH) { int x(int u, int v, int h) { return -u; }           int z(int u, int v, int h) { return h + 1 + v; } },
        WEST(BlockFace.EAST)   { int x(int u, int v, int h) { return -(h + 1 + v); } int z(int u, int v, int h) { return -u; } };

        final BlockFace inward;
        WallSide(BlockFace inward) { this.inward = inward; }
        abstract int x(int u, int v, int h);
        abstract int z(int u, int v, int h);
    }

    private final World world;
    private final int carouselRadius;
    private final int roomSize;
    private final int roomHeight;
    private final int roomCount;
    /** Half-extent of the open floor: the floor spans [-half, half] on both axes. */
    private final int half;

    private final List<Room> rooms = new ArrayList<>();
    private final Material[][] carouselCache;

    public Arena(World world, ConfigurationSection cfg) {
        this.world = world;
        this.carouselRadius = Math.max(3, cfg.getInt("carousel-radius", 9));
        this.roomSize = Math.max(2, cfg.getInt("room-size", 3));
        this.roomHeight = Math.max(2, cfg.getInt("room-height", 3));
        this.roomCount = Math.max(4, cfg.getInt("rooms", 16));
        int gap = Math.max(2, cfg.getInt("ring-gap", 6));

        int perSideMax = (roomCount + 3) / 4;
        int rowSpan = perSideMax * (roomSize + 1) + 1;
        this.half = Math.max(carouselRadius + gap, (rowSpan + 1) / 2);
        int d = carouselRadius * 2 + 1;
        this.carouselCache = new Material[d][d];
    }

    public World world() { return world; }
    public List<Room> rooms() { return Collections.unmodifiableList(rooms); }
    public int carouselRadius() { return carouselRadius; }

    // ------------------------------------------------------------------ building

    public void build() {
        rooms.clear();
        int outer = half + roomSize + 4;

        // wipe the whole volume
        fill(-outer, FLOOR_Y - 1, -outer, outer, FLOOR_Y + roomHeight + 8, outer, Material.AIR);

        // open floor (checkerboard) with a trim border
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                boolean edge = Math.abs(x) == half || Math.abs(z) == half;
                set(x, FLOOR_Y, z, edge ? TRIM : ((x + z & 1) == 0 ? Material.SMOOTH_QUARTZ : Material.PINK_TERRACOTTA));
            }
        }
        // something solid underneath so nothing looks like it is floating in the void
        fill(-half - roomSize - 2, FLOOR_Y - 1, -half - roomSize - 2, half + roomSize + 2, FLOOR_Y - 1, half + roomSize + 2, Material.BARRIER);

        int id = 1;
        for (WallSide side : WallSide.values()) {
            // front wall (with the room doors cut into it later)
            for (int u = -half - 1; u <= half + 1; u++) {
                for (int y = FLOOR_Y; y <= FLOOR_Y + roomHeight + 1; y++) {
                    set(side.x(u, 0, half), y, side.z(u, 0, half), y == FLOOR_Y + roomHeight + 1 ? TRIM : WALL);
                }
            }

            int n = roomCount / 4 + (side.ordinal() < roomCount % 4 ? 1 : 0);
            int pitch = roomSize + 1;
            int u0 = -((n * pitch - 1) / 2);
            for (int i = 0; i < n; i++) {
                rooms.add(buildRoom(id++, side, u0 + i * pitch));
            }
        }

        renderCarousel(0);
        buildLobby();
        closeAllDoors();
    }

    private Room buildRoom(int id, WallSide side, int a) {
        int s = roomSize, h = half;
        int top = FLOOR_Y + roomHeight + 1;

        for (int u = a - 1; u <= a + s; u++) {
            for (int v = 1; v <= s + 1; v++) {
                boolean wall = u == a - 1 || u == a + s || v == s + 1;
                int x = side.x(u, v, h), z = side.z(u, v, h);
                set(x, FLOOR_Y, z, wall ? ROOM_WALL : ROOM_FLOOR);
                for (int y = FLOOR_Y + 1; y < top; y++) {
                    set(x, y, z, wall ? ROOM_WALL : Material.AIR);
                }
                set(x, top, z, TRIM);
            }
        }
        // light in the ceiling
        int mid = a + s / 2;
        set(side.x(mid, (s + 1) / 2, h), top, side.z(mid, (s + 1) / 2, h), Material.SEA_LANTERN);

        int doorX = side.x(mid, 0, h), doorZ = side.z(mid, 0, h);
        int outX = side.x(mid, -1, h), outZ = side.z(mid, -1, h);

        // number sign above the door
        if (roomHeight >= 3) {
            Block b = world.getBlockAt(outX, FLOOR_Y + 3, outZ);
            b.setType(Material.BIRCH_WALL_SIGN, false);
            if (b.getBlockData() instanceof WallSign data) {
                data.setFacing(side.inward);
                b.setBlockData(data, false);
            }
            if (b.getState() instanceof Sign sign) {
                sign.getSide(Side.FRONT).line(1, Component.text("ROOM", NamedTextColor.DARK_PURPLE));
                sign.getSide(Side.FRONT).line(2, Component.text(String.valueOf(id), NamedTextColor.DARK_PURPLE)
                        .decorate(TextDecoration.BOLD));
                sign.setWaxed(true);
                sign.update(true, false);
            }
        }

        int x1 = side.x(a, 1, h), z1 = side.z(a, 1, h);
        int x2 = side.x(a + s - 1, s, h), z2 = side.z(a + s - 1, s, h);
        return new Room(id,
                Math.min(x1, x2), Math.max(x1, x2), Math.min(z1, z2), Math.max(z1, z2),
                doorX, doorZ, outX, outZ, side.inward, roomHeight);
    }

    private void buildLobby() {
        int r = LOBBY_RADIUS;
        fill(-r - 1, LOBBY_Y, -r - 1, r + 1, LOBBY_Y + 4, r + 1, Material.AIR);
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                boolean border = Math.abs(x) == r || Math.abs(z) == r;
                set(x, LOBBY_Y, z, border ? Material.PURPLE_STAINED_GLASS : Material.GLASS);
                if (border) {
                    for (int y = LOBBY_Y + 1; y <= LOBBY_Y + 3; y++) set(x, y, z, Material.BARRIER);
                }
            }
        }
        set(0, LOBBY_Y, 0, Material.SEA_LANTERN);
    }

    // ------------------------------------------------------------------ carousel

    /** Paints the carousel with its coloured wedges rotated by {@code angle} radians. */
    public void renderCarousel(double angle) {
        int r = carouselRadius;
        double wedge = Math.PI * 2 / WEDGES.length;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                double dist = Math.sqrt(x * x + z * z);
                if (dist > r + 0.5) continue;
                Material m;
                if (dist <= 1.5) {
                    m = Material.GOLD_BLOCK;
                } else if (dist > r - 0.5) {
                    m = Material.PURPLE_CONCRETE;
                } else {
                    double a = Math.atan2(z, x) + angle;
                    a = ((a % (Math.PI * 2)) + Math.PI * 2) % (Math.PI * 2);
                    m = WEDGES[(int) (a / wedge) % WEDGES.length];
                }
                if (carouselCache[x + r][z + r] != m) {
                    carouselCache[x + r][z + r] = m;
                    set(x, FLOOR_Y, z, m);
                }
            }
        }
    }

    public boolean onCarousel(Location loc) {
        double x = loc.getX(), z = loc.getZ();
        return x * x + z * z <= carouselRadius * carouselRadius && loc.getY() < FLOOR_Y + 3;
    }

    /** Evenly spaced standing spots around the carousel. */
    public Location carouselSpot(int index, int total) {
        double radius = Math.max(2.5, carouselRadius - 2.5);
        double a = Math.PI * 2 * index / Math.max(1, total);
        Location l = new Location(world, Math.cos(a) * radius + 0.5, FLOOR_Y + 1, Math.sin(a) * radius + 0.5);
        l.setDirection(new Location(world, 0.5, FLOOR_Y + 1, 0.5).toVector().subtract(l.toVector()));
        return l;
    }

    // ------------------------------------------------------------------ doors

    public void setDoor(Room room, Room.DoorState state) {
        room.door = state;
        Material m = switch (state) {
            case OPEN -> Material.AIR;
            case CLOSED -> DOOR_CLOSED;
            case LOCKED -> DOOR_LOCKED;
        };
        if (state != Room.DoorState.OPEN) {
            // never seal someone inside a door block
            Location out = room.outside(world);
            for (Player p : world.getPlayers()) {
                if (room.inDoorway(p.getLocation())) {
                    out.setPitch(p.getLocation().getPitch());
                    p.teleport(out);
                }
            }
        }
        set(room.doorX, FLOOR_Y + 1, room.doorZ, m);
        set(room.doorX, FLOOR_Y + 2, room.doorZ, m);
    }

    public void closeAllDoors() {
        for (Room room : rooms) setDoor(room, Room.DoorState.CLOSED);
    }

    public Room roomAt(Location loc) {
        if (loc.getWorld() != world) return null;
        for (Room room : rooms) {
            if (room.contains(loc)) return room;
        }
        return null;
    }

    // ------------------------------------------------------------------ locations

    public Location lobby() {
        return new Location(world, 0.5, LOBBY_Y + 1, 0.5);
    }

    public Location spectatorView() {
        Location l = new Location(world, 0.5, FLOOR_Y + 14, half + 0.5);
        l.setDirection(new Location(world, 0.5, FLOOR_Y, 0.5).toVector().subtract(l.toVector()));
        return l;
    }

    /** True if the location is somewhere a player should never end up (fell out, glitched outside). */
    public boolean outOfBounds(Location loc) {
        if (loc.getWorld() != world) return true;
        int limit = half + roomSize + 4;
        return loc.getY() < FLOOR_Y - 5 || Math.abs(loc.getX()) > limit + 20 || Math.abs(loc.getZ()) > limit + 20;
    }

    // ------------------------------------------------------------------ helpers

    private void set(int x, int y, int z, Material m) {
        world.getBlockAt(x, y, z).setType(m, false);
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material m) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
                    set(x, y, z, m);
    }
}
