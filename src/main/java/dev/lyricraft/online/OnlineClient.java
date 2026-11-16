package dev.lyricraft.online;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lyricraft.Config;
import dev.lyricraft.LyricraftClient;
import dev.lyricraft.lyrics.Lyrics;
import dev.lyricraft.media.MediaSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.Player;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets Lyricraft players see each other: a badge next to their name and (if they share it) the
 * line they're hearing right now above their head.
 *
 * Talks to the Lyricraft online service (see server/ in the repo) over one WebSocket. To prove
 * who you are without sending your login anywhere, it does exactly what joining a Minecraft
 * server does: it tells Mojang "I'm joining server &lt;random id&gt;", and the service asks Mojang
 * which account that was.
 */
public final class OnlineClient {

	/** What another Lyricraft player is listening to (all fields may be null if they don't share). */
	/** @param echoes they show their lines as Echoes panels (so no need to repeat them above their head) */
	public record Remote(UUID id, String song, String artist, String line, long durMs, long elapsedMs, long receivedNanos,
	                     boolean echoes) {
		/** Where in the current line they are right now. */
		public long elapsedNow() {
			return elapsedMs + (System.nanoTime() - receivedNanos) / 1_000_000;
		}
	}

	public enum Status { OFF, NOT_SET_UP, CONNECTING, ONLINE, OFFLINE, NOT_VERIFIED }

	/** An Echoes panel another player placed: a line pinned to a spot in their world. */
	public record RemoteEcho(UUID owner, double x, double y, double z, String dim, String line,
	                         long durMs, long elapsedMs, float wBlocks, float hBlocks, long receivedNanos) {
		public long elapsedNow() {
			return elapsedMs + (System.nanoTime() - receivedNanos) / 1_000_000;
		}

		public float ageSeconds() {
			return (System.nanoTime() - receivedNanos) / 1e9f;
		}
	}

	/** Limits for other players' Echoes, so a busy server can't flood your screen. */
	public static final int ECHOES_PER_PLAYER = 4, ECHOES_TOTAL = 16;
	public static final float ECHO_LIFETIME_S = 40f;
	private final Map<UUID, java.util.ArrayDeque<RemoteEcho>> echoes = new ConcurrentHashMap<>();
	private long lastEchoSent;

	private final LyricraftClient mod;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private final Map<UUID, Remote> remotes = new ConcurrentHashMap<>();

	private volatile WebSocket socket;
	private volatile Status status = Status.OFF;
	private volatile UUID myId;
	/** Every id we can be known by (premium id and offline id when verified). */
	private volatile Set<UUID> myIds = Set.of();
	private volatile boolean verified;
	private volatile boolean connecting;
	private long retryAt;
	private long backoffMs = 3000;
	private int ticks;
	private String lastUrl;
	private Set<String> lastWatch = Set.of();
	private String lastStateKey = "";
	private long lastStateSent;
	private final StringBuilder partial = new StringBuilder();

	public OnlineClient(LyricraftClient mod) {
		this.mod = mod;
	}

	public Status status() {
		return status;
	}

	/** Does this player use Lyricraft (and is online right now)? */
	public boolean usesMod(UUID id) {
		if (id == null || status != Status.ONLINE) return false;
		return remotes.containsKey(id);
	}

	public Remote remote(UUID id) {
		return status == Status.ONLINE && id != null ? remotes.get(id) : null;
	}

	/** How many other Lyricraft players you can see right now. */
	public int othersOnline() {
		return (int) remotes.keySet().stream().filter(id -> !isMe(id)).count();
	}

	/** True if the service knows us by this id. */
	public boolean isMe(UUID id) {
		return id != null && (id.equals(myId) || myIds.contains(id));
	}

	/** True when the service confirmed this is an official Minecraft account. */
	public boolean verified() {
		return verified;
	}

	// ------------------------------------------------------------------ lifecycle (client thread)

	private volatile boolean demo;

	/** Pretend this player shares this line (screenshots/tests only). */
	public void showDemo(UUID id, String song, String artist, String line, long durMs, long elapsedMs) {
		demo = true;
		myId = id;
		status = Status.ONLINE;
		remotes.put(id, new Remote(id, song, artist, line, durMs, elapsedMs, System.nanoTime(), false));
	}

