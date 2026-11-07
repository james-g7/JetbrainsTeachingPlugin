package actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import walkthrough.WalkthroughProvider;
import walkthrough.WalkthroughService;

import javax.swing.JComponent;

/**
 * Asks the AI for a walkthrough of the function at the caret, with inputs and a level of detail
 * the learner chooses, then plays it in the editor. Works for any language.
 */
public class WalkThroughFunctionAction extends AnAction {
  private static final Key<String> LAST_INPUTS = Key.create("walkthrough.lastInputs");
  private static final Key<WalkthroughProvider.Detail> LAST_DETAIL = Key.create("walkthrough.lastDetail");

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    e.getPresentation().setEnabledAndVisible(e.getProject() != null && e.getData(CommonDataKeys.EDITOR) != null
        && e.getData(CommonDataKeys.PSI_FILE) != null);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
    if (project == null || editor == null || file == null || file.getVirtualFile() == null) return;

    String selection = editor.getSelectionModel().getSelectedText();
    Dialog dialog = new Dialog(project, guessFunction(file, editor.getCaretModel().getOffset()),
        project.getUserData(LAST_INPUTS), project.getUserData(LAST_DETAIL));
    if (!dialog.showAndGet()) return;
    project.putUserData(LAST_INPUTS, dialog.inputs.getText());
    project.putUserData(LAST_DETAIL, dialog.detail());

    VirtualFile vfile = file.getVirtualFile();
    VirtualFile base = ProjectUtil.guessProjectDir(project);
    String path = base != null && VfsUtilCore.isAncestor(base, vfile, false) ? VfsUtilCore.getRelativePath(vfile, base) : vfile.getPath();
    WalkthroughService.getInstance(project).generate(new WalkthroughProvider.Request(
        path, file.getLanguage().getID(), editor.getDocument().getText(),
        dialog.function.getText().trim(), dialog.inputs.getText().trim(), dialog.detail(),
        selection == null || selection.isBlank() ? null : selection));
  }

  /**
   * The name of the innermost named element around the caret that looks like a function. Works with
   * any language's PSI; it's only a starting value the learner can edit.
   */
  private static String guessFunction(PsiFile file, int offset) {
    String fallback = "";
    for (PsiElement e = file.findElementAt(offset); e != null && e != file; e = e.getParent()) {
      if (e instanceof PsiNameIdentifierOwner owner && owner.getName() != null) {
        String type = e.getClass().getSimpleName().toLowerCase();
        if (type.contains("method") || type.contains("function") || type.contains("fun")) return owner.getName();
        if (fallback.isEmpty() && !type.contains("variable") && !type.contains("parameter")) fallback = owner.getName();
      }
    }
    return fallback;
  }

  private static final class Dialog extends DialogWrapper {
    final JBTextField function;
    final JBTextField inputs;
    private final ComboBox<WalkthroughProvider.Detail> detail = new ComboBox<>(WalkthroughProvider.Detail.values());

    Dialog(Project project, String function, @Nullable String inputs, @Nullable WalkthroughProvider.Detail detail) {
      super(project);
      this.function = new JBTextField(function, 28);
      this.inputs = new JBTextField(inputs == null ? "" : inputs, 28);
      this.detail.setSelectedItem(detail == null ? WalkthroughProvider.Detail.BEGINNER : detail);
      setTitle("Walk Through Function");
      setOKButtonText("Generate");
      init();
    }

    @Override
    protected JComponent createCenterPanel() {
      JBLabel hint = new JBLabel("Inputs are free text for the AI, e.g. \"n = 3\" or \"[3, 1, 2]\". Leave empty if there are none.");
      hint.setComponentStyle(UIUtil.ComponentStyle.SMALL);
      hint.setFontColor(UIUtil.FontColor.BRIGHTER);
      return FormBuilder.createFormBuilder()
          .addLabeledComponent("Function:", function)
          .addLabeledComponent("Inputs:", inputs)
          .addLabeledComponent("Level:", detail)
          .addComponent(hint)
          .getPanel();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
      return function.getText().isBlank() ? function : inputs;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
      return function.getText().isBlank() ? new ValidationInfo("Enter the function to walk through", function) : null;
    }

    WalkthroughProvider.Detail detail() {
      return (WalkthroughProvider.Detail) detail.getSelectedItem();
    }
  }
}
