package com.github.jamesg7.hacktheburghxiii.explain;

import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A range of code to highlight. Lines and columns are 1-based and the end column is inclusive;
 * a column of {@link #WHOLE_LINE} means "start of line" (start) or "end of line" (end).
 *
 * @param path project-relative (or absolute) file path, or null to use the surrounding file
 */
public record CodeRange(@Nullable String path, int startLine, int startColumn, int endLine, int endColumn) {
  public static final int WHOLE_LINE = -1;

  private static final Pattern SYNTAX = Pattern.compile(
      "(?:(?<path>[^@]+)@)?(?<sl>\\d+)(?::(?<sc>\\d+))?(?:-(?<el>\\d+)(?::(?<ec>\\d+))?)?");

  /**
   * Parses a range, or returns null if it is malformed. Accepted forms:
   * {@code 12} (one line), {@code 12-15} (whole lines), {@code 12:5-15:20} (exact range),
   * each optionally prefixed by a file path and {@code @}, e.g. {@code src/Foo.java@3-8}.
   */
  public static @Nullable CodeRange parse(@Nullable String text) {
    if (text == null) return null;
    Matcher m = SYNTAX.matcher(text.trim());
    if (!m.matches()) return null;
    try {
      String path = m.group("path") == null ? null : m.group("path").trim();
      int startLine = Integer.parseInt(m.group("sl"));
      int startColumn = m.group("sc") == null ? WHOLE_LINE : Integer.parseInt(m.group("sc"));
      int endLine = m.group("el") == null ? startLine : Integer.parseInt(m.group("el"));
      int endColumn = m.group("ec") == null ? WHOLE_LINE : Integer.parseInt(m.group("ec"));
      return new CodeRange(path, startLine, startColumn, endLine, endColumn);
    } catch (NumberFormatException e) {
      return null; // number too large
    }
  }
}
