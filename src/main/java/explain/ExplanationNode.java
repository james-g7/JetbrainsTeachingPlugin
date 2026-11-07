package explain;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * One explanation: the code it highlights and the text shown above it.
 *
 * <p>The text may contain links: {@code [label](#otherId)} shows another node (generated on the
 * fly by the {@link ExplanationProvider} if it isn't stored yet), and {@code [label](12-15)}
 * just highlights a range without an explanation.
 *
 * @param id    unique within the {@link ExplanationStore}
 * @param file  project-relative (or absolute) path, or null to use the file of the node that linked here
 * @param range see {@link CodeRange#parse}; a {@code path@} prefix overrides {@code file}
 * @param text  the explanation; may be empty
 */
public record ExplanationNode(@NotNull String id, @Nullable String file, @NotNull String range, @NotNull String text) {
  public ExplanationNode {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(range, "range");
    if (text == null) text = "";
    if (CodeRange.parse(range) == null) {
      throw new IllegalArgumentException("Node '" + id + "': invalid range '" + range
          + "' (expected e.g. 12, 12-15 or 12:5-15:20)");
    }
  }

  public static ExplanationNode of(String id, String range, String text) {
    return new ExplanationNode(id, null, range, text);
  }

  public CodeRange codeRange() {
    return Objects.requireNonNull(CodeRange.parse(range));
  }
}