	public boolean demoActive() {
		return demo;
	}

	public void tick(Minecraft mc) {
		if (demo) return;
		Config cfg = mod.config;
		boolean wanted = cfg.online && mc.level != null && mc.player != null;
		String url = serverUrl(cfg);
		if (url != null && !url.equals(lastUrl)) {
			// a different service: start fresh instead of waiting out the old one's retry timer
			lastUrl = url;
			disconnect();
			retryAt = 0;
			backoffMs = 3000;
			if (status == Status.NOT_VERIFIED) status = Status.OFFLINE;
		}
		if (!wanted || url == null) {
			if (socket != null || status == Status.ONLINE || status == Status.CONNECTING) disconnect();
			status = !cfg.online ? Status.OFF : url == null ? Status.NOT_SET_UP : Status.OFFLINE;
			return;
		}
		WebSocket ws = socket;
		if (ws == null) {
			if (!connecting && System.currentTimeMillis() >= retryAt) connect(mc, url);
			return;
		}
		if (status != Status.ONLINE) return;
		ticks++;
		if (ticks % 40 == 0) sendWatch(mc);
		if (ticks % 2 == 0) sendState(false);
	}

	private void connect(Minecraft mc, String url) {
		connecting = true;
		status = Status.CONNECTING;
		Thread t = new Thread(() -> {
			try {
				socket = http.newWebSocketBuilder()
						.connectTimeout(Duration.ofSeconds(8))
						.buildAsync(URI.create(url), new Listener())
						.join();
				// the service now sends a one-time challenge; we log in when it arrives (see login())
			} catch (Exception e) {
				LyricraftClient.LOG.debug("Lyricraft online: couldn't connect: {}", e.toString());
				failed();
			} finally {
				connecting = false;
			}
		}, "Lyricraft online");
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Say who we are for this connection's challenge.
	 *
	 * Official accounts have a chat key pair that Mojang signed (the same one Minecraft uses to
	 * sign chat messages). We sign the challenge with it and send Mojang's certificate, so the
	 * service can check it's really this account without contacting Mojang. Your login token is
	 * never sent anywhere. The signature only fits this one challenge, so it can't be reused.
	 *
	 * Offline accounts don't have that key; they just send their name and the service gives
	 * them the same id a cracked server would.
	 */
	private void login(WebSocket ws, String nonce) {
		Minecraft mc = Minecraft.getInstance();
		User user = mc.getUser();
		Thread t = new Thread(() -> {
			try {
				JsonObject hello = new JsonObject();
				hello.addProperty("t", "hello");
				hello.addProperty("name", user.getName());
				hello.addProperty("v", LyricraftClient.VERSION);
				try {
					var keys = mc.getProfileKeyPairManager().prepareKeyPair().get(10, java.util.concurrent.TimeUnit.SECONDS);
					if (keys.isPresent()) {
						var pair = keys.get();
						var data = pair.publicKey().data();
						var b64 = java.util.Base64.getEncoder();
						JsonObject cert = new JsonObject();
						cert.addProperty("uuid", user.getProfileId().toString());
						cert.addProperty("exp", data.expiresAt().toEpochMilli());
						cert.addProperty("key", b64.encodeToString(data.key().getEncoded()));
						cert.addProperty("sig", b64.encodeToString(data.keySignature()));
						hello.add("cert", cert);
						var signer = java.security.Signature.getInstance("SHA256withRSA");
						signer.initSign(pair.privateKey());
						signer.update(("lyricraft:" + nonce).getBytes(java.nio.charset.StandardCharsets.UTF_8));
						hello.addProperty("proof", b64.encodeToString(signer.sign()));
					}
				} catch (Exception e) {
					// offline account, chat disabled, or Mojang unreachable: join as an offline account
					LyricraftClient.LOG.debug("Lyricraft online: no chat key, joining unverified: {}", e.toString());
				}
				// test shortcut: only ever sent to a test service on this computer
				if (isLocal(mod.config)) hello.addProperty("devId", user.getProfileId().toString());
				ws.sendText(hello.toString(), true);
			} catch (Exception e) {
				LyricraftClient.LOG.debug("Lyricraft online: login failed: {}", e.toString());
			}
		}, "Lyricraft online login");
		t.setDaemon(true);
		t.start();
	}

	private static boolean isLocal(Config cfg) {
		try {
			String host = URI.create(cfg.onlineServer.trim()).getHost();
			return "127.0.0.1".equals(host) || "localhost".equals(host) || "[::1]".equals(host);
		} catch (Exception e) {
			return false;
		}
	}

	private void failed() {
		socket = null;
		echoes.clear();
		if (status != Status.NOT_VERIFIED) status = Status.OFFLINE;
		remotes.clear();
		retryAt = System.currentTimeMillis() + backoffMs;
		backoffMs = Math.min(backoffMs * 2, status == Status.NOT_VERIFIED ? 600_000 : 120_000);
	}

	public void disconnect() {
		WebSocket ws = socket;
		socket = null;
		echoes.clear();
		remotes.clear();
		lastWatch = Set.of();
		lastStateKey = "";
		if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
	}

	/** Send our state right away (after changing a sharing setting). */
	public void refresh() {
		lastStateKey = "";
		retryAt = 0; // try again right away after changing a setting
		if (!mod.config.online) disconnect();
	}

	// ------------------------------------------------------------------ Echoes sharing

	/** Share an Echoes panel you just placed (only if you share your lyrics and your Echoes). */
	public void sendEcho(double x, double y, double z, String dim, String line, long durMs, long elapsedMs, float w, float h) {
		Config cfg = mod.config;
		if (demo || status != Status.ONLINE || !cfg.shareLyrics || !cfg.shareEchoes) return;
		long now = System.currentTimeMillis();
		if (now - lastEchoSent < 1500) return; // same limit as the service
		lastEchoSent = now;
		JsonObject o = new JsonObject();
		o.addProperty("t", "echo");
		JsonArray pos = new JsonArray();
		pos.add(x);
		pos.add(y);
		pos.add(z);
		o.add("pos", pos);
		o.addProperty("dim", dim);
		o.addProperty("line", line);
		o.addProperty("dur", durMs);
		o.addProperty("elapsed", elapsedMs);
		o.addProperty("w", w);
		o.addProperty("h", h);
		send(o);
	}

	/** Other players' Echoes panels that are still fresh. */
	public java.util.List<RemoteEcho> echoes() {
		if (status != Status.ONLINE) return java.util.List.of();
		java.util.List<RemoteEcho> out = new java.util.ArrayList<>();
		for (var q : echoes.values()) {
			synchronized (q) {
				q.removeIf(e -> e.ageSeconds() > ECHO_LIFETIME_S);
				out.addAll(q);
			}
		}
		return out;
	}

	private void addEcho(JsonObject m) {
		try {
			UUID id = UUID.fromString(m.get("id").getAsString());
			if (isMe(id) && !demo) return;
			JsonArray p = m.getAsJsonArray("pos");
			double x = p.get(0).getAsDouble(), y = p.get(1).getAsDouble(), z = p.get(2).getAsDouble();
			if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || Math.abs(x) > 3e7 || Math.abs(z) > 3e7 || Math.abs(y) > 4096) return;
			String dim = m.get("dim").getAsString();
			if (!dim.matches("[a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,64}")) return;
			String line = SafeText.clean(m.get("line").getAsString(), 200);
			if (line.isEmpty()) return;
			long dur = Math.clamp(m.get("dur").getAsLong(), 0, 60_000);
			long elapsed = Math.clamp(m.get("elapsed").getAsLong(), -60_000, 60_000);
			float w = (float) Math.clamp(m.get("w").getAsDouble(), 0.2, 12), h = (float) Math.clamp(m.get("h").getAsDouble(), 0.1, 3);
			putEcho(new RemoteEcho(id, x, y, z, dim, line, dur, elapsed, w, h, System.nanoTime()));
		} catch (RuntimeException e) {
			LyricraftClient.LOG.debug("Lyricraft online: bad echo: {}", e.toString());
		}
	}

