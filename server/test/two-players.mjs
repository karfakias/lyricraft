// Tests the service with fake players against a local `npm run dev` (DEV mode).
// Usage: node test/two-players.mjs [ws://127.0.0.1:8787/ws]

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
// 2) a login that isn't really from Mojang is refused (real Mojang check, no dev id)
liar.send({ t: "hello", name: "Notch" });
check(!!(await liar.next((m) => m.t === "denied").catch(() => null)), "a fake login is refused");

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

// B leaves
b.ws.close();
check(!!(await a.next((m) => m.t === "gone" && m.id === B).catch(() => null)), "A is told when B goes offline");

for (const p of [a, liar]) p.ws.close();
console.log(failed ? `${failed} FAILED` : "ALL SERVICE CHECKS PASSED");
process.exit(failed ? 1 : 0);
