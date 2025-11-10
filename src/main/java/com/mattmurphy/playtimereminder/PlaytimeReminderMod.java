package com.mattmurphy.playtimereminder;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

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
import net.minecraft.world.BossEvent;
import net.minecraft.server.level.ServerBossEvent;

public final class PlaytimeReminderMod implements ModInitializer {
	private static final int TICKS_PER_MINUTE = 20 * 60;

	private Config config = new Config();

	    private final Map<UUID, Long> playerJoinTick = new HashMap<>();
	    private final Map<UUID, ServerBossEvent> playerBossBar = new HashMap<>(); // Tracks the boss bar for each player
	        private final Map<UUID, Long> playerDisconnectTime = new HashMap<>(); // Tracks the time a player disconnected in milliseconds
	    private final Map<UUID, Long> playerPlaytimeTicks = new HashMap<>(); // Ticks played in the session before last disconnect
	    private final Map<UUID, Integer> lastReminderMinute = new HashMap<>();
	    private final Map<UUID, Boolean> warned5min = new HashMap<>();
	    private final Map<UUID, Boolean> warned1min = new HashMap<>();
	    private final Map<UUID, Boolean> warned10s = new HashMap<>();
	    private final Map<UUID, Integer> playerDailyPlaytime = new HashMap<>(); // Total minutes played today
	    private final Map<UUID, String> delayedJoinMessages = new HashMap<>(); // Messages to send after a short delay
	    private long currentServerTick = 0;
	    private int lastDayOfMonth = -1;
	
