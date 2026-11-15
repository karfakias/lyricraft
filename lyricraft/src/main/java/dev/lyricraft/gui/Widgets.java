package dev.lyricraft.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lyricraft.hud.Draw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Flat, modern widgets for the Lyricraft menu (all custom drawn, no vanilla button textures). */
final class Widgets {
	private Widgets() {}

	/** Accent colour of the menu (follows the lyric colour / album cover). */
	static IntSupplier accent = () -> 0x1ED760;

	static Font font() {
		return Minecraft.getInstance().font;
	}

	static void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
		g.fill(x0 + 1, y0, x1 - 1, y0 + 1, color);
		g.fill(x0, y0 + 1, x1, y1 - 1, color);
		g.fill(x0 + 1, y1 - 1, x1 - 1, y1, color);
	}

	/** Base: rounded dark tile that lights up on hover. */
	abstract static class Base extends AbstractWidget {
		protected float hover;

		Base(int x, int y, int w, int h, String label, String help) {
			super(x, y, w, h, Component.literal(label));
			if (help != null && !help.isEmpty()) setTooltip(Tooltip.create(Component.literal(help)));
		}

		protected void background(GuiGraphicsExtractor g, boolean selected) {
			hover = Draw.approach(hover, isHoveredOrFocused() && active ? 1f : 0f, 0.15f);
			int acc = accent.getAsInt();
			int x0 = getX(), y0 = getY(), x1 = getRight(), y1 = getBottom();
			int base = selected ? Draw.mix(0x17171D, acc, 0.22f) : Draw.mix(0x17171D, 0x2A2A34, hover);
			panel(g, x0, y0, x1, y1, Draw.argb(base, active ? 0.92f : 0.5f));
			if (selected || hover > 0.01f) {
				int edge = Draw.argb(acc, selected ? 0.9f : 0.55f * hover);
				g.fill(x0 + 1, y0, x1 - 1, y0 + 1, edge);
				g.fill(x0 + 1, y1 - 1, x1 - 1, y1, edge);
				g.fill(x0, y0 + 1, x0 + 1, y1 - 1, edge);
				g.fill(x1 - 1, y0 + 1, x1, y1 - 1, edge);
			}
		}

		protected int textY() {
			return getY() + (getHeight() - 8) / 2;
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput out) {
			defaultButtonNarrationText(out);
		}
	}

	/** Label on the left, animated on/off switch on the right. */
	static final class Toggle extends Base {
		private final BooleanSupplier get;
		private final Consumer<Boolean> set;
		private float knob;

		Toggle(int x, int y, int w, String label, BooleanSupplier get, Consumer<Boolean> set, String help) {
			super(x, y, w, 20, label, help);
			this.get = get;
			this.set = set;
			this.knob = get.getAsBoolean() ? 1f : 0f;
		}

		@Override
		public void onClick(MouseButtonEvent e, boolean doubleClick) {
			set.accept(!get.getAsBoolean());
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
			background(g, false);
			boolean on = get.getAsBoolean();
			knob = Draw.approach(knob, on ? 1f : 0f, 0.2f);
			Font f = font();
			int maxText = getWidth() - 34;
			g.text(f, Draw.ellipsize(f, getMessage().getString(), maxText), getX() + 7, textY(), on ? 0xFFFFFFFF : 0xFFB0B0BA, false);
			int sw = 20, sh = 10, sx = getRight() - sw - 6, sy = getY() + (getHeight() - sh) / 2;
			int track = Draw.mix(0x3A3A44, accent.getAsInt(), knob);
			panel(g, sx, sy, sx + sw, sy + sh, Draw.argb(track, 1f));
			int kx = Math.round(sx + 1 + knob * (sw - sh));
			panel(g, kx, sy + 1, kx + sh - 2, sy + sh - 1, 0xFFFFFFFF);
		}
	}

	/** Label on the left, "‹ value ›" on the right. Left click = next, right click = previous. */
	static final class Choice extends Base {
		private final Supplier<String> value;
		private final IntSupplier valueColor;
		private final Consumer<Integer> step;

		Choice(int x, int y, int w, String label, Supplier<String> value, IntSupplier valueColor, Consumer<Integer> step, String help) {
			super(x, y, w, 20, label, help);
			this.value = value;
			this.valueColor = valueColor;
			this.step = step;
		}

		@Override
		protected boolean isValidClickButton(MouseButtonInfo info) {
			return info.button() == InputConstants.MOUSE_BUTTON_LEFT || info.button() == InputConstants.MOUSE_BUTTON_RIGHT;
		}

		@Override
		public void onClick(MouseButtonEvent e, boolean doubleClick) {
			step.accept(e.button() == InputConstants.MOUSE_BUTTON_RIGHT ? -1 : 1);
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
			background(g, false);
			Font f = font();
			String v = value.get();
			int vw = f.width(v);
			int right = getRight() - 7;
			int arrows = Draw.argb(accent.getAsInt(), 0.5f + 0.5f * hover);
			g.text(f, "›", right - f.width("›"), textY(), arrows, false);
			int vx = right - f.width("›") - 4 - vw;
			g.text(f, v, vx, textY(), 0xFF000000 | valueColor.getAsInt(), false);
			g.text(f, "‹", vx - 4 - f.width("‹"), textY(), arrows, false);
			int maxLabel = vx - 10 - f.width("‹") - getX() - 7;
			g.text(f, Draw.ellipsize(f, getMessage().getString(), maxLabel), getX() + 7, textY(), 0xFFB0B0BA, false);
		}
	}

	/** Plain button. {@code primary} = filled with the accent colour. */
	static final class Action extends Base {
		private final Runnable run;
		private final boolean primary;

		Action(int x, int y, int w, int h, String label, boolean primary, Runnable run, String help) {
			super(x, y, w, h, label, help);
			this.run = run;
			this.primary = primary;
		}

		@Override
		public void onClick(MouseButtonEvent e, boolean doubleClick) {
			run.run();
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
			if (primary) {
				hover = Draw.approach(hover, isHoveredOrFocused() ? 1f : 0f, 0.15f);
				int acc = accent.getAsInt();
				panel(g, getX(), getY(), getRight(), getBottom(), Draw.argb(Draw.mix(acc, 0xFFFFFF, hover * 0.2f), 1f));
			} else background(g, false);
			Font f = font();
			String s = getMessage().getString();
			int col = primary ? 0xFF0A0A0E : 0xFFFFFFFF;
			g.text(f, s, getX() + (getWidth() - f.width(s)) / 2, textY(), col, false);
		}
	}

	/** A selectable tile (styles, overlays, card looks). */
	static final class Chip extends Base {
		private final BooleanSupplier selected;
		private final Runnable pick;
		final String info;

		Chip(int x, int y, int w, int h, String label, BooleanSupplier selected, Runnable pick, String info) {
			super(x, y, w, h, label, null);
			this.selected = selected;
			this.pick = pick;
			this.info = info;
		}

		@Override
		public void onClick(MouseButtonEvent e, boolean doubleClick) {
			pick.run();
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
			boolean on = selected.getAsBoolean();
			background(g, on);
			Font f = font();
			String s = Draw.ellipsize(f, getMessage().getString(), getWidth() - (on ? 20 : 10));
			int col = on ? 0xFFFFFFFF : Draw.argb(Draw.mix(0xB0B0BA, 0xFFFFFF, hover), 1f);
			g.text(f, s, getX() + 7, textY(), col, false);
			if (on) g.text(f, "✔", getRight() - 12, textY(), 0xFF000000 | accent.getAsInt(), false);
		}
	}

	/** Vertical tab in the left rail: icon + name. */
	static final class Tab extends Base {
		private final String icon;
		private final BooleanSupplier selected;
		private final Runnable pick;

		Tab(int x, int y, int w, int h, String icon, String label, BooleanSupplier selected, Runnable pick) {
			super(x, y, w, h, label, null);
			this.icon = icon;
			this.selected = selected;
			this.pick = pick;
		}

		@Override
		public void onClick(MouseButtonEvent e, boolean doubleClick) {
			pick.run();
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
			boolean on = selected.getAsBoolean();
			hover = Draw.approach(hover, isHoveredOrFocused() ? 1f : 0f, 0.15f);
			int acc = accent.getAsInt();
			if (on) {
				g.fill(getX(), getY(), getRight(), getBottom(), Draw.argb(acc, 0.16f));
				g.fill(getX(), getY(), getX() + 2, getBottom(), 0xFF000000 | acc);
			} else if (hover > 0.01f) {
				g.fill(getX(), getY(), getRight(), getBottom(), Draw.argb(0xFFFFFF, 0.06f * hover));
			}
			Font f = font();
			int col = on ? 0xFFFFFFFF : Draw.argb(Draw.mix(0x8A8A96, 0xFFFFFF, hover), 1f);
			int iconCol = on ? 0xFF000000 | acc : col;
			g.text(f, icon, getX() + 8, textY(), iconCol, false);
			g.text(f, getMessage().getString(), getX() + 21, textY(), col, false);
		}
	}

	/** Flat slider: label left, value right, thin track with a round knob. */
	static final class Slider extends AbstractSliderButton {
		private final String label;
		private final DoubleFunction<String> valueText;
		private final Consumer<Double> apply;
		private float hover;

		Slider(int x, int y, int w, String label, double value, DoubleFunction<String> valueText, Consumer<Double> apply, String help) {
			super(x, y, w, 20, Component.literal(label), Math.clamp(value, 0, 1));
			this.label = label;
			this.valueText = valueText;
			this.apply = apply;
			if (help != null) setTooltip(Tooltip.create(Component.literal(help)));
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(label + ": " + valueText.apply(value)));
		}

		@Override
		protected void applyValue() {
			apply.accept(value);
		}

		@Override
		public void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
			hover = Draw.approach(hover, isHoveredOrFocused() ? 1f : 0f, 0.15f);
			int acc = accent.getAsInt();
			int x0 = getX(), y0 = getY(), x1 = getRight(), y1 = getBottom();
			panel(g, x0, y0, x1, y1, Draw.argb(Draw.mix(0x17171D, 0x2A2A34, hover), 0.92f));
			Font f = font();
			String v = valueText.apply(value);
			g.text(f, label, x0 + 7, y0 + 3, 0xFFB0B0BA, false);
			g.text(f, v, x1 - 7 - f.width(v), y0 + 3, 0xFF000000 | acc, false);
			int tx0 = x0 + 7, tx1 = x1 - 7, ty = y1 - 6;
			g.fill(tx0, ty, tx1, ty + 2, 0xFF3A3A44);
			int kx = tx0 + (int) Math.round((tx1 - tx0) * value);
			g.fill(tx0, ty, kx, ty + 2, 0xFF000000 | acc);
			panel(g, kx - 3, ty - 2, kx + 3, ty + 4, 0xFFFFFFFF);
		}
	}
}
