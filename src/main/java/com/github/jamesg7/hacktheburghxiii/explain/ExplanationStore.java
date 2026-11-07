package com.github.jamesg7.hacktheburghxiii.explain;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.github.jamesg7.hacktheburghxiii.util.HighlightUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Central, per-project storage of explanation nodes. An agent fills it once (as JSON or through
 * {@link #put}) and every link refers to nodes by id, so nothing is repeated. Links to ids that
 * aren't stored are generated on demand by the {@link ExplanationProvider}, if one is set.
 *
 * <pre>{@code
 * ExplanationStore store = ExplanationStore.getInstance(project);
 * store.showJson(json);                       // load a tree and show its root
 * store.setProvider(myAgent);                 // optional: generate deeper nodes and answer questions
 * store.addListener(myTracker, disposable);   // optional: see what the learner explores
 * }</pre>
 *
 * See docs/EXPLANATIONS.md for the JSON format.
 */
@Service(Service.Level.PROJECT)
public final class ExplanationStore {
  private final Project project;
  private final Map<String, ExplanationNode> nodes = new ConcurrentHashMap<>();
  private final Map<String, CompletableFuture<ExplanationNode>> pending = new ConcurrentHashMap<>();
  private final List<ExplanationListener> listeners = new CopyOnWriteArrayList<>();
  private final AtomicInteger navigation = new AtomicInteger();
  private volatile @Nullable ExplanationProvider provider;
  private volatile Theme theme = Theme.DEFAULT;

  public ExplanationStore(Project project) {
    this.project = project;
  }

  public static ExplanationStore getInstance(Project project) {
    return project.getService(ExplanationStore.class);
  }

  // ---- Nodes ----

  /** Adds or replaces a node. */
  public void put(ExplanationNode node) {
    nodes.put(node.id(), node);
  }

  public @Nullable ExplanationNode get(String id) {
    return nodes.get(id);
  }

  /** Removes every node and resets the theme (the provider and listeners stay). */
  public void clear() {
    nodes.clear();
    theme = Theme.DEFAULT;
  }

  /** The node whose {@code next} is {@code id}, for the "Back" button of a guided tour. */
  public @Nullable ExplanationNode findPrevious(String id) {
    for (ExplanationNode node : nodes.values()) {
      if (id.equals(node.next())) return node;
    }
    return null;
  }

  /**
   * Adds the nodes in {@code json} to the store, replacing nodes with the same id, and applies the
   * tree's theme if it has one. Nothing changes if anything is invalid.
   *
   * @return the tree's root id ({@code "root"}, or the first node), or null if it has no nodes
   * @throws IllegalArgumentException if the JSON or a node is invalid
   */
  public @Nullable String loadJson(String json) {
    ExplanationJson.Tree tree = ExplanationJson.parseTree(json);
    if (tree.root() != null && tree.nodes().stream().noneMatch(n -> n.id().equals(tree.root()))
        && !nodes.containsKey(tree.root())) {
      throw new IllegalArgumentException("Root '" + tree.root() + "' is not a node");
    }
    tree.nodes().forEach(this::put);
    if (tree.theme() != null) theme = tree.theme();
    if (tree.root() != null) return tree.root();
    return tree.nodes().isEmpty() ? null : tree.nodes().get(0).id();
  }

  /** Like {@link #loadJson}, reading a project-relative (or absolute) file. */
  public @Nullable String loadFile(String path) throws IOException {
    return loadJson(Files.readString(Path.of(resolvePath(path)), StandardCharsets.UTF_8));
  }

  /** Loads a tree and shows its root. */
  public void showJson(String json) {
    String root = loadJson(json);
    if (root == null) throw new IllegalArgumentException("The explanation JSON has no nodes");
    show(root);
  }

  // ---- Display ----

  /** Shows the node with this id as the start of a new chain, replacing anything shown before. */
  public void show(String id) {
    ExplanationNode node = nodes.get(id);
    if (node == null) throw new IllegalArgumentException("No explanation node '" + id + "'");
    navigation.incrementAndGet();
    HighlightUtil.showNode(project, node, null, 0);
  }

  /** Removes every highlight and box from the editors. */
  public void hide() {
    navigation.incrementAndGet();
    HighlightUtil.clearAll(project);
  }

  public Theme theme() {
    return theme;
  }

  public void setTheme(Theme theme) {
    this.theme = theme;
  }

  // ---- Provider ----

  public void setProvider(@Nullable ExplanationProvider provider) {
    this.provider = provider;
  }

  /** Whether clicking a link to {@code id} can show something: it's stored, or a provider can generate it. */
  public boolean canResolve(String id) {
    return nodes.containsKey(id) || provider != null;
  }

  public boolean canAnswerQuestions() {
    ExplanationProvider current = provider;
    return current != null && current.canAnswerQuestions();
  }

  /**
   * Returns the requested node, from the store or else from the provider (storing the result).
   * Concurrent requests for the same id share one provider call.
   */
  public CompletableFuture<ExplanationNode> resolve(ExplanationRequest request) {
    ExplanationNode stored = nodes.get(request.id());
    if (stored != null) return CompletableFuture.completedFuture(stored);

    ExplanationProvider current = provider;
    if (current == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("No explanation for '" + request.id() + "'"));
    }
    String id = request.id();
    CompletableFuture<ExplanationNode> result = new CompletableFuture<>();
    CompletableFuture<ExplanationNode> existing = pending.putIfAbsent(id, result);
    if (existing != null) return existing;

    // Not chained inside a map operation: the provider's future may already be complete.
    call(() -> current.explain(project, request)).whenComplete((n, error) -> {
      pending.remove(id, result);
      complete(result, n == null ? null : n.withId(id), error, "Provider returned no node for '" + id + "'");
    });
    return result;
  }

  /** Asks the provider to answer a learner's question about {@code node}, storing the answer node. */
  public CompletableFuture<ExplanationNode> ask(ExplanationNode node, String question) {
    ExplanationProvider current = provider;
    if (current == null || !current.canAnswerQuestions()) {
      return CompletableFuture.failedFuture(new IllegalStateException("No provider that answers questions"));
    }
    fire(l -> l.questionAsked(node, question));
    CompletableFuture<ExplanationNode> result = new CompletableFuture<>();
    call(() -> current.answerQuestion(project, node, question)).whenComplete((n, error) ->
        complete(result, n, error, "Provider returned no answer"));
    return result;
  }

  private static CompletableFuture<ExplanationNode> call(java.util.function.Supplier<CompletableFuture<ExplanationNode>> call) {
    try {
      CompletableFuture<ExplanationNode> future = call.get();
      return future != null ? future : CompletableFuture.completedFuture(null);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  private void complete(CompletableFuture<ExplanationNode> result, @Nullable ExplanationNode node,
                        @Nullable Throwable error, String missingMessage) {
    if (error != null) {
      result.completeExceptionally(error);
    } else if (node == null) {
      result.completeExceptionally(new IllegalStateException(missingMessage));
    } else {
      put(node);
      result.complete(node);
    }
  }

  // ---- Listeners ----

  /** Adds a listener, removed when {@code parent} is disposed. */
  public void addListener(ExplanationListener listener, Disposable parent) {
    listeners.add(listener);
    Disposer.register(parent, () -> listeners.remove(listener));
  }

  /** Notifies listeners. Used by the UI; not needed by agents. */
  public void fire(Consumer<ExplanationListener> event) {
    for (ExplanationListener listener : listeners) {
      try {
        event.accept(listener);
      } catch (RuntimeException e) {
        com.intellij.openapi.diagnostic.Logger.getInstance(ExplanationStore.class).warn("Explanation listener failed", e);
      }
    }
  }

  // ---- Misc ----

  /** Increments and returns the navigation counter, so stale on-the-fly results can be ignored. */
  public int nextNavigation() {
    return navigation.incrementAndGet();
  }

  public int currentNavigation() {
    return navigation.get();
  }

  /** Resolves a project-relative path to an absolute one; absolute paths are returned unchanged. */
  public @NotNull String resolvePath(String path) {
    if (Path.of(path).isAbsolute()) return path;
    return project.getBasePath() + "/" + path;
  }
}
