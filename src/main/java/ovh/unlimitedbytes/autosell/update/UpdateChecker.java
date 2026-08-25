package ovh.unlimitedbytes.autosell.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.unlimitedbytes.autosell.BytesAutoSellClient;
import ovh.unlimitedbytes.autosell.config.AutoSellConfig;
import ovh.unlimitedbytes.autosell.util.VersionComparator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * One-shot update check against the GitHub releases API, run on server join.
 *
 * This is the mod's only network traffic besides the Minecraft connection, and it
 * is deliberately conservative: a single async HTTPS GET (the game thread is never
 * blocked; the result is marshalled back with {@link MinecraftClient#execute}),
 * a 5-second timeout, no retries, and complete silence on any error — a broken
 * check must never nag or disturb play. Can be turned off in the settings
 * (config {@code updateCheckEnabled}).
 */
public final class UpdateChecker {
	private static final Logger LOGGER = LoggerFactory.getLogger("BytesAutoSellUpdate");

	public static final String RELEASES_API_URL =
			"https://api.github.com/repos/UnlimitedBytes/bytes-auto-sell/releases/latest";
	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private UpdateChecker() {
	}

	/** Starts the check if enabled; returns immediately in every case. */
	public static void checkOnJoin(MinecraftClient client) {
		if (!AutoSellConfig.get().isUpdateCheckEnabled()) {
			return;
		}
		String localVersion = localVersion();
		HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
		HttpRequest request = HttpRequest.newBuilder(URI.create(RELEASES_API_URL))
				.timeout(TIMEOUT)
				.header("User-Agent", "bytes-auto-sell (Minecraft mod update check)")
				.header("Accept", "application/vnd.github+json")
				.GET()
				.build();
		http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.thenApply(HttpResponse::body)
				.thenAccept(body -> client.execute(() -> announceIfOutdated(client, body, localVersion)))
				.exceptionally(error -> {
					LOGGER.debug("Update check failed (ignored)", error);
					return null;
				});
	}

	/** Runs on the client thread via {@link MinecraftClient#execute}. */
	private static void announceIfOutdated(MinecraftClient client, String body, String localVersion) {
		try {
			JsonObject release = JsonParser.parseString(body).getAsJsonObject();
			String tagName = release.get("tag_name").getAsString();
			String releaseUrl = release.get("html_url").getAsString();
			String remoteVersion = VersionComparator.normalize(tagName);
			if (remoteVersion == null || !VersionComparator.isNewer(tagName, localVersion)) {
				return;
			}
			if (client.player == null) {
				return; // left the server before the response arrived
			}
			Text link = Text.translatable("bytesautosell.msg.update_link")
					.styled(style -> style
							.withColor(Formatting.AQUA)
							.withUnderline(true)
							.withClickEvent(new ClickEvent.OpenUrl(URI.create(releaseUrl)))
							.withHoverEvent(new HoverEvent.ShowText(Text.literal(releaseUrl))));
			client.player.sendMessage(Text.translatable("bytesautosell.msg.update_available", remoteVersion)
					.append(Text.literal(" "))
					.append(link), false);
		} catch (Throwable t) {
			// Malformed/unexpected response: stay silent, log for diagnosis.
			LOGGER.debug("Unexpected update-check response (ignored)", t);
		}
	}

	private static String localVersion() {
		return FabricLoader.getInstance().getModContainer(BytesAutoSellClient.MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("");
	}
}
