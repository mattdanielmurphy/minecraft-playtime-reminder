package com.mattmurphy.playtimereminder;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class PlaytimeReminderMod implements ModInitializer {
	private static final int TICKS_PER_MINUTE = 20 * 60;

	private Config config = new Config();

	private final Map<UUID, Integer> playerJoinTick = new HashMap<>();
	private final Map<UUID, Integer> lastReminderMinute = new HashMap<>();
	private final Map<UUID, Boolean> warned5min = new HashMap<>();
	private final Map<UUID, Boolean> warned1min = new HashMap<>();
	private final Map<UUID, Boolean> warned10s = new HashMap<>();
	private int currentServerTick = 0;

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> loadConfig());

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			UUID id = handler.getPlayer().getUUID();
			playerJoinTick.put(id, currentServerTick);
			lastReminderMinute.remove(id);
			warned5min.remove(id);
			warned1min.remove(id);
			warned10s.remove(id);
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID id = handler.getPlayer().getUUID();
			playerJoinTick.remove(id);
			lastReminderMinute.remove(id);
			warned5min.remove(id);
			warned1min.remove(id);
			warned10s.remove(id);
		});

		ServerTickEvents.START_SERVER_TICK.register(this::onServerTick);
	}

	private void onServerTick(MinecraftServer server) {
		currentServerTick++;

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

			// Regular reminders (once per minute)
			if (ticksPlayed % TICKS_PER_MINUTE == 0 && minutesPlayed > 0 && minutesPlayed < config.strongReminderThresholdMinutes && minutesPlayed % config.reminderIntervalMinutes == 0 && minutesPlayed != lastMinute) {
				player.sendSystemMessage(Component.literal(config.regularMessagePrefix + minutesPlayed + config.regularMessageSuffix));
				lastReminderMinute.put(id, minutesPlayed);
			}

			// Strong reminder and kick logic
			if (minutesPlayed >= config.strongReminderThresholdMinutes) {
				long strongReminderThresholdTicks = (long)config.strongReminderThresholdMinutes * TICKS_PER_MINUTE;
				long strongReminderRepeatTicks = (long)config.strongReminderRepeatMinutes * TICKS_PER_MINUTE;

				long nextKickTick = strongReminderThresholdTicks;
				if (ticksPlayed > strongReminderThresholdTicks) {
					long ticksOverThreshold = ticksPlayed - strongReminderThresholdTicks;
					long repeats = ticksOverThreshold / strongReminderRepeatTicks;
					nextKickTick = strongReminderThresholdTicks + (repeats + 1) * strongReminderRepeatTicks;
				}

				long ticksUntilKick = nextKickTick - ticksPlayed;
				long secondsUntilKick = ticksUntilKick / 20;

				if (secondsUntilKick <= 300 && !warned5min.getOrDefault(id, false)) {
					player.sendSystemMessage(Component.literal(config.warningMessage5min));
					warned5min.put(id, true);
				}
				if (secondsUntilKick <= 60 && !warned1min.getOrDefault(id, false)) {
					player.sendSystemMessage(Component.literal(config.warningMessage1min));
					warned1min.put(id, true);
				}
				if (secondsUntilKick <= 10 && !warned10s.getOrDefault(id, false)) {
					player.sendSystemMessage(Component.literal(config.warningMessage10s));
					warned10s.put(id, true);
				}

				if (ticksUntilKick <= 0) {
					insistBreak(player, minutesPlayed);
					// Reset for next interval
					warned5min.remove(id);
					warned1min.remove(id);
					warned10s.remove(id);
					lastReminderMinute.put(id, minutesPlayed);
				}
			}
		}
	}

	private void insistBreak(ServerPlayer player, int minutesPlayed) {
		player.sendSystemMessage(Component.literal(config.strongMessagePrefix + minutesPlayed + config.strongMessageSuffix));
		if (!config.disconnectOnStrong) {
			return;
		}
		player.connection.disconnect(Component.literal(config.disconnectMessage));
	}

	private void loadConfig() {
		Path dir = Paths.get("config");
		Path file = dir.resolve("playtime_reminder.json");
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		try {
			if (!Files.exists(dir)) {
				Files.createDirectories(dir);
			}
			if (Files.exists(file)) {
				try (Reader r = Files.newBufferedReader(file)) {
					Config loaded = gson.fromJson(r, Config.class);
					if (loaded != null) {
						this.config = loaded;
					}
				}
			} else {
				try (Writer w = Files.newBufferedWriter(file)) {
					gson.toJson(this.config, w);
				}
			}
		} catch (IOException e) {
			// Keep defaults on error
		}
	}

	private static final class Config {
		int reminderIntervalMinutes = 30; // default for testing
		int strongReminderThresholdMinutes = 120;
		int strongReminderRepeatMinutes = 10;
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
