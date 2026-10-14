package dev.mingle;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Mingle game loop.
 *
 * <pre>
 *  WAITING -> COUNTDOWN -> [ SPIN -> FIND -> RESOLVE ] x N -> ENDED -> WAITING
 * </pre>
 *
 * SPIN    everyone rides the carousel while the music plays.
 * FIND    a number is called and some doors open. A room whose head-count hits the number
 *         exactly locks itself. Only (alive - 1) / number rooms open, so every round
 *         eliminates at least one player and the game always finishes.
 * RESOLVE anyone not inside a locked room is eliminated.
 */
public final class Game {

    public enum State { WAITING, COUNTDOWN, SPIN, FIND, RESOLVE, ENDED }

    private static final TextColor PINK = TextColor.color(0xFF5FA2);

    /** Carousel waltz, in note-block semitones (0..24); -1 = rest. One entry per beat. */
    private static final int[] MELODY = {
            6, 10, 13, 10, 13, -1, 8, 11, 15, 11, 15, -1,
            6, 10, 13, 18, 17, 15, 13, 11, 10, 8, 6, -1,
            11, 15, 18, 15, 18, -1, 10, 13, 17, 13, 17, -1,
            18, 17, 15, 13, 11, 10, 8, 10, 6, -1, -1, -1,
    };
    private static final int BEAT_TICKS = 5;

    private final MinglePlugin plugin;
    private final Arena arena;

    // settings
    private final int minPlayers, lobbyCountdown, spinTicks, findTicks, minNumber, maxNumber, winners;
    private final boolean spinPush;

