// Lyricraft online service
//
// Lets players who use Lyricraft see each other: a badge next to their name, and (if they
// share it) the lyric line they're hearing right now, floating above their head.
//
// Every player keeps one WebSocket open to a single "Hub" Durable Object.
//
// Security model:
// - Login: the hub sends a one-time challenge. The mod turns it into a Mojang "server id" and
//   tells Mojang "I'm joining that server" with the player's real login; the hub then asks Mojang
//   (hasJoined) which account did that. The login token itself only ever goes to Mojang. Because
//   the server id comes from OUR challenge, a login code captured by some Minecraft server
//   can't be replayed here.
// - Privacy: a player's badge, song and line only go to players who can see them AND whom they
//   can see (both are in each other's tab list, i.e. really on the same server). Nobody can look
//   up an arbitrary player from afar.
// - Abuse: per-IP connection limits, one login attempt per connection, unverified connections
//   are dropped after 15 s, message size and rate limits, and all text is cleaned.
// - Nothing is stored and nothing is logged. The hub only knows who is connected right now.

const LIMITS = {
	song: 120, artist: 120, line: 200, watch: 300,
	msgsPer10s: 40, messageBytes: 8192,
	connPerIp: 8, newConnPerIpPerMin: 20, totalConnections: 20000,
	loginTimeoutMs: 15_000,
};

export default {
	async fetch(request, env) {
		const url = new URL(request.url);
		const hub = env.HUB.get(env.HUB.idFromName("global"));
		if (url.pathname === "/ws") {
			if (request.headers.get("Upgrade") !== "websocket") return new Response("Expected a WebSocket", { status: 426 });
			return hub.fetch(request);
		}
		if (url.pathname === "/" || url.pathname === "/health") {
			return hub.fetch(new Request(new URL("/health", request.url)));
		}
		return new Response("Not found", { status: 404 });
	},
};

export class Hub {
	constructor(ctx, env) {
		this.ctx = ctx;
		this.env = env;
		this.recentConnects = new Map(); // ip -> { window, count } (in memory only)
	}

	async fetch(request) {
		const url = new URL(request.url);
		if (url.pathname === "/health") {
			// only says it's up; no player counts or ids
			return Response.json({ ok: true });
		}

		const ip = request.headers.get("CF-Connecting-IP") || "local";
		this.sweep();
		const sockets = this.ctx.getWebSockets();
		if (sockets.length >= LIMITS.totalConnections) return new Response("Busy", { status: 503 });
		if (sockets.filter((ws) => this.att(ws).ip === ipKey(ip)).length >= LIMITS.connPerIp) {
			return new Response("Too many connections", { status: 429 });
		}
		const now = Date.now();
		const rc = this.recentConnects.get(ip) || { window: now, count: 0 };
		if (now - rc.window > 60_000) { rc.window = now; rc.count = 0; }
		if (++rc.count > LIMITS.newConnPerIpPerMin) return new Response("Slow down", { status: 429 });
		this.recentConnects.set(ip, rc);
		if (this.recentConnects.size > 50_000) this.recentConnects.clear();

		const pair = new WebSocketPair();
		const [client, server] = Object.values(pair);
		this.ctx.acceptWebSocket(server);
		const nonce = randomHex(32);
		server.serializeAttachment({
			id: null, nonce, tried: false, ip: ipKey(ip), opened: now,
			watch: [], state: null, window: 0, count: 0,
		});
		server.send(JSON.stringify({ t: "challenge", nonce }));
		// make sure connections that never log in get dropped, even while the hub sleeps
		if (!(await this.ctx.storage.getAlarm())) await this.ctx.storage.setAlarm(now + LIMITS.loginTimeoutMs + 500);
		return new Response(null, { status: 101, webSocket: client });
	}

	async alarm() {
		this.sweep();
		const waiting = this.ctx.getWebSockets().some((ws) => !this.att(ws).id);
		if (waiting) await this.ctx.storage.setAlarm(Date.now() + 5000);
	}

