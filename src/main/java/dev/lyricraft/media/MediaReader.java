package dev.lyricraft.media;

import java.util.function.Consumer;

/** Reads the currently playing track from the operating system. Runs on its own thread. */
public interface MediaReader {
	/** Blocks, calling {@code sink} with each new reading (or {@code null} when nothing plays), until interrupted. */
	void run(Consumer<MediaSnapshot> sink) throws Exception;

	static MediaReader forThisOs() {
		String os = System.getProperty("os.name", "").toLowerCase();
		if (os.contains("win")) return new WindowsMediaReader();
		if (os.contains("mac")) return new MacMediaReader();
		return new LinuxMediaReader();
	}
}
