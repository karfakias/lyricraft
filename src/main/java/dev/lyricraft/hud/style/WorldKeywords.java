package dev.lyricraft.hud.style;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Words that change the world when they're sung in the Edit style: "fire" sets the words alight,
 * "rain" makes it rain around you, "storm" brings a lightning bolt down on the word, "gold"
 * throws gold out of it, and so on. The word itself takes on that colour too.
 *
 * <p>Not just English: the main ones are here in Spanish, Portuguese, French, Italian, German,
 * Turkish, Greek, Russian, Arabic, Hindi, Korean, Japanese and Chinese too (leaving out ones that
 * are also everyday English, like French "or" or Spanish "para"). Accents don't
 * matter (νύχτα = νυχτα, corazón = corazon). Korean, Japanese and Chinese glue little words on
 * (별빛이 = starlight + "is"), so for those a keyword anywhere in the chunk counts.
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
	/** Korean / Japanese / Chinese keywords, found inside a chunk rather than as a whole word. */
	private static final List<Map.Entry<String, Kind>> INSIDE = new ArrayList<>();

	private static void put(Kind k, String... words) {
		for (String w : words) WORDS.put(norm(w), k);
	}

	private static void inside(Kind k, String... words) {
		for (String w : words) INSIDE.add(Map.entry(w, k));
	}

	/** Lowercase, and without accents for Latin, Greek and Cyrillic (never for scripts where the marks matter). */
	private static String norm(String w) {
		String l = w.toLowerCase(Locale.ROOT);
		if (WordArt.needsRealFont(l)) return l;
		return Normalizer.normalize(Normalizer.normalize(l, Normalizer.Form.NFD).replaceAll("\\p{M}", ""), Normalizer.Form.NFC);
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

		// Spanish
		put(Kind.FIRE, "fuego", "llamas", "quemar", "quema", "arde", "arder", "caliente");
		put(Kind.RAIN, "lluvia", "llueve", "llover");
		put(Kind.STORM, "tormenta", "trueno", "truenos", "rayo", "relámpago");
		put(Kind.HEART, "amor", "corazón", "beso", "besos", "besar", "bebé", "cariño", "enamorado", "enamorada");
		put(Kind.STAR, "estrella", "estrellas", "brillar", "brilla", "brillo");
		put(Kind.NIGHT, "noche", "noches", "luna", "medianoche", "oscuridad", "oscuro");
		put(Kind.SUN, "sol", "verano", "amanecer", "atardecer");
		put(Kind.SNOW, "nieve", "frío", "hielo", "invierno");
		put(Kind.WATER, "mar", "océano", "agua", "olas", "río", "playa");
		put(Kind.FLOWER, "flor", "flores", "rosa", "rosas");
		put(Kind.GOLD, "oro", "dinero", "rico", "millones", "rey", "reina");
		put(Kind.TEARS, "llorar", "lloro", "llora", "lágrimas", "triste");
		put(Kind.MUSIC, "bailar", "baila", "bailando", "música", "canción", "cantar", "fiesta", "perreo");
		put(Kind.GHOST, "alma", "fantasma", "muerte", "morir", "muero");
		put(Kind.HELL, "infierno", "diablo", "demonio", "pecado");
		put(Kind.SKY, "cielo", "volar", "vuela", "alas", "ángel", "nubes");
		put(Kind.BLOOD, "sangre", "rojo", "roja");
		// Portuguese
		put(Kind.FIRE, "fogo", "queimar");
		put(Kind.RAIN, "chuva", "chover");
		put(Kind.STORM, "tempestade", "trovão");
		put(Kind.HEART, "coração", "beijo", "beijos", "paixão", "saudade");
		put(Kind.STAR, "estrela", "estrelas", "brilhar", "brilha");
		put(Kind.NIGHT, "noite", "lua", "escuridão");
		put(Kind.SUN, "verão");
		put(Kind.SNOW, "neve", "frio", "gelo", "inverno");
		put(Kind.WATER, "oceano", "água", "ondas", "praia");
		put(Kind.GOLD, "ouro", "dinheiro");
		put(Kind.TEARS, "chorar", "choro", "lágrima");
		put(Kind.MUSIC, "dançar", "dança", "canção", "cantar");
		put(Kind.HELL, "inferno");
		put(Kind.SKY, "céu", "voar", "asas", "anjo");
		put(Kind.BLOOD, "sangue", "vermelho");
		// French
		put(Kind.FIRE, "feu", "flamme", "flammes", "brûler", "brûle");
		put(Kind.RAIN, "pluie", "pleut");
		put(Kind.STORM, "orage", "tempête", "tonnerre", "éclair");
		put(Kind.HEART, "amour", "cœur", "coeur", "bisou", "baiser", "chérie", "chéri");
		put(Kind.STAR, "étoile", "étoiles", "briller", "brille");
		put(Kind.NIGHT, "nuit", "lune", "minuit", "nuits");
		put(Kind.SUN, "soleil", "été");
		put(Kind.SNOW, "neige", "froid", "glace", "hiver");
		put(Kind.WATER, "mer", "océan", "eau", "vagues");
		put(Kind.FLOWER, "fleur", "fleurs");
		put(Kind.GOLD, "argent", "riche", "roi", "reine");
		put(Kind.TEARS, "pleurer", "pleure", "larmes");
		put(Kind.MUSIC, "danser", "danse", "musique", "chanson", "chanter");
		put(Kind.GHOST, "âme", "fantôme", "mort", "mourir");
		put(Kind.HELL, "enfer", "diable", "démon");
		put(Kind.SKY, "ciel", "voler", "ailes", "ange", "nuages");
		put(Kind.BLOOD, "rouge");
		// Italian
		put(Kind.FIRE, "fuoco", "fiamme", "bruciare");
		put(Kind.RAIN, "pioggia", "piove");
		put(Kind.STORM, "tempesta", "tuono", "fulmine");
		put(Kind.HEART, "amore", "cuore", "bacio", "baci");
		put(Kind.STAR, "stella", "stelle", "brillare");
		put(Kind.NIGHT, "notte", "notti", "buio");
		put(Kind.SUN, "estate");
		put(Kind.SNOW, "freddo", "ghiaccio", "inverno");
		put(Kind.WATER, "acqua", "onde", "fiume");
		put(Kind.FLOWER, "fiore", "fiori");
		put(Kind.GOLD, "soldi", "ricco");
		put(Kind.TEARS, "piangere", "piango", "lacrime");
		put(Kind.MUSIC, "ballare", "balla", "musica", "canzone", "cantare");
		put(Kind.GHOST, "anima", "fantasma", "morte");
		put(Kind.SKY, "volare", "angelo", "nuvole");
		put(Kind.BLOOD, "rosso");
		// German
		put(Kind.FIRE, "feuer", "flammen", "brennen", "brennt");
		put(Kind.RAIN, "regen", "regnet");
		put(Kind.STORM, "sturm", "donner", "blitz", "gewitter");
		put(Kind.HEART, "liebe", "herz", "kuss", "küss", "schatz");
		put(Kind.STAR, "stern", "sterne", "leuchten", "glänzen");
		put(Kind.NIGHT, "nacht", "nächte", "mond", "mitternacht", "dunkel");
		put(Kind.SUN, "sonne", "sommer");
		put(Kind.SNOW, "schnee", "kalt", "eis");
		put(Kind.WATER, "meer", "wasser", "wellen", "fluss");
		put(Kind.FLOWER, "blume", "blumen");
		put(Kind.GOLD, "geld", "reich", "könig", "königin");
		put(Kind.TEARS, "weinen", "tränen", "traurig");
		put(Kind.MUSIC, "tanzen", "tanz", "musik", "singen");
		put(Kind.GHOST, "seele", "geist", "tod", "sterben");
		put(Kind.HELL, "hölle", "teufel");
		put(Kind.SKY, "himmel", "fliegen", "flügel", "engel", "wolken");
		put(Kind.BLOOD, "blut");
		// Turkish
		put(Kind.FIRE, "ateş", "yanıyor");
		put(Kind.RAIN, "yağmur");
		put(Kind.STORM, "fırtına");
		put(Kind.HEART, "kalp", "kalbim", "sevgilim");
		put(Kind.STAR, "yıldız", "yıldızlar");
		put(Kind.NIGHT, "gece", "geceler");
		put(Kind.SUN, "güneş");
		put(Kind.SNOW, "kış");
		put(Kind.WATER, "deniz", "dalga");
		put(Kind.FLOWER, "çiçek", "gül");
		put(Kind.GOLD, "altın");
		put(Kind.TEARS, "gözyaşı", "ağla", "ağlıyorum");
		put(Kind.MUSIC, "şarkı");
		put(Kind.GHOST, "ruh", "ölüm");
		put(Kind.HELL, "cehennem", "şeytan");
		put(Kind.SKY, "gökyüzü", "melek");
		put(Kind.BLOOD, "kan", "kırmızı");
		// Greek
		put(Kind.FIRE, "φωτιά", "φλόγα", "φλόγες", "καίγομαι", "καίει");
		put(Kind.RAIN, "βροχή");
		put(Kind.STORM, "καταιγίδα", "κεραυνός", "κεραυνοί", "αστραπή", "μπόρα");
		put(Kind.HEART, "αγάπη", "αγάπη μου", "καρδιά", "φιλί", "φιλιά", "μωρό", "έρωτας", "ερωτευμένος");
		put(Kind.STAR, "αστέρι", "αστέρια", "λάμπει", "λάμψη");
		put(Kind.NIGHT, "νύχτα", "νύχτες", "φεγγάρι", "σκοτάδι", "απόψε", "μεσάνυχτα");
		put(Kind.SUN, "ήλιος", "ήλιε", "καλοκαίρι");
		put(Kind.SNOW, "χιόνι", "κρύο", "πάγος", "χειμώνας");
		put(Kind.WATER, "θάλασσα", "νερό", "κύματα", "ωκεανός");
		put(Kind.FLOWER, "λουλούδι", "λουλούδια", "τριαντάφυλλο");
		put(Kind.GOLD, "χρυσό", "χρυσός", "λεφτά", "χρήμα", "βασιλιάς");
		put(Kind.TEARS, "δάκρυα", "δάκρυ", "κλαίω", "κλάμα");
		put(Kind.MUSIC, "χορός", "χορεύω", "χόρευε", "μουσική", "τραγούδι", "τραγουδάω");
		put(Kind.GHOST, "ψυχή", "φάντασμα", "θάνατος");
		put(Kind.HELL, "κόλαση", "διάβολος");
		put(Kind.SKY, "ουρανός", "ουρανό", "πετάω", "φτερά", "άγγελος");
		put(Kind.BLOOD, "αίμα", "κόκκινο");
		// Russian
		put(Kind.FIRE, "огонь", "пламя", "гореть", "горит");
		put(Kind.RAIN, "дождь", "дожди");
		put(Kind.STORM, "гроза", "гром", "молния", "буря");
		put(Kind.HEART, "любовь", "сердце", "поцелуй", "люблю", "малыш");
		put(Kind.STAR, "звезда", "звёзды", "звезды", "сиять");
		put(Kind.NIGHT, "ночь", "ночи", "луна", "тьма", "полночь");
		put(Kind.SUN, "солнце", "лето");
		put(Kind.SNOW, "снег", "холод", "лёд", "зима");
		put(Kind.WATER, "море", "океан", "вода", "волны");
		put(Kind.FLOWER, "цветы", "роза", "розы");
		put(Kind.GOLD, "золото", "деньги");
		put(Kind.TEARS, "слёзы", "слезы", "плакать", "плачу");
		put(Kind.MUSIC, "танцы", "танцуй", "музыка", "песня");
		put(Kind.GHOST, "душа", "призрак", "смерть");
		put(Kind.HELL, "ад", "дьявол");
		put(Kind.SKY, "небо", "лететь", "крылья", "ангел");
		put(Kind.BLOOD, "кровь", "красный");
		// Arabic (also found with the "al-" in front)
		put(Kind.FIRE, "نار");
		put(Kind.RAIN, "مطر");
		put(Kind.STORM, "عاصفة", "رعد", "برق");
		put(Kind.HEART, "حب", "حبيبي", "حبيبتي", "قلب", "قلبي", "بوسة");
		put(Kind.STAR, "نجمة", "نجوم", "نجم");
		put(Kind.NIGHT, "ليل", "ليلة", "قمر", "الليلة");
		put(Kind.SUN, "شمس", "صيف");
		put(Kind.SNOW, "ثلج", "برد", "شتاء");
		put(Kind.WATER, "بحر", "ماء", "موج");
		put(Kind.FLOWER, "ورد", "وردة", "زهرة");
		put(Kind.GOLD, "ذهب", "فلوس");
		put(Kind.TEARS, "دموع", "دمعة", "بكاء");
		put(Kind.MUSIC, "رقص", "ارقص", "أغنية", "غناء");
		put(Kind.GHOST, "روح", "موت");
		put(Kind.HELL, "جحيم", "شيطان");
		put(Kind.SKY, "سماء", "طير", "ملاك");
		put(Kind.BLOOD, "دم", "أحمر");
		// Hindi
		put(Kind.FIRE, "आग");
		put(Kind.RAIN, "बारिश", "बरसात");
		put(Kind.STORM, "तूफ़ान", "तूफान");
		put(Kind.HEART, "प्यार", "दिल", "इश्क़", "इश्क", "मोहब्बत");
		put(Kind.STAR, "तारे", "तारा", "सितारे");
		put(Kind.NIGHT, "रात", "चाँद", "चांद");
		put(Kind.SUN, "सूरज", "धूप");
		put(Kind.SNOW, "बर्फ़", "बर्फ");
		put(Kind.WATER, "समंदर", "समुंदर", "पानी", "दरिया");
		put(Kind.FLOWER, "फूल", "गुलाब");
		put(Kind.GOLD, "सोना", "पैसा");
		put(Kind.TEARS, "आँसू", "आंसू", "रोना");
		put(Kind.MUSIC, "नाच", "नाचो", "गाना");
		put(Kind.GHOST, "रूह", "मौत");
		put(Kind.SKY, "आसमान", "आसमां", "फ़रिश्ता");
		put(Kind.BLOOD, "खून", "लाल");
		// Korean, Japanese, Chinese: inside the chunk
		inside(Kind.FIRE, "불꽃", "불타", "炎", "燃", "火焰");
		inside(Kind.RAIN, "빗속", "비가", "빗물", "雨");
		inside(Kind.STORM, "폭풍", "천둥", "번개", "嵐", "雷", "暴风");
		inside(Kind.HEART, "사랑", "키스", "愛", "恋", "キス", "爱", "吻");
		inside(Kind.STAR, "별빛", "별이", "별을", "星");
		inside(Kind.NIGHT, "밤하늘", "오늘 밤", "달빛", "夜", "月光", "月亮", "今夜");
		inside(Kind.SUN, "햇살", "태양", "여름", "太陽", "太阳", "阳光", "夏");
		inside(Kind.SNOW, "겨울", "눈송이", "雪", "冬");
		inside(Kind.WATER, "바다", "파도", "海", "波", "浪");
		inside(Kind.FLOWER, "꽃", "장미", "花", "桜", "玫瑰");
		inside(Kind.GOLD, "황금", "金色", "黄金", "钱");
		inside(Kind.TEARS, "눈물", "涙", "泣", "眼泪", "哭");
		inside(Kind.MUSIC, "춤", "노래", "ダンス", "踊", "跳舞", "音乐", "音楽");
		inside(Kind.GHOST, "영혼", "유령", "魂", "幽霊", "鬼");
		inside(Kind.HELL, "지옥", "악마", "地獄", "地狱", "悪魔", "魔鬼");
		inside(Kind.SKY, "하늘", "날개", "천사", "空", "翼", "天使", "天空", "飞");
		inside(Kind.BLOOD, "血");
	}

	/** The keyword kind of a sung word, or null. */
	static Kind find(String word) {
		String w = norm(word).replaceAll("[^\\p{L}\\p{M}'-]", "").replaceAll("'s$", "");
		if (w.isEmpty()) return null;
		Kind k = WORDS.get(w);
		if (k == null && w.startsWith("ال") && w.length() > 3) k = WORDS.get(w.substring(2)); // Arabic "the"
		if (k == null && unspaced(w)) {
			for (var e : INSIDE) if (w.contains(e.getKey())) return e.getValue();
		}
		return k;
	}

	private static boolean unspaced(String w) {
		return w.codePoints().anyMatch(cp -> switch (Character.UnicodeScript.of(cp)) {
			case HANGUL, HAN, HIRAGANA, KATAKANA -> true;
			default -> false;
		});
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
