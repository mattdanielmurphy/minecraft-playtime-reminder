package com.mattmurphy.playtimereminder;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class PlaytimeReminderMod implements ModInitializer {
	private static final int TICKS_PER_MINUTE = 20 * 60;

	private Config config = new Config();

	    private final Map<UUID, Integer> playerJoinTick = new HashMap<>();
	    private final Map<UUID, Integer> playerDisconnectTick = new HashMap<>(); // Tracks the tick a player disconnected
	    private final Map<UUID, Integer> lastReminderMinute = new HashMap<>();
	    private final Map<UUID, Boolean> warned5min = new HashMap<>();
	    private final Map<UUID, Boolean> warned1min = new HashMap<>();
	    private final Map<UUID, Boolean> warned10s = new HashMap<>();
	    private final Map<UUID, Integer> playerDailyPlaytime = new HashMap<>(); // Total minutes played today
	    private final Map<UUID, String> delayedJoinMessages = new HashMap<>(); // Messages to send after a short delay
	    private int currentServerTick = 0;
	    private int lastDayOfMonth = -1;
	
	    @Override
	    public void onInitialize() {
	        ServerLifecycleEvents.SERVER_STARTING.register(server -> loadConfig());
	
	        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
	            ServerPlayer player = handler.getPlayer();
	            UUID id = player.getUUID();
	            Integer disconnectTick = playerDisconnectTick.remove(id);
	
	            if (disconnectTick != null) {
	                long ticksSinceDisconnect = currentServerTick - disconnectTick;
	                long breakThresholdTicks = (long)config.breakDurationMinutes * TICKS_PER_MINUTE;
	
	                if (ticksSinceDisconnect >= breakThresholdTicks) {
	                    // Break taken, reset playtime
	                    playerJoinTick.put(id, currentServerTick);
	                } else {
	                    // Break NOT taken, playtime continues. 
	                    // We need to adjust the join tick to account for the time they were away.
	                    Integer oldJoinTick = playerJoinTick.get(id);
	                    if (oldJoinTick != null) {
	                        long ticksPlayed = currentServerTick - oldJoinTick;
	                        // The new join tick is moved forward by the time they were disconnected.
	                        // This preserves the session playtime but accounts for the break.
	                        int newJoinTick = (int) (currentServerTick - (ticksPlayed - ticksSinceDisconnect));
	                        playerJoinTick.put(id, newJoinTick);

	                        // Now, schedule a warning based on the adjusted playtime.
	                        long adjustedTicksPlayed = currentServerTick - newJoinTick;
	                        long nextKickTick = calculateNextKickTick(adjustedTicksPlayed);
	                        long ticksUntilKick = nextKickTick - adjustedTicksPlayed;
	                        long minutesUntilKick = (ticksUntilKick + TICKS_PER_MINUTE - 1) / TICKS_PER_MINUTE;

	                        if (minutesUntilKick > 0) {
	                            String message = "Your break was less than " + config.breakDurationMinutes + " minutes. Your playtime continues. You will be kicked in approximately " + minutesUntilKick + " minutes.";
	                            delayedJoinMessages.put(id, message);
	                        }
	                    }
	                }
	            } else {
	                // First join, record playtime
	                playerJoinTick.put(id, currentServerTick);
	            }
	
	            lastReminderMinute.remove(id);
	            warned5min.remove(id);
	            warned1min.remove(id);
	            warned10s.remove(id);
	        });
	
	        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
	            UUID id = handler.getPlayer().getUUID();
	            playerDisconnectTick.put(id, currentServerTick); // Store disconnect time
	            // Do NOT remove playerJoinTick, so we can check it on rejoin
	            lastReminderMinute.remove(id);
	            warned5min.remove(id);
	            warned1min.remove(id);
	            warned10s.remove(id);
	        });
	
	        ServerTickEvents.START_SERVER_TICK.register(this::onServerTick);
	    }
	
	    private long calculateNextKickTick(long ticksPlayed) {
	        long strongReminderThresholdTicks = (long)config.strongReminderThresholdMinutes * TICKS_PER_MINUTE;
	        long strongReminderRepeatTicks = (long)config.strongReminderRepeatMinutes * TICKS_PER_MINUTE;
	        
	        if (ticksPlayed < strongReminderThresholdTicks) {
	            // Case 1: Approaching the first kick
	            return strongReminderThresholdTicks;
	        } else {
	            // Case 2: After the first kick, calculating the next repeat kick
	            long ticksOverThreshold = ticksPlayed - strongReminderThresholdTicks;
	            long repeats = ticksOverThreshold / strongReminderRepeatTicks;
	            long lastKickTick = strongReminderThresholdTicks + repeats * strongReminderRepeatTicks;
	            return lastKickTick + strongReminderRepeatTicks;
	        }
	    }
	
	    private void onServerTick(MinecraftServer server) {
	        currentServerTick++;
	
	        // Check for day change
	        Calendar now = Calendar.getInstance();
	        int currentDayOfMonth = now.get(Calendar.DAY_OF_MONTH);
	        if (lastDayOfMonth == -1) {
	            lastDayOfMonth = currentDayOfMonth;
	        } else if (currentDayOfMonth != lastDayOfMonth) {
	            playerDailyPlaytime.clear();
	            lastDayOfMonth = currentDayOfMonth;
	        }
	
	        // Process delayed join messages (1 second delay = 20 ticks)
	        if (currentServerTick % 20 == 0) {
	            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
	                UUID id = player.getUUID();
	                String message = delayedJoinMessages.remove(id);
	                if (message != null) {
	                    player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.RED));
	                }
	            }
	        }
	
			java.util.List<ServerPlayer> playersToKick = new java.util.ArrayList<>();

	        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
	            UUID id = player.getUUID();
	            Integer joinTick = playerJoinTick.get(id);
	            if (joinTick == null) {
	                joinTick = currentServerTick; // fallback if missed join event
	                playerJoinTick.put(id, joinTick);
	            }
	
	            int ticksPlayed = currentServerTick - joinTick;
	            int minutesPlayed = Math.max(0, ticksPlayed / TICKS_PER_MINUTE);
	            int lastMinute = lastReminderMinute.getOrDefault(id, -1);
	            
	            // Update daily playtime
	            int dailyMinutes = playerDailyPlaytime.getOrDefault(id, 0);
	            if (minutesPlayed > 0 && minutesPlayed > dailyMinutes) {
	                playerDailyPlaytime.put(id, minutesPlayed);
	            }
	            
	            String dailyPlaytimeMessage = " (Total today: " + dailyMinutes + "m)";
	
	            // Strong reminder constants
	            long strongReminderThresholdTicks = (long)config.strongReminderThresholdMinutes * TICKS_PER_MINUTE;
	            long strongReminderRepeatTicks = (long)config.strongReminderRepeatMinutes * TICKS_PER_MINUTE;
	
	            // Regular reminders (once per minute)
	            boolean isRegularReminderTick = ticksPlayed % TICKS_PER_MINUTE == 0 && minutesPlayed > 0 && minutesPlayed < config.strongReminderThresholdMinutes && minutesPlayed % config.reminderIntervalMinutes == 0 && minutesPlayed != lastMinute;
	
	            // Check if a strong reminder warning is due in this minute.
	            // We only need to check if we are in the minute where a warning is sent.
	            boolean isStrongWarningMinute = false;
	            if (minutesPlayed < config.strongReminderThresholdMinutes) {
	                // Approaching first kick
	                long ticksUntilFirstKick = strongReminderThresholdTicks - ticksPlayed;
	                long secondsUntilFirstKick = ticksUntilFirstKick / 20;
	                if (secondsUntilFirstKick <= 300) {
	                    isStrongWarningMinute = true;
	                }
	            } else {
	                // Approaching a repeat kick
	                long ticksOverThreshold = ticksPlayed - strongReminderThresholdTicks;
	                long repeats = ticksOverThreshold / strongReminderRepeatTicks;
	                long lastKickTick = strongReminderThresholdTicks + repeats * strongReminderRepeatTicks;
	                long nextKickTick = lastKickTick + strongReminderRepeatTicks;
	                long ticksUntilNextKick = nextKickTick - ticksPlayed;
	                long secondsUntilNextKick = ticksUntilNextKick / 20;
	                if (secondsUntilNextKick <= 300) {
	                    isStrongWarningMinute = true;
	                }
	            }
	
	            if (isRegularReminderTick && !isStrongWarningMinute) {
	                player.sendSystemMessage(Component.literal(config.regularMessagePrefix + minutesPlayed + config.regularMessageSuffix + dailyPlaytimeMessage));
	                lastReminderMinute.put(id, minutesPlayed);
	            }
	
	            // 1. Kick Check: Check if the threshold has been reached and if it's a scheduled kick time.
	            if (minutesPlayed >= config.strongReminderThresholdMinutes) {
	                long ticksOverThreshold = ticksPlayed - strongReminderThresholdTicks;
	
	                // Check if we are exactly on a kick tick (threshold or a multiple of repeat interval after threshold)
	                if (ticksOverThreshold >= 0 && ticksOverThreshold % strongReminderRepeatTicks == 0) {
						playersToKick.add(player);
	                    System.out.println("[PlaytimeReminder] KICKED " + player.getName().getString() + " (Playtime: " + minutesPlayed + "m) - Kick time reached.");
	
	                    // Reset for next interval
	                    warned5min.remove(id);
	                    warned1min.remove(id);
	                    warned10s.remove(id);
	                    lastReminderMinute.put(id, minutesPlayed);
	
	                    // Continue to next player, as this player is now disconnected or about to be.
	                    continue;
	                }
	            }
	
	            // 2. Warning Logic: Determine the next scheduled kick time for warnings.
	            long nextKickTick;
	            if (ticksPlayed < strongReminderThresholdTicks) {
	                // Case 1: Approaching the first kick
	                nextKickTick = strongReminderThresholdTicks;
	            } else {
	                // Case 2: After the first kick, calculating the next repeat kick
	                long ticksOverThreshold = ticksPlayed - strongReminderThresholdTicks;
	                long repeats = ticksOverThreshold / strongReminderRepeatTicks;
	                long lastKickTick = strongReminderThresholdTicks + repeats * strongReminderRepeatTicks;
	                nextKickTick = lastKickTick + strongReminderRepeatTicks;
	            }
	
	            			long ticksUntilKick = nextKickTick - ticksPlayed;
	            			long secondsUntilKick = ticksUntilKick / 20;
	            
	            			// Only send 5-min warning if kick is scheduled for 5 minutes or less, AND more than 1 minute away.
	            			if (secondsUntilKick <= 300 && secondsUntilKick > 60 && !warned5min.getOrDefault(id, false)) {
	            				player.sendSystemMessage(Component.literal(config.warningMessage5min + dailyPlaytimeMessage).withStyle(ChatFormatting.RED));
	            				System.out.println("[PlaytimeReminder] 5-min warning sent to " + player.getName().getString() + " (Playtime: " + minutesPlayed + "m)");
	            				warned5min.put(id, true);
	            			}
	            			if (secondsUntilKick <= 60 && !warned1min.getOrDefault(id, false)) {
	            				player.sendSystemMessage(Component.literal(config.warningMessage1min + dailyPlaytimeMessage).withStyle(ChatFormatting.RED));
	            				System.out.println("[PlaytimeReminder] 1-min warning sent to " + player.getName().getString() + " (Playtime: " + minutesPlayed + "m)");
	            				warned1min.put(id, true);
	            			}
	            			if (secondsUntilKick <= 10 && !warned10s.getOrDefault(id, false)) {
	            				player.sendSystemMessage(Component.literal(config.warningMessage10s + dailyPlaytimeMessage).withStyle(ChatFormatting.RED));
	            				System.out.println("[PlaytimeReminder] 10-sec warning sent to " + player.getName().getString() + " (Playtime: " + minutesPlayed + "m)");
	            				warned10s.put(id, true);
	            			}	        }
			for (ServerPlayer player : playersToKick) {
				int ticksPlayed = currentServerTick - playerJoinTick.get(player.getUUID());
				int minutesPlayed = Math.max(0, ticksPlayed / TICKS_PER_MINUTE);
				insistBreak(player, minutesPlayed);
			}
	    }
	private void insistBreak(ServerPlayer player, int minutesPlayed) {
		player.sendSystemMessage(Component.literal(config.strongMessagePrefix + minutesPlayed + config.strongMessageSuffix));
		if (!config.disconnectOnStrong) {
			return;
		}
		player.connection.disconnect(Component.literal(config.disconnectMessage));
	}

	private void saveConfig() {
		Path dir = Paths.get("config");
		Path file = dir.resolve("playtime_reminder.json5");
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		try {
			if (!Files.exists(dir)) {
				Files.createDirectories(dir);
			}
			try (Writer w = Files.newBufferedWriter(file)) {
				gson.toJson(this.config, w);
			}
		} catch (IOException e) {
			// Keep defaults on error
		}
	}

	private void loadConfig() {
		Path dir = Paths.get("config");
		Path newFile = dir.resolve("playtime_reminder.json5");
		Path oldFile = dir.resolve("playtime_reminder.json");
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		
		// Keep a copy of the defaults for merging
		Config defaults = new Config();
		
		try {
			if (!Files.exists(dir)) {
				Files.createDirectories(dir);
			}
			
			// Helper to load and clean config content
			java.util.function.Function<Path, Config> loadAndClean = (path) -> {
				try {
					String content = Files.readString(path);
					// Regex to remove trailing commas: finds a comma followed by optional whitespace and a closing brace/bracket
					content = content.replaceAll(",(\\s*[\\}\\]])", "$1");
					return gson.fromJson(content, Config.class);
				} catch (IOException e) {
					System.err.println("[PlaytimeReminder] Error reading config file " + path + ": " + e.getMessage());
					return null;
				}
			};

			// 1. Check for and migrate old .json file
			if (Files.exists(oldFile)) {
				Config loaded = loadAndClean.apply(oldFile);
				if (loaded != null) {
					this.config = loaded;
				}
				// Delete old file after successful load
				Files.delete(oldFile);
				System.out.println("[PlaytimeReminder] Migrated config from .json to .json5");
			}
			
			// 2. Load new .json5 file (or continue with defaults/migrated config)
			if (Files.exists(newFile)) {
				Config loaded = loadAndClean.apply(newFile);
				
				if (loaded != null) {
					// Set the current config to the loaded one.
					this.config = loaded;
					
					// Fill in any missing String fields with defaults (config migration).
					if (this.config.regularMessagePrefix == null) this.config.regularMessagePrefix = defaults.regularMessagePrefix;
					if (this.config.regularMessageSuffix == null) this.config.regularMessageSuffix = defaults.regularMessageSuffix;
					if (this.config.strongMessagePrefix == null) this.config.strongMessagePrefix = defaults.strongMessagePrefix;
					if (this.config.strongMessageSuffix == null) this.config.strongMessageSuffix = defaults.strongMessageSuffix;
					if (this.config.disconnectMessage == null) this.config.disconnectMessage = defaults.disconnectMessage;
					if (this.config.warningMessage5min == null) this.config.warningMessage5min = defaults.warningMessage5min;
					if (this.config.warningMessage1min == null) this.config.warningMessage1min = defaults.warningMessage1min;
					if (this.config.warningMessage10s == null) this.config.warningMessage10s = defaults.warningMessage10s;
				}
			}
			
			// Always save the config after loading/merging to persist any new default values, 
			// or to create the file if it didn't exist.
			saveConfig();
			
		} catch (IOException e) {
			// Keep defaults on error
			System.err.println("[PlaytimeReminder] Error loading or migrating config: " + e.getMessage());
		}
	}

	public static final class Config {
		int reminderIntervalMinutes = 30; // default for testing
		int strongReminderThresholdMinutes = 120;
		int strongReminderRepeatMinutes = 10;
		int breakDurationMinutes = 5; // New parameter for break duration
		boolean disconnectOnStrong = true;
		String regularMessagePrefix = "You've been playing for ";
		String regularMessageSuffix = " minutes this session. Consider taking a short break soon.";
		String strongMessagePrefix = "You've been playing for ";
		String strongMessageSuffix = " minutes this session. Please take a break now.";
		String disconnectMessage = "Break reminder: Please take a short break and rejoin when ready.";
		String warningMessage5min = "You will be kicked in 5 minutes to remind you to take a break.";
		String warningMessage1min = "You will be kicked in 1 minute.";
		String warningMessage10s = "You will be kicked in 10 seconds.";
	}
}
