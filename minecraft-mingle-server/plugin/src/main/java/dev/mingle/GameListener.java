package dev.mingle;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.util.Vector;

/** Keeps the server a safe, unbreakable Mingle playground. */
public final class GameListener implements Listener {

    private final MinglePlugin plugin;

    public GameListener(MinglePlugin plugin) {
        this.plugin = plugin;
    }

    private boolean builder(Player p) {
        return p.getGameMode() == GameMode.CREATIVE && p.hasPermission("mingle.admin");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (plugin.game() != null) plugin.game().onJoin(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (plugin.game() != null) plugin.game().onQuit(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player) e.setCancelled(true);
    }

    /** No damage, but you can shove people - very useful when a room is one over. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onShove(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim) || !(e.getDamager() instanceof Player attacker)) return;
        e.setCancelled(true);
        Game game = plugin.game();
        if (game == null || !game.isAlive(victim) || !game.isAlive(attacker)) return;
        if (game.state() != Game.State.FIND && game.state() != Game.State.SPIN) return;
        Vector push = victim.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
        if (push.lengthSquared() < 1.0E-4) return;
        victim.setVelocity(push.normalize().multiply(0.9).setY(0.3));
    }

    @EventHandler
    public void onHunger(FoodLevelChangeEvent e) {
        e.setCancelled(true);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (!builder(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (!builder(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (!builder(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (!builder(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (!builder(e.getPlayer()) && e.getClickedBlock() != null) e.setCancelled(true);
    }
}
