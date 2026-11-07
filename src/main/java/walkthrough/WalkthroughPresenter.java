package walkthrough;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.FoldingModelEx;
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager;
import com.intellij.openapi.editor.markup.EffectType;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.scale.JBUIScale;
import explain.CodeRange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Draws a walkthrough step in the editor: the step's lines highlighted, the step box above them,
 * variable values at the end of the line, variables shown as their values, and extra outlines.
 */
final class WalkthroughPresenter {
  private static final Color VALUE_COLOR = new JBColor(new Color(0x6B7280), new Color(0x9CA3AF));
  private static final Color CHANGED_COLOR = new JBColor(new Color(0xC2410C), new Color(0xFDBA74));
  private static final Color OUTLINE_COLOR = new JBColor(new Color(0xF97316), new Color(0xFB923C));
  private static final int MAX_INLINE_VALUES = 8;

  private final Project project;
  private @Nullable Editor editor;
  private final List<RangeHighlighter> highlighters = new ArrayList<>();
  private final List<FoldRegion> folds = new ArrayList<>();
  private final List<Inlay<?>> inlays = new ArrayList<>();

  WalkthroughPresenter(Project project) {
    this.project = project;
  }

  void show(Walkthrough.Step step, Set<String> changed, StepBox.Content content, StepBox.Controls controls) {
    clear();
    Path path = Path.of(step.file());
    if (!path.isAbsolute()) path = Path.of(project.getBasePath()).resolve(path);
    VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
    if (file == null) {
      com.intellij.openapi.ui.Messages.showErrorDialog(project, "File not found: " + step.file(), "Walkthrough");
      return;
    }
    Editor editor = FileEditorManager.getInstance(project)
        .openTextEditor(new OpenFileDescriptor(project, file, step.range().startLine() - 1, 0), false);
    if (editor == null) return;
    this.editor = editor;

    Document document = editor.getDocument();
    int firstLine = Math.max(1, Math.min(step.range().startLine(), document.getLineCount()));
    int lastLine = Math.max(firstLine, Math.min(step.range().endLine(), document.getLineCount()));
    int start = document.getLineStartOffset(firstLine - 1);
    int end = document.getLineEndOffset(lastLine - 1);
    Colors colors = colors(editor, step.kind());

    TextAttributes line = new TextAttributes();
    line.setBackgroundColor(colors.line());
    highlighters.add(editor.getMarkupModel().addRangeHighlighter(start, end, HighlighterLayer.SELECTION - 2, line,
        HighlighterTargetArea.LINES_IN_RANGE));
    for (String range : step.highlight()) outline(editor, CodeRange.parse(range));

    List<Replacement> replacements = new ArrayList<>();
    for (Map.Entry<String, String> entry : step.replace().entrySet()) {
      int[] offsets = offsets(document, CodeRange.parse(entry.getKey()));
      if (offsets != null) replacements.add(new Replacement(offsets[0], offsets[1], entry.getValue()));
    }
    if (!step.substitute().isEmpty()) {
      for (int l = firstLine; l <= lastLine; l++) replacements.addAll(substitutions(document, l, step.vars(), step.substitute()));
    }
    fold(editor, replacements);

    List<Segment> values = values(step, changed);
    if (!values.isEmpty()) {
      inlays.add(editor.getInlayModel().addAfterLineEndElement(document.getLineEndOffset(firstLine - 1), true,
          new ValuesRenderer(values)));
    }
    addBox(editor, firstLine, content, colors, controls);
    editor.getScrollingModel().scrollTo(new LogicalPosition(firstLine - 1, 0), ScrollType.MAKE_VISIBLE);
  }

  void clear() {
    Editor editor = this.editor;
    this.editor = null;
    for (Inlay<?> inlay : inlays) if (inlay.isValid()) Disposer.dispose(inlay);
    inlays.clear();
    if (editor == null || editor.isDisposed()) {
      highlighters.clear();
      folds.clear();
      return;
    }
    for (RangeHighlighter highlighter : highlighters) {
      if (highlighter.isValid()) editor.getMarkupModel().removeHighlighter(highlighter);
    }
    highlighters.clear();
    if (!folds.isEmpty()) {
      FoldingModelEx folding = (FoldingModelEx) editor.getFoldingModel();
      folding.runBatchFoldingOperation(() -> {
        for (FoldRegion fold : folds) if (fold.isValid()) folding.removeFoldRegion(fold);
      });
      folds.clear();
    }
  }

  // ---- Step box ----

