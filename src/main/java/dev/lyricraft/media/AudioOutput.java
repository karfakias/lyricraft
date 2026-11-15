package dev.lyricraft.media;

/**
 * Where your sound is coming out (Windows only for now). Bluetooth headphones play audio
 * ~150-250 ms after it's sent, so they get their own learned timing.
 */
public final class AudioOutput {
	private AudioOutput() {}

	private static volatile boolean bluetooth;
	private static volatile String name = "";

	public static boolean bluetooth() {
		return bluetooth;
	}

	/** Friendly device name, e.g. "Headphones (WH-1000XM4)"; empty if unknown. */
	public static String name() {
		return name;
	}

	static void update(String deviceName, boolean isBluetooth) {
		name = deviceName == null ? "" : deviceName;
		bluetooth = isBluetooth;
	}
}
