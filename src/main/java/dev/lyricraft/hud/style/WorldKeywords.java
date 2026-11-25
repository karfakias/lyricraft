package dev.lyricraft.hud.style;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Words that change the world when they're sung in the Edit style: "fire" sets the words alight,
 * "rain" makes it rain around you, "storm" brings a lightning bolt down on the word, "gold"
 * throws gold out of it, and so on. The word itself takes on that colour too.
 */
final class WorldKeywords {
	private WorldKeywords() {}

	enum Kind {
		FIRE(0xFF7A1A), RAIN(0x7FB2FF), STORM(0xBFE8FF), HEART(0xFF6FAE), STAR(0xFFE27A), NIGHT(0xB9A8FF),
		SUN(0xFFD23F), SNOW(0xCFF4FF), WATER(0x4FC3FF), FLOWER(0xFF8FB8), GOLD(0xFFC43D), DIAMOND(0x6FF2F2),
		TEARS(0x9EC8FF), MUSIC(0xA8FF8A), BOOM(0xFFFFFF), GHOST(0x5FF2D6), HELL(0xFF3030), SKY(0xEAF6FF),
		BLOOD(0xC8102E), ELECTRIC(0x7FE8FF);

		final int color;

		Kind(int color) {
			this.color = color;
		}

		/** Does this one keep going around you for a few seconds (weather-like)? */
		boolean ambient() {
			return this == RAIN || this == SNOW || this == FLOWER || this == NIGHT || this == TEARS;
		}
	}

	private static final Map<String, Kind> WORDS = new HashMap<>();

	private static void put(Kind k, String... words) {
		for (String w : words) WORDS.put(w, k);
	}

	static {
		put(Kind.FIRE, "fire", "fires", "flame", "flames", "burn", "burning", "burns", "burned", "burnt", "blaze", "ablaze", "heat", "hot", "smoke");
		put(Kind.RAIN, "rain", "raining", "rains", "rainy", "pouring");
		put(Kind.STORM, "storm", "storms", "thunder", "lightning", "hurricane", "tornado");
		put(Kind.HEART, "heart", "hearts", "love", "loving", "loved", "lover", "lovers", "kiss", "kisses", "kissed", "babe", "baby", "darling", "valentine");
		put(Kind.STAR, "star", "stars", "starlight", "shine", "shining", "shines", "sparkle", "sparkles", "glow", "glowing", "glitter");
		put(Kind.NIGHT, "night", "nights", "midnight", "tonight", "moon", "moonlight", "dark", "darkness");
		put(Kind.SUN, "sun", "sunshine", "sunlight", "sunset", "sunrise", "summer", "golden", "daylight", "bright");
		put(Kind.SNOW, "snow", "snowing", "cold", "ice", "icy", "frozen", "freeze", "freezing", "winter", "frost");
		put(Kind.WATER, "ocean", "oceans", "sea", "seas", "water", "waters", "wave", "waves", "river", "tide", "drown", "drowning", "swim", "underwater");
		put(Kind.FLOWER, "flower", "flowers", "rose", "roses", "bloom", "blooming", "garden", "petals", "cherry", "daisy", "lily");
		put(Kind.GOLD, "gold", "money", "rich", "cash", "million", "millions", "billion", "paid", "dollar", "dollars", "crown", "king", "queen");
		put(Kind.DIAMOND, "diamond", "diamonds", "ice-cold", "crystal", "jewels");
		put(Kind.TEARS, "cry", "crying", "cried", "tears", "tear", "weep", "sad");
		put(Kind.MUSIC, "dance", "dancing", "music", "song", "songs", "sing", "singing", "melody", "party", "beat", "rhythm");
		put(Kind.BOOM, "boom", "bang", "explode", "explosion", "bomb", "crash", "shatter", "shattered", "break", "broke", "broken", "smash");
		put(Kind.GHOST, "ghost", "ghosts", "soul", "souls", "spirit", "haunt", "haunted", "haunting", "dead", "die", "dying", "death", "grave");
		put(Kind.HELL, "hell", "devil", "demon", "demons", "sin", "sins", "villain", "evil", "wicked");
		put(Kind.SKY, "fly", "flying", "sky", "skies", "wings", "heaven", "angel", "angels", "float", "floating", "clouds", "cloud");
		put(Kind.BLOOD, "blood", "bleed", "bleeding", "red", "wound", "scars");
		put(Kind.ELECTRIC, "electric", "energy", "power", "spark", "sparks", "volt", "wired", "neon", "voltage");
	}