  private void addBox(Editor editor, int line, StepBox.Content content, Colors colors, StepBox.Controls controls) {
    if (!(editor instanceof EditorEx editorEx)) return;
    // Indent to the line's first non-blank character, so the box sits over the code.
    Document document = editor.getDocument();
    int lineStart = document.getLineStartOffset(line - 1);
    CharSequence text = document.getCharsSequence();
    int firstText = lineStart;
    while (firstText < document.getLineEndOffset(line - 1) && Character.isWhitespace(text.charAt(firstText))) firstText++;
    int indent = editor.offsetToXY(firstText).x - editor.offsetToXY(lineStart).x;

    StepBox box = new StepBox(content, colors.box(), editor.getColorsScheme().getEditorFontName(), Math.max(0, indent), controls);
    Inlay<?> inlay = EditorEmbeddedComponentManager.getInstance().addComponent(editorEx, box,
        new EditorEmbeddedComponentManager.Properties(EditorEmbeddedComponentManager.ResizePolicy.none(), null,
            false, true, 0, lineStart));
    if (inlay != null) {
      box.setInlay(inlay);
      inlays.add(inlay);
    }
  }

  // ---- Colours ----

  private record Colors(Color line, StepBox.Colors box) {}

  private static Colors colors(Editor editor, Walkthrough.Kind kind) {
    Color base = switch (kind) {
      case LINE -> new JBColor(new Color(0xBFDBFE), new Color(0x1E3A5F));
      case CALL -> new JBColor(new Color(0xBBF7D0), new Color(0x1F4D33));
      case RETURN -> new JBColor(new Color(0xDDD6FE), new Color(0x3B2F63));
      case ERROR -> new JBColor(new Color(0xFECACA), new Color(0x5F1E1E));
    };
    EditorColorsScheme scheme = editor.getColorsScheme();
    Color background = scheme.getDefaultBackground();
    Color foreground = scheme.getDefaultForeground();
    boolean dark = ColorUtil.isDark(background);
    Color box = ColorUtil.mix(background, base, dark ? 0.45 : 0.3);
    return new Colors(base, new StepBox.Colors(
        box,
        ColorUtil.mix(base, foreground, dark ? 0.15 : 0.35),
        foreground,
        ColorUtil.mix(foreground, box, 0.4),
        CHANGED_COLOR,
        ColorUtil.mix(box, foreground, 0.08)));
  }

  // ---- Showing values in the code ----

  private record Replacement(int start, int end, String text) {}

  /**
   * Finds reads of the named variables on a line, by name, so it works for any language: whole-word
   * matches outside string literals, skipping assignments ({@code x = …}, {@code x += …}) and
   * increments ({@code x++}), where the value before the line isn't what the code means.
   */
  private static List<Replacement> substitutions(Document document, int line, Map<String, String> vars, List<String> names) {
    List<Replacement> found = new ArrayList<>();
    int lineStart = document.getLineStartOffset(line - 1);
    String text = document.getCharsSequence().subSequence(lineStart, document.getLineEndOffset(line - 1)).toString();
    boolean all = names.contains("*");
    for (Map.Entry<String, String> var : vars.entrySet()) {
      if (!all && !names.contains(var.getKey())) continue;
      Matcher m = Pattern.compile("(?<![\\w$.])" + Pattern.quote(var.getKey()) + "(?![\\w$])").matcher(text);
      while (m.find()) {
        if (insideString(text, m.start())) continue;
        String after = text.substring(m.end());
        String before = text.substring(0, m.start());
        if (after.matches("\\s*(?:[-+*/%&|^]|<<|>>>?|\\?\\?)?=(?!=).*")) continue;
        if (after.matches("\\s*(?:\\+\\+|--).*") || before.matches(".*(?:\\+\\+|--)\\s*")) continue;
        found.add(new Replacement(lineStart + m.start(), lineStart + m.end(), var.getValue()));
      }
    }
    return found;
  }

  /** Whether {@code index} is inside a "…" or '…' literal, scanning from the start of the line. */
  private static boolean insideString(String text, int index) {
    char quote = 0;
    for (int i = 0; i < index; i++) {
      char c = text.charAt(i);
      if (quote != 0 && c == '\\') {
        i++;
      } else if (quote == 0 && (c == '"' || c == '\'' || c == '`')) {
        quote = c;
      } else if (c == quote) {
        quote = 0;
      }
    }
    return quote != 0;
  }

