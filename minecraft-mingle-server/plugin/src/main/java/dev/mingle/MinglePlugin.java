package dev.mingle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.stream.Stream;

public final class MinglePlugin extends JavaPlugin {

    private Arena arena;
    private Game game;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        // worlds are loaded after STARTUP plugins enable, so set up on the first tick
        Bukkit.getScheduler().runTask(this, this::setup);
    }

    @Override
    public void onDisable() {
        if (game != null) game.shutdown();
    }

    /** Lets bukkit.yml use "generator: Mingle" for the main world. */
    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return new VoidGenerator();
    }

    private void setup() {
        String name = getConfig().getString("world", "mingle");
        World world = Bukkit.getWorld(name);
        if (world == null) {
            getLogger().info("Creating void world '" + name + "'");
            world = new WorldCreator(name).generator(new VoidGenerator()).createWorld();
        }
        prepareWorld(world);

        arena = new Arena(world, getConfig().getConfigurationSection("arena"));
        long t = System.currentTimeMillis();
        arena.build();
        getLogger().info("Built arena with " + arena.rooms().size() + " rooms in " + (System.currentTimeMillis() - t) + " ms");

        game = new Game(this, arena, getConfig().getConfigurationSection("game"));
        game.start();
    }

    private void prepareWorld(World world) {
        world.setSpawnLocation(0, Arena.LOBBY_Y + 1, 0);
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setTime(6000);
        world.setStorm(false);
        world.setThundering(false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        world.setGameRule(GameRule.FALL_DAMAGE, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setGameRule(GameRule.SPAWN_RADIUS, 0);
    }

    public Arena arena() { return arena; }
    public Game game() { return game; }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (game == null) {
            sender.sendMessage(Component.text("Mingle is still starting up.", NamedTextColor.RED));
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "join" -> {
                if (!(sender instanceof Player p)) return playersOnly(sender);
                game.join(p);
                sender.sendMessage(Component.text("You're in the queue for the next game.", NamedTextColor.GREEN));
            }
            case "leave" -> {
                if (!(sender instanceof Player p)) return playersOnly(sender);
                game.leave(p);
                sender.sendMessage(Component.text("You're spectating. /mingle join to play again.", NamedTextColor.YELLOW));
            }
            case "status" -> {
                sender.sendMessage(Component.text("Mingle: " + game.state()
                        + (game.running() ? "  round " + game.round() + ", " + game.aliveCount() + " alive"
                        + (game.number() > 0 ? ", number " + game.number() : "") : ""), NamedTextColor.LIGHT_PURPLE));
            }
            case "start" -> {
                if (!admin(sender)) return true;
                sender.sendMessage(game.forceStart()
                        ? Component.text("Game started.", NamedTextColor.GREEN)
                        : Component.text("Can't start: need 2+ queued players and no game running.", NamedTextColor.RED));
            }
            case "stop" -> {
                if (!admin(sender)) return true;
                game.forceStop();
                sender.sendMessage(Component.text("Game stopped.", NamedTextColor.YELLOW));
            }
            case "rebuild", "reload" -> {
                if (!admin(sender)) return true;
                game.forceStop();
                game.shutdown();
                if (sub.equals("reload")) reloadConfig();
                setup();
                sender.sendMessage(Component.text("Arena rebuilt (" + arena.rooms().size() + " rooms).", NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text("/" + label + " <join|leave|status|start|stop|rebuild|reload>", NamedTextColor.GRAY));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        Stream<String> subs = sender.hasPermission("mingle.admin")
                ? Stream.of("join", "leave", "status", "start", "stop", "rebuild", "reload")
                : Stream.of("join", "leave", "status");
        return subs.filter(s -> s.startsWith(args[0].toLowerCase())).toList();
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("mingle.admin")) return true;
        sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
        return false;
    }

    private boolean playersOnly(CommandSender sender) {
        sender.sendMessage(Component.text("Only players can do that.", NamedTextColor.RED));
        return true;
    }
}
