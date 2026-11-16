// Tests the service with fake players against a local `npm run dev` (DEV mode).
// Usage: node test/two-players.mjs [ws://127.0.0.1:8787/ws]

import { createHash, createPrivateKey, generateKeyPairSync, sign } from "node:crypto";
import { TEST_MOJANG_PRIVATE } from "./test-mojang-key.mjs";

const URL_ = process.argv[2] || "ws://127.0.0.1:8787/ws";
const A = "11111111-1111-1111-1111-111111111111";
const B = "22222222-2222-2222-2222-222222222222";
const C = "33333333-3333-3333-3333-333333333333";

function connect(label) {
	return new Promise((resolve, reject) => {
		const ws = new WebSocket(URL_);
		const inbox = [];
		const waiters = [];
		ws.onmessage = (e) => {
			const m = JSON.parse(e.data);
			const w = waiters.findIndex((x) => x.pred(m));
			if (w >= 0) waiters.splice(w, 1)[0].res(m);
			else inbox.push(m);
		};
		ws.onopen = () => resolve({
			ws,
			label,
			send: (m) => ws.send(typeof m === "string" ? m : JSON.stringify(m)),
			next(pred, ms = 3000) {
				const i = inbox.findIndex(pred);
				if (i >= 0) return Promise.resolve(inbox.splice(i, 1)[0]);
				return new Promise((res, rej) => {
					const w = { pred, res };
					waiters.push(w);
					setTimeout(() => {
						const i = waiters.indexOf(w);
						if (i >= 0) waiters.splice(i, 1); // stop listening once timed out
						rej(new Error(`${label}: timed out waiting`));
					}, ms);
				});
			},
			nothing(pred, ms = 800) {
				return this.next(pred, ms).then(() => false, () => true);
			},
		});
		ws.onerror = reject;
	});
}

let failed = 0;
function check(ok, what) {
	console.log(`${ok ? "ok  " : "FAIL"} ${what}`);
	if (!ok) failed++;
}

async function player(label, id) {
	const p = await connect(label);
	const ch = await p.next((m) => m.t === "challenge");
	p.nonce = ch.nonce;
	p.send({ t: "hello", name: label, devId: id });
	await p.next((m) => m.t === "welcome");
	return p;
}

// 1) every connection gets its own one-time challenge
const liar = await connect("liar");
const ch = await liar.next((m) => m.t === "challenge");
check(/^[0-9a-f]{64}$/.test(ch.nonce), "a connection gets a random challenge");
// 2) a login with a name no Minecraft account can have is refused
liar.send({ t: "hello", name: "§cNot a name!" });
check(!!(await liar.next((m) => m.t === "denied").catch(() => null)), "a broken login is refused");

// 3) players
const a = await player("PlayerA", A);
const b = await player("PlayerB", B);
check(true, "two players logged in");

// only one login per connection
a.send({ t: "hello", name: "x", devId: C });
check(await a.nothing((m) => m.t === "welcome"), "a second login on the same connection is ignored");

// B shares a line
b.send({ t: "state", sharing: true, song: "Song", artist: "Artist", line: "first line", dur: 3000, elapsed: 100 });

// A watches B, but B doesn't see A: A must learn NOTHING about B
a.send({ t: "watch", ids: [B] });
const snap1 = await a.next((m) => m.t === "snapshot");
check(!snap1.players.some((p) => p.id === B), "watching someone who can't see you reveals nothing (no stalking)");

// now B sees A too: A gets B's state
b.send({ t: "watch", ids: [A] });
const live = await a.next((m) => m.t === "state" && m.id === B);
check(live.state?.line === "first line", "once both see each other, A gets B's line");
a.send({ t: "watch", ids: [B, C] });
const snap2 = await a.next((m) => m.t === "snapshot");
check(snap2.players.some((p) => p.id === B && p.state?.line === "first line"), "snapshot includes B's line when it's mutual");
check(snap2.players.some((p) => p.id === A), "snapshot includes yourself");

