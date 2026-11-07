package explain;

import com.intellij.openapi.project.Project;

import java.util.concurrent.CompletableFuture;

/**
 * Generates explanation nodes on demand, when the user clicks a link to an id that isn't in the
 * {@link ExplanationStore}. Register one with {@link ExplanationStore#setProvider}.
 *
 * <p>Called on the EDT, so return quickly and do slow work (e.g. calling a model) asynchronously.
 * The returned node is stored under {@link ExplanationRequest#id()}, whatever id it carries, so
 * later clicks reuse it. The node's text can link to further ids, which are generated the same way.
 * Complete the future exceptionally to show the user an error.
 */
@FunctionalInterface
public interface ExplanationProvider {
  CompletableFuture<ExplanationNode> explain(Project project, ExplanationRequest request);
}
