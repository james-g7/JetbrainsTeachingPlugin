package com.github.jamesg7.hacktheburghxiii.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;
import com.github.jamesg7.hacktheburghxiii.walkthrough.WalkthroughService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Plays a walkthrough JSON file (see docs/WALKTHROUGH.md) in the editor. */
public class ShowWalkthroughAction extends AnAction {
  private static final String TITLE = "Show Walkthrough";

  @Override
  public void actionPerformed(@NotNull AnActionEvent event) {
    Project project = event.getProject();
    if (project == null) return;
    String input = Messages.showInputDialog(project, "Walkthrough JSON file (relative to project root, or absolute):",
        TITLE, Messages.getQuestionIcon(), ".walkthrough/walkthrough.json", null);
    if (input == null || input.isBlank()) return;

    Path path = Path.of(input.trim());
    if (!path.isAbsolute()) path = Path.of(project.getBasePath()).resolve(path);
    try {
      WalkthroughService.getInstance(project).showJson(Files.readString(path, StandardCharsets.UTF_8));
    } catch (IOException e) {
      Messages.showErrorDialog(project, "Could not read " + path + ": " + e.getMessage(), TITLE);
    } catch (IllegalArgumentException e) {
      Messages.showErrorDialog(project, e.getMessage(), TITLE);
    }
  }
}
