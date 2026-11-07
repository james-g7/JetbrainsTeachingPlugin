package explain;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import util.HighlightUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central, per-project storage of explanation nodes. An agent fills it once (as JSON or through
 * {@link #put}) and every link refers to nodes by id, so nothing is repeated. Links to ids that
 * aren't stored are generated on demand by the {@link ExplanationProvider}, if one is set.
 *
 * <p>Typical use:
 * <pre>{@code
 * ExplanationStore store = ExplanationStore.getInstance(project);
 * String root = store.loadJson(json);
 * store.show(root);
 * }</pre>
 *
 * <p>JSON format (see docs/EXPLANATIONS.md):
 * <pre>{@code
 * {
 *   "file": "src/Main.java",               // default file for nodes without their own
 *   "root": "main",                        // optional; defaults to the first node
 *   "nodes": {
 *     "main": { "range": "3-13", "text": "Entry point. It [loops](#loop)." },
 *     "loop": { "range": "8-12", "text": "Counts to 5." }
 *   }
 * }
 * }</pre>
 */
@Service(Service.Level.PROJECT)
public final class ExplanationStore {
  private final Project project;
  private final Map<String, ExplanationNode> nodes = new ConcurrentHashMap<>();
  private final Map<String, CompletableFuture<ExplanationNode>> pending = new ConcurrentHashMap<>();
  private final AtomicInteger navigation = new AtomicInteger();
  private volatile @Nullable ExplanationProvider provider;

  public ExplanationStore(Project project) {
    this.project = project;
  }

  public static ExplanationStore getInstance(Project project) {
    return project.getService(ExplanationStore.class);
  }

  /** Adds or replaces a node. */
  public void put(ExplanationNode node) {
    nodes.put(node.id(), node);
  }

  public @Nullable ExplanationNode get(String id) {
    return nodes.get(id);
  }

  /** Removes every node (the provider stays set). */
  public void clear() {
    nodes.clear();
  }

  public void setProvider(@Nullable ExplanationProvider provider) {
    this.provider = provider;
  }

  /** Whether clicking a link to {@code id} can show something: it's stored, or a provider can generate it. */
  public boolean canResolve(String id) {
    return nodes.containsKey(id) || provider != null;
  }

  /**
   * Adds the nodes in {@code json} to the store, replacing nodes with the same id. Nothing is
   * added if any node is invalid.
   *
   * @return the tree's root id ({@code "root"}, or the first node), or null if it has no nodes
   * @throws IllegalArgumentException if the JSON or a node is invalid
   */
  public @Nullable String loadJson(String json) {
    TreeJson tree;
    try {
      tree = new Gson().fromJson(json, TreeJson.class);
    } catch (JsonParseException e) {
      throw new IllegalArgumentException("Invalid explanation JSON: " + e.getMessage(), e);
    }
    if (tree == null || tree.nodes == null) {
      throw new IllegalArgumentException("Explanation JSON must have a \"nodes\" object");
    }

    List<ExplanationNode> parsed = new ArrayList<>();
    for (Map.Entry<String, NodeJson> entry : tree.nodes.entrySet()) {
      NodeJson n = entry.getValue();
      if (n == null || n.range == null) {
        throw new IllegalArgumentException("Node '" + entry.getKey() + "' needs a \"range\"");
      }
      parsed.add(new ExplanationNode(entry.getKey(), n.file != null ? n.file : tree.file, n.range, n.text));
    }
    if (tree.root != null && tree.nodes.get(tree.root) == null && !nodes.containsKey(tree.root)) {
      throw new IllegalArgumentException("Root '" + tree.root + "' is not a node");
    }

    parsed.forEach(this::put);
    if (tree.root != null) return tree.root;
    return parsed.isEmpty() ? null : parsed.get(0).id();
  }

  /** Like {@link #loadJson}, reading a project-relative (or absolute) file. */
  public @Nullable String loadFile(String path) throws IOException {
    return loadJson(Files.readString(Path.of(resolvePath(path)), StandardCharsets.UTF_8));
  }

  /** Shows the node with this id as the start of a new chain, replacing anything shown before. */
  public void show(String id) {
    ExplanationNode node = nodes.get(id);
    if (node == null) throw new IllegalArgumentException("No explanation node '" + id + "'");
    navigation.incrementAndGet();
    HighlightUtil.showNode(project, node, null, 0);
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

    CompletableFuture<ExplanationNode> generated;
    try {
      generated = current.explain(project, request);
    } catch (RuntimeException e) {
      generated = CompletableFuture.failedFuture(e);
    }
    if (generated == null) generated = CompletableFuture.completedFuture(null);
    // Not chained inside a map operation: the provider's future may already be complete.
    generated.whenComplete((n, error) -> {
      pending.remove(id, result);
      if (error != null) {
        result.completeExceptionally(error);
      } else if (n == null) {
        result.completeExceptionally(new IllegalStateException("Provider returned no node for '" + id + "'"));
      } else {
        try {
          ExplanationNode node = new ExplanationNode(id, n.file(), n.range(), n.text());
          put(node);
          result.complete(node);
        } catch (RuntimeException e) {
          result.completeExceptionally(e);
        }
      }
    });
    return result;
  }

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

  // Gson targets for the JSON format.
  private static final class TreeJson {
    String file;
    String root;
    Map<String, NodeJson> nodes;
  }

  private static final class NodeJson {
    String file;
    String range;
    String text;
  }
}
