package walkthrough;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * The AI that writes walkthroughs. Register with {@link WalkthroughService#setProvider}; it's used
 * by Tools → Walk Through Function… Called on the EDT, so do the model call asynchronously.
 * Typically: send {@link Request} plus docs/WALKTHROUGH.md as instructions, and turn the reply into
 * a walkthrough with {@link Walkthrough#fromJson} (its error messages can go back to the model).
 */
@FunctionalInterface
public interface WalkthroughProvider {
  CompletableFuture<Walkthrough> generate(Project project, Request request);

  /**
   * What the learner asked for.
   *
   * @param file      project-relative path of the file
   * @param language  the file's language, e.g. "JAVA", "Python"
   * @param code      the file's full text, for reference; line numbers in the walkthrough are 1-based lines of this
   * @param function  the function to walk through (guessed from the caret; the learner can edit it)
   * @param inputs    the learner's inputs, free text, e.g. "n = 3" or "[3, 1, 2]"
   * @param detail    how detailed to be
   * @param selection the selected text, if any, e.g. to walk through just a block
   */
  record Request(String file, String language, String code, String function, String inputs, Detail detail,
                 @Nullable String selection) {}

  /** How much detail to go into, for the learner's level. */
  enum Detail {
    BEGINNER("Beginner: every line, stepping into each function call, explaining each change"),
    INTERMEDIATE("Intermediate: the function's own lines; calls it makes as one step each"),
    ADVANCED("Advanced: function by function, only calls and what they return");

    private final String description;

    Detail(String description) {
      this.description = description;
    }

    /** Text to put in the prompt. */
    public String description() {
      return description;
    }

    @Override
    public String toString() {
      return description.substring(0, description.indexOf(':'));
    }
  }
}
