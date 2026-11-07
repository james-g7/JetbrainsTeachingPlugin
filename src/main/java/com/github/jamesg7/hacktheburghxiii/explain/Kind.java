package com.github.jamesg7.hacktheburghxiii.explain;

import com.intellij.icons.AllIcons;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.Locale;

/** What sort of explanation a node is. Sets the box's icon and default colour. */
public enum Kind {
  NOTE(null, null),
  INFO(AllIcons.General.Information, ColorSpec.named("blue")),
  TIP(AllIcons.Actions.IntentionBulb, ColorSpec.named("green")),
  WARNING(AllIcons.General.Warning, ColorSpec.named("orange")),
  QUESTION(AllIcons.General.QuestionDialog, ColorSpec.named("purple")),
  EXAMPLE(AllIcons.Actions.Execute, ColorSpec.named("teal"));

  private final @Nullable Icon icon;
  private final @Nullable ColorSpec color;

  Kind(@Nullable Icon icon, @Nullable ColorSpec color) {
    this.icon = icon;
    this.color = color;
  }

  public @Nullable Icon icon() {
    return icon;
  }

  /** The default highlight colour, or null to use the theme's palette for the node's depth. */
  public @Nullable ColorSpec color() {
    return color;
  }

  public static Kind parse(@Nullable String text) {
    if (text == null || text.isBlank()) return NOTE;
    try {
      return valueOf(text.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unknown kind '" + text + "' (use note, info, tip, warning, question or example)");
    }
  }
}
