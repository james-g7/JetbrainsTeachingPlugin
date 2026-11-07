package explain;

import com.intellij.openapi.project.Project;

import java.util.concurrent.CompletableFuture;

/**
 * Generates explanation nodes on demand. Register one with {@link ExplanationStore#setProvider}.
 *
 * <p>Methods are called on the EDT, so return quickly and do slow work (e.g. calling a model)
 * asynchronously. Complete the future exceptionally to show the user an error.
 * {@link ExplanationNode#fromJson} turns a model's JSON reply into a node.
 */
public interface ExplanationProvider {
  /**
   * Generates the node for a link to an id that isn't in the store. The result is stored under
   * {@link ExplanationRequest#id()}, whatever id it carries, so later clicks reuse it. Its text
   * can link to further ids, which are generated the same way.
   */
  CompletableFuture<ExplanationNode> explain(Project project, ExplanationRequest request);

  /** Whether to offer an "Ask a question…" button on every box. */
  default boolean canAnswerQuestions() {
    return false;
  }

  /**
   * Answers a learner's free-text question about {@code node}. The answer is stored under its
   * own id and shown one level below {@code node}; give it a unique id (e.g. node id + counter).
   */
  default CompletableFuture<ExplanationNode> answerQuestion(Project project, ExplanationNode node, String question) {
    return CompletableFuture.failedFuture(new UnsupportedOperationException("This provider doesn't answer questions"));
  }
}
