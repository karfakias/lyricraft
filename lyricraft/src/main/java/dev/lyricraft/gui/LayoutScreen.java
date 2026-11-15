package dev.lyricraft.gui;

import dev.lyricraft.Config;
import dev.lyricraft.LyricraftClient;
import dev.lyricraft.hud.Draw;
import dev.lyricraft.hud.LyricsAnchor;
import dev.lyricraft.hud.LyricsHud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Full-screen layout editor: drag the Now Playing card and the lyrics anywhere.
 * Snaps to edges and centre, scroll to resize, right-click to reset, arrow keys to fine-tune.
 */
public final class LayoutScreen extends Screen implements LyricraftUi {

	private final LyricraftClient mod;
	private final Screen parent;
	private final LayoutDrag drag;
	private double mouseX, mouseY;

	public LayoutScreen(LyricraftClient mod, Screen parent) {
		super(Component.literal("Edit layout"));
		this.mod = mod;
		this.parent = parent;
		this.drag = new LayoutDrag(mod.config);
	}

	private Config cfg() {
		return mod.config;
	}

	@Override
	protected void init() {
		int bw = Math.min(330, width - 8), x = width / 2 - bw / 2, y = height - 28;
		int third = (bw - 8) / 3;
		Config.CardStyle[] looks = Config.CardStyle.values();
		addRenderableWidget(new Widgets.Choice(x, y, third, "Look", () -> LyricraftScreen.cardName(cfg().cardStyle),
				() -> 0xFFFFFF, d -> {
					cfg().cardStyle = looks[Math.floorMod(cfg().cardStyle.ordinal() + d, looks.length)];
					cfg().save();
				}, "Card look. Right-click to go back."));
		Config.CardTheme[] themes = Config.CardTheme.values();
		addRenderableWidget(new Widgets.Choice(x + third + 4, y, third, "Theme", () -> LyricraftScreen.themeName(cfg().cardTheme),
				() -> 0xFFFFFF, d -> {
					cfg().cardTheme = themes[Math.floorMod(cfg().cardTheme.ordinal() + d, themes.length)];
					cfg().save();
				}, "Card colours. Right-click to go back."));
		addRenderableWidget(new Widgets.Action(x + 2 * third + 8, y, bw - 2 * third - 8, 20, "Done", true, this::onClose, null));
		addRenderableWidget(new Widgets.Action(x, y - 22, (bw - 4) / 2, 20, "Reset card", false,
				() -> drag.reset(LayoutDrag.Target.CARD), "Card back to the top-left corner"));
		addRenderableWidget(new Widgets.Action(x + (bw - 4) / 2 + 4, y - 22, bw - (bw - 4) / 2 - 4, 20, "Reset lyrics", false,
				() -> drag.reset(LayoutDrag.Target.LYRICS), "Lyrics back above the hotbar"));
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float partial) {
		if (minecraft.level == null) {
			super.extractBackground(g, mx, my, partial);
			LyricsHud.previewCard(g);
		}
		// faint centre guides
		g.fill(width / 2, 0, width / 2 + 1, height, Draw.argb(0xFFFFFF, 0.05f));
		g.fill(0, height / 2, width, height / 2 + 1, Draw.argb(0xFFFFFF, 0.05f));
		drag.draw(g, font, width, height, mx, my, true);

		String hint = "Drag the card or the lyrics  ·  Scroll to resize  ·  Right-click to reset  ·  Arrows to fine-tune";
		if (!LyricsAnchor.movable(cfg().style)) hint = LyricraftClient.styleName(cfg().style)
				+ " lives in the world, so only the card can move. Classic, Pop, Neon and Wave can be dragged.";
		else if (!cfg().enabled) hint = "Lyrics are switched off (J), so only the card can move.";
		if (!cfg().showNowPlaying) hint = "The Now Playing card is switched off in the Card tab.";
		float sc = Math.min(1f, (width - 24f) / Math.max(1, font.width(hint)));
		float hw = font.width(hint) * sc;
		int hy = height - 66;
		Widgets.panel(g, Math.round(width / 2f - hw / 2 - 8), hy, Math.round(width / 2f + hw / 2 + 8), hy + 15, Draw.argb(0x0A0A0E, 0.88f));
		Draw.text(g, font, hint, width / 2f - hw / 2, hy + 4 + (1 - sc) * 4, sc, 0xFFE0E0E8, false);
	}

	@Override
	public void mouseMoved(double x, double y) {
		mouseX = x;
		mouseY = y;
		super.mouseMoved(x, y);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) return true;
		return drag.press(e, width, height);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (drag.drag(e, width, height)) return true;
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		if (drag.release()) return true;
		return super.mouseReleased(e);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (drag.scroll(mx, my, sy, width, height)) return true;
		return super.mouseScrolled(mx, my, sx, sy);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		int dx = 0, dy = 0;
		switch (e.input()) {
			case 262 -> dx = 1;  // right
			case 263 -> dx = -1; // left
			case 264 -> dy = 1;  // down
			case 265 -> dy = -1; // up
			default -> {
				return super.keyPressed(e);
			}
		}
		drag.nudge(mouseX, mouseY, dx, dy, width, height);
		return true;
	}

	@Override
	public void onClose() {
		drag.release();
		cfg().save();
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
