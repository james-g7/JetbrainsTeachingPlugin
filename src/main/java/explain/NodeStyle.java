package explain;

import org.jetbrains.annotations.Nullable;

/**
 * Per-node appearance overrides; any field may be null to use the default. Box colours default to
 * tints of the highlight colour.
 */
public record NodeStyle(
    @Nullable ColorSpec highlight,
    @Nullable ColorSpec background,
    @Nullable ColorSpec border,
    @Nullable ColorSpec text,
    @Nullable HighlightMode highlightMode
) {
  public static final NodeStyle DEFAULT = new NodeStyle(null, null, null, null, null);
}