// live update
b.send({ t: "state", sharing: true, song: "Song", artist: "Artist", line: "second line", dur: 2500, elapsed: 0 });
const upd = await a.next((m) => m.t === "state" && m.id === B && m.state?.line === "second line");
check(upd.state.dur === 2500, "A gets B's new line live");
b.send({ t: "state", sharing: true, song: "Song", line: "on echoes", dur: 2000, elapsed: 0, echoes: true });
const onEchoes = await a.next((m) => m.t === "state" && m.id === B && m.state?.line === "on echoes");
check(onEchoes.state.echoes === true, "A learns that B shows their lines as Echoes panels");
b.send({ t: "state", sharing: true, song: "Song", line: "not echoes", dur: 2000, elapsed: 0, echoes: "yes please" });
const junk = await a.next((m) => m.t === "state" && m.id === B && m.state?.line === "not echoes");
check(junk.state.echoes === undefined, "only a real true counts as being on Echoes");

// B stops seeing A (e.g. A left B's server): A is told B is gone and gets no more updates
b.send({ t: "watch", ids: [] });
check(!!(await a.next((m) => m.t === "gone" && m.id === B).catch(() => null)), "when B can't see A anymore, A loses B");
b.send({ t: "state", sharing: true, song: "Song", line: "private now" });
check(await a.nothing((m) => m.state?.line === "private now"), "and gets nothing more from B");
b.send({ t: "watch", ids: [A] });
await a.next((m) => m.t === "state" && m.id === B);

// sharing off: badge only
b.send({ t: "state", sharing: false, song: "Song", artist: "Artist", line: "secret" });
const off = await a.next((m) => m.t === "state" && m.id === B && m.state === null);
check(off.state === null, "turning sharing off sends no lyrics, only the badge");

// text cleaning: colour codes, control and invisible characters are removed, length capped
b.send({ t: "state", sharing: true, song: "§4§lFAKE ADMIN‮\u0000song", line: "l".repeat(900) + "§k" });
const cleaned = await a.next((m) => m.t === "state" && m.id === B && m.state?.song);
check(!cleaned.state.song.includes("§") && !/[\u0000‮]/.test(cleaned.state.song), "colour codes and invisible characters are stripped");
check(cleaned.state.line.length === 200, "long text is cut to size");

// oversized message is ignored
b.send(JSON.stringify({ t: "state", sharing: true, line: "x".repeat(20000) }));
check(await a.nothing((m) => m.state?.line?.startsWith("xxxx")), "an oversized message is ignored");

// Echoes panels
await new Promise((r) => setTimeout(r, 1600));
b.send({ t: "echo", pos: [10.5, 70.2, -3.25], dim: "minecraft:overworld", line: "§cEcho §lline", dur: 3000, elapsed: -200, w: 2.5, h: 0.4 });
const echo = await a.next((m) => m.t === "echo" && m.id === B).catch(() => null);
check(!!echo && echo.pos[0] === 10.5 && echo.dim === "minecraft:overworld", "A gets B's Echoes panel with its spot in the world");
check(echo && echo.line === "Echo line", "the panel text is cleaned");
check(await b.nothing((m) => m.t === "echo"), "you don't get your own panel echoed back");
b.send({ t: "echo", pos: [11, 70, -3], dim: "minecraft:overworld", line: "too soon", dur: 3000 });
check(await a.nothing((m) => m.t === "echo" && m.line === "too soon"), "at most one panel every 1.5 s");
await new Promise((r) => setTimeout(r, 1600));
for (const bad of [
	{ pos: [1e12, 70, 0], dim: "minecraft:overworld", line: "far" },
	{ pos: [1, "x", 0], dim: "minecraft:overworld", line: "nan" },
	{ pos: [1, 70, 0], dim: "<script>", line: "dim" },
	{ pos: [1, 70, 0], dim: "minecraft:overworld", line: "" },
]) b.send({ t: "echo", dur: 3000, ...bad });
check(await a.nothing((m) => m.t === "echo"), "broken or out-of-world panels are refused");
await new Promise((r) => setTimeout(r, 1600));
b.send({ t: "watch", ids: [] });
await a.next((m) => m.t === "gone" && m.id === B);
b.send({ t: "echo", pos: [1, 70, 0], dim: "minecraft:overworld", line: "hidden", dur: 3000 });
check(await a.nothing((m) => m.t === "echo"), "panels only go to players who see each other");
b.send({ t: "watch", ids: [A] });

// ---------------------------------------------------------------- logins without a dev id

