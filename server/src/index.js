// Lyricraft online service
//
// Lets players who use Lyricraft see each other: a badge next to their name, and (if they
// share it) the lyric line they're hearing right now, floating above their head.
//
// Every player keeps one WebSocket open to a single "Hub" Durable Object. A player proves who
// they are with Mojang's own login check (the same one Minecraft servers use), tells the hub
// which players they can see, and the hub forwards those players' lyric updates to them.
//
// Nothing is stored on disk. The hub only remembers what's connected right now.

const LIMITS = { song: 120, artist: 120, line: 200, watch: 300, msgsPer10s: 40 };

export default {
	async fetch(request, env) {
		const url = new URL(request.url);
		if (url.pathname === "/ws") {
			if (request.headers.get("Upgrade") !== "websocket") return new Response("Expected a WebSocket", { status: 426 });
			const hub = env.HUB.get(env.HUB.idFromName("global"));
			return hub.fetch(request);
		}
		if (url.pathname === "/" || url.pathname === "/health") {
			const hub = env.HUB.get(env.HUB.idFromName("global"));
			return hub.fetch(new Request(new URL("/count", request.url)));
		}
		return new Response("Not found", { status: 404 });
	},
};

export class Hub {
	constructor(ctx, env) {
		this.ctx = ctx;
		this.env = env;
	}

	async fetch(request) {
		const url = new URL(request.url);
		if (url.pathname === "/count") {
			const online = this.ctx.getWebSockets().filter((ws) => this.att(ws).id).length;
			return Response.json({ ok: true, online });
		}
		const pair = new WebSocketPair();
		const [client, server] = Object.values(pair);
		this.ctx.acceptWebSocket(server);
		server.serializeAttachment({ id: null, name: null, watch: [], state: null, window: 0, count: 0 });
		return new Response(null, { status: 101, webSocket: client });
	}

	att(ws) {
		return ws.deserializeAttachment() || {};
	}

	async webSocketMessage(ws, raw) {
		let msg;
		try {
			msg = JSON.parse(typeof raw === "string" ? raw : new TextDecoder().decode(raw));
		} catch {
			return;
		}
		const a = this.att(ws);

		// simple flood protection
		const now = Date.now();
		if (now - a.window > 10_000) {
			a.window = now;
			a.count = 0;
		}
		if (++a.count > LIMITS.msgsPer10s) {
			ws.serializeAttachment(a);
			return;
		}

		if (msg.t === "hello") {
			const id = await this.verify(msg);
			if (!id) {
				ws.send(JSON.stringify({ t: "denied" }));
				ws.close(4001, "not verified");
				return;
			}
			a.id = id;
			a.name = String(msg.name || "").slice(0, 16);
			ws.serializeAttachment(a);
			ws.send(JSON.stringify({ t: "welcome", id }));
			this.broadcast(id, { t: "state", id, state: null });
			return;
		}
		if (!a.id) return; // everything else needs a verified player

		if (msg.t === "watch") {
			const ids = Array.isArray(msg.ids) ? msg.ids.slice(0, LIMITS.watch).map(normalizeId).filter(Boolean) : [];
			a.watch = ids;
			ws.serializeAttachment(a);
			// tell them right away which of those players use Lyricraft (and what they're hearing)
			const wanted = new Set(ids);
			const players = [];
			for (const other of this.ctx.getWebSockets()) {
				const o = this.att(other);
				if (o.id && wanted.has(o.id)) players.push({ id: o.id, state: o.state });
			}
			ws.send(JSON.stringify({ t: "snapshot", players }));
			return;
		}

		if (msg.t === "state") {
			// sharing off = just the badge; otherwise song (+ the current line, if one is being sung)
			a.state = msg.sharing === false
				? null
				: clean({ song: msg.song, artist: msg.artist, line: msg.line, dur: msg.dur, elapsed: msg.elapsed });
			ws.serializeAttachment(a);
			this.broadcast(a.id, { t: "state", id: a.id, state: a.state });
		}
	}

	async webSocketClose(ws) {
		this.left(ws);
	}

	async webSocketError(ws) {
		this.left(ws);
	}

	left(ws) {
		const a = this.att(ws);
		if (!a.id) return;
		// still connected from somewhere else (two game windows)? then they haven't left
		const still = this.ctx.getWebSockets().some((o) => o !== ws && this.att(o).id === a.id);
		if (!still) this.broadcast(a.id, { t: "gone", id: a.id }, ws);
	}

	/** Send a message about player `id` to everyone who can see them (they also get their own). */
	broadcast(id, message, skip) {
		const text = JSON.stringify(message);
		for (const ws of this.ctx.getWebSockets()) {
			if (ws === skip) continue;
			const a = this.att(ws);
			if (!a.id) continue;
			if (a.id === id || (a.watch && a.watch.includes(id))) {
				try {
					ws.send(text);
				} catch {
					// socket going away
				}
			}
		}
	}

	/**
	 * Mojang login check: the mod told Mojang "I'm joining server <serverId>" with its real
	 * login, so Mojang can confirm which account that was. Returns the player's UUID or null.
	 */
	async verify(msg) {
		const name = String(msg.name || "");
		const serverId = String(msg.serverId || "");
		if (this.env.DEV === "1" && msg.devId) return normalizeId(msg.devId); // local testing only
		if (!/^[A-Za-z0-9_]{1,16}$/.test(name) || !/^[0-9a-f]{8,64}$/.test(serverId)) return null;
		try {
			const res = await fetch(
				`https://sessionserver.mojang.com/session/minecraft/hasJoined?username=${encodeURIComponent(name)}&serverId=${serverId}`,
			);
			if (res.status !== 200) return null;
			const profile = await res.json();
			return normalizeId(profile.id);
		} catch {
			return null;
		}
	}
}

function normalizeId(id) {
	const hex = String(id || "").toLowerCase().replace(/-/g, "");
	if (!/^[0-9a-f]{32}$/.test(hex)) return null;
	return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function clean(s) {
	const out = {};
	if (s.song) out.song = String(s.song).slice(0, LIMITS.song);
	if (s.artist) out.artist = String(s.artist).slice(0, LIMITS.artist);
	if (s.line) out.line = String(s.line).slice(0, LIMITS.line);
	if (Number.isFinite(s.dur)) out.dur = Math.max(0, Math.min(60_000, Math.round(s.dur)));
	if (Number.isFinite(s.elapsed)) out.elapsed = Math.max(0, Math.min(60_000, Math.round(s.elapsed)));
	return out;
}