	    @Override
	    public void onInitialize() {
	        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
				loadConfig();
				clearPlayerState();
			});

	        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
	            dispatcher.register(Commands.literal("playtime")
	                .requires(source -> source.hasPermission(2)) // Requires op level 2
	                .then(Commands.literal("set")
	                    .then(Commands.literal("reminderIntervalMinutes")
	                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1))
	                            .executes(context -> setIntConfig(context.getSource(), "reminderIntervalMinutes", IntegerArgumentType.getInteger(context, "minutes")))))
	                    .then(Commands.literal("strongReminderThresholdMinutes")
	                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1))
	                            .executes(context -> setIntConfig(context.getSource(), "strongReminderThresholdMinutes", IntegerArgumentType.getInteger(context, "minutes")))))
	                    .then(Commands.literal("strongReminderRepeatMinutes")
	                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1))
	                            .executes(context -> setIntConfig(context.getSource(), "strongReminderRepeatMinutes", IntegerArgumentType.getInteger(context, "minutes")))))
	                    .then(Commands.literal("breakDurationMinutes")
	                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1))
	                            .executes(context -> setIntConfig(context.getSource(), "breakDurationMinutes", IntegerArgumentType.getInteger(context, "minutes")))))
	                    .then(Commands.literal("disconnectOnStrong")
	                        .then(Commands.argument("value", BoolArgumentType.bool())
	                            .executes(context -> setBoolConfig(context.getSource(), "disconnectOnStrong", BoolArgumentType.getBool(context, "value")))))
	                )
	                .then(Commands.literal("get")
	                    .executes(context -> getConfig(context.getSource())))
	            );
	        });

	        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
	            ServerPlayer player = handler.getPlayer();
	            UUID id = player.getUUID();
	            Long disconnectTime = playerDisconnectTime.remove(id);
	
	            if (disconnectTime != null) {
	                long breakMillis = System.currentTimeMillis() - disconnectTime;
	                long breakThresholdMillis = (long)config.breakDurationMinutes * 60 * 1000;
	
	                if (breakMillis >= breakThresholdMillis) {
	                    // Break taken, reset playtime
	                    playerJoinTick.put(id, currentServerTick);
	                    playerPlaytimeTicks.remove(id);
	                } else {
	                    // Break NOT taken, playtime continues.
	                    // Restore the player's session playtime from before they disconnected.
	                    Long previousTicksPlayed = playerPlaytimeTicks.remove(id);
	                    if (previousTicksPlayed != null) {
	                        // To restore playtime, calculate a new join tick that preserves the played time.
	                        long newJoinTick = currentServerTick - previousTicksPlayed;
	                        playerJoinTick.put(id, newJoinTick);

	                        // Now, schedule a warning based on the restored playtime.
	                        long nextKickTick = calculateNextKickTick(previousTicksPlayed);
	                        long ticksUntilKick = nextKickTick - previousTicksPlayed;
	                        long minutesUntilKick = (ticksUntilKick + TICKS_PER_MINUTE - 1) / TICKS_PER_MINUTE;

	                        if (minutesUntilKick > 0) {
	                            String message = "Your break was less than " + config.breakDurationMinutes + " minutes. Your playtime continues. You will be kicked in approximately " + minutesUntilKick + " minutes.";
	                            delayedJoinMessages.put(id, message);
	                        }
	                    } else {
	                        // Fallback if playtime wasn't tracked, start fresh
	                        playerJoinTick.put(id, currentServerTick);
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
	            
	            // Store session playtime to be restored if break is not taken
	            Long joinTick = playerJoinTick.get(id);
	            if (joinTick != null) {
	                long ticksPlayed = currentServerTick - joinTick;
	                playerPlaytimeTicks.put(id, ticksPlayed);
	            }

	            playerDisconnectTime.put(id, System.currentTimeMillis()); // Store disconnect time in milliseconds
	            
	            // Do NOT remove playerJoinTick, so we can check it on rejoin
	            lastReminderMinute.remove(id);
	            warned5min.remove(id);
	            warned1min.remove(id);
	            warned10s.remove(id);
	            
	            // Remove boss bar on disconnect to ensure it is recreated and re-added on rejoin
	            ServerBossEvent bossBar = playerBossBar.remove(id);
	            if (bossBar != null) {
	                bossBar.removePlayer(handler.getPlayer());
	            }
	        });
	
	        ServerTickEvents.START_SERVER_TICK.register(this::onServerTick);
	    }
	
	    private void clearPlayerState() {
	        playerJoinTick.clear();
	        playerBossBar.clear();
	        playerDisconnectTime.clear();
	        playerPlaytimeTicks.clear();
	        lastReminderMinute.clear();
	        warned5min.clear();
	        warned1min.clear();
	        warned10s.clear();
	        playerDailyPlaytime.clear();
	        delayedJoinMessages.clear();
	        currentServerTick = 0;
	        lastDayOfMonth = -1;
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
	            boolean isDelayedMessagePending = delayedJoinMessages.containsKey(id);
	            Long joinTick = playerJoinTick.get(id);
	            if (joinTick == null) {
	                joinTick = currentServerTick; // fallback if missed join event
	                playerJoinTick.put(id, joinTick);
	            }
	
	            long ticksPlayed = currentServerTick - joinTick;
	            int minutesPlayed = Math.max(0, (int) (ticksPlayed / TICKS_PER_MINUTE));
	            int lastMinute = lastReminderMinute.getOrDefault(id, -1);
	            
	            // Update daily playtime
	            int dailyMinutes = playerDailyPlaytime.getOrDefault(id, 0);
	            if (minutesPlayed > 0 && minutesPlayed > dailyMinutes) {
	                playerDailyPlaytime.put(id, minutesPlayed);
	                dailyMinutes = minutesPlayed; // Update the local variable for the message
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

	            			// Check if player has just joined (less than 3 seconds)
	            			boolean isNewlyJoined = ticksPlayed < (20 * 3); // 60 ticks
	            			
	            			// Boss Bar Logic
	            			ServerBossEvent bossBar = playerBossBar.get(id);
	            			
	            			if (secondsUntilKick <= 300 && secondsUntilKick > 0) {
	            				float progress = (float)secondsUntilKick / 300.0f;
	            				int minutesRemaining = (int) (secondsUntilKick / 60);
	            				int secondsRemaining = (int) (secondsUntilKick % 60);
	            				
	            				String titleText = String.format(config.bossBarTitle, String.format("%d:%02d", minutesRemaining, secondsRemaining));
	            				
	            				if (bossBar == null) {
	            					bossBar = new ServerBossEvent(
	            						Component.literal(titleText), 
	            						BossEvent.BossBarColor.RED, 
	            						BossEvent.BossBarOverlay.PROGRESS
	            					);
	            					playerBossBar.put(id, bossBar);
	            					bossBar.addPlayer(player);
	            				} else {
	            					bossBar.setName(Component.literal(titleText));
	            					// Re-add player to boss bar for a few ticks after joining to ensure the packet is sent
	            					if (isNewlyJoined) {
	            						bossBar.addPlayer(player);
	            					}
	            				}
	            				bossBar.setProgress(progress);
	            			} else if (bossBar != null) {
	            				// Remove boss bar if time is up or > 5 minutes away
	            				bossBar.removePlayer(player);
	            				playerBossBar.remove(id);
	            			}
	            
	            			// Only send 5-min warning if kick is scheduled for 5 minutes or less, AND more than 1 minute away.
	            			if (!isDelayedMessagePending && secondsUntilKick <= 300 && secondsUntilKick > 60 && !warned5min.getOrDefault(id, false)) {
	            				player.sendSystemMessage(Component.literal(config.warningMessage5min + dailyPlaytimeMessage).withStyle(ChatFormatting.RED));
	            				System.out.println("[PlaytimeReminder] 5-min warning sent to " + player.getName().getString() + " (Playtime: " + minutesPlayed + "m)");
	            				warned5min.put(id, true);
	            			}
	            			
	            			// Send 60-sec warning as a big on-screen title/subtitle message.
	            			// This also serves as the 1-minute system message warning.
	            			if (!isDelayedMessagePending && secondsUntilKick <= 60 && secondsUntilKick > 10 && !warned1min.getOrDefault(id, false)) {
	            				// Send Title/Subtitle
	            				player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20)); // Fade in: 0.5s, Stay: 3.5s, Fade out: 1s
	            				player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("BREAK REMINDER").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
	            				player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(config.warningMessage1min).withStyle(ChatFormatting.YELLOW)));
	            				
	            				player.sendSystemMessage(Component.literal(config.warningMessage1min + dailyPlaytimeMessage).withStyle(ChatFormatting.RED));
	            				System.out.println("[PlaytimeReminder] 1-min warning sent to " + player.getName().getString() + " (Playtime: " + minutesPlayed + "m)");
	            				warned1min.put(id, true);
	            			}
	            			
	            			// Send 10-sec warning as a big on-screen title/subtitle message.
	            			if (!isDelayedMessagePending && secondsUntilKick <= 10 && !warned10s.getOrDefault(id, false)) {
	            				// Send Title/Subtitle
	            				player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20)); // Fade in: 0.5s, Stay: 3.5s, Fade out: 1s
	            				player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("BREAK REMINDER").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
	            				player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(config.warningMessage10s).withStyle(ChatFormatting.YELLOW)));
	            				
	            				player.sendSystemMessage(Component.literal(config.warningMessage10s + dailyPlaytimeMessage).withStyle(ChatFormatting.RED));
	            				System.out.println("[PlaytimeReminder] 10-sec warning sent to " + player.getName().getString() + " (Playtime: " + minutesPlayed + "m)");
	            				warned10s.put(id, true);
	            			}	        }
			for (ServerPlayer player : playersToKick) {
				long ticksPlayed = currentServerTick - playerJoinTick.get(player.getUUID());
				int minutesPlayed = Math.max(0, (int) (ticksPlayed / TICKS_PER_MINUTE));
				insistBreak(player, minutesPlayed);
			}
	    }

	    private int setIntConfig(CommandSourceStack source, String fieldName, int value) {
	        try {
	            java.lang.reflect.Field field = Config.class.getDeclaredField(fieldName);
	            field.setAccessible(true);
	            field.set(config, value);
	            saveConfig();
	            source.sendSuccess(() -> Component.literal("Set config '" + fieldName + "' to " + value).withStyle(ChatFormatting.GREEN), true);
	            return 1;
	        } catch (NoSuchFieldException | IllegalAccessException e) {
	            source.sendFailure(Component.literal("Failed to set config: " + e.getMessage()));
	            return 0;
	        }
	    }

	    private int setBoolConfig(CommandSourceStack source, String fieldName, boolean value) {
	        try {
	            java.lang.reflect.Field field = Config.class.getDeclaredField(fieldName);
	            field.setAccessible(true);
	            field.set(config, value);
	            saveConfig();
	            source.sendSuccess(() -> Component.literal("Set config '" + fieldName + "' to " + value).withStyle(ChatFormatting.GREEN), true);
	            return 1;
	        } catch (NoSuchFieldException | IllegalAccessException e) {
	            source.sendFailure(Component.literal("Failed to set config: " + e.getMessage()));
	            return 0;
	        }
	    }

	    private int getConfig(CommandSourceStack source) {
	        source.sendSuccess(() -> Component.literal("--- Playtime Reminder Config ---").withStyle(ChatFormatting.YELLOW), false);
	        source.sendSuccess(() -> Component.literal("  reminderIntervalMinutes: " + config.reminderIntervalMinutes), false);
	        source.sendSuccess(() -> Component.literal("  strongReminderThresholdMinutes: " + config.strongReminderThresholdMinutes), false);
	        source.sendSuccess(() -> Component.literal("  strongReminderRepeatMinutes: " + config.strongReminderRepeatMinutes), false);
	        source.sendSuccess(() -> Component.literal("  breakDurationMinutes: " + config.breakDurationMinutes), false);
	        source.sendSuccess(() -> Component.literal("  disconnectOnStrong: " + config.disconnectOnStrong), false);
	        source.sendSuccess(() -> Component.literal("----------------------------------").withStyle(ChatFormatting.YELLOW), false);
	        return 1;
	    }

	private void insistBreak(ServerPlayer player, int minutesPlayed) {
		UUID id = player.getUUID();
		ServerBossEvent bossBar = playerBossBar.remove(id);
		if (bossBar != null) {
			bossBar.removePlayer(player);
		}
		
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
					if (this.config.bossBarTitle == null) this.config.bossBarTitle = defaults.bossBarTitle; // New config
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
		public int reminderIntervalMinutes = 30; // default for testing
		public int strongReminderThresholdMinutes = 120;
		public int strongReminderRepeatMinutes = 10;
		public int breakDurationMinutes = 5; // New parameter for break duration
		public boolean disconnectOnStrong = true;
		public String regularMessagePrefix = "You've been playing for ";
		public String regularMessageSuffix = " minutes this session. Consider taking a short break soon.";
		public String strongMessagePrefix = "You've been playing for ";
		public String strongMessageSuffix = " minutes this session. Please take a break now.";
		public String disconnectMessage = "Break reminder: Please take a short break and rejoin when ready.";
		public String warningMessage5min = "You will be kicked in 5 minutes to remind you to take a break.";
		public String warningMessage1min = "You will be kicked in 1 minute.";
		public String warningMessage10s = "[Break Enforcement] You will be kicked in 10s.";
		public String bossBarTitle = "Break Reminder: %s remaining"; // New config for boss bar title
	}
}