	att(ws) {
		try {
			return ws.deserializeAttachment() || {};
		} catch {
			return {};
		}
	}

	/** Drop connections that never logged in. */
	sweep() {
		const now = Date.now();
		for (const ws of this.ctx.getWebSockets()) {
			const a = this.att(ws);
			if (!a.id && now - (a.opened || 0) > LIMITS.loginTimeoutMs) {
				try { ws.close(4002, "login timeout"); } catch { /* already closing */ }
				// mark it so it no longer counts towards limits while it finishes closing
				try { ws.serializeAttachment({ ...a, id: null, ip: "closed", opened: Number.MAX_SAFE_INTEGER }); } catch { /* gone */ }
			}
		}
	}

	async webSocketMessage(ws, raw) {
		const text = typeof raw === "string" ? raw : "";
		if (!text || text.length > LIMITS.messageBytes) return; // ignore binary and oversized frames
		let msg;
		try {
			msg = JSON.parse(text);
		} catch {
			return;
		}
		if (!msg || typeof msg !== "object") return;
		const a = this.att(ws);

		// flood protection
		const now = Date.now();
		if (now - a.window > 10_000) { a.window = now; a.count = 0; }
		if (++a.count > LIMITS.msgsPer10s) {
			ws.serializeAttachment(a);
			return;
		}

		if (msg.t === "hello") {
			if (a.id || a.tried) return; // one login attempt per connection
			a.tried = true;
			ws.serializeAttachment(a);
			const profile = await this.verify(msg, a.nonce);
			if (!profile) {
				try { ws.send(JSON.stringify({ t: "denied" })); ws.close(4001, "not verified"); } catch { /* gone */ }
				return;
			}
			const fresh = this.att(ws);
			fresh.id = profile.id;
			fresh.nonce = null;
			ws.serializeAttachment(fresh);
			ws.send(JSON.stringify({ t: "welcome", id: profile.id }));
			return;
		}
		if (!a.id) return; // everything else needs a verified player

		if (msg.t === "watch") {
			const before = new Set(a.watch || []);
			const ids = Array.isArray(msg.ids)
				? [...new Set(msg.ids.slice(0, LIMITS.watch).map(normalizeId).filter(Boolean))]
				: [];
			a.watch = ids;
			ws.serializeAttachment(a);
			const after = new Set(ids);

			// what they can see now: everyone in their list who also sees them
			const players = [];
			for (const other of this.ctx.getWebSockets()) {
				const o = this.att(other);
				if (!o.id || o.id === a.id || !after.has(o.id)) continue;
				if ((o.watch || []).includes(a.id)) players.push({ id: o.id, state: o.state });
			}
			const self = { id: a.id, state: a.state };
			ws.send(JSON.stringify({ t: "snapshot", players: [self, ...dedupe(players)] }));

			// the other direction: players who just came into / left this player's view
			for (const other of this.ctx.getWebSockets()) {
				if (other === ws) continue;
				const o = this.att(other);
				if (!o.id || o.id === a.id || !(o.watch || []).includes(a.id)) continue;
				const nowSees = after.has(o.id), sawBefore = before.has(o.id);
				if (nowSees && !sawBefore) safeSend(other, { t: "state", id: a.id, state: a.state });
				if (!nowSees && sawBefore) safeSend(other, { t: "gone", id: a.id });
			}
			return;
		}

		if (msg.t === "state") {
			// sharing off = just the badge; otherwise song (+ the current line, if one is being sung)
			a.state = msg.sharing === false
				? null
				: clean({ song: msg.song, artist: msg.artist, line: msg.line, dur: msg.dur, elapsed: msg.elapsed });
			ws.serializeAttachment(a);
			this.broadcast(a, { t: "state", id: a.id, state: a.state });
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
		if (!still) this.broadcast(a, { t: "gone", id: a.id }, ws);
	}

	/**
	 * Send something about player `a` to: themselves, and every player who can see them and
	 * whom they can see (mutual). Nobody else learns anything about them.
	 */
	broadcast(a, message, skip) {
		const text = JSON.stringify(message);
		const theySee = new Set();
		for (const ws of this.ctx.getWebSockets()) {
			const o = this.att(ws);
			if (o.id === a.id) for (const id of o.watch || []) theySee.add(id); // all their game windows
		}
		for (const ws of this.ctx.getWebSockets()) {
			if (ws === skip) continue;
			const o = this.att(ws);
			if (!o.id) continue;
			const mutual = theySee.has(o.id) && (o.watch || []).includes(a.id);
			if (o.id === a.id || mutual) safeSend(ws, text);
		}
	}

	/**
	 * Mojang login check, bound to this connection's challenge. Returns { id, name } or null.
	 */
	async verify(msg, nonce) {
		if (this.env.DEV === "1" && msg.devId) {
			const id = normalizeId(msg.devId); // local testing only, never in production
			return id ? { id, name: "dev" } : null;
		}
		const name = String(msg.name || "");
		if (!nonce || !/^[A-Za-z0-9_]{1,16}$/.test(name)) return null;
		const serverId = await serverIdFor(nonce);
		try {
			const res = await fetch(
				`https://sessionserver.mojang.com/session/minecraft/hasJoined?username=${encodeURIComponent(name)}&serverId=${serverId}`,
			);
			if (res.status !== 200) return null;
			const profile = await res.json();
			const id = normalizeId(profile.id);
			return id ? { id, name: String(profile.name || "") } : null;
		} catch {
			return null;
		}
	}
}

/** The Mojang server id for a challenge: sha1("lyricraft:" + nonce) as hex. The mod computes the same. */
async function serverIdFor(nonce) {
	const data = new TextEncoder().encode(`lyricraft:${nonce}`);
	const hash = await crypto.subtle.digest("SHA-1", data);
	return [...new Uint8Array(hash)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function randomHex(bytes) {
	const b = new Uint8Array(bytes);
	crypto.getRandomValues(b);
	return [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
}

/** Keep only a coarse key for the per-IP limit (never sent anywhere, gone when they disconnect). */
function ipKey(ip) {
	return String(ip).slice(0, 64);
}

function safeSend(ws, message) {
	try {
		ws.send(typeof message === "string" ? message : JSON.stringify(message));
	} catch {
		// socket going away
	}
}

function dedupe(players) {
	const seen = new Set();
	return players.filter((p) => !seen.has(p.id) && seen.add(p.id));
}

function normalizeId(id) {
	const hex = String(id || "").toLowerCase().replace(/-/g, "");
	if (!/^[0-9a-f]{32}$/.test(hex)) return null;
	return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/**
 * Text other players will see: no Minecraft colour/format codes, no control or invisible
 * direction-changing characters, single line, limited length.
 */
function cleanText(s, max) {
	return String(s)
		.replace(/§./gs, "")
		.replace(/[\u0000-\u001f\u007f-\u009f­؜ᅟᅠ᠎​-‏‪-‮⁠-⁯ㅤ﻿ﾠ￰-￿]/g, "")
		.replace(/\s+/g, " ")
		.trim()
		.slice(0, max);
}

function clean(s) {
	const out = {};
	if (typeof s.song === "string" && cleanText(s.song, LIMITS.song)) out.song = cleanText(s.song, LIMITS.song);
	if (typeof s.artist === "string" && cleanText(s.artist, LIMITS.artist)) out.artist = cleanText(s.artist, LIMITS.artist);
	if (typeof s.line === "string" && cleanText(s.line, LIMITS.line)) out.line = cleanText(s.line, LIMITS.line);
	if (Number.isFinite(s.dur)) out.dur = Math.max(0, Math.min(60_000, Math.round(s.dur)));
	if (Number.isFinite(s.elapsed)) out.elapsed = Math.max(0, Math.min(60_000, Math.round(s.elapsed)));
	return out;
}
