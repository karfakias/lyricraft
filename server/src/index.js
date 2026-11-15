import { PLAYER_CERTIFICATE_KEYS } from "./mojang-keys.js";

// Lyricraft online service
//
// Lets players who use Lyricraft see each other: a badge next to their name, and (if they
// share it) the lyric line they're hearing right now, floating above their head.
//
// Every player keeps one WebSocket open to a single "Hub" Durable Object.
//
// Security model:
// - Login: the hub sends a one-time challenge.
//   * Official accounts sign it with their chat key (the key pair every Minecraft account gets
//     for chat signing) and send the certificate Mojang made for that key. The hub checks
//     Mojang's signature on the certificate and the player's signature on the challenge, so the
//     account is verified without calling Mojang (Mojang blocks requests from Cloudflare) and
//     the login token never leaves the game. A signature only works for this connection's
//     challenge, so it can't be replayed.
//   * Everyone else (cracked / offline accounts) is let in under the offline id a cracked
//     server gives their name ("OfflinePlayer:" + name). Those ids never appear on official
//     servers, so nobody can pretend to be a premium player.
//   * Verified players also get their offline id, so they show up on cracked servers too.
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
	echoGapMs: 1500, worldLimit: 30_000_000,
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
				try { ws.send(JSON.stringify({ t: "denied" })); ws.close(4001, "bad login"); } catch { /* gone */ }
				return;
			}
			const fresh = this.att(ws);
			fresh.id = profile.id;
			fresh.alt = profile.alt;
			fresh.verified = profile.verified;
			fresh.nonce = null;
			ws.serializeAttachment(fresh);
			ws.send(JSON.stringify({ t: "welcome", id: profile.id, ids: idsOf(fresh), verified: profile.verified }));
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
				if (!o.id || o.id === a.id || !seesAny(after, o)) continue;
				if (watches(o, a)) players.push({ id: seenAs(o, after), state: o.state });
			}
			const self = idsOf(a).map((id) => ({ id, state: a.state }));
			ws.send(JSON.stringify({ t: "snapshot", players: [...self, ...dedupe(players)] }));

			// the other direction: players who just came into / left this player's view
			for (const other of this.ctx.getWebSockets()) {
				if (other === ws) continue;
				const o = this.att(other);
				if (!o.id || o.id === a.id || !watches(o, a)) continue;
				const nowSees = seesAny(after, o), sawBefore = seesAny(before, o);
				const as = seenAs(a, new Set(o.watch || []));
				if (nowSees && !sawBefore) safeSend(other, { t: "state", id: as, state: a.state });
				if (!nowSees && sawBefore) safeSend(other, { t: "gone", id: as });
			}
			return;
		}

		if (msg.t === "echo") {
			// one Echoes panel the player just placed, so nearby friends can see it in the same spot
			if (now - (a.lastEcho || 0) < LIMITS.echoGapMs) return; // at most one every 1.5 s
			const echo = cleanEcho(msg);
			if (!echo) return;
			a.lastEcho = now;
			ws.serializeAttachment(a);
			this.broadcast(a, (id) => ({ t: "echo", id, ...echo }), null, true);
			return;
		}

		if (msg.t === "state") {
			// sharing off = just the badge; otherwise song (+ the current line, if one is being sung)
			a.state = msg.sharing === false
				? null
				: clean({ song: msg.song, artist: msg.artist, line: msg.line, dur: msg.dur, elapsed: msg.elapsed });
			ws.serializeAttachment(a);
			this.broadcast(a, (id) => ({ t: "state", id, state: a.state }));
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
		if (!still) this.broadcast(a, (id) => ({ t: "gone", id }), ws, true);
	}

	/**
	 * Send something about player `a` to: themselves, and every player who can see them and
	 * whom they can see (mutual). Nobody else learns anything about them. Each viewer gets it
	 * under the id they know `a` by (premium id on official servers, offline id on cracked ones).
	 */
	broadcast(a, make, skip, othersOnly = false) {
		const theySee = new Set();
		for (const ws of this.ctx.getWebSockets()) {
			const o = this.att(ws);
			if (o.id === a.id) for (const id of o.watch || []) theySee.add(id); // all their game windows
		}
		for (const ws of this.ctx.getWebSockets()) {
			if (ws === skip) continue;
			const o = this.att(ws);
			if (!o.id) continue;
			if (o.id === a.id) {
				if (!othersOnly) for (const id of idsOf(a)) safeSend(ws, make(id));
				continue;
			}
			if (seesAny(theySee, o) && watches(o, a)) safeSend(ws, make(seenAs(a, new Set(o.watch || []))));
		}
	}

	/**
	 * Works out who's logging in. Returns { id, alt, verified } or null for a broken login.
	 * Official accounts that prove their chat certificate get their real id (plus their offline
	 * id as alt). Everyone else gets the offline id for their name.
	 */
	async verify(msg, nonce) {
		if (this.env.DEV === "1" && msg.devId) {
			const id = normalizeId(msg.devId); // local testing only, never in production
			return id ? { id, alt: null, verified: false } : null;
		}
		const name = String(msg.name || "");
		if (!nonce || !/^[A-Za-z0-9_]{1,16}$/.test(name)) return null;
		const offline = await offlineId(name);
		const premium = await verifyCertificate(msg.cert, msg.proof, nonce, this.env).catch(() => null);
		if (premium && premium !== offline) return { id: premium, alt: offline, verified: true };
		return { id: offline, alt: null, verified: false };
	}
}

