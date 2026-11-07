package actions;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import util.HighlightUtil;

/**
 * Action that highlights a range of code and shows an explanation box directly above it.
 */
public class ExplainHighlightAction extends HighlightAction {

  @Override
  protected void highlight(
      Project project, String path,
      int startLine, int startColumn,
      int endLine, int endColumn
  ) {
    String explanation = Messages.showMultilineInputDialog(
        project,
        "Explain what the highlighted code does.\n"
            + "Link to code with [label](range), where range is 12, 12-15, 12:5-15:20,\n"
            + "or path/relative/to/project@12-15 for another file.\n"
            + "Link to a node in the explanation store with [label](#id).",
        "Highlight with Explanation",
        "",
        Messages.getQuestionIcon(),
        null
    );
    if (explanation == null) return;

    HighlightUtil.highlightRange(project, path, startLine, startColumn, endLine, endColumn, explanation);
  }
}
