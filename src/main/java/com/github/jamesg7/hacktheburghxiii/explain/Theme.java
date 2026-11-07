package com.github.jamesg7.hacktheburghxiii.explain;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Tree-wide appearance.
 *
 * @param palette       highlight colours by depth (root first), cycling; empty for the built-in palette
 * @param maxWidth      maximum width of an explanation box's text, in pixels (before UI scaling)
 * @param fontSize      box font size in points, or null for the IDE's UI font size
 * @param highlightMode default highlight style, or null for {@link HighlightMode#FILL}
 */
public record Theme(List<ColorSpec> palette, int maxWidth, @Nullable Integer fontSize, @Nullable HighlightMode highlightMode) {
  private static final List<ColorSpec> BUILT_IN = List.of(
      ColorSpec.named("yellow"), ColorSpec.named("blue"), ColorSpec.named("green"),
      ColorSpec.named("pink"), ColorSpec.named("orange"));

  public static final Theme DEFAULT = new Theme(List.of(), 520, null, null);

  public Theme {
    palette = List.copyOf(palette);
    if (maxWidth < 100) throw new IllegalArgumentException("theme.maxWidth must be at least 100");
  }

  public ColorSpec colorForDepth(int depth) {
    List<ColorSpec> colors = palette.isEmpty() ? BUILT_IN : palette;
    return colors.get(depth % colors.size());
  }

  public HighlightMode mode() {
    return highlightMode == null ? HighlightMode.FILL : highlightMode;
  }
}
