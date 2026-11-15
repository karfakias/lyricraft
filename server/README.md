# Lyricraft online service

This small service lets Lyricraft players see each other: the ♫ badge after their name, and the line they're hearing above their head. Minecraft servers can't pass that data between players, so the mod connects here instead. No server mod needed.

It runs on Cloudflare Workers, which is free at this size.

## What it does

- Each player opens one WebSocket to the service.
- They prove who they are with Mojang's own login check (the same thing that happens when you join a Minecraft server). Their login token only goes to Mojang, never here.
- They send the list of players they can see, and their own song and current line (if sharing is on).
- The service forwards each player's updates to the players who can see them.

Nothing is saved. The service only knows who is connected right now.

## Security

- **Logins can't be faked or replayed.** Every connection gets a one-time random challenge. The mod tells Mojang "I'm joining server `sha1("lyricraft:" + challenge)`" with the player's real login, and the service asks Mojang which account did that. A login code captured by some Minecraft server you joined is for a different server id, so it's useless here. The login token itself never reaches the service.
- **No looking people up.** A player's badge, song and line only go to players who can see them *and* whom they can see (both in each other's tab list, so really on the same server). Asking about anyone else returns nothing, so nobody can check what a player is listening to, or whether they're online, from afar.
- **Limits.** 8 connections per IP at once and 20 new ones per minute, one login attempt per connection, connections that don't log in within 15 s are dropped, messages over 8 KB are ignored, and at most 40 messages per 10 s per player.
- **Clean text.** Minecraft colour codes (`§`), control characters and invisible direction-flipping characters are stripped from everything players send, and lengths are capped. The mod cleans it again when it arrives.
- **No logs, no storage.** Logging is switched off in `wrangler.toml`, the code logs nothing, and nothing is written to storage. The `/health` page only says it's up, without player counts or ids.
- **Can't cost you money.** On Cloudflare's free plan, going over the limits makes requests fail for the rest of the day; there's no bill.
- **Test mode is local only.** `npm run dev` accepts fake players for testing (`DEV=1`). The deployed service never sets that, and refuses them.

For the person who deploys it: turn on two-factor login for your Cloudflare account, and run `npx wrangler logout` when you're done deploying so the deploy token isn't left on your PC.

## Deploy it (once)

You need a free [Cloudflare account](https://dash.cloudflare.com/sign-up) and Node.js.

```bash
cd server
npm install
npx wrangler login
npx wrangler deploy
```

`wrangler login` opens your browser to sign in to Cloudflare. `wrangler deploy` prints the address of your service, something like:

```
https://lyricraft.your-name.workers.dev
```

Put that address in the mod's default config (`onlineServer` in `src/main/java/dev/lyricraft/Config.java`), then rebuild the jar. Players can also change it in `config/lyricraft.json`.

To check it's running, open the address in a browser. It should say `{"ok":true,"online":0}`.

## Free plan limits

The free plan includes 100,000 requests a day. A player's lyric updates are WebSocket messages, which Cloudflare counts at 20 messages per request, so a few hundred players listening for hours a day still fits. If it ever grows past that, the paid plan is $5/month.

## Test it locally

```bash
cd server
npm run dev
```

This runs the service on `http://127.0.0.1:8787` in test mode, which accepts fake players. In another terminal:

```bash
npm test
```

runs a test with fake players. The mod's in-game test (`./gradlew runClientGameTest`) also uses this local service when it's running.
