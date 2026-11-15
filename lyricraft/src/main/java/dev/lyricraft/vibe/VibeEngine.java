package dev.lyricraft.vibe;

import dev.lyricraft.Config;
import dev.lyricraft.hud.Draw;
import dev.lyricraft.lyrics.Lyrics;
import dev.lyricraft.media.MediaSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Random;

/**
 * The "beat" of the vibe. We can't hear the audio, but the lyrics tell us exactly when every
 * line and (roughly) every word is sung, so those become the beats: each word gives a small
 * pulse, each new line a big one. Fast, wordy songs pulse fast; slow songs breathe slowly.
 *
 * Also picks a colour palette per song (same song = same colours) and spawns client-side
 * music particles around the player. Particles are only on your screen, never on the server.
 */
public final class VibeEngine {

	private final Random random = new Random();

	private int lastLine = Integer.MIN_VALUE;
	private int lastWord = -1;
	private volatile long lastHitNanos;
	private volatile float hitStrength;
	private volatile float energy = 0.4f; // 0..1, how busy the song is
	private Lyrics energyFor;

	// palette (current, eased towards target)
	private int targetA = 0x1ED760, targetB = 0x1DB954;
	private volatile int colorA = 0x1ED760, colorB = 0x1DB954;
	private String paletteFor = "";
	private float hueSeed;

	/** Called 20x per second. */
	public void tick(Minecraft mc, Config cfg, MediaSnapshot track, Lyrics lyrics, long posMs, boolean playing) {
		if (track != null && !track.trackKey().equals(paletteFor)) {
			paletteFor = track.trackKey();
			hueSeed = (track.trackKey().hashCode() & 0x7fffffff) % 360;
			targetA = Draw.hsv(hueSeed, 0.75f, 1f);
			targetB = Draw.hsv(hueSeed + 55f, 0.85f, 1f);
			lastLine = Integer.MIN_VALUE;
			lastWord = -1;
		}
		// the album cover's colours beat the made-up palette once the cover has loaded
		if (track != null && cfg.coverColors) {
			dev.lyricraft.media.CoverArt.Cover cover = dev.lyricraft.media.CoverArt.get(track.trackKey());
			if (cover != null) {
				targetA = cover.colorA();
				targetB = cover.colorB();
			}
		}
		colorA = Draw.mix(colorA, targetA, 0.08f);
		colorB = Draw.mix(colorB, targetB, 0.08f);

		if (!playing || lyrics == null || lyrics.kind != Lyrics.Kind.SYNCED || cfg.vibe == Config.Vibe.OFF) return;
		if (energyFor != lyrics) {
			energyFor = lyrics;
			energy = estimateEnergy(lyrics);
		}

		int line = lyrics.activeIndex(posMs);
		if (line < 0) return;
		Lyrics.Line l = lyrics.lines.get(line);
		List<Lyrics.Word> words = l.words();
		float p = l.progress(posMs);
		int word = -1;
		for (int i = 0; i < words.size(); i++) if (p >= words.get(i).start()) word = i;

		boolean newLine = line != lastLine;
		boolean newWord = newLine || word != lastWord;
		if (newLine) hit(1f);
		else if (newWord && word >= 0) hit(0.45f + 0.25f * energy);
		lastLine = line;
		lastWord = word;

		if (cfg.vibeParticles && mc.player != null && mc.level != null && !mc.isPaused()) {
			spawnParticles(mc.level, mc.player, cfg.vibe == Config.Vibe.FULL, newLine, newWord && word >= 0);
		}
	}

	private void hit(float strength) {
		hitStrength = Math.max(strength, pulse(System.nanoTime()) + strength * 0.5f);
		hitStrength = Math.min(hitStrength, 1.3f);
		lastHitNanos = System.nanoTime();
	}

	/** Current beat pulse, 0..~1.3, decaying smoothly after each hit. */
	public float pulse(long now) {
		float t = (now - lastHitNanos) / 1e9f;
		return hitStrength * (float) Math.exp(-t / 0.22f);
	}

	public int colorA() {
		return colorA;
	}

	public int colorB() {
		return colorB;
	}

	public float energy() {
		return energy;
	}

	/** Words per second across the song, mapped to 0..1. */
	private static float estimateEnergy(Lyrics lyrics) {
		if (lyrics == null || lyrics.lines.isEmpty()) return 0.4f;
		long sungMs = 0;
		int words = 0;
		for (Lyrics.Line l : lyrics.lines) {
			sungMs += l.durationMs();
			words += l.words().size();
		}
		float wps = words / Math.max(1f, sungMs / 1000f);
		return Math.clamp((wps - 1.2f) / 2.5f, 0f, 1f); // ~1.2 wps = calm, ~3.7 wps = rap
	}

	private void spawnParticles(ClientLevel level, Player player, boolean full, boolean newLine, boolean newWord) {
		Vec3 pos = player.position();

		// sparkles drifting up around you
		float ambientChance = full ? 0.6f + energy * 0.4f : 0.18f;
		int ambientCount = full ? 2 : 1;
		for (int i = 0; i < ambientCount; i++) {
			if (random.nextFloat() > ambientChance) continue;
			double a = random.nextDouble() * Math.PI * 2, r = 2 + random.nextDouble() * 5;
			level.addParticle(ParticleTypes.END_ROD,
					pos.x + Math.cos(a) * r, pos.y + random.nextDouble() * 2.5, pos.z + Math.sin(a) * r,
					0, 0.015 + random.nextDouble() * 0.02, 0);
		}

		// a coloured music note pops above your head on sung words
		if (newWord && (full || random.nextFloat() < 0.35f)) {
			double note = ((hueSeed / 360.0) + random.nextDouble() * 0.25) % 1.0;
			level.addParticle(ParticleTypes.NOTE,
					pos.x + (random.nextDouble() - 0.5) * 1.6, pos.y + 2.3 + random.nextDouble() * 0.4,
					pos.z + (random.nextDouble() - 0.5) * 1.6, note, 0, 0);
		}

		// each new line sends a ring of light outward across the ground
		if (newLine && full) {
			int n = 18;
			for (int i = 0; i < n; i++) {
				double a = Math.PI * 2 * i / n;
				level.addParticle(ParticleTypes.END_ROD, pos.x, pos.y + 0.15, pos.z,
						Math.cos(a) * 0.16, 0.01, Math.sin(a) * 0.16);
			}
			if (energy > 0.55f) {
				for (int i = 0; i < 6; i++) {
					level.addParticle(ParticleTypes.ELECTRIC_SPARK,
							pos.x + (random.nextDouble() - 0.5) * 2, pos.y + 1 + random.nextDouble(),
							pos.z + (random.nextDouble() - 0.5) * 2, 0, 0.05, 0);
				}
			}
		}
	}
}