/** Java's UUID.nameUUIDFromBytes("OfflinePlayer:" + name), what a cracked server uses. */
function javaOfflineId(name) {
	const h = createHash("md5").update(`OfflinePlayer:${name}`).digest();
	h[6] = (h[6] & 0x0f) | 0x30;
	h[8] = (h[8] & 0x3f) | 0x80;
	const x = h.toString("hex");
	return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`;
}

/** A chat certificate like Mojang's, signed with `signer` (the test stand-in, or a random key). */
function certificate(uuid, expMs, signer) {
	const player = generateKeyPairSync("rsa", { modulusLength: 2048 });
	const spki = player.publicKey.export({ type: "spki", format: "der" });
	const payload = Buffer.alloc(24 + spki.length);
	Buffer.from(uuid.replace(/-/g, ""), "hex").copy(payload, 0);
	payload.writeBigInt64BE(BigInt(expMs), 16);
	spki.copy(payload, 24);
	const sig = sign("sha1", payload, signer);
	return {
		cert: { uuid, exp: expMs, key: spki.toString("base64"), sig: sig.toString("base64") },
		proofFor: (nonce) => sign("sha256", Buffer.from(`lyricraft:${nonce}`), player.privateKey).toString("base64"),
	};
}

async function login(label, hello) {
	const p = await connect(label);
	const ch = await p.next((m) => m.t === "challenge");
	p.send({ t: "hello", ...hello(ch.nonce) });
	p.welcome = await p.next((m) => m.t === "welcome").catch(() => null);
	return p;
}

const mojang = createPrivateKey({ key: Buffer.from(TEST_MOJANG_PRIVATE, "base64"), format: "der", type: "pkcs8" });
const PREMIUM = "44444444-4444-4444-4444-444444444444";

const cracked = await login("cracked", () => ({ name: "CrackedSteve" }));
check(cracked.welcome?.id === javaOfflineId("CrackedSteve") && cracked.welcome?.verified === false,
	"an offline account gets in, with the same id a cracked server gives it");

const good = certificate(PREMIUM, Date.now() + 3_600_000, mojang);
const premium = await login("premium", (nonce) => ({ name: "Favrd", cert: good.cert, proof: good.proofFor(nonce) }));
check(premium.welcome?.id === PREMIUM && premium.welcome?.verified === true, "an official account is verified from its chat certificate");
check(premium.welcome?.ids?.includes(javaOfflineId("Favrd")), "a verified player also gets their offline id (for cracked servers)");

const forged = certificate(PREMIUM, Date.now() + 3_600_000, generateKeyPairSync("rsa", { modulusLength: 2048 }).privateKey);
const faker = await login("faker", (nonce) => ({ name: "Favrd", cert: forged.cert, proof: forged.proofFor(nonce) }));
check(faker.welcome?.id === javaOfflineId("Favrd") && !faker.welcome?.verified, "a certificate Mojang didn't sign can't claim a premium id");

const old = certificate(PREMIUM, Date.now() - 1000, mojang);
const expired = await login("expired", (nonce) => ({ name: "Favrd", cert: old.cert, proof: old.proofFor(nonce) }));
check(expired.welcome?.id !== PREMIUM, "an expired certificate isn't accepted");

const replay = await login("replay", () => ({ name: "Favrd", cert: good.cert, proof: good.proofFor("0".repeat(64)) }));
check(replay.welcome?.id !== PREMIUM, "a signature for a different challenge can't be replayed");

// on a cracked server the verified player shows up under their offline id
const crackedFavrd = javaOfflineId("Favrd");
premium.send({ t: "watch", ids: [cracked.welcome.id] });
premium.send({ t: "state", sharing: true, song: "Cracked server song" });
cracked.send({ t: "watch", ids: [crackedFavrd] });
const seen = await cracked.next((m) => m.t === "state" && m.state?.song === "Cracked server song").catch(() => null);
check(seen?.id === crackedFavrd, "players on a cracked server see a verified friend under the id their server uses");
for (const p of [cracked, premium, faker, expired, replay]) p.ws.close();

// B leaves
b.ws.close();
check(!!(await a.next((m) => m.t === "gone" && m.id === B).catch(() => null)), "A is told when B goes offline");

for (const p of [a, liar]) p.ws.close();
console.log(failed ? `${failed} FAILED` : "ALL SERVICE CHECKS PASSED");
process.exit(failed ? 1 : 0);
