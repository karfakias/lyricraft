// Tests the service with two fake players against a local `npm run dev` (DEV mode).
// Usage: node test/two-players.mjs [ws://localhost:8787/ws]

const URL_ = process.argv[2] || "ws://localhost:8787/ws";
const A = "11111111-1111-1111-1111-111111111111";
const B = "22222222-2222-2222-2222-222222222222";

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
			send: (m) => ws.send(JSON.stringify(m)),
			next(pred, ms = 3000) {
				const i = inbox.findIndex(pred);
				if (i >= 0) return Promise.resolve(inbox.splice(i, 1)[0]);
				return new Promise((res, rej) => {
					waiters.push({ pred, res });
					setTimeout(() => rej(new Error(`${label}: timed out waiting`)), ms);
				});
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

// 1) a fake login without the dev id must be refused
const liar = await connect("liar");
liar.send({ t: "hello", name: "Notch", serverId: "deadbeef" });
const denied = await liar.next((m) => m.t === "denied").catch(() => null);
check(denied !== null, "an unverified player is refused");

// 2) two real (dev) players
const a = await connect("A");
const b = await connect("B");
a.send({ t: "hello", name: "PlayerA", devId: A });
b.send({ t: "hello", name: "PlayerB", devId: B });
check((await a.next((m) => m.t === "welcome")).id === A, "A is welcomed with its id");
check((await b.next((m) => m.t === "welcome")).id === B, "B is welcomed with its id");

// B shares a line before anyone watches
b.send({ t: "state", sharing: true, song: "Song", artist: "Artist", line: "first line", dur: 3000, elapsed: 100 });

// A starts watching B: the snapshot says B uses the mod and what B is hearing
a.send({ t: "watch", ids: [B, "33333333-3333-3333-3333-333333333333"] });
const snap = await a.next((m) => m.t === "snapshot");
check(snap.players.length === 1 && snap.players[0].id === B, "snapshot lists only players who use the mod");
check(snap.players[0].state?.line === "first line", "snapshot includes B's current line");

// live update
b.send({ t: "state", sharing: true, song: "Song", artist: "Artist", line: "second line", dur: 2500, elapsed: 0 });
const upd = await a.next((m) => m.t === "state" && m.id === B && m.state?.line === "second line");
check(upd.state.song === "Song" && upd.state.dur === 2500, "A gets B's new line live");

// B turns sharing off: A still knows B uses the mod (badge), but no lyrics
b.send({ t: "state", sharing: false, song: "Song", artist: "Artist", line: "secret" });
const off = await a.next((m) => m.t === "state" && m.id === B && m.state === null);
check(off.state === null, "turning sharing off sends no lyrics, only the badge");

// someone A doesn't watch doesn't reach A
const c = await connect("C");
c.send({ t: "hello", name: "PlayerC", devId: "33333333-3333-3333-3333-444444444444" });
await c.next((m) => m.t === "welcome");
c.send({ t: "state", sharing: true, song: "x", line: "should not arrive" });
const leak = await a.next((m) => m.t === "state" && m.state?.line === "should not arrive", 800).catch(() => null);
check(leak === null, "players A can't see don't reach A");

// B leaves
b.ws.close();
const gone = await a.next((m) => m.t === "gone" && m.id === B);
check(gone.id === B, "A is told when B goes offline");

// junk is ignored and long text is cut
a.send({ t: "state", sharing: true, song: "s".repeat(500), line: "l".repeat(900) });
const own = await a.next((m) => m.t === "state" && m.id === A && m.state?.line);
check(own.state.line.length === 200 && own.state.song.length === 120, "long text is cut to size");

for (const p of [a, c, liar]) p.ws.close();
console.log(failed ? `${failed} FAILED` : "ALL SERVICE CHECKS PASSED");
process.exit(failed ? 1 : 0);
