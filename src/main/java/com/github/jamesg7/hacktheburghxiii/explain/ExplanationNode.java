package com.github.jamesg7.hacktheburghxiii.explain;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One explanation: the code it highlights and the box shown above it. See docs/EXPLANATIONS.md.
 *
 * @param id     unique within the {@link ExplanationStore}
 * @param file   project-relative (or absolute) path, or null to use the file of the node that linked here
 * @param range  see {@link CodeRange#parse}; a {@code path@} prefix overrides {@code file}
 * @param text   Markdown. Links: {@code [label](#id)} shows another node, {@code [label](12-15)}
 *               highlights a range, {@code [label](https://...)} opens a browser
 * @param title  bold heading for the box, or null
 * @param kind   sets the icon and default colour
 * @param style  colour and highlight-style overrides
 * @param also   extra ranges in the same file to outline alongside the main one (e.g. other uses of a variable)
 * @param hints  Markdown hints the learner can reveal one at a time
 * @param answer Markdown answer the learner can reveal, e.g. to a question asked in {@code text}
 * @param next   id of the next node in a guided tour, shown with a "Next" button; "Back" appears automatically
 */
public record ExplanationNode(
    @NotNull String id,
    @Nullable String file,
    @NotNull String range,
    // These may be passed as null (e.g. missing from JSON) and are replaced with defaults below, so
    // they must not be @NotNull: the build instruments @NotNull parameters to throw before this
    // constructor body runs. The accessors never return null.
    String text,
    @Nullable String title,
    Kind kind,
    NodeStyle style,
    List<String> also,
    List<String> hints,
    @Nullable String answer,
    @Nullable String next
) {
  public ExplanationNode {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(range, "range");
    if (text == null) text = "";
    if (kind == null) kind = Kind.NOTE;
    if (style == null) style = NodeStyle.DEFAULT;
    also = also == null ? List.of() : List.copyOf(also);
    hints = hints == null ? List.of() : List.copyOf(hints);
    if (title != null && title.isBlank()) title = null;
    if (answer != null && answer.isBlank()) answer = null;
    if (next != null && next.isBlank()) next = null;

    if (CodeRange.parse(range) == null) throw new IllegalArgumentException(invalidRange(id, "range", range));
    for (String r : also) {
      if (CodeRange.parse(r) == null) throw new IllegalArgumentException(invalidRange(id, "also", r));
    }
  }

  private static String invalidRange(String id, String field, String value) {
    return "Node '" + id + "': invalid " + field + " '" + value + "' (expected e.g. 12, 12-15 or 12:5-15:20)";
  }

  /** A node with just a range and text. */
  public static ExplanationNode of(String id, String range, String text) {
    return builder(id, range).text(text).build();
  }

  public static Builder builder(String id, String range) {
    return new Builder(id, range);
  }

  /**
   * Parses a single node from JSON, in the same format as an entry of a tree's {@code "nodes"}
   * map, e.g. {@code {"range": "11", "text": "Prints **i**."}}. Handy for providers that get JSON
   * back from a model.
   */
  public static ExplanationNode fromJson(String id, String json) {
    return ExplanationJson.parseNode(id, json);
  }

  public CodeRange codeRange() {
    return Objects.requireNonNull(CodeRange.parse(range));
  }

  /** Whether there is anything to show in a box. */
  public boolean hasBoxContent() {
    return !text.isBlank() || title != null || !hints.isEmpty() || answer != null || next != null;
  }

  /** A copy with a different id. */
  public ExplanationNode withId(String newId) {
    return new ExplanationNode(newId, file, range, text, title, kind, style, also, hints, answer, next);
  }

  /** A copy with a file, if it doesn't have one. */
  public ExplanationNode withDefaultFile(@Nullable String defaultFile) {
    if (file != null || defaultFile == null) return this;
    return new ExplanationNode(id, defaultFile, range, text, title, kind, style, also, hints, answer, next);
  }

  public static final class Builder {
    private final String id;
    private final String range;
    private String file;
    private String text = "";
    private String title;
    private Kind kind = Kind.NOTE;
    private NodeStyle style = NodeStyle.DEFAULT;
    private final List<String> also = new ArrayList<>();
    private final List<String> hints = new ArrayList<>();
    private String answer;
    private String next;

    private Builder(String id, String range) {
      this.id = id;
      this.range = range;
    }

    public Builder file(String file) { this.file = file; return this; }
    public Builder text(String markdown) { this.text = markdown; return this; }
    public Builder title(String title) { this.title = title; return this; }
    public Builder kind(Kind kind) { this.kind = kind; return this; }
    public Builder style(NodeStyle style) { this.style = style; return this; }
    /** Shorthand for a style with just the highlight colour, e.g. "#FFF59D" or "blue". */
    public Builder color(String color) {
      this.style = new NodeStyle(ColorSpec.parse(color), style.background(), style.border(), style.text(), style.highlightMode());
      return this;
    }
    public Builder also(String... ranges) { this.also.addAll(List.of(ranges)); return this; }
    public Builder hint(String markdown) { this.hints.add(markdown); return this; }
    public Builder answer(String markdown) { this.answer = markdown; return this; }
    public Builder next(String id) { this.next = id; return this; }

    public ExplanationNode build() {
      return new ExplanationNode(id, file, range, text, title, kind, style, also, hints, answer, next);
    }
  }
}
