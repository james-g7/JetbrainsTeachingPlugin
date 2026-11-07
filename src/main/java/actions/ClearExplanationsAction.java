package actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import explain.ExplanationStore;
import org.jetbrains.annotations.NotNull;

/** Removes every explanation highlight and box from the editors (stored nodes are kept). */
public class ClearExplanationsAction extends AnAction {
  @Override
  public void actionPerformed(@NotNull AnActionEvent event) {
    Project project = event.getProject();
    if (project != null) ExplanationStore.getInstance(project).hide();
  }
}