	private void putEcho(RemoteEcho e) {
		var q = echoes.computeIfAbsent(e.owner(), k -> new java.util.ArrayDeque<>());
		synchronized (q) {
			q.addLast(e);
			while (q.size() > ECHOES_PER_PLAYER) q.removeFirst();
		}
		// global cap: drop the oldest panels of anyone
		while (echoes.values().stream().mapToInt(java.util.ArrayDeque::size).sum() > ECHOES_TOTAL) {
			RemoteEcho oldest = null;
			for (var qq : echoes.values()) synchronized (qq) {
				if (!qq.isEmpty() && (oldest == null || qq.peekFirst().receivedNanos() < oldest.receivedNanos())) oldest = qq.peekFirst();
			}
			if (oldest == null) break;
			var oq = echoes.get(oldest.owner());
			synchronized (oq) {
				oq.removeFirst();
			}
		}
	}

	/** Pretend a player placed this panel (screenshots/tests only). */
	public void addDemoEcho(UUID owner, double x, double y, double z, String dim, String line, long durMs, long elapsedMs, float w, float h) {
		demo = true;
		status = Status.ONLINE;
		putEcho(new RemoteEcho(owner, x, y, z, dim, line, durMs, elapsedMs, w, h, System.nanoTime()));
	}

	// ------------------------------------------------------------------ outgoing