    // state
    private State state = State.WAITING;
    private final Set<UUID> optedOut = new HashSet<>();
    private final Set<UUID> alive = new LinkedHashSet<>();
    private int timer;          // ticks left in the current phase
    private int phaseLength;    // ticks the phase started with (for the boss bar)
    private int round;
    private int number;
    private int tick;
    private double carouselAngle;
    private final Set<Room> openRooms = new HashSet<>();
    private final BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.PINK, BossBar.Overlay.PROGRESS);
    private BukkitTask task;

    public Game(MinglePlugin plugin, Arena arena, ConfigurationSection cfg) {
        this.plugin = plugin;
        this.arena = arena;
        this.minPlayers = Math.max(2, cfg.getInt("min-players", 2));
        this.lobbyCountdown = Math.max(1, cfg.getInt("lobby-countdown", 30)) * 20;
        this.spinTicks = Math.max(2, cfg.getInt("spin-time", 12)) * 20;
        this.findTicks = Math.max(3, cfg.getInt("find-time", 20)) * 20;
        this.minNumber = Math.max(1, cfg.getInt("min-number", 2));
        this.maxNumber = Math.max(minNumber, cfg.getInt("max-number", 6));
        this.winners = Math.max(1, cfg.getInt("winners", 1));
        this.spinPush = cfg.getBoolean("spin-push", true);
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        for (Player p : Bukkit.getOnlinePlayers()) onJoin(p);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
        arena.closeAllDoors();
    }

    public State state() { return state; }
    public boolean running() { return state == State.SPIN || state == State.FIND || state == State.RESOLVE; }
    public boolean isAlive(Player p) { return alive.contains(p.getUniqueId()); }
    public int round() { return round; }
    public int aliveCount() { return alive.size(); }
    public int number() { return number; }

    // ------------------------------------------------------------------ players

    public void onJoin(Player p) {
        p.showBossBar(bar);
        if (running() || state == State.ENDED) {
            toSpectator(p);
            p.sendMessage(Component.text("A game is in progress - you're spectating. You'll join the next one.", PINK));
        } else {
            toLobby(p);
        }
    }

    public void onQuit(Player p) {
        p.hideBossBar(bar);
        if (alive.remove(p.getUniqueId()) && running()) {
            broadcast(Component.text(p.getName() + " left the game.", NamedTextColor.GRAY));
            checkForWinner();
        }
    }

    public boolean join(Player p) {
        boolean changed = optedOut.remove(p.getUniqueId());
        if (!running()) toLobby(p);
        return changed;
    }

    public boolean leave(Player p) {
        boolean changed = optedOut.add(p.getUniqueId());
        if (alive.remove(p.getUniqueId())) {
            broadcast(Component.text(p.getName() + " gave up.", NamedTextColor.GRAY));
            checkForWinner();
        }
        toSpectator(p);
        return changed;
    }

    private List<Player> queued() {
        List<Player> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!optedOut.contains(p.getUniqueId())) list.add(p);
        }
        return list;
    }

    private List<Player> alivePlayers() {
        List<Player> list = new ArrayList<>();
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) list.add(p);
        }
        return list;
    }

    private void reset(Player p, GameMode mode) {
        p.setGameMode(mode);
        p.getInventory().clear();
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0);
        AttributeInstance hp = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(hp != null ? hp.getValue() : 20.0);
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
    }

    private void toLobby(Player p) {
        reset(p, optedOut.contains(p.getUniqueId()) ? GameMode.SPECTATOR : GameMode.ADVENTURE);
        p.teleport(arena.lobby());
    }

    private void toSpectator(Player p) {
        reset(p, GameMode.SPECTATOR);
        p.teleport(arena.spectatorView());
    }

    // ------------------------------------------------------------------ admin

    public boolean forceStart() {
        if (running() || state == State.ENDED) return false;
        if (queued().size() < 2) return false;
        startGame();
        return true;
    }

    public void forceStop() {
        if (state == State.WAITING) return;
        broadcast(Component.text("The game was stopped by an admin.", NamedTextColor.RED));
        backToLobby();
    }

    // ------------------------------------------------------------------ loop

    private void tick() {
        tick++;
        switch (state) {
            case WAITING -> tickWaiting();
            case COUNTDOWN -> tickCountdown();
            case SPIN -> tickSpin();
            case FIND -> tickFind();
            case RESOLVE, ENDED -> tickPause();
        }
        if (phaseLength > 0) bar.progress(Math.max(0f, Math.min(1f, timer / (float) phaseLength)));
        if (state == State.WAITING || state == State.COUNTDOWN) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getGameMode() != GameMode.SPECTATOR && arena.outOfBounds(p.getLocation())) p.teleport(arena.lobby());
            }
        }
    }

    private void setPhase(State next, int ticks) {
        state = next;
        timer = ticks;
        phaseLength = ticks;
    }

    private void tickWaiting() {
        int q = queued().size();
        if (q >= minPlayers) {
            setPhase(State.COUNTDOWN, lobbyCountdown);
            broadcast(Component.text("Enough players! The game starts in " + lobbyCountdown / 20 + " seconds.", PINK));
            return;
        }
        if (tick % 20 == 0) {
            phaseLength = 0;
            bar.progress(1f);
            bar.color(BossBar.Color.PINK);
            bar.name(Component.text("Waiting for players  " + q + "/" + minPlayers, PINK));
        }
    }

    private void tickCountdown() {
        if (queued().size() < minPlayers) {
            setPhase(State.WAITING, 0);
            broadcast(Component.text("Not enough players - countdown cancelled.", NamedTextColor.GRAY));
            return;
        }
        int secs = (timer + 19) / 20;
        if (timer % 20 == 0) {
            bar.name(Component.text("Mingle starts in " + secs + "s", PINK));
            if (secs <= 5 && secs > 0) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
                    p.showTitle(Title.title(Component.text(secs, PINK).decorate(TextDecoration.BOLD), Component.empty(),
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100))));
                }
            }
        }
        if (--timer <= 0) startGame();
    }

    private void startGame() {
        alive.clear();
        for (Player p : queued()) {
            alive.add(p.getUniqueId());
            reset(p, GameMode.ADVENTURE);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!alive.contains(p.getUniqueId())) toSpectator(p);
        }
        round = 0;
        broadcast(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━", PINK));
        broadcast(Component.text(" MINGLE", PINK).decorate(TextDecoration.BOLD)
                .append(Component.text("  -  " + alive.size() + " players", NamedTextColor.WHITE)));
        broadcast(Component.text(" Ride the carousel. When the music stops, a number is called.", NamedTextColor.GRAY));
        broadcast(Component.text(" Get into a room with EXACTLY that many players - it locks you in.", NamedTextColor.GRAY));
        broadcast(Component.text(" Anyone left outside a locked room is eliminated.", NamedTextColor.GRAY));
        broadcast(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━", PINK));
        startRound();
    }

    private void startRound() {
        round++;
        number = 0;
        openRooms.clear();
        arena.closeAllDoors();
        List<Player> players = alivePlayers();
        Collections.shuffle(players);
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            p.teleport(arena.carouselSpot(i, players.size()));
            p.showTitle(Title.title(Component.text("Round " + round, PINK).decorate(TextDecoration.BOLD),
                    Component.text("Round and round we go...", NamedTextColor.WHITE)));
        }
        bar.color(BossBar.Color.PINK);
        bar.name(Component.text("Round " + round + "  ·  " + alive.size() + " alive  ·  the carousel is spinning", PINK));
        setPhase(State.SPIN, spinTicks);
    }

    private void tickSpin() {
        // spin speeds up as the round goes on
        double progress = 1.0 - timer / (double) phaseLength;
        double speed = 0.03 + 0.05 * progress;
        carouselAngle += speed;
        if (tick % 2 == 0) arena.renderCarousel(carouselAngle);

        if (spinPush && tick % 2 == 0) {
            for (Player p : alivePlayers()) {
                Location l = p.getLocation();
                if (!arena.onCarousel(l)) continue;
                double r = Math.hypot(l.getX(), l.getZ());
                if (r < 0.5) continue;
                // tangential push, like being carried around by the spinning floor
                Vector tangent = new Vector(-l.getZ() / r, 0, l.getX() / r).multiply(-speed * r * 0.5);
                Vector v = p.getVelocity();
                p.setVelocity(new Vector(tangent.getX(), v.getY(), tangent.getZ()));
            }
        }

        if (tick % BEAT_TICKS == 0) {
            int beat = (tick / BEAT_TICKS) % MELODY.length;
            int note = MELODY[beat];
            for (Player p : arena.world().getPlayers()) {
                Location l = p.getLocation();
                if (note >= 0) p.playSound(l, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, pitch(note));
                if (beat % 3 == 0) p.playSound(l, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, pitch((beat / 6) % 2 == 0 ? 6 : 13));
                else p.playSound(l, Sound.BLOCK_NOTE_BLOCK_HAT, 0.3f, 1.2f);
            }
        }

        if (--timer <= 0) callNumber();
    }

    private static float pitch(int note) {
        return (float) Math.pow(2.0, (note - 12) / 12.0);
    }

    private void callNumber() {
        int n = alive.size();
        // choose a group size that leaves at least one free slot unavailable
        List<Integer> options = new ArrayList<>();
        for (int k = minNumber; k <= Math.min(maxNumber, n - 1); k++) options.add(k);
        number = options.isEmpty() ? Math.max(1, n - 1) : options.get(ThreadLocalRandom.current().nextInt(options.size()));

        int roomsToOpen = Math.max(1, Math.min(arena.rooms().size(), (n - 1) / number));
        List<Room> shuffled = new ArrayList<>(arena.rooms());
        Collections.shuffle(shuffled);
        openRooms.clear();
        openRooms.addAll(shuffled.subList(0, roomsToOpen));
        for (Room r : openRooms) arena.setDoor(r, Room.DoorState.OPEN);

        Component big = Component.text(number, NamedTextColor.RED).decorate(TextDecoration.BOLD);
        Component sub = Component.text("Groups of " + number + "!  " + roomsToOpen + " room" + (roomsToOpen == 1 ? "" : "s") + " open", NamedTextColor.WHITE);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(Title.title(big, sub, Title.Times.times(Duration.ZERO, Duration.ofSeconds(3), Duration.ofMillis(300))));
        }
        broadcast(Component.text("The music stops... ", NamedTextColor.GRAY)
                .append(Component.text(number + "!", NamedTextColor.RED).decorate(TextDecoration.BOLD))
                .append(Component.text("  (" + roomsToOpen + " room" + (roomsToOpen == 1 ? "" : "s") + " open)", NamedTextColor.GRAY)));
        // ring the bell once per person in a group
        for (int i = 0; i < number; i++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (Player p : arena.world().getPlayers()) p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 1f, 1f);
            }, i * 8L);
        }
        bar.color(BossBar.Color.RED);
        setPhase(State.FIND, findTicks);
    }

    private void tickFind() {
        if (tick % 4 == 0) {
            Map<Room, List<Player>> occupants = new HashMap<>();
            Map<Player, Room> where = new HashMap<>();
            for (Player p : alivePlayers()) {
                Room r = arena.roomAt(p.getLocation());
                if (r == null) continue;
                where.put(p, r);
                occupants.computeIfAbsent(r, k -> new ArrayList<>()).add(p);
            }
            for (Map.Entry<Room, List<Player>> e : occupants.entrySet()) {
                Room r = e.getKey();
                if (r.door() == Room.DoorState.OPEN && e.getValue().size() == number) lock(r, e.getValue());
            }
            for (Player p : alivePlayers()) {
                Room r = where.get(p);
                Component msg;
                if (r != null && r.door() == Room.DoorState.LOCKED) {
                    msg = Component.text("SAFE - room " + r.id() + " is locked", NamedTextColor.GREEN).decorate(TextDecoration.BOLD);
                } else if (r != null) {
                    int c = occupants.get(r).size();
                    msg = Component.text("Room " + r.id() + ": " + c + "/" + number
                            + (c > number ? "  - too many! someone has to leave" : ""), c > number ? NamedTextColor.RED : NamedTextColor.YELLOW);
                } else {
                    msg = Component.text("Find a group of " + number + " and get into a room!", NamedTextColor.RED);
                }
                p.sendActionBar(msg);
            }
        }
        int secs = (timer + 19) / 20;
        long locked = openRooms.stream().filter(r -> r.door() == Room.DoorState.LOCKED).count();
        bar.name(Component.text("Groups of " + number + "  ·  " + secs + "s  ·  " + locked + "/" + openRooms.size() + " rooms locked", NamedTextColor.RED));
        if (timer % 20 == 0 && secs <= 5 && secs > 0) {
            for (Player p : arena.world().getPlayers()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.5f + secs * 0.1f);
        }
        // everyone safe or nobody can still make it -> finish early
        boolean allOpenLocked = openRooms.stream().allMatch(r -> r.door() == Room.DoorState.LOCKED);
        if (--timer <= 0 || allOpenLocked) resolve();
    }

    private void lock(Room room, List<Player> inside) {
        arena.setDoor(room, Room.DoorState.LOCKED);
        for (Player p : inside) {
            p.playSound(p.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, 1f, 0.8f);
            p.showTitle(Title.title(Component.text("LOCKED IN", NamedTextColor.GREEN).decorate(TextDecoration.BOLD),
                    Component.text("You're safe this round", NamedTextColor.WHITE),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(300))));
        }
        Location door = room.outside(arena.world());
        arena.world().playSound(door, Sound.BLOCK_IRON_DOOR_CLOSE, 1f, 0.6f);
    }

    private void resolve() {
        List<Player> out = new ArrayList<>();
        for (Player p : alivePlayers()) {
            Room r = arena.roomAt(p.getLocation());
            if (r == null || r.door() != Room.DoorState.LOCKED) out.add(p);
        }
        for (Room r : arena.rooms()) {
            if (r.door() != Room.DoorState.LOCKED) arena.setDoor(r, Room.DoorState.CLOSED);
        }

        for (Player p : out) eliminate(p);
        broadcast(Component.text("Round " + round + ": ", PINK)
                .append(Component.text(out.size() + " eliminated", NamedTextColor.RED))
                .append(Component.text(", " + alive.size() + " remain.", NamedTextColor.WHITE)));
        bar.color(BossBar.Color.WHITE);
        bar.name(Component.text("Round " + round + " over  ·  " + alive.size() + " remain", NamedTextColor.WHITE));
        setPhase(State.RESOLVE, 100);
    }

    private void eliminate(Player p) {
        alive.remove(p.getUniqueId());
        arena.world().strikeLightningEffect(p.getLocation());
        p.showTitle(Title.title(Component.text("ELIMINATED", NamedTextColor.RED).decorate(TextDecoration.BOLD),
                Component.text("Round " + round, NamedTextColor.GRAY)));
        p.setGameMode(GameMode.SPECTATOR);
        broadcast(Component.text("  ✖ " + p.getName(), NamedTextColor.RED));
    }

    private void tickPause() {
        if (state == State.ENDED && tick % 10 == 0) {
            for (Player p : alivePlayers()) firework(p.getLocation());
        }
        if (--timer > 0) return;
        if (state == State.ENDED) {
            backToLobby();
        } else if (!checkForWinner()) {
            startRound();
        }
    }

    /** Ends the game if few enough players remain. */
    private boolean checkForWinner() {
        if (!running() || alive.size() > winners) return false;
        List<Player> champs = alivePlayers();
        openRooms.clear();
        arena.closeAllDoors();
        if (champs.isEmpty()) {
            broadcast(Component.text("Nobody survived. There are no winners.", NamedTextColor.RED));
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.showTitle(Title.title(Component.text("NO WINNER", NamedTextColor.RED).decorate(TextDecoration.BOLD), Component.empty()));
            }
        } else {
            List<String> names = champs.stream().map(Player::getName).toList();
            Component who = Component.text(String.join(", ", names), NamedTextColor.GOLD).decorate(TextDecoration.BOLD);
            broadcast(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━", PINK));
            broadcast(Component.text(" Winner" + (names.size() == 1 ? ": " : "s: "), PINK).append(who)
                    .append(Component.text("  after " + round + " round" + (round == 1 ? "" : "s"), NamedTextColor.GRAY)));
            broadcast(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━", PINK));
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.showTitle(Title.title(who, Component.text(names.size() == 1 ? "wins Mingle!" : "win Mingle!", PINK)));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
            for (Player p : champs) p.teleport(arena.carouselSpot(0, 1));
        }
        bar.color(BossBar.Color.YELLOW);
        bar.name(Component.text("Game over  ·  back to the lobby soon", NamedTextColor.GOLD));
        setPhase(State.ENDED, 200);
        return true;
    }

    private void firework(Location l) {
        Firework fw = l.getWorld().spawn(l.clone().add(0, 1, 0), Firework.class);
        FireworkMeta meta = fw.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.BALL_LARGE)
                .withColor(Color.fromRGB(0xFF5FA2), Color.WHITE)
                .withFade(Color.PURPLE)
                .flicker(true).build());
        meta.setPower(1);
        fw.setFireworkMeta(meta);
    }

    private void backToLobby() {
        alive.clear();
        openRooms.clear();
        number = 0;
        round = 0;
        arena.closeAllDoors();
        arena.renderCarousel(0);
        for (Player p : Bukkit.getOnlinePlayers()) toLobby(p);
        setPhase(State.WAITING, 0);
    }

    private void broadcast(Component c) {
        Bukkit.getServer().sendMessage(c);
    }
}
