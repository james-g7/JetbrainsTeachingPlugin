package explain;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** How a highlighted block is painted. */
public enum HighlightMode {
  /** A filled rectangle behind the code. */
  FILL,
  /** A rounded outline around the code. */
  OUTLINE,
  /** A bar down the left of the code. */
  BAR;

  public static @Nullable HighlightMode parse(@Nullable String text) {
    if (text == null || text.isBlank()) return null;
    try {
      return valueOf(text.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unknown highlightStyle '" + text + "' (use fill, outline or bar)");
    }
  }
}
