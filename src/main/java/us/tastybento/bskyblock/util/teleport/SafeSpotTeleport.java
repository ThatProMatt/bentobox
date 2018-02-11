package us.tastybento.bskyblock.util.teleport;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import us.tastybento.bskyblock.BSkyBlock;
import us.tastybento.bskyblock.database.objects.Island;
import us.tastybento.bskyblock.util.Pair;

/**
 * A class that calculates finds a safe spot asynchronously and then teleports the player there.
 * @author tastybento
 *
 */
public class SafeSpotTeleport {

    private static final int MAX_CHUNKS = 50;
    private static final long SPEED = 5;
    private boolean checking = true;
    private BukkitTask task;

    // Parameters
    private final Entity entity;
    private final Location location;
    private final boolean portal;
    private final int homeNumber;

    // Locations
    private Location bestSpot;


    private BSkyBlock plugin;
    private List<Pair<Integer, Integer>> chunksToScan;

    /**
     * Teleports and entity to a safe spot on island
     * @param plugin
     * @param entity
     * @param location
     * @param failureMessage - already translated failure message
     * @param portal
     * @param homeNumber
     */
    protected SafeSpotTeleport(BSkyBlock plugin, Entity entity, Location location, String failureMessage, boolean portal,
            int homeNumber) {
        this.plugin = plugin;
        this.entity = entity;
        this.location = location;
        this.portal = portal;
        this.homeNumber = homeNumber;

        // Put player into spectator mode
        if (entity instanceof Player && ((Player)entity).getGameMode().equals(GameMode.SURVIVAL)) {
            ((Player)entity).setGameMode(GameMode.SPECTATOR);
        }

        // Get chunks to scan
        chunksToScan = getChunksToScan();

        // Start checking
        checking = true;

        // Start a recurring task until done or cancelled
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            List<ChunkSnapshot> chunkSnapshot = new ArrayList<>();
            if (checking) {
                Iterator<Pair<Integer, Integer>> it = chunksToScan.iterator();
                if (!it.hasNext()) {
                    // Nothing left
                    tidyUp(entity, failureMessage);
                    return;
                }
                // Add chunk snapshots to the list
                while (it.hasNext() && chunkSnapshot.size() < MAX_CHUNKS) {
                    Pair<Integer, Integer> pair = it.next();
                    chunkSnapshot.add(location.getWorld().getChunkAt(pair.x, pair.z).getChunkSnapshot());
                    it.remove();
                }
                // Move to next step
                checking = false;
                checkChunks(chunkSnapshot);
            }
        }, 0L, SPEED);
    }

    private void tidyUp(Entity entity, String failureMessage) {
        // Nothing left to check and still not canceled
        task.cancel();
        // Check portal
        if (portal && bestSpot != null) {
            // No portals found, teleport to the best spot we found
            teleportEntity(bestSpot);
            return;
        }
        // Failed - no safe spot 
        if (entity instanceof Player && !failureMessage.isEmpty()) {
            entity.sendMessage(failureMessage);
        }
    }

    /**
     * Gets a set of chunk coords that will be scanned.
     * @param entity
     * @param location
     * @return
     */
    private List<Pair<Integer, Integer>> getChunksToScan() {
        List<Pair<Integer, Integer>> result = new ArrayList<>();
        // Get island if available
        Optional<Island> island = plugin.getIslands().getIslandAt(location);
        int maxRadius = island.map(Island::getProtectionRange).orElse(plugin.getSettings().getIslandProtectionRange());
        int x = location.getBlockX();
        int z = location.getBlockZ();
        // Create ever increasing squares around the target location
        int radius = 0;
        do {
            for (int i = x - radius; i <= x + radius; i++) {
                for (int j = z - radius; j <= z + radius; j++) {

                    Pair<Integer, Integer> blockCoord = new Pair<>(i,j);
                    Pair<Integer, Integer> chunkCoord = new Pair<>(i/16, j/16);    
                    if (!result.contains(chunkCoord)) {
                        // Add the chunk coord
                        if (!island.isPresent()) {
                            // If there is no island, just add it
                            result.add(chunkCoord);
                        } else {
                            // If there is an island, only add it if the coord is in island space
                            island.ifPresent(is -> {
                                if (is.inIslandSpace(blockCoord)) {
                                    result.add(chunkCoord);
                                }
                            });  
                        }
                    }
                }
            }
            radius++;
        } while (radius < maxRadius);
        return result;
    }

    /**
     * Loops through the chunks and if a safe spot is found, fires off the teleportation 
     * @param chunkSnapshot
     */
    private void checkChunks(List<ChunkSnapshot> chunkSnapshot) {
        // Run async task to scan chunks
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {

            for (ChunkSnapshot chunk: chunkSnapshot) {
                if (scanChunk(chunk)) {
                    task.cancel();
                    return;
                }
            }
            // Nothing happened, change state
            checking = true;
        });
    }


    /**
     * @param chunk
     * @return true if a safe spot was found
     */
    private boolean scanChunk(ChunkSnapshot chunk) { 
        // Max height
        int maxHeight = location.getWorld().getMaxHeight() - 20;
        // Run through the chunk
        for (int x = 0; x< 16; x++) {
            for (int z = 0; z < 16; z++) {
                // Work down from the entry point up
                for (int y = Math.min(chunk.getHighestBlockYAt(x, z), maxHeight); y >= 0; y--) {
                    if (checkBlock(chunk, x,y,z, maxHeight)) {
                        return true;
                    }
                } // end y
            } //end z
        } // end x
        return false;
    }

    /**
     * Teleports entity to the safe spot
     */
    private void teleportEntity(Location loc) {
        task.cancel();
        // Return to main thread and teleport the player
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!portal && entity instanceof Player) {
                // Set home
                plugin.getPlayers().setHomeLocation(entity.getUniqueId(), loc, homeNumber);
            }
            Vector velocity = entity.getVelocity();
            entity.teleport(loc);
            // Exit spectator mode if in it
            if (entity instanceof Player) {
                Player player = (Player)entity;
                if (player.getGameMode().equals(GameMode.SPECTATOR)) {
                    player.setGameMode(GameMode.SURVIVAL);
                }
            } else {
                entity.setVelocity(velocity);
            }
        });

    }

    /**
     * Returns true if the location is a safe one.
     * @param chunk
     * @param x
     * @param y
     * @param z
     * @param worldHeight
     * @return true if this is a safe spot, false if this is a portal scan
     */
    private boolean checkBlock(ChunkSnapshot chunk, int x, int y, int z, int worldHeight) {
        World world = location.getWorld();
        Material type = chunk.getBlockType(x, y, z);
        if (!type.equals(Material.AIR)) { // AIR
            Material space1 = chunk.getBlockType(x, Math.min(y + 1, worldHeight), z);
            Material space2 = chunk.getBlockType(x, Math.min(y + 2, worldHeight), z);
            if ((space1.equals(Material.AIR) && space2.equals(Material.AIR)) || (space1.equals(Material.PORTAL) && space2.equals(Material.PORTAL))
                    && (!type.toString().contains("FENCE") && !type.toString().contains("DOOR") && !type.toString().contains("GATE") && !type.toString().contains("PLATE"))) {
                switch (type) {
                // Unsafe
                case ANVIL:
                case BARRIER:
                case BOAT:
                case CACTUS:
                case DOUBLE_PLANT:
                case ENDER_PORTAL:
                case FIRE:
                case FLOWER_POT:
                case LADDER:
                case LAVA:
                case LEVER:
                case LONG_GRASS:
                case PISTON_EXTENSION:
                case PISTON_MOVING_PIECE:
                case SIGN_POST:
                case SKULL:
                case STANDING_BANNER:
                case STATIONARY_LAVA:
                case STATIONARY_WATER:
                case STONE_BUTTON:
                case TORCH:
                case TRIPWIRE:
                case WATER:
                case WEB:
                case WOOD_BUTTON:
                    //Block is dangerous
                    break;
                case PORTAL:
                    if (portal) {
                        Bukkit.getLogger().info("Portal found");
                        Vector newSpot = new Vector(chunk.getX() * 16 + x + 0.5D, y + 1, chunk.getZ() * 16 + z + 0.5D);
                        // Teleport as soon as we find a portal
                        teleportEntity(newSpot.toLocation(world));
                        return true;
                    }
                    break;
                default:
                    // Safe
                    Vector newSpot = new Vector(chunk.getX() * 16 + x + 0.5D, y + 1, chunk.getZ() * 16 + z + 0.5D);
                    // Check for portal
                    if (portal) {
                        if (bestSpot == null) {
                            Bukkit.getLogger().info("Best spot found = " + bestSpot);
                            // Stash the best spot
                            bestSpot = newSpot.toLocation(world);
                            return false;
                        }
                    } else {
                        // Regular search - teleport as soon as we find something
                        Bukkit.getLogger().info("Safe spot found, teleporting to new spot");
                        teleportEntity(newSpot.toLocation(world));
                        return true;
                    }
                }
            }
        }
        return false;
    }


}