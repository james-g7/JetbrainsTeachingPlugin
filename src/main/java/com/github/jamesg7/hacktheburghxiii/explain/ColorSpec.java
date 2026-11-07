package com.github.jamesg7.hacktheburghxiii.explain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.util.Locale;
import java.util.Map;

/**
 * A colour with light- and dark-theme variants. In JSON it is one of:
 * <ul>
 *   <li>a hex string: {@code "#RGB"}, {@code "#RRGGBB"} or {@code "#RRGGBBAA"} (used in both themes)</li>
 *   <li>a name: yellow, blue, green, pink, orange, purple, red, teal, gray (with tuned dark variants)</li>
 *   <li>an object: {@code {"light": "#FFF59D", "dark": "#6B5B00"}}</li>
 * </ul>
 */
public record ColorSpec(Color light, Color dark) {
  private static final Map<String, ColorSpec> NAMED = Map.of(
      "yellow", new ColorSpec(new Color(0xFFF176), new Color(0x6B5B00)),
      "blue", new ColorSpec(new Color(0xBFDBFE), new Color(0x1E3A5F)),
      "green", new ColorSpec(new Color(0xBBF7D0), new Color(0x1F4D33)),
      "pink", new ColorSpec(new Color(0xFBCFE8), new Color(0x5B2142)),
      "orange", new ColorSpec(new Color(0xFED7AA), new Color(0x5C3A1A)),
      "purple", new ColorSpec(new Color(0xDDD6FE), new Color(0x3B2F63)),
      "red", new ColorSpec(new Color(0xFECACA), new Color(0x5F1E1E)),
      "teal", new ColorSpec(new Color(0x99F6E4), new Color(0x134E48)),
      "gray", new ColorSpec(new Color(0xE5E7EB), new Color(0x3A3F47)),
      "grey", new ColorSpec(new Color(0xE5E7EB), new Color(0x3A3F47))
  );

  public static ColorSpec named(String name) {
    return NAMED.get(name);
  }

  /** The colour for the current IDE theme; follows theme switches. */
  public Color toColor() {
    return new JBColor(light, dark);
  }

  /** Parses a hex string or colour name. */
  public static ColorSpec parse(String text) {
    String s = text.trim().toLowerCase(Locale.ROOT);
    ColorSpec named = NAMED.get(s);
    if (named != null) return named;
    Color color = parseHex(s);
    return new ColorSpec(color, color);
  }

  /** Parses a JSON colour (string or {light, dark} object); null stays null. */
  public static @Nullable ColorSpec parse(@Nullable JsonElement json) {
    if (json == null || json.isJsonNull()) return null;
    if (json.isJsonPrimitive()) return parse(json.getAsString());
    if (json.isJsonObject()) {
      JsonObject o = json.getAsJsonObject();
      if (!o.has("light") || !o.has("dark")) {
        throw new IllegalArgumentException("Colour object needs \"light\" and \"dark\": " + json);
      }
      return new ColorSpec(parse(o.get("light").getAsString()).light(), parse(o.get("dark").getAsString()).dark());
    }
    throw new IllegalArgumentException("Invalid colour: " + json);
  }

  private static Color parseHex(String s) {
    String hex = s.startsWith("#") ? s.substring(1) : s;
    try {
      switch (hex.length()) {
        case 3: {
          int r = Integer.parseInt(hex.substring(0, 1), 16) * 17;
          int g = Integer.parseInt(hex.substring(1, 2), 16) * 17;
          int b = Integer.parseInt(hex.substring(2, 3), 16) * 17;
          return new Color(r, g, b);
        }
        case 6:
          return new Color(Integer.parseInt(hex, 16));
        case 8: {
          long v = Long.parseLong(hex, 16);
          return new Color((int) (v >> 24) & 0xFF, (int) (v >> 16) & 0xFF, (int) (v >> 8) & 0xFF, (int) v & 0xFF);
        }
        default:
          break;
      }
    } catch (NumberFormatException ignored) {
      // fall through to the error below
    }
    throw new IllegalArgumentException("Invalid colour '" + s + "' (use #RGB, #RRGGBB, #RRGGBBAA or a name like "
        + String.join(", ", NAMED.keySet()) + ")");
  }
}
