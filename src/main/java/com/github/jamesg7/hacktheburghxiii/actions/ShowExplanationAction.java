package com.github.jamesg7.hacktheburghxiii.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.github.jamesg7.hacktheburghxiii.explain.ExplanationStore;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Loads an explanation tree from a JSON file into the project's {@link ExplanationStore} and shows
 * its root. See docs/EXPLANATIONS.md for the format.
 */
public class ShowExplanationAction extends AnAction {
  private static final String TITLE = "Show Explanation Tree";

  @Override
  public void actionPerformed(@NotNull AnActionEvent event) {
    Project project = event.getProject();
    if (project == null) return;

    String path = Messages.showInputDialog(
        project,
        "Explanation JSON file (relative to project root, or absolute):",
        TITLE,
        Messages.getQuestionIcon(),
        ".explanations/explanation.json",
        null
    );
    if (path == null || path.isBlank()) return;

    ExplanationStore store = ExplanationStore.getInstance(project);
    try {
      String root = store.loadFile(path.trim());
      if (root == null) {
        Messages.showErrorDialog(project, "The file has no nodes.", TITLE);
        return;
      }
      store.show(root);
    } catch (IOException e) {
      Messages.showErrorDialog(project, "Could not read " + path + ": " + e.getMessage(), TITLE);
    } catch (IllegalArgumentException e) {
      Messages.showErrorDialog(project, e.getMessage(), TITLE);
    }
  }
}
