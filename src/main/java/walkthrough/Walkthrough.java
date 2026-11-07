package walkthrough;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import explain.CodeRange;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A step-by-step walkthrough of running some code, written by an AI as JSON (any language) and
 * played back in the editor. See docs/WALKTHROUGH.md for the format.
 *
 * @param title shown in the step box, e.g. "sum(3)"
 * @param delay milliseconds per step when playing
 * @param steps in order
 */
public record Walkthrough(String title, int delay, List<Step> steps) {
  public static final int DEFAULT_DELAY = 1800;

  public Walkthrough {
    if (title == null) title = "";
    if (delay < 200) delay = DEFAULT_DELAY;
    steps = List.copyOf(steps);
    if (steps.isEmpty()) throw new IllegalArgumentException("A walkthrough needs at least one step");
  }

  /** How a step is coloured in the editor. */
  public enum Kind {
    LINE, CALL, RETURN, ERROR;

    static Kind parse(@Nullable String text, int step) {
      if (text == null || text.isBlank()) return LINE;
      try {
        return valueOf(text.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Step " + step + ": unknown kind '" + text + "' (use line, call, return or error)");
      }
    }
  }

  /**
   * One step.
   *
   * @param file       project-relative or absolute path (defaults to the previous step's, then the walkthrough's)
   * @param range      the code this step is about; a line ("8") or lines ("8-12") for a step covering a whole block or call
   * @param kind       colour of the highlight
   * @param title      bold heading in the step box
   * @param note       Markdown explanation
   * @param vars       variable name → value at this step
   * @param changed    variables to emphasise; null to work out from the previous step with the same stack
   * @param substitute names of variables to show as their values in the code (on the step's lines); "*" for all
   * @param replace    exact ranges to show as other text, e.g. {"11:29-11:29": "1"}, for precision substitute can't give
   * @param highlight  extra ranges to outline
   * @param stack      call stack, outermost first, e.g. ["main()", "sum(n = 3)"]
   * @param output     text the program prints during this step
   * @param pause      stop auto-play here
   */
  public record Step(@Nullable String file, CodeRange range, Kind kind, @Nullable String title, @Nullable String note,
                     Map<String, String> vars, @Nullable List<String> changed, List<String> substitute,
                     Map<String, String> replace, List<String> highlight, List<String> stack, String output, boolean pause) {
    public Step {
      vars = vars == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(vars));
      changed = changed == null ? null : List.copyOf(changed);
      substitute = substitute == null ? List.of() : List.copyOf(substitute);
      replace = replace == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(replace));
      highlight = highlight == null ? List.of() : List.copyOf(highlight);
      stack = stack == null ? List.of() : List.copyOf(stack);
      if (output == null) output = "";
    }
  }

  /** Parses a walkthrough. Error messages name the step and field at fault, so they can go back to the AI. */
  public static Walkthrough fromJson(String json) {
    WalkthroughJson parsed;
    try {
      parsed = new Gson().fromJson(json, WalkthroughJson.class);
    } catch (JsonParseException e) {
      throw new IllegalArgumentException("Invalid walkthrough JSON: " + e.getMessage(), e);
    }
    if (parsed == null || parsed.steps == null) throw new IllegalArgumentException("Walkthrough JSON needs a \"steps\" array");

    List<Step> steps = new ArrayList<>();
    String file = parsed.file;
    List<String> stack = List.of();
    for (int i = 0; i < parsed.steps.size(); i++) {
      StepJson s = parsed.steps.get(i);
      if (s == null) throw new IllegalArgumentException("Step " + i + " is empty");
      if (s.file != null) file = s.file;
      if (file == null) throw new IllegalArgumentException("Step " + i + ": no \"file\" (set it on the walkthrough or the step)");
      if (s.line == null || s.line.isJsonNull()) throw new IllegalArgumentException("Step " + i + " needs a \"line\", e.g. 8 or \"8-12\"");
      CodeRange range = CodeRange.parse(s.line.getAsString());
      if (range == null || range.path() != null) {
        throw new IllegalArgumentException("Step " + i + ": invalid line '" + s.line + "' (use e.g. 8 or \"8-12\")");
      }
      for (String r : s.highlight == null ? List.<String>of() : s.highlight) checkRange(i, "highlight", r);
      if (s.replace != null) for (String r : s.replace.keySet()) checkRange(i, "replace", r);
      if (s.stack != null) stack = s.stack; // the stack carries over until a step changes it
      steps.add(new Step(file, range, Kind.parse(s.kind, i), s.title, s.note, s.vars, s.changed,
          substituteList(s.substitute), s.replace, s.highlight, stack, s.output, Boolean.TRUE.equals(s.pause)));
    }
    return new Walkthrough(parsed.title, parsed.delay == null ? DEFAULT_DELAY : parsed.delay, steps);
  }

  private static void checkRange(int step, String field, String range) {
    if (CodeRange.parse(range) == null) {
      throw new IllegalArgumentException("Step " + step + ": invalid " + field + " range '" + range + "' (use e.g. 11 or 11:29-11:29)");
    }
  }

  /** Accepts ["i", "total"], "i", or "*". */
  private static List<String> substituteList(@Nullable JsonElement json) {
    if (json == null || json.isJsonNull()) return List.of();
    if (json.isJsonPrimitive()) return List.of(json.getAsString());
    List<String> names = new ArrayList<>();
    json.getAsJsonArray().forEach(e -> names.add(e.getAsString()));
    return names;
  }

  // Gson targets.
  private static final class WalkthroughJson {
    String title;
    String file;
    Integer delay;
    List<StepJson> steps;
  }

  private static final class StepJson {
    String file;
    JsonElement line;
    String kind;
    String title;
    String note;
    Map<String, String> vars;
    List<String> changed;
    JsonElement substitute;
    Map<String, String> replace;
    List<String> highlight;
    List<String> stack;
    String output;
    Boolean pause;
  }
}
