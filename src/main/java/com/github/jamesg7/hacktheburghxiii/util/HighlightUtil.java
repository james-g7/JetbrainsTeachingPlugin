package com.github.jamesg7.hacktheburghxiii.util;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager;
import com.intellij.openapi.editor.markup.CustomHighlighterOrder;
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileDocumentManager;
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
import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.ui.JBUI;
import com.github.jamesg7.hacktheburghxiii.explain.CodeRange;
import com.github.jamesg7.hacktheburghxiii.explain.ColorSpec;
import com.github.jamesg7.hacktheburghxiii.explain.ExplanationNode;
import com.github.jamesg7.hacktheburghxiii.explain.ExplanationRequest;
import com.github.jamesg7.hacktheburghxiii.explain.ExplanationStore;
import com.github.jamesg7.hacktheburghxiii.explain.HighlightMode;
import com.github.jamesg7.hacktheburghxiii.explain.NodeStyle;
import com.github.jamesg7.hacktheburghxiii.explain.Theme;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Highlights code with an optional explanation box above it. Explanation links to {@code #id}
 * show nodes from the {@link ExplanationStore}, which can link on further.
 *
 * <p>Each step of the chain is a "level": level 0 is the first node shown, and following a link in
 * level N's box shows level N+1, replacing whatever was at N+1 and deeper. "Next"/"Back" in a
 * guided tour replace the current level. Levels are tracked per project, so a chain can span files.
 */
public class HighlightUtil {
  private static final String TITLE = "Highlight Range";

  /** The levels currently shown in a project, indexed by depth. Only touched on the EDT. */
  private static final Key<List<Level>> LEVELS = Key.create("hacktheburghxiii.levels");

  private static final Color LINK_TEXT = JBUI.CurrentTheme.Link.Foreground.ENABLED;
  private static final Color DEAD_LINK_TEXT = new JBColor(new Color(0x9CA3AF), new Color(0x6B7280));

  public static void highlightRange(
      Project project, String path,
      int startLine, int startColumn,
      int endLine, int endColumn
  ) {
    highlightRange(project, path, startLine, startColumn, endLine, endColumn, null);
  }

  /**
   * Highlights the range and, when {@code explanation} (Markdown) is non-blank, shows it in a box
   * directly above the highlighted block. Replaces anything previously shown.
   */
  public static void highlightRange(
      Project project, String path,
      int startLine, int startColumn,
      int endLine, int endColumn,
      String explanation
  ) {
    String range = startLine + ":" + startColumn + "-" + endLine + ":" + endColumn;
    ExplanationNode node = ExplanationNode.of("highlight", range, explanation);
    ExplanationStore.getInstance(project).nextNavigation();
    showLevel(project, path, 0, node.codeRange(), node, null);
  }

  /**
   * Shows {@code node} at {@code depth} of the chain, replacing that level and everything deeper.
   * Its file is the range's {@code path@} prefix, else {@link ExplanationNode#file()}, else
   * {@code fallbackPath} (absolute; the file of the node that linked here).
   */
  public static void showNode(Project project, ExplanationNode node, @Nullable String fallbackPath, int depth) {
    showNode(project, node, fallbackPath, depth, null);
  }

  /** As above; {@code source} is the href of the link that opened this level, if any (see {@link Level}). */
  private static void showNode(Project project, ExplanationNode node, @Nullable String fallbackPath, int depth,
                               @Nullable String source) {
    ExplanationStore store = ExplanationStore.getInstance(project);
    CodeRange range = node.codeRange();
    String file = range.path() != null ? range.path() : node.file();
    String path = file != null ? store.resolvePath(file) : fallbackPath;
    if (path == null) {
      ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(project,
          "Explanation '" + node.id() + "' has no file. Set \"file\" on the node or the tree.", TITLE));
      return;
    }
    showLevel(project, path, depth, range, node, source);
  }

  /** Removes every level from every editor. */
  public static void clearAll(Project project) {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (!project.isDisposed()) clearFrom(project, 0);
    });
  }

  /**
   * Shows the level at {@code depth}: replaces it and every deeper level with a highlight of
   * {@code range} in {@code path}, plus an explanation box when the node has content.
   */
  private static void showLevel(Project project, String path, int depth, CodeRange range,
                                @Nullable ExplanationNode node, @Nullable String source) {
    withEditor(project, path, editor -> {
      Block block = computeBlock(editor, range, error -> Messages.showErrorDialog(project, error, TITLE));
      if (block == null) return;

      List<Level> levels = levels(project);
      if (levels.size() < depth) return; // the level holding the clicked link has been cleared
      clearFrom(project, depth);

      ExplanationStore store = ExplanationStore.getInstance(project);
      Theme theme = store.theme();
      NodeStyle style = node == null ? NodeStyle.DEFAULT : node.style();
      Color highlight = highlightColor(node, depth, theme);
      HighlightMode mode = style.highlightMode() != null ? style.highlightMode() : theme.mode();

      List<PaintedBlock> blocks = new ArrayList<>();
      blocks.add(addBlock(editor, block, depth, highlight, mode));
      if (node != null) {
        // Extra ranges are outlined in the same colour, so they read as "related" rather than "the subject".
        for (String also : node.also()) {
          Block extra = computeBlock(editor, CodeRange.parse(also), error -> {});
          if (extra != null) blocks.add(addBlock(editor, extra, depth, highlight, HighlightMode.OUTLINE));
        }
      }

      Inlay<?> inlay = null;
      if (node != null && node.hasBoxContent()) {
        inlay = addBox(project, path, editor, block, depth, node, boxColors(editor, style, highlight), theme);
      }
      levels.add(new Level(editor, node, source, blocks, inlay));

      moveTo(editor, block);
      if (node != null) store.fire(l -> l.nodeShown(node, depth));
    });
  }

  private static @Nullable Inlay<?> addBox(
      Project project, String path, Editor editor, Block block, int depth,
      ExplanationNode node, ExplanationBox.Colors colors, Theme theme
  ) {
    if (!(editor instanceof EditorEx editorEx)) return null;
    ExplanationStore store = ExplanationStore.getInstance(project);
    ExplanationNode previous = store.findPrevious(node.id());
    // Every box opened from another box gets a link back to it, so the learner is never stranded.
    // The levels above this one are already in place (this level is added after the box is built).
    List<Level> levels = levels(project);
    Level parent = depth > 0 && levels.size() >= depth ? levels.get(depth - 1) : null;

    ExplanationBox.Options options = new ExplanationBox.Options(
        previous != null,
        node.next() != null && store.canResolve(node.next()),
        store.canAnswerQuestions(),
        parent == null ? null : upLabel(parent.node()),
        theme.maxWidth(),
        theme.fontSize(),
        editor.getColorsScheme().getEditorFontName(),
        href -> isLinkLive(store, href));

    // Indent the box to line up with the highlighted block's left edge.
    int indent = editor.logicalPositionToXY(new LogicalPosition(block.firstLine, block.leftColumn)).x
        - editor.logicalPositionToXY(new LogicalPosition(block.firstLine, 0)).x;

    ExplanationBox box = new ExplanationBox(node, colors, options, Math.max(0, indent), new ExplanationBox.Actions() {
      @Override
      public void link(String href, String label) {
        followLink(project, path, depth, node, href, label);
      }

      @Override
      public void next() {
        String next = node.next();
        if (next == null) return;
        int navigation = store.nextNavigation();
        showWhenReady(project, store.resolve(new ExplanationRequest(next, "Next", node, depth)),
            "the next step", navigation, path, depth, null);
      }

      @Override
      public void back() {
        ExplanationNode back = store.findPrevious(node.id());
        if (back == null) return;
        store.nextNavigation();
        showNode(project, back, path, depth);
      }

      @Override
      public void up() {
        List<Level> current = levels(project);
        if (depth == 0 || current.size() < depth) return; // the parent has been closed already
        Level target = current.get(depth - 1);
        store.nextNavigation();
        clearFrom(project, depth);
        store.fire(l -> l.closed(node));
        reveal(project, target);
      }

      @Override
      public void close() {
        store.nextNavigation();
        clearFrom(project, depth);
        store.fire(l -> l.closed(node));
      }

      @Override
      public void ask() {
        String question = Messages.showInputDialog(project, "What would you like to know about this code?",
            "Ask a Question", Messages.getQuestionIcon());
        if (question == null || question.isBlank()) return;
        int navigation = store.nextNavigation();
        showWhenReady(project, store.ask(node, question.trim()), "your question", navigation, path, depth + 1, null);
      }

      @Override
      public void hintRevealed(int index) {
        store.fire(l -> l.hintRevealed(node, index));
      }

      @Override
      public void answerRevealed() {
        store.fire(l -> l.answerRevealed(node));
      }
    });

    Inlay<?> inlay = EditorEmbeddedComponentManager.getInstance().addComponent(editorEx, box,
        new EditorEmbeddedComponentManager.Properties(EditorEmbeddedComponentManager.ResizePolicy.none(), null,
            false, true, 0, editor.getDocument().getLineStartOffset(block.firstLine)));
    box.setInlay(inlay);
    return inlay;
  }

  /** The text of the link back to a parent box, e.g. "↑ Back to The main method". */
  private static String upLabel(@Nullable ExplanationNode parent) {
    String title = parent == null ? null : parent.title();
    if (title == null) return "↑ Back up";
    if (title.length() > 40) title = title.substring(0, 39).trim() + "…";
    return "↑ Back to " + title;
  }

  /** Brings a shown level back into view: opens its editor's file and scrolls to its block. */
  private static void reveal(Project project, Level level) {
    Editor editor = level.editor();
    if (editor.isDisposed() || level.blocks().isEmpty()) return;
    RangeMarker marker = level.blocks().get(0).marker();
    if (!marker.isValid()) return;
    VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
    if (file != null) FileEditorManager.getInstance(project).openFile(file, true);
    int offset = marker.getStartOffset();
    editor.getCaretModel().moveToOffset(offset);
    editor.getScrollingModel().scrollTo(editor.offsetToLogicalPosition(offset), ScrollType.CENTER);
  }

  private static boolean isLinkLive(ExplanationStore store, String href) {
    if (href.startsWith("#")) return href.length() > 1 && store.canResolve(href.substring(1));
    return isExternal(href) || CodeRange.parse(href) != null;
  }

  private static boolean isExternal(String href) {
    return href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:");
  }

  private static void followLink(Project project, String path, int depth, ExplanationNode from, String href, String label) {
    if (isExternal(href)) {
      BrowserUtil.browse(href);
      return;
    }
    ExplanationStore store = ExplanationStore.getInstance(project);
    store.fire(l -> l.linkClicked(from, href));
    int navigation = store.nextNavigation();

    // Clicking the link that opened the level below again hides it (and everything deeper).
    List<Level> levels = levels(project);
    if (levels.size() > depth + 1 && href.equals(levels.get(depth + 1).source())) {
      ExplanationNode hidden = levels.get(depth + 1).node();
      clearFrom(project, depth + 1);
      if (hidden != null) store.fire(l -> l.closed(hidden));
      return;
    }

    if (href.startsWith("#")) {
      String id = href.substring(1);
      showWhenReady(project, store.resolve(new ExplanationRequest(id, label, from, depth + 1)),
          "\"" + label + "\"", navigation, path, depth + 1, href);
      return;
    }
    CodeRange range = CodeRange.parse(href);
    if (range != null) {
      String targetPath = range.path() == null ? path : store.resolvePath(range.path());
      showLevel(project, targetPath, depth + 1, range, null, href);
    }
  }

  /** Shows a node once it's available, unless the user has navigated elsewhere in the meantime. */
  private static void showWhenReady(Project project, CompletableFuture<ExplanationNode> future, String what,
                                    int navigation, String fallbackPath, int depth, @Nullable String source) {
    ExplanationStore store = ExplanationStore.getInstance(project);
    if (!future.isDone()) setStatus(project, "Generating an explanation for " + what + "…");
    future.whenComplete((node, error) -> ApplicationManager.getApplication().invokeLater(() -> {
      if (project.isDisposed()) return;
      setStatus(project, "");
      if (store.currentNavigation() != navigation) return;
      if (error != null) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        Messages.showErrorDialog(project, "Could not get an explanation for " + what + ": " + cause.getMessage(), TITLE);
        return;
      }
      showNode(project, node, fallbackPath, depth, source);
    }));
  }

  private static void setStatus(Project project, String text) {
    StatusBar statusBar = WindowManager.getInstance().getStatusBar(project);
    if (statusBar != null) statusBar.setInfo(text);
  }

  // ---- Colours ----

  /** Explicit style colour, else node colour shorthand (folded into style), else kind default, else theme palette. */
  private static Color highlightColor(@Nullable ExplanationNode node, int depth, Theme theme) {
    if (node != null) {
      if (node.style().highlight() != null) return node.style().highlight().toColor();
      if (node.kind().color() != null) return node.kind().color().toColor();
    }
    return theme.colorForDepth(depth).toColor();
  }

  /** Box colours: explicit style overrides, otherwise tints of the highlight colour over the editor background. */
  private static ExplanationBox.Colors boxColors(Editor editor, NodeStyle style, Color highlight) {
    EditorColorsScheme scheme = editor.getColorsScheme();
    Color background = scheme.getDefaultBackground();
    Color foreground = scheme.getDefaultForeground();
    Color solid = new Color(highlight.getRGB() & 0xFFFFFF);
    boolean dark = ColorUtil.isDark(background);

    Color box = resolve(style.background(), ColorUtil.mix(background, solid, dark ? 0.45 : 0.3));
    Color border = resolve(style.border(), ColorUtil.mix(solid, foreground, dark ? 0.15 : 0.35));
    Color text = resolve(style.text(), foreground);
    Color code = ColorUtil.mix(box, foreground, 0.08);
    return new ExplanationBox.Colors(box, border, text, LINK_TEXT, DEAD_LINK_TEXT, code);
  }

  private static Color resolve(@Nullable ColorSpec spec, Color fallback) {
    return spec == null ? fallback : spec.toColor();
  }

  // ---- Levels ----

  /**
   * One step of a highlight chain. {@code source} is the href of the link (in the level above's
   * box) that opened it, so clicking that link again can hide it; null if it was opened another
   * way (the first node, Next/Back, or a question's answer).
   */
  private record Level(Editor editor, @Nullable ExplanationNode node, @Nullable String source,
                       List<PaintedBlock> blocks, @Nullable Inlay<?> inlay) {
    void dispose() {
      if (inlay != null && inlay.isValid()) Disposer.dispose(inlay);
      for (PaintedBlock block : blocks) removeBlock(editor, block);
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

  // ---- Editors and blocks ----

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
   * Validates the 1-based range and computes the block covering it, or reports an error and
   * returns null. The end column is inclusive.
   */
  private static @Nullable Block computeBlock(Editor editor, @Nullable CodeRange range, Consumer<String> onError) {
    if (range == null) return null;
    int startLine = range.startLine(), startColumn = range.startColumn();
    int endLine = range.endLine(), endColumn = range.endColumn();
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
      onError.accept(error);
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
      onError.accept("The selected range contains only whitespace.");
      return null;
    }
    return new Block(firstLine, lastLine, leftColumn, rightColumn, firstOffset);
  }

  /**
   * Adds a block to the editor's {@link BlockPainter}. The painter draws all blocks in depth order,
   * so a deeper level is always on top: separate highlighters starting on the same line are
   * painted in no guaranteed order, which could hide a nested highlight under its parent's.
   */
  private static PaintedBlock addBlock(Editor editor, Block block, int depth, Color color, HighlightMode mode) {
    Document document = editor.getDocument();
    BlockPainter painter = editor.getUserData(PAINTER);
    if (painter == null || !painter.highlighter.isValid()) {
      // One highlighter over the whole document, greedy so it keeps covering text added at either end.
      RangeHighlighter highlighter = editor.getMarkupModel().addRangeHighlighter(
          0, document.getTextLength(), HighlighterLayer.SELECTION - 1, null, HighlighterTargetArea.LINES_IN_RANGE);
      highlighter.setGreedyToLeft(true);
      highlighter.setGreedyToRight(true);
      painter = new BlockPainter(highlighter);
      highlighter.setCustomRenderer(painter);
      editor.putUserData(PAINTER, painter);
    }
    // The marker tracks the block's lines through edits.
    RangeMarker marker = document.createRangeMarker(
        document.getLineStartOffset(block.firstLine), document.getLineEndOffset(block.lastLine));
    PaintedBlock painted = new PaintedBlock(marker, block.leftColumn, block.rightColumn, color, mode, depth);
    painter.blocks.add(painted);
    editor.getContentComponent().repaint();
    return painted;
  }

  private static void removeBlock(Editor editor, PaintedBlock block) {
    block.marker().dispose();
    if (editor.isDisposed()) return;
    BlockPainter painter = editor.getUserData(PAINTER);
    if (painter == null) return;
    painter.blocks.remove(block);
    if (painter.blocks.isEmpty()) {
      if (painter.highlighter.isValid()) editor.getMarkupModel().removeHighlighter(painter.highlighter);
      editor.putUserData(PAINTER, null);
    }
    editor.getContentComponent().repaint();
  }

  private static void moveTo(Editor editor, Block block) {
    editor.getCaretModel().moveToOffset(block.firstOffset);
    editor.getScrollingModel().scrollTo(editor.offsetToLogicalPosition(block.firstOffset), ScrollType.CENTER);
  }

  /** A highlighted block: lines tracked by {@code marker}, between two logical columns. */
  private record PaintedBlock(RangeMarker marker, int leftColumn, int rightColumn, Color color, HighlightMode mode, int depth) {}

  private static final Key<BlockPainter> PAINTER = Key.create("hacktheburghxiii.blockPainter");

  /** Paints every block in one editor, shallowest first, so deeper levels end up on top. */
  private static final class BlockPainter implements CustomHighlighterRenderer {
    final RangeHighlighter highlighter;
    final List<PaintedBlock> blocks = new ArrayList<>();

    BlockPainter(RangeHighlighter highlighter) {
      this.highlighter = highlighter;
    }

    @Override
    public void paint(@NotNull Editor editor, @NotNull RangeHighlighter highlighter, @NotNull Graphics g) {
      List<PaintedBlock> ordered = new ArrayList<>(blocks);
      ordered.sort(java.util.Comparator.comparingInt(PaintedBlock::depth)); // stable: same-depth keeps insertion order
      for (PaintedBlock block : ordered) paintBlock(editor, block, g);
    }

    private static void paintBlock(Editor editor, PaintedBlock block, Graphics g) {
      if (!block.marker().isValid()) return;
      Document document = editor.getDocument();
      // Line numbers are recomputed on each paint so the block follows edits above it.
      int firstLine = document.getLineNumber(block.marker().getStartOffset());
      int lastLine = document.getLineNumber(block.marker().getEndOffset());

      Point topLeft = editor.logicalPositionToXY(new LogicalPosition(firstLine, block.leftColumn()));
      Point bottomRight = editor.logicalPositionToXY(new LogicalPosition(lastLine, block.rightColumn()));
      int x = topLeft.x, y = topLeft.y;
      int width = bottomRight.x - topLeft.x;
      int height = bottomRight.y + editor.getLineHeight() - topLeft.y;

      Graphics2D g2 = (Graphics2D) g.create();
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(block.color());
        switch (block.mode()) {
          case FILL -> g2.fillRect(x, y, width, height);
          case OUTLINE -> {
            int pad = JBUIScale.scale(2);
            g2.setStroke(new BasicStroke(JBUIScale.scale(2f)));
            g2.drawRoundRect(x - pad, y, width + 2 * pad, height - 1, JBUIScale.scale(6), JBUIScale.scale(6));
          }
          case BAR -> {
            int barWidth = JBUIScale.scale(3);
            g2.fillRect(Math.max(0, x - JBUIScale.scale(6)), y, barWidth, height);
          }
        }
      } finally {
        g2.dispose();
      }
    }

    @Override
    public @NotNull CustomHighlighterOrder getOrder() {
      return CustomHighlighterOrder.AFTER_BACKGROUND; // draw beneath the text
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
