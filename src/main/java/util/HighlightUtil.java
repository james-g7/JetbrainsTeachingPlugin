package util;

import com.intellij.ide.ui.UISettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.editor.event.EditorMouseMotionListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.markup.CustomHighlighterOrder;
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.JBColor;
import explain.CodeRange;
import explain.ExplanationNode;
import explain.ExplanationRequest;
import explain.ExplanationStore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Highlights code with an optional explanation box above it. Explanation links to {@code #id}
 * show nodes from the {@link ExplanationStore}, which can link on further.
 *
 * <p>Each step of the chain is a "level": level 0 is the original highlight, and clicking a link
 * in level N's box shows level N+1, replacing whatever was at N+1 and deeper. Levels are tracked
 * per project, so a chain can span several files.
 */
public class HighlightUtil {
  private static final String TITLE = "Highlight Range";

  /** The levels currently shown in a project, indexed by depth. Only touched on the EDT. */
  private static final Key<List<Level>> LEVELS = Key.create("hacktheburghxiii.levels");

  // Colours per depth, cycling when the chain is deeper than the palette.
  private static final Color[] BLOCK_COLORS = {
      new JBColor(Color.YELLOW, new Color(0x6B5B00)),
      new JBColor(new Color(0xBFDBFE), new Color(0x1E3A5F)),
      new JBColor(new Color(0xBBF7D0), new Color(0x1F4D33)),
      new JBColor(new Color(0xFBCFE8), new Color(0x5B2142)),
      new JBColor(new Color(0xFED7AA), new Color(0x5C3A1A)),
  };
  private static final Color[] NOTE_BACKGROUNDS = {
      new JBColor(new Color(0xFFF8D6), new Color(0x3D3820)),
      new JBColor(new Color(0xEFF6FF), new Color(0x1C2A3D)),
      new JBColor(new Color(0xF0FDF4), new Color(0x1A3326)),
      new JBColor(new Color(0xFDF2F8), new Color(0x3A1B2C)),
      new JBColor(new Color(0xFFF7ED), new Color(0x3A2A1A)),
  };
  private static final Color[] NOTE_BORDERS = {
      new JBColor(new Color(0xD4B106), new Color(0x8C7A1E)),
      new JBColor(new Color(0x3B82F6), new Color(0x4A7BC8)),
      new JBColor(new Color(0x22C55E), new Color(0x3F9E66)),
      new JBColor(new Color(0xEC4899), new Color(0xB84A86)),
      new JBColor(new Color(0xF97316), new Color(0xC27032)),
  };
  private static final Color NOTE_TEXT = new JBColor(new Color(0x1F2937), new Color(0xE5E7EB));
  private static final Color LINK_TEXT = new JBColor(new Color(0x1D4ED8), new Color(0x8AB4F8));
  private static final Color DEAD_LINK_TEXT = new JBColor(new Color(0x9CA3AF), new Color(0x6B7280));

  private static Color byDepth(Color[] palette, int depth) {
    return palette[depth % palette.length];
  }

  public static void highlightRange(
      Project project, String path,
      int startLine, int startColumn,
      int endLine, int endColumn
  ) {
    highlightRange(project, path, startLine, startColumn, endLine, endColumn, null);
  }

  /**
   * Highlights the range and, when {@code explanation} is non-blank, shows it in a box directly
   * above the highlighted block. Replaces anything previously highlighted by this class.
   *
   * <p>The explanation may contain links: {@code [label](#id)} shows a node from the project's
   * {@link ExplanationStore}, and {@code [label](12-15)} highlights a range (see {@link CodeRange#parse}).
   */
  public static void highlightRange(
      Project project, String path,
      int startLine, int startColumn,
      int endLine, int endColumn,
      String explanation
  ) {
    String range = startLine + ":" + startColumn + "-" + endLine + ":" + endColumn;
    ExplanationNode node = new ExplanationNode("highlight", null, range, explanation);
    ExplanationStore.getInstance(project).nextNavigation();
    showLevel(project, path, 0, node.codeRange(), node);
  }

  /**
   * Shows {@code node} at {@code depth} of the chain, replacing that level and everything deeper.
   * Its file is the range's {@code path@} prefix, else {@link ExplanationNode#file()}, else
   * {@code fallbackPath} (absolute; the file of the node that linked here).
   */
  public static void showNode(Project project, ExplanationNode node, @Nullable String fallbackPath, int depth) {
    ExplanationStore store = ExplanationStore.getInstance(project);
    CodeRange range = node.codeRange();
    String file = range.path() != null ? range.path() : node.file();
    String path = file != null ? store.resolvePath(file) : fallbackPath;
    if (path == null) {
      ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(project,
          "Explanation '" + node.id() + "' has no file. Set \"file\" on the node or the tree.", TITLE));
      return;
    }
    showLevel(project, path, depth, range, node);
  }

  /**
   * Shows the level at {@code depth}: replaces it and every deeper level with a highlight of
   * {@code range} in {@code path}, plus an explanation box when the node has text.
   */
  private static void showLevel(
      Project project, String path, int depth, CodeRange range, @Nullable ExplanationNode node
  ) {
    withEditor(project, path, editor -> {
      Block block = computeBlock(project, editor,
          range.startLine(), range.startColumn(), range.endLine(), range.endColumn());
      if (block == null) return;

      List<Level> levels = levels(project);
      if (levels.size() < depth) return; // the level holding the clicked link has been cleared
      clearFrom(project, depth);

      RangeHighlighter highlighter = addBlockHighlighter(editor, block, depth);
      Inlay<?> inlay = null;
      if (node != null && !node.text().isBlank()) {
        inlay = addExplanation(project, path, editor, block, depth, node);
      }
      levels.add(new Level(editor, highlighter, inlay));

      moveTo(editor, block);
    });
  }

  private static @Nullable Inlay<?> addExplanation(
      Project project, String path, Editor editor, Block block, int depth, ExplanationNode node
  ) {
    Inlay<ExplanationRenderer> inlay = editor.getInlayModel().addBlockElement(
        editor.getDocument().getLineStartOffset(block.firstLine), false, true, 0,
        new ExplanationRenderer(project, node.text().strip(), block.leftColumn, block.firstLine, depth));
    if (inlay == null) return null;

    LinkMouseHandler handler = new LinkMouseHandler(project, path, inlay, depth, node);
    // Registering with the inlay as parent removes the listeners when the inlay goes away.
    editor.addEditorMouseListener(handler, inlay);
    editor.addEditorMouseMotionListener(handler, inlay);
    Disposer.register(inlay, handler::resetCursor);
    return inlay;
  }

  /** One step of a highlight chain: its block highlight and, optionally, its explanation box. */
  private record Level(Editor editor, RangeHighlighter highlighter, @Nullable Inlay<?> inlay) {
    void dispose() {
      if (inlay != null && inlay.isValid()) Disposer.dispose(inlay);
      if (!editor.isDisposed() && highlighter.isValid()) editor.getMarkupModel().removeHighlighter(highlighter);
    }
  }

  private static List<Level> levels(Project project) {
    List<Level> levels = project.getUserData(LEVELS);
    if (levels == null) {
      levels = new ArrayList<>();
      project.putUserData(LEVELS, levels);
    }
    return levels;
  }

  /** Removes the level at {@code depth} and every deeper one. */
  private static void clearFrom(Project project, int depth) {
    List<Level> levels = levels(project);
    while (levels.size() > depth) levels.remove(levels.size() - 1).dispose();
  }

  /** Opens the file in an editor on the EDT and runs {@code action} with it. */
  private static void withEditor(Project project, String path, Consumer<Editor> action) {
    VirtualFile vfile = LocalFileSystem.getInstance().refreshAndFindFileByPath(path);
    if (vfile == null) {
      ApplicationManager.getApplication().invokeLater(() ->
          Messages.showErrorDialog(project, "File not found: " + path, TITLE));
      return;
    }

    ApplicationManager.getApplication().invokeLater(() -> {
      if (project.isDisposed()) return;
      Editor editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vfile), true);
      if (editor == null) {
        Messages.showErrorDialog(project, "Could not open editor for file: " + path, TITLE);
        return;
      }
      action.accept(editor);
    });
  }

  /** The rectangle to highlight: whole lines firstLine..lastLine (0-based), between two logical columns. */
  private record Block(int firstLine, int lastLine, int leftColumn, int rightColumn, int firstOffset) {}

  /**
   * Validates the 1-based range and computes the block covering it, or shows an error and
   * returns null. The end column is inclusive.
   */
  private static @Nullable Block computeBlock(
      Project project, Editor editor,
      int startLine, int startColumn,
      int endLine, int endColumn
  ) {
    Document document = editor.getDocument();
    CharSequence text = document.getCharsSequence();
    int lineCount = document.getLineCount();

    String error = validateLine("Start", startLine, lineCount);
    if (error == null) error = validateLine("End", endLine, lineCount);
    if (error == null) {
      if (startColumn == CodeRange.WHOLE_LINE) startColumn = 1;
      if (endColumn == CodeRange.WHOLE_LINE) endColumn = Math.max(1, lineLength(document, endLine));
      error = validateColumn(document, "Start", startLine, startColumn);
    }
    if (error == null) error = validateColumn(document, "End", endLine, endColumn);
    if (error == null && (endLine < startLine || (endLine == startLine && endColumn < startColumn))) {
      error = "End position must not be before start position.";
    }
    if (error != null) {
      Messages.showErrorDialog(project, error, TITLE);
      return null;
    }

    // The block's left edge is the leftmost text column and its right edge is the end of the
    // longest line, ignoring indentation and trailing whitespace. Blank lines inside are covered too.
    int firstOffset = -1;
    int firstLine = -1, lastLine = -1;
    int leftColumn = Integer.MAX_VALUE, rightColumn = 0;
    for (int line = startLine - 1; line <= endLine - 1; line++) {
      int lineStart = document.getLineStartOffset(line);
      int lineEnd = document.getLineEndOffset(line); // excludes the line separator

      int from = line == startLine - 1 ? lineStart + startColumn - 1 : lineStart;
      int to = line == endLine - 1 ? Math.min(lineStart + endColumn, lineEnd) : lineEnd;

      while (from < to && Character.isWhitespace(text.charAt(from))) from++;
      while (to > from && Character.isWhitespace(text.charAt(to - 1))) to--;
      if (from >= to) continue; // blank line: covered by the block, but doesn't size it

      // Logical columns expand tabs, so the block lines up visually.
      leftColumn = Math.min(leftColumn, editor.offsetToLogicalPosition(from).column);
      rightColumn = Math.max(rightColumn, editor.offsetToLogicalPosition(to).column);
      if (firstLine < 0) firstLine = line;
      lastLine = line;
      if (firstOffset < 0) firstOffset = from;
    }

    if (firstOffset < 0) {
      Messages.showErrorDialog(project, "The selected range contains only whitespace.", TITLE);
      return null;
    }
    return new Block(firstLine, lastLine, leftColumn, rightColumn, firstOffset);
  }

  private static RangeHighlighter addBlockHighlighter(Editor editor, Block block, int depth) {
    Document document = editor.getDocument();
    // One highlighter spanning the block's lines; the renderer paints the rectangle, which can
    // extend past the end of shorter lines and across blank ones. Deeper levels paint on top.
    RangeHighlighter highlighter = editor.getMarkupModel().addRangeHighlighter(
        document.getLineStartOffset(block.firstLine), document.getLineEndOffset(block.lastLine),
        HighlighterLayer.SELECTION - 100 + Math.min(depth, 99), null, HighlighterTargetArea.LINES_IN_RANGE);
    highlighter.setCustomRenderer(new BlockRenderer(block.leftColumn, block.rightColumn, byDepth(BLOCK_COLORS, depth)));
    return highlighter;
  }

  private static void moveTo(Editor editor, Block block) {
    editor.getCaretModel().moveToOffset(block.firstOffset);
    editor.getScrollingModel().scrollTo(editor.offsetToLogicalPosition(block.firstOffset), ScrollType.CENTER);
  }

  /** Paints a filled rectangle from leftColumn to rightColumn over every line of the highlighter. */
  private static final class BlockRenderer implements CustomHighlighterRenderer {
    private final int leftColumn;
    private final int rightColumn;
    private final Color color;

    BlockRenderer(int leftColumn, int rightColumn, Color color) {
      this.leftColumn = leftColumn;
      this.rightColumn = rightColumn;
      this.color = color;
    }

    @Override
    public void paint(@NotNull Editor editor, @NotNull RangeHighlighter highlighter, @NotNull Graphics g) {
      if (!highlighter.isValid()) return;
      Document document = editor.getDocument();
      // Line numbers are recomputed on each paint so the block follows edits above it.
      int firstLine = document.getLineNumber(highlighter.getStartOffset());
      int lastLine = document.getLineNumber(highlighter.getEndOffset());

      Point topLeft = editor.logicalPositionToXY(new LogicalPosition(firstLine, leftColumn));
      Point bottomRight = editor.logicalPositionToXY(new LogicalPosition(lastLine, rightColumn));
      int bottom = bottomRight.y + editor.getLineHeight();

      g.setColor(color);
      g.fillRect(topLeft.x, topLeft.y, bottomRight.x - topLeft.x, bottom - topLeft.y);
    }

    @Override
    public @NotNull CustomHighlighterOrder getOrder() {
      return CustomHighlighterOrder.AFTER_BACKGROUND; // draw beneath the text
    }
  }

  /**
   * Where a clicked link goes: a stored (or to-be-generated) node, or a plain range with no
   * explanation. Exactly one of {@code nodeId} and {@code range} is set.
   */
  private record Link(String label, @Nullable String nodeId, @Nullable CodeRange range) {}

  /** A piece of explanation text laid out on one line; x is relative to the start of the text area. */
  private record Run(String text, @Nullable Link link, int x, int width) {}

  /** Paints an explanation as a bordered note box, aligned with the left edge of the highlighted block. */
  private static final class ExplanationRenderer implements EditorCustomElementRenderer {
    private static final int PADDING = 6;
    private static final int MARGIN = 3; // vertical space between the box and the surrounding lines
    private static final int MAX_TEXT_WIDTH = 600;
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\(([^)]+)\\)");

    /** One word of the explanation, with the link it belongs to and whether a space precedes it. */
    private record Token(String text, @Nullable Link link, boolean spaceBefore) {}

    /** Each inner list is one paragraph (a line the user typed). */
    private final List<List<Token>> paragraphs;
    private final Project project;
    private final int leftColumn;
    private final int initialLine;
    private final int depth;

    ExplanationRenderer(Project project, String text, int leftColumn, int initialLine, int depth) {
      this.paragraphs = tokenize(text);
      this.project = project;
      this.leftColumn = leftColumn;
      this.initialLine = initialLine;
      this.depth = depth;
    }

    @Override
    public int calcWidthInPixels(@NotNull Inlay inlay) {
      // Called while the inlay is still being constructed, when inlay.getOffset() is not yet
      // valid, so use the line the inlay was created on. The width only affects scrolling.
      Editor editor = inlay.getEditor();
      FontMetrics metrics = metrics(editor);
      int line = Math.min(initialLine, Math.max(0, editor.getDocument().getLineCount() - 1));
      return boxX(editor, line) + textWidth(layout(metrics)) + 2 * PADDING;
    }

    @Override
    public int calcHeightInPixels(@NotNull Inlay inlay) {
      FontMetrics metrics = metrics(inlay.getEditor());
      return layout(metrics).size() * metrics.getHeight() + 2 * PADDING + 2 * MARGIN;
    }

    @Override
    public void paint(@NotNull Inlay inlay, @NotNull Graphics g, @NotNull Rectangle region, @NotNull TextAttributes attributes) {
      Editor editor = inlay.getEditor();
      FontMetrics metrics = metrics(editor);
      List<List<Run>> lines = layout(metrics);

      int x = currentBoxX(inlay);
      int y = region.y + MARGIN;
      int width = textWidth(lines) + 2 * PADDING;
      int height = region.height - 2 * MARGIN;

      UISettings.setupAntialiasing(g);
      g.setColor(byDepth(NOTE_BACKGROUNDS, depth));
      g.fillRoundRect(x, y, width, height, 8, 8);
      g.setColor(byDepth(NOTE_BORDERS, depth));
      g.drawRoundRect(x, y, width - 1, height - 1, 8, 8);

      g.setFont(metrics.getFont());
      int baseline = y + PADDING + metrics.getAscent();
      for (List<Run> line : lines) {
        for (Run run : line) {
          int runX = x + PADDING + run.x();
          boolean clickable = isClickable(run.link());
          // A link to a node that isn't stored, with no provider to generate it, is shown dimmed.
          g.setColor(clickable ? LINK_TEXT : run.link() != null ? DEAD_LINK_TEXT : NOTE_TEXT);
          g.drawString(run.text(), runX, baseline);
          if (clickable) g.drawLine(runX, baseline + 1, runX + run.width(), baseline + 1);
        }
        baseline += metrics.getHeight();
      }
    }

    /** Returns the link under {@code point} (editor content coordinates), or null. */
    @Nullable Link linkAt(Inlay<?> inlay, Point point) {
      Rectangle bounds = inlay.getBounds();
      if (bounds == null || !bounds.contains(point)) return null;

      FontMetrics metrics = metrics(inlay.getEditor());
      List<List<Run>> lines = layout(metrics);
      int textTop = bounds.y + MARGIN + PADDING;
      if (point.y < textTop) return null;
      int lineIndex = (point.y - textTop) / metrics.getHeight();
      if (lineIndex >= lines.size()) return null;

      int relX = point.x - (currentBoxX(inlay) + PADDING);
      for (Run run : lines.get(lineIndex)) {
        if (isClickable(run.link()) && relX >= run.x() && relX <= run.x() + run.width()) return run.link();
      }
      return null;
    }

    private boolean isClickable(@Nullable Link link) {
      if (link == null) return false;
      return link.nodeId() == null || ExplanationStore.getInstance(project).canResolve(link.nodeId());
    }

    /** X of the block's left edge on the line the inlay is currently attached to. */
    private int currentBoxX(Inlay<?> inlay) {
      Editor editor = inlay.getEditor();
      return boxX(editor, editor.getDocument().getLineNumber(inlay.getOffset()));
    }

    private int boxX(Editor editor, int line) {
      return editor.logicalPositionToXY(new LogicalPosition(line, leftColumn)).x;
    }

    private static FontMetrics metrics(Editor editor) {
      Font font = editor.getColorsScheme().getFont(EditorFontType.ITALIC);
      return editor.getContentComponent().getFontMetrics(font);
    }

    private static int textWidth(List<List<Run>> lines) {
      int width = 0;
      for (List<Run> line : lines) {
        if (!line.isEmpty()) {
          Run last = line.get(line.size() - 1);
          width = Math.max(width, last.x() + last.width());
        }
      }
      return width;
    }

    /**
     * Splits the text into paragraphs (the user's line breaks) of words. {@code [label](target)}
     * becomes the label's words tagged with the link, where target is {@code #id} or a range; any
     * other target is kept as plain text. Node ids are looked up when clicked, not here, so nodes
     * added to the store later still work.
     */
    private static List<List<Token>> tokenize(String text) {
      List<List<Token>> result = new ArrayList<>();
      for (String paragraph : text.split("\\R", -1)) {
        List<Token> tokens = new ArrayList<>();
        boolean[] pendingSpace = {false};
        Matcher m = LINK.matcher(paragraph);
        int pos = 0;
        while (m.find()) {
          Link link = parseLink(m.group(1).trim(), m.group(2).trim());
          if (link == null) continue; // left in the surrounding plain text
          addWords(tokens, paragraph.substring(pos, m.start()), null, pendingSpace);
          addWords(tokens, m.group(1), link, pendingSpace);
          pos = m.end();
        }
        addWords(tokens, paragraph.substring(pos), null, pendingSpace);
        result.add(tokens);
      }
      return result;
    }

    private static @Nullable Link parseLink(String label, String target) {
      if (target.startsWith("#") && target.length() > 1) return new Link(label, target.substring(1), null);
      CodeRange range = CodeRange.parse(target);
      return range == null ? null : new Link(label, null, range);
    }

    /** Appends the words of {@code text}; pendingSpace carries "whitespace seen" across calls. */
    private static void addWords(List<Token> tokens, String text, @Nullable Link link, boolean[] pendingSpace) {
      StringBuilder word = new StringBuilder();
      for (int i = 0; i <= text.length(); i++) {
        boolean end = i == text.length();
        if (end || Character.isWhitespace(text.charAt(i))) {
          if (!word.isEmpty()) {
            tokens.add(new Token(word.toString(), link, pendingSpace[0]));
            word.setLength(0);
            pendingSpace[0] = false;
          }
          if (!end) pendingSpace[0] = true;
        } else {
          word.append(text.charAt(i));
        }
      }
    }

    /** Word-wraps the paragraphs to MAX_TEXT_WIDTH, merging adjacent words with the same link into one run. */
    private List<List<Run>> layout(FontMetrics metrics) {
      int spaceWidth = metrics.charWidth(' ');
      List<List<Run>> lines = new ArrayList<>();
      for (List<Token> paragraph : paragraphs) {
        List<Run> line = new ArrayList<>();
        int x = 0;
        for (Token token : paragraph) {
          int gap = token.spaceBefore() && x > 0 ? spaceWidth : 0;
          int width = metrics.stringWidth(token.text());
          if (x > 0 && x + gap + width > MAX_TEXT_WIDTH) {
            lines.add(line);
            line = new ArrayList<>();
            x = 0;
            gap = 0;
          }
          Run last = line.isEmpty() ? null : line.get(line.size() - 1);
          if (last != null && last.link() == token.link()) {
            String merged = last.text() + (gap > 0 ? " " : "") + token.text();
            Run run = new Run(merged, last.link(), last.x(), metrics.stringWidth(merged));
            line.set(line.size() - 1, run);
            x = run.x() + run.width();
          } else {
            line.add(new Run(token.text(), token.link(), x + gap, width));
            x += gap + width;
          }
        }
        lines.add(line);
      }
      return lines;
    }
  }

  /** Makes links in an explanation clickable, and shows a hand cursor over them. */
  private static final class LinkMouseHandler implements EditorMouseListener, EditorMouseMotionListener {
    private final Project project;
    private final String path;
    private final Inlay<ExplanationRenderer> inlay;
    private final int depth;
    private final ExplanationNode node;

    LinkMouseHandler(Project project, String path, Inlay<ExplanationRenderer> inlay, int depth, ExplanationNode node) {
      this.project = project;
      this.path = path;
      this.inlay = inlay;
      this.depth = depth;
      this.node = node;
    }

    @Override
    public void mouseClicked(@NotNull EditorMouseEvent event) {
      Link link = linkAt(event);
      if (link == null) return;
      event.consume();
      ExplanationStore store = ExplanationStore.getInstance(project);
      int navigation = store.nextNavigation();

      if (link.range() != null) {
        CodeRange range = link.range();
        String targetPath = range.path() == null ? path : store.resolvePath(range.path());
        showLevel(project, targetPath, depth + 1, range, null);
        return;
      }

      CompletableFuture<ExplanationNode> future =
          store.resolve(new ExplanationRequest(link.nodeId(), link.label(), node, depth + 1));
      if (!future.isDone()) setStatus("Generating explanation for \"" + link.label() + "\"…");
      future.whenComplete((child, error) -> ApplicationManager.getApplication().invokeLater(() -> {
        if (project.isDisposed()) return;
        setStatus("");
        // Ignore the result if the user has clicked something else since.
        if (store.currentNavigation() != navigation) return;
        if (error != null) {
          Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
          Messages.showErrorDialog(project,
              "Could not get the explanation for \"" + link.label() + "\": " + cause.getMessage(), TITLE);
          return;
        }
        showNode(project, child, path, depth + 1);
      }));
    }

    private void setStatus(String text) {
      StatusBar statusBar = WindowManager.getInstance().getStatusBar(project);
      if (statusBar != null) statusBar.setInfo(text);
    }

    @Override
    public void mouseMoved(@NotNull EditorMouseEvent event) {
      setCursor(linkAt(event) != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null);
    }

    void resetCursor() {
      setCursor(null);
    }

    private void setCursor(@Nullable Cursor cursor) {
      if (inlay.getEditor() instanceof EditorEx editorEx) editorEx.setCustomCursor(this, cursor);
    }

    private @Nullable Link linkAt(EditorMouseEvent event) {
      if (!inlay.isValid()) return null;
      return inlay.getRenderer().linkAt(inlay, event.getMouseEvent().getPoint());
    }
  }

  private static int lineLength(Document document, int line) {
    return document.getLineEndOffset(line - 1) - document.getLineStartOffset(line - 1);
  }

  private static String validateLine(String which, int line, int lineCount) {
    if (line < 1 || line > lineCount) {
      return which + " line " + line + " is out of range. File has " + lineCount + " line(s) (1.." + lineCount + ").";
    }
    return null;
  }

  /** Columns run from 1 to (line length + 1), the same positions the editor caret can take. */
  private static String validateColumn(Document document, String which, int line, int column) {
    int lineLength = lineLength(document, line);
    if (column < 1 || column > lineLength + 1) {
      return which + " column " + column + " is out of range for line " + line + " (1.." + (lineLength + 1) + ").";
    }
    return null;
  }
}