/** Every id a player can be known by. */
function idsOf(a) {
	return a.alt ? [a.id, a.alt] : a.id ? [a.id] : [];
}

/** Does this watch list contain any of `o`'s ids? */
function seesAny(list, o) {
	return idsOf(o).some((id) => list.has(id));
}

/** Is `o` watching any of `a`'s ids? */
function watches(o, a) {
	return seesAny(new Set(o.watch || []), a);
}

/** The id of `a` that appears in a viewer's watch list (or the main one). */
function seenAs(a, list) {
	return idsOf(a).find((id) => list.has(id)) || a.id;
}

/** The id a cracked (offline mode) server gives a name: UUID v3 of "OfflinePlayer:" + name, like Java's nameUUIDFromBytes. */
export async function offlineId(name) {
	const hash = new Uint8Array(await crypto.subtle.digest("MD5", new TextEncoder().encode(`OfflinePlayer:${name}`)));
	hash[6] = (hash[6] & 0x0f) | 0x30;
	hash[8] = (hash[8] & 0x3f) | 0x80;
	const hex = [...hash].map((b) => b.toString(16).padStart(2, "0")).join("");
	return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

let mojangKeys;
async function mojangCertificateKeys(env) {
	if (!mojangKeys) {
		const keys = [...PLAYER_CERTIFICATE_KEYS];
		// local tests only: a stand-in "Mojang" key, never accepted in production
		if (env.DEV === "1" && typeof env.TEST_MOJANG_KEY === "string") keys.push(env.TEST_MOJANG_KEY);
		mojangKeys = await Promise.all(keys.map((k) => crypto.subtle.importKey(
			"spki", b64(k), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-1" }, false, ["verify"])));
	}
	return mojangKeys;
}

/**
 * Checks an official account's chat certificate and its signature on our challenge.
 * cert = { uuid, exp (ms), key (base64 X.509 public key), sig (base64, Mojang's signature) },
 * proof = base64 SHA256withRSA signature of "lyricraft:" + nonce made with the chat key.
 * Returns the verified id, or null.
 */
async function verifyCertificate(cert, proof, nonce, env) {
	if (!cert || typeof cert !== "object" || typeof proof !== "string" || proof.length > 2048) return null;
	const id = normalizeId(cert.uuid);
	const exp = Number(cert.exp);
	if (!id || !Number.isSafeInteger(exp) || exp < Date.now()) return null;
	if (typeof cert.key !== "string" || cert.key.length > 2048 || typeof cert.sig !== "string" || cert.sig.length > 2048) return null;
	const key = b64(cert.key), sig = b64(cert.sig);

	// what Mojang signed: uuid (16 bytes) + expiry (8 bytes, big endian) + the public key
	const payload = new Uint8Array(24 + key.length);
	const hex = id.replace(/-/g, "");
	for (let i = 0; i < 16; i++) payload[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
	new DataView(payload.buffer).setBigInt64(16, BigInt(exp));
	payload.set(key, 24);
	let signedByMojang = false;
	for (const k of await mojangCertificateKeys(env)) {
		if (await crypto.subtle.verify("RSASSA-PKCS1-v1_5", k, sig, payload)) { signedByMojang = true; break; }
	}
	if (!signedByMojang) return null;

	// and the player holds the matching private key: they signed this connection's challenge
	const chatKey = await crypto.subtle.importKey("spki", key, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
	const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", chatKey, b64(proof), new TextEncoder().encode(`lyricraft:${nonce}`));
	return ok ? id : null;
}

function b64(s) {
	const bin = atob(s);
	const out = new Uint8Array(bin.length);
	for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
	return out;
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

/** Checks and cleans one Echoes panel: a real position inside the world, a valid dimension, clean text. */
function cleanEcho(m) {
	const p = Array.isArray(m.pos) ? m.pos : null;
	if (!p || p.length !== 3 || !p.every((v) => Number.isFinite(v) && Math.abs(v) <= LIMITS.worldLimit)) return null;
	if (typeof m.dim !== "string" || !/^[a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,64}$/.test(m.dim)) return null;
	if (typeof m.line !== "string") return null;
	const line = cleanText(m.line, LIMITS.line);
	if (!line) return null;
	const num = (v, lo, hi, d) => (Number.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : d);
	return {
		pos: p.map((v) => Math.round(v * 100) / 100),
		dim: m.dim,
		line,
		dur: Math.round(num(m.dur, 0, 60_000, 3000)),
		elapsed: Math.round(num(m.elapsed, -60_000, 60_000, 0)),
		w: Math.round(num(m.w, 0.2, 12, 2) * 100) / 100,
		h: Math.round(num(m.h, 0.1, 3, 0.4) * 100) / 100,
	};
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
