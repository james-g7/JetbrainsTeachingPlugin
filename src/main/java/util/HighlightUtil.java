package util;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.markup.CustomHighlighterOrder;
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Point;

public class HighlightUtil {
  private static final String TITLE = "Highlight Range";

  /** Marks highlighters created by this util so a new highlight replaces the previous one. */
  private static final Key<Boolean> OUR_HIGHLIGHT = Key.create("hacktheburghxiii.highlight");

  private static final Color BLOCK_COLOR = new JBColor(Color.YELLOW, new Color(0x6B5B00));

  public static void highlightRange(
      Project project, String path,
      int startLine, int startColumn,
      int endLine, int endColumn
  ) {
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

      Document document = editor.getDocument();
      CharSequence text = document.getCharsSequence();
      int lineCount = document.getLineCount();

      // Inputs are 1-based, matching the editor's status bar. End column is inclusive.
      String error = validateLine("Start", startLine, lineCount);
      if (error == null) error = validateLine("End", endLine, lineCount);
      if (error == null) error = validateColumn(document, "Start", startLine, startColumn);
      if (error == null) error = validateColumn(document, "End", endLine, endColumn);
      if (error == null && (endLine < startLine || (endLine == startLine && endColumn < startColumn))) {
        error = "End position must not be before start position.";
      }
      if (error != null) {
        Messages.showErrorDialog(project, error, TITLE);
        return;
      }

      MarkupModel markupModel = editor.getMarkupModel();
      for (RangeHighlighter existing : markupModel.getAllHighlighters()) {
        if (Boolean.TRUE.equals(existing.getUserData(OUR_HIGHLIGHT))) {
          markupModel.removeHighlighter(existing);
        }
      }

      // Highlight the range as a rectangular block. Its left edge is the leftmost text
      // column and its right edge is the end of the longest line, ignoring indentation
      // and trailing whitespace. Blank lines inside the block are covered too.
      int firstHighlighted = -1;
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
        if (firstHighlighted < 0) firstHighlighted = from;
      }

      if (firstHighlighted < 0) {
        Messages.showErrorDialog(project, "The selected range contains only whitespace.", TITLE);
        return;
      }

      // One highlighter spanning the block's lines; the renderer paints the rectangle,
      // which can extend past the end of shorter lines and across blank ones.
      RangeHighlighter highlighter = markupModel.addRangeHighlighter(
          document.getLineStartOffset(firstLine), document.getLineEndOffset(lastLine),
          HighlighterLayer.SELECTION - 1, null, HighlighterTargetArea.LINES_IN_RANGE);
      highlighter.putUserData(OUR_HIGHLIGHT, Boolean.TRUE);
      highlighter.setCustomRenderer(new BlockRenderer(leftColumn, rightColumn));

      editor.getCaretModel().moveToOffset(firstHighlighted);
      editor.getScrollingModel().scrollTo(editor.offsetToLogicalPosition(firstHighlighted), ScrollType.CENTER);
    });
  }

  /** Paints a filled rectangle from leftColumn to rightColumn over every line of the highlighter. */
  private static final class BlockRenderer implements CustomHighlighterRenderer {
    private final int leftColumn;
    private final int rightColumn;

    BlockRenderer(int leftColumn, int rightColumn) {
      this.leftColumn = leftColumn;
      this.rightColumn = rightColumn;
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

      g.setColor(BLOCK_COLOR);
      g.fillRect(topLeft.x, topLeft.y, bottomRight.x - topLeft.x, bottom - topLeft.y);
    }

    @Override
    public @NotNull CustomHighlighterOrder getOrder() {
      return CustomHighlighterOrder.AFTER_BACKGROUND; // draw beneath the text
    }
  }

  private static String validateLine(String which, int line, int lineCount) {
    if (line < 1 || line > lineCount) {
      return which + " line " + line + " is out of range. File has " + lineCount + " line(s) (1.." + lineCount + ").";
    }
    return null;
  }

  /** Columns run from 1 to (line length + 1), the same positions the editor caret can take. */
  private static String validateColumn(Document document, String which, int line, int column) {
    int lineLength = document.getLineEndOffset(line - 1) - document.getLineStartOffset(line - 1);
    if (column < 1 || column > lineLength + 1) {
      return which + " column " + column + " is out of range for line " + line + " (1.." + (lineLength + 1) + ").";
    }
    return null;
  }
}
