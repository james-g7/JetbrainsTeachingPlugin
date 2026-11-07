package com.github.jamesg7.hacktheburghxiii.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;
import com.github.jamesg7.hacktheburghxiii.util.HighlightUtil;

/**
 * Action to highlight a range of text in a file.
 */
public class HighlightAction extends AnAction {

  /**
   * Handles the action event triggered by the user to highlight a specific range of text in a file.
   * Prompts the user for required inputs, such as file path and range coordinates, and performs
   * the highlight operation if all inputs are valid.
   *
   * @param anActionEvent the event that triggered this action; provides context such as the current
   *                      project and presentation settings for the action
   */
  @Override
  public void actionPerformed(@NotNull AnActionEvent anActionEvent) {
    Project project = anActionEvent.getProject();
    if (project == null) return;

    String relativePath = Messages.showInputDialog(
        project,
        "Enter file path (relative to project root):",
        "Highlight Range",
        Messages.getQuestionIcon(),
        "main.java",
        null
    );
    if (relativePath == null || relativePath.isBlank()) return;
    String path = project.getBasePath() + "/" + relativePath;

    Integer startLine = promptForInt(project, "Enter start line:", "1");
    if (startLine == null) return;
    Integer startColumn = promptForInt(project, "Enter start column:", "1");
    if (startColumn == null) return;
    Integer endLine = promptForInt(project, "Enter end line:", "1");
    if (endLine == null) return;
    Integer endColumn = promptForInt(project, "Enter end column:", "1");
    if (endColumn == null) return;

    highlight(project, path, startLine, startColumn, endLine, endColumn);
  }

  /**
   * Called once the user has entered a valid file path and range. Subclasses can override this to
   * collect more input before highlighting.
   */
  protected void highlight(
      Project project, String path,
      int startLine, int startColumn,
      int endLine, int endColumn
  ) {
    HighlightUtil.highlightRange(project, path, startLine, startColumn, endLine, endColumn);
  }

  /**
   * Prompts the user for an integer input via a dialog box. Repeats the prompt until a valid integer
   * is entered or the dialog is cancelled.
   *
   * @param project the current project context for displaying dialogs
   * @param message the message to display in the input dialog
   * @param initialValue the initial value to pre-fill in the input field
   * @return the integer entered by the user, or {@code null} if the dialog was cancelled
   */
  private Integer promptForInt(Project project, String message, String initialValue) {
    while (true) {
      String input = Messages.showInputDialog(
          project,
          message,
          "Highlight Range",
          Messages.getQuestionIcon(),
          initialValue,
          null
      );
      if (input == null) return null;
      try {
        return Integer.parseInt(input.trim());
      } catch (NumberFormatException e) {
        Messages.showErrorDialog(project, "Please enter a valid integer.", "Invalid Input");
      }
    }
  }
}