	/** The keyword kind of a sung word, or null. */
	static Kind find(String word) {
		String w = word.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}'-]", "").replaceAll("'s$", "");
		return w.isEmpty() ? null : WORDS.get(w);
	}

	/** The burst when the word lands. */
	static void burst(Minecraft mc, Kind k, Vec3 at, Vector3f n, float size, Random rng) {
		var lvl = mc.level;
		if (lvl == null) return;
		int count = 14;
		for (int i = 0; i < count; i++) {
			double ox = (rng.nextDouble() - 0.5) * size, oy = (rng.nextDouble() - 0.5) * size * 0.4, oz = (rng.nextDouble() - 0.5) * size;
			double x = at.x + ox + n.x * 0.2, y = at.y + oy + n.y * 0.2, z = at.z + oz + n.z * 0.2;
			double vx = n.x * 0.06 + (rng.nextDouble() - 0.5) * 0.08, vy = 0.03 + n.y * 0.06 + rng.nextDouble() * 0.06,
					vz = n.z * 0.06 + (rng.nextDouble() - 0.5) * 0.08;
			ParticleOptions p = switch (k) {
				case FIRE -> i % 3 == 0 ? ParticleTypes.LAVA : ParticleTypes.FLAME;
				case RAIN, TEARS -> ParticleTypes.DRIPPING_WATER;
				case STORM, ELECTRIC -> ParticleTypes.ELECTRIC_SPARK;
				case HEART -> i % 3 == 0 ? ParticleTypes.HEART : ParticleTypes.CHERRY_LEAVES;
				case STAR -> i % 2 == 0 ? ParticleTypes.FIREWORK : ParticleTypes.END_ROD;
				case NIGHT -> ParticleTypes.END_ROD;
				case SUN -> i % 2 == 0 ? ParticleTypes.WAX_ON : ParticleTypes.END_ROD;
				case SNOW -> ParticleTypes.SNOWFLAKE;
				case WATER -> i % 2 == 0 ? ParticleTypes.BUBBLE_POP : ParticleTypes.SPLASH;
				case FLOWER -> ParticleTypes.CHERRY_LEAVES;
				case GOLD -> new ItemParticleOption(ParticleTypes.ITEM, i % 2 == 0 ? Items.GOLD_INGOT : Items.GOLD_NUGGET);
				case DIAMOND -> new ItemParticleOption(ParticleTypes.ITEM, i % 3 == 0 ? Items.EMERALD : Items.DIAMOND);
				case MUSIC -> ParticleTypes.NOTE;
				case BOOM -> i < 2 ? ParticleTypes.EXPLOSION : ParticleTypes.CLOUD;
				case GHOST -> i % 2 == 0 ? ParticleTypes.SOUL : ParticleTypes.SCULK_SOUL;
				case HELL -> i % 2 == 0 ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME;
				case SKY -> i % 2 == 0 ? ParticleTypes.CLOUD : ParticleTypes.END_ROD;
				case BLOOD -> new net.minecraft.core.particles.DustParticleOptions(0xC8102E, 1.4f);
			};
			if (k == Kind.GOLD || k == Kind.DIAMOND) {
				// thrown out of the word like loot
				vx = n.x * 0.18 + (rng.nextDouble() - 0.5) * 0.25;
				vy = 0.25 + rng.nextDouble() * 0.2;
				vz = n.z * 0.18 + (rng.nextDouble() - 0.5) * 0.25;
			}
			if (k == Kind.MUSIC) vx = rng.nextDouble(); // note colour
			lvl.addParticle(p, x, y, z, vx, vy, vz);
		}
		if (k == Kind.BOOM) lvl.playLocalSound(at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(),
				net.minecraft.sounds.SoundSource.AMBIENT, 0.3f, 1.3f, false);
		if (k == Kind.STORM) lvl.playLocalSound(at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.LIGHTNING_BOLT_THUNDER,
				net.minecraft.sounds.SoundSource.AMBIENT, 0.35f, 1f, false);
	}

	/** A bit of weather around you while an ambient keyword lasts (called every frame). */
	static void ambient(Minecraft mc, Kind k, float dt, Random rng) {
		var lvl = mc.level;
		var pl = mc.player;
		if (lvl == null || pl == null) return;
		float rate = switch (k) {
			case RAIN -> 160f;
			case SNOW -> 70f;
			case FLOWER -> 35f;
			case TEARS -> 40f;
			case NIGHT -> 25f;
			default -> 0f;
		};
		int n = (int) (rate * dt + rng.nextFloat());
		for (int i = 0; i < n; i++) {
			double x = pl.getX() + (rng.nextDouble() - 0.5) * 18, z = pl.getZ() + (rng.nextDouble() - 0.5) * 18;
			double y = pl.getEyeY() + 4 + rng.nextDouble() * 5;
			switch (k) {
				case RAIN, TEARS -> lvl.addParticle(ParticleTypes.FALLING_WATER, x, y, z, 0, 0, 0);
				case SNOW -> lvl.addParticle(ParticleTypes.SNOWFLAKE, x, y, z, (rng.nextDouble() - 0.5) * 0.04, -0.05, (rng.nextDouble() - 0.5) * 0.04);
				case FLOWER -> lvl.addParticle(ParticleTypes.CHERRY_LEAVES, x, y, z, 0, 0, 0);
				case NIGHT -> lvl.addParticle(ParticleTypes.END_ROD, x, pl.getEyeY() + rng.nextDouble() * 6, z, 0, 0.01, 0);
				default -> { }
			}
		}
	}
}
