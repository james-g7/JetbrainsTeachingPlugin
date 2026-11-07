package com.github.jamesg7.hacktheburghxiii.explain;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reads the JSON format described in docs/EXPLANATIONS.md. */
final class ExplanationJson {
  private static final Gson GSON = new Gson();

  /** A parsed tree. {@code theme} is null if the JSON has none. */
  record Tree(@Nullable Theme theme, @Nullable String root, List<ExplanationNode> nodes) {}

  private ExplanationJson() {}

  static Tree parseTree(String json) {
    TreeJson tree = fromJson(json, TreeJson.class);
    if (tree == null || tree.nodes == null) {
      throw new IllegalArgumentException("Explanation JSON must have a \"nodes\" object");
    }
    List<ExplanationNode> nodes = new ArrayList<>();
    for (Map.Entry<String, NodeJson> entry : tree.nodes.entrySet()) {
      nodes.add(toNode(entry.getKey(), entry.getValue()).withDefaultFile(tree.file));
    }
    return new Tree(toTheme(tree.theme), tree.root, nodes);
  }

  static ExplanationNode parseNode(String id, String json) {
    return toNode(id, fromJson(json, NodeJson.class));
  }

  private static <T> T fromJson(String json, Class<T> type) {
    try {
      return GSON.fromJson(json, type);
    } catch (JsonParseException e) {
      throw new IllegalArgumentException("Invalid explanation JSON: " + e.getMessage(), e);
    }
  }

  private static ExplanationNode toNode(String id, @Nullable NodeJson n) {
    if (n == null || n.range == null) throw new IllegalArgumentException("Node '" + id + "' needs a \"range\"");
    try {
      NodeStyle style = toStyle(n.style, n.color);
      return new ExplanationNode(id, n.file, n.range, n.text, n.title, Kind.parse(n.kind), style,
          n.also, n.hints, n.answer, n.next);
    } catch (IllegalArgumentException e) {
      String message = e.getMessage();
      throw new IllegalArgumentException(message.startsWith("Node '") ? message : "Node '" + id + "': " + message, e);
    }
  }

  private static NodeStyle toStyle(@Nullable StyleJson s, @Nullable JsonElement color) {
    ColorSpec shorthand = ColorSpec.parse(color);
    if (s == null) return shorthand == null ? NodeStyle.DEFAULT : new NodeStyle(shorthand, null, null, null, null);
    ColorSpec highlight = s.highlight != null ? ColorSpec.parse(s.highlight) : shorthand;
    return new NodeStyle(highlight, ColorSpec.parse(s.background), ColorSpec.parse(s.border),
        ColorSpec.parse(s.text), HighlightMode.parse(s.highlightStyle));
  }

  private static @Nullable Theme toTheme(@Nullable ThemeJson t) {
    if (t == null) return null;
    List<ColorSpec> palette = new ArrayList<>();
    if (t.palette != null) {
      for (JsonElement c : t.palette) palette.add(ColorSpec.parse(c));
    }
    int maxWidth = t.maxWidth != null ? t.maxWidth : Theme.DEFAULT.maxWidth();
    return new Theme(palette, maxWidth, t.fontSize, HighlightMode.parse(t.highlightStyle));
  }

  // Gson targets.
  private static final class TreeJson {
    String file;
    String root;
    ThemeJson theme;
    Map<String, NodeJson> nodes;
  }

  private static final class ThemeJson {
    List<JsonElement> palette;
    Integer maxWidth;
    Integer fontSize;
    String highlightStyle;
  }

  private static final class NodeJson {
    String file;
    String range;
    String text;
    String title;
    String kind;
    JsonElement color;
    StyleJson style;
    List<String> also;
    List<String> hints;
    String answer;
    String next;
  }

  private static final class StyleJson {
    JsonElement highlight;
    JsonElement background;
    JsonElement border;
    JsonElement text;
    String highlightStyle;
  }
}