	private void sendWatch(Minecraft mc) {
		Set<String> ids = new TreeSet<>();
		ClientPacketListener conn = mc.getConnection();
		if (conn != null) for (PlayerInfo p : conn.getOnlinePlayers()) ids.add(p.getProfile().id().toString());
		if (mc.level != null) for (Player p : mc.level.players()) ids.add(p.getUUID().toString());
		if (ids.equals(lastWatch)) return;
		lastWatch = ids;
		JsonObject o = new JsonObject();
		o.addProperty("t", "watch");
		JsonArray arr = new JsonArray();
		ids.stream().limit(300).forEach(arr::add);
		o.add("ids", arr);
		send(o);
	}

	/** Tell the service what we're hearing, when it changes (and every few seconds while singing). */
	private void sendState(boolean force) {
		Config cfg = mod.config;
		MediaSnapshot track = mod.tracker.current();
		boolean playing = track != null && mod.tracker.isPlaying();
		Lyrics lyrics = playing ? mod.lyrics() : null;
		Lyrics.Line line = null;
		long elapsed = 0;
		if (lyrics != null && lyrics.kind == Lyrics.Kind.SYNCED && cfg.enabled) {
			long pos = mod.tracker.positionMs() - mod.currentOffsetMs();
			int i = lyrics.activeIndex(pos);
			if (i >= 0) {
				line = lyrics.lines.get(i);
				elapsed = pos - line.startMs();
			}
		}
		// on Echoes your lines are shown as panels in the world, so friends skip the one above your head
		boolean echoes = cfg.style == Config.Style.ECHOES && cfg.shareEchoes && cfg.enabled;
		String key = cfg.shareLyrics + "|" + echoes + "|" + (playing ? track.trackKey() : "") + "|" + (line == null ? "" : line.startMs() + line.text());
		long now = System.currentTimeMillis();
		if (!force && key.equals(lastStateKey)) return;
		if (!force && now - lastStateSent < 300) return;
		lastStateKey = key;
		lastStateSent = now;

		JsonObject o = new JsonObject();
		o.addProperty("t", "state");
		o.addProperty("sharing", cfg.shareLyrics);
		if (cfg.shareLyrics && playing) {
			if (echoes) o.addProperty("echoes", true);
			o.addProperty("song", track.title());
			o.addProperty("artist", track.artist());
			if (line != null) {
				o.addProperty("line", line.text());
				o.addProperty("dur", line.durationMs());
				o.addProperty("elapsed", Math.max(0, elapsed));
			}
		}
		send(o);
	}

	private void send(JsonObject o) {
		WebSocket ws = socket;
		if (ws == null) return;
		try {
			ws.sendText(o.toString(), true);
		} catch (Exception e) {
			LyricraftClient.LOG.debug("Lyricraft online: send failed: {}", e.toString());
		}
	}

	private static String serverUrl(Config cfg) {
		String base = cfg.onlineServer == null ? "" : cfg.onlineServer.trim();
		if (base.isEmpty() || base.contains("YOUR-SUBDOMAIN")) return null;
		if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
		if (base.startsWith("https://")) base = "wss://" + base.substring(8);
		else if (base.startsWith("http://")) base = "ws://" + base.substring(7);
		return base + "/ws";
	}