  /** Shows each replacement's text in place of the code, without changing the document. */
  private void fold(Editor editor, List<Replacement> replacements) {
    if (replacements.isEmpty()) return;
    replacements.sort(Comparator.comparingInt(Replacement::start));
    FoldingModelEx folding = (FoldingModelEx) editor.getFoldingModel();
    folding.runBatchFoldingOperation(() -> {
      int lastEnd = -1;
      for (Replacement r : replacements) {
        if (r.start() < lastEnd || r.start() >= r.end()) continue; // overlapping; keep the first
        FoldRegion fold = folding.createFoldRegion(r.start(), r.end(), r.text(), null, true);
        if (fold != null) {
          fold.setExpanded(false);
          folds.add(fold);
          lastEnd = r.end();
        }
      }
    });
  }

  private void outline(Editor editor, @Nullable CodeRange range) {
    int[] offsets = offsets(editor.getDocument(), range);
    if (offsets == null) return;
    TextAttributes attributes = new TextAttributes(null, null, OUTLINE_COLOR, EffectType.ROUNDED_BOX, Font.PLAIN);
    highlighters.add(editor.getMarkupModel().addRangeHighlighter(offsets[0], offsets[1], HighlighterLayer.SELECTION - 1,
        attributes, HighlighterTargetArea.EXACT_RANGE));
  }

  /** Document offsets for a range (end column inclusive), or null if it's outside the document. */
  private static int @Nullable [] offsets(Document document, @Nullable CodeRange range) {
    if (range == null || range.startLine() < 1 || range.endLine() > document.getLineCount() || range.endLine() < range.startLine()) return null;
    int startLineStart = document.getLineStartOffset(range.startLine() - 1);
    int start = startLineStart + (range.startColumn() == CodeRange.WHOLE_LINE ? 0 : range.startColumn() - 1);
    int endLineEnd = document.getLineEndOffset(range.endLine() - 1);
    int end = range.endColumn() == CodeRange.WHOLE_LINE ? endLineEnd
        : document.getLineStartOffset(range.endLine() - 1) + range.endColumn();
    start = Math.min(start, endLineEnd);
    end = Math.min(end, endLineEnd);
    return start < end ? new int[]{start, end} : null;
  }

  // ---- End-of-line values ----

  private record Segment(String text, Color color, boolean bold) {}

  private static List<Segment> values(Walkthrough.Step step, Set<String> changed) {
    List<Segment> segments = new ArrayList<>();
    List<Map.Entry<String, String>> entries = new ArrayList<>(step.vars().entrySet());
    // Changed values first, so they're never cut off.
    entries.sort((a, b) -> Boolean.compare(changed.contains(b.getKey()), changed.contains(a.getKey())));
    for (Map.Entry<String, String> entry : entries) {
      if (segments.size() >= MAX_INLINE_VALUES) {
        segments.add(new Segment("…", VALUE_COLOR, false));
        break;
      }
      boolean isChanged = changed.contains(entry.getKey());
      segments.add(new Segment(entry.getKey() + " = " + entry.getValue(), isChanged ? CHANGED_COLOR : VALUE_COLOR, isChanged));
    }
    return segments;
  }

  private static final class ValuesRenderer implements EditorCustomElementRenderer {
    private static final String GAP = "   ";
    private final List<Segment> segments;

    ValuesRenderer(List<Segment> segments) {
      this.segments = segments;
    }

    @Override
    public int calcWidthInPixels(@NotNull Inlay inlay) {
      Editor editor = inlay.getEditor();
      int width = JBUIScale.scale(16);
      for (Segment segment : segments) width += metrics(editor, segment).stringWidth(segment.text() + GAP);
      return width;
    }

    @Override
    public void paint(@NotNull Inlay inlay, @NotNull Graphics g, @NotNull Rectangle region, @NotNull TextAttributes attributes) {
      Editor editor = inlay.getEditor();
      int x = region.x + JBUIScale.scale(16);
      int baseline = region.y + editor.getAscent();
      for (Segment segment : segments) {
        FontMetrics metrics = metrics(editor, segment);
        g.setFont(metrics.getFont());
        g.setColor(segment.color());
        g.drawString(segment.text(), x, baseline);
        x += metrics.stringWidth(segment.text() + GAP);
      }
    }

    private static FontMetrics metrics(Editor editor, Segment segment) {
      Font font = editor.getColorsScheme().getFont(segment.bold() ? EditorFontType.BOLD_ITALIC : EditorFontType.ITALIC);
      return editor.getContentComponent().getFontMetrics(font);
    }
  }
}
