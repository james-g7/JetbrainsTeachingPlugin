package explain;

/**
 * Notified of what the learner does with explanations, e.g. so a teaching agent can track what
 * they've explored and adapt. Register with {@link ExplanationStore#addListener}. All methods are
 * called on the EDT and default to doing nothing.
 */
public interface ExplanationListener {
  /** A node's highlight (and box, if it has content) was shown at this depth of the chain. */
  default void nodeShown(ExplanationNode node, int depth) {}

  /** A link in {@code from}'s box was clicked; {@code href} is e.g. "#loop", "12-15" or a URL. */
  default void linkClicked(ExplanationNode from, String href) {}

  /** Hint number {@code index} (0-based) of {@code node} was revealed. */
  default void hintRevealed(ExplanationNode node, int index) {}

  default void answerRevealed(ExplanationNode node) {}

  /** The learner closed {@code node}'s box (which also closes everything deeper). */
  default void closed(ExplanationNode node) {}

  /** The learner asked a question about {@code node}; the provider's answer is shown when ready. */
  default void questionAsked(ExplanationNode node, String question) {}
}