	// ------------------------------------------------------------------ incoming

	private void handle(JsonObject m, WebSocket from) {
		String t = m.has("t") ? m.get("t").getAsString() : "";
		switch (t) {
			case "challenge" -> {
				String nonce = m.has("nonce") ? m.get("nonce").getAsString() : "";
				// answer on the connection it came from (it can arrive before we've stored the socket)
				if (nonce.matches("[0-9a-f]{16,128}")) login(from, nonce);
			}
			case "welcome" -> {
				myId = UUID.fromString(m.get("id").getAsString());
				Set<UUID> ids = new java.util.HashSet<>();
				ids.add(myId);
				if (m.has("ids") && m.get("ids").isJsonArray()) {
					for (JsonElement el : m.getAsJsonArray("ids")) {
						try {
							ids.add(UUID.fromString(el.getAsString()));
						} catch (RuntimeException ignored) {
							// skip anything that isn't an id
						}
						if (ids.size() >= 4) break;
					}
				}
				myIds = Set.copyOf(ids);
				verified = m.has("verified") && m.get("verified").getAsBoolean();
				status = Status.ONLINE;
				backoffMs = 3000;
				lastWatch = Set.of();
				lastStateKey = "";
				ticks = 39; // send the watch list on the next tick
				LyricraftClient.LOG.info("Lyricraft online: connected ({})", verified ? "verified account" : "offline account");
			}
			case "denied" -> status = Status.NOT_VERIFIED;
			case "snapshot" -> {
				remotes.keySet().removeIf(id -> !isMe(id));
				for (JsonElement el : m.getAsJsonArray("players")) apply(el.getAsJsonObject());
			}
			case "state" -> apply(m);
			case "gone" -> {
				UUID gone = UUID.fromString(m.get("id").getAsString());
				remotes.remove(gone);
				echoes.remove(gone);
			}
			case "echo" -> addEcho(m);
			default -> { }
		}
	}

	private void apply(JsonObject m) {
		UUID id = UUID.fromString(m.get("id").getAsString());
		JsonElement s = m.get("state");
		if (s == null || s.isJsonNull()) {
			remotes.put(id, new Remote(id, null, null, null, 0, 0, System.nanoTime(), false));
			return;
		}
		JsonObject o = s.getAsJsonObject();
		long dur = o.has("dur") ? Math.clamp(o.get("dur").getAsLong(), 0, 60_000) : 0;
		long elapsed = o.has("elapsed") ? Math.clamp(o.get("elapsed").getAsLong(), 0, 60_000) : 0;
		if (remotes.size() > 500 && !remotes.containsKey(id)) return; // never grow without bound
		boolean echoes = o.has("echoes") && o.get("echoes").isJsonPrimitive() && o.get("echoes").getAsBoolean();
		remotes.put(id, new Remote(id, str(o, "song"), str(o, "artist"), str(o, "line"), dur, elapsed, System.nanoTime(), echoes));
	}

	/** Text from other players, cleaned again here (never trust the network). */
	private static String str(JsonObject o, String k) {
		if (!o.has(k) || o.get(k).isJsonNull() || !o.get(k).isJsonPrimitive()) return null;
		String t = SafeText.clean(o.get(k).getAsString(), 200);
		return t.isEmpty() ? null : t;
	}

	private final class Listener implements WebSocket.Listener {
		@Override
		public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
			partial.append(data);
			if (partial.length() > 64_000) { // a sane service never sends this much at once
				partial.setLength(0);
				ws.request(1);
				return null;
			}
			if (last) {
				String text = partial.toString();
				partial.setLength(0);
				try {
					JsonElement el = JsonParser.parseString(text);
					if (el.isJsonObject()) handle(el.getAsJsonObject(), ws);
				} catch (Exception e) {
					LyricraftClient.LOG.debug("Lyricraft online: bad message: {}", e.toString());
				}
			}
			ws.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
			if (code == 4001) status = Status.NOT_VERIFIED;
			if (socket == ws) failed();
			return null;
		}

		@Override
		public void onError(WebSocket ws, Throwable error) {
			if (socket == ws) failed();
		}
	}
}
