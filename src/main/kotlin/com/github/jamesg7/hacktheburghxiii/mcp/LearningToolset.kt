package com.github.jamesg7.hacktheburghxiii.mcp

import com.github.jamesg7.hacktheburghxiii.explain.CodeRange
import com.github.jamesg7.hacktheburghxiii.explain.ExplanationNode
import com.github.jamesg7.hacktheburghxiii.explain.ExplanationStore
import com.github.jamesg7.hacktheburghxiii.walkthrough.WalkthroughService
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Exposes the plugin's teaching features as tools on the IDE's built-in MCP server, so an MCP
 * client (such as Air) can highlight code, show explanation trees and play walkthroughs.
 *
 * The descriptions are what the client's model reads to decide how to call each tool, so they
 * spell out the input formats. The full references are served by [learning_get_format_reference].
 */
class LearningToolset : McpToolset {

  @McpTool(name = "learning_get_format_reference")
  @McpDescription(
    """
    Returns the full reference for a JSON format used by the other learning_* tools.
    Read it before writing your first explanation tree or walkthrough in a session.
    It covers every field, the range syntax, link syntax, colours, levels of detail and teaching patterns.
    """
  )
  suspend fun learning_get_format_reference(
    @McpDescription("Which format: \"explanations\" (for learning_show_explanation_tree) or \"walkthrough\" (for learning_play_walkthrough).")
    format: String,
  ): String {
    val resource = when (format.trim().lowercase()) {
      "explanations", "explanation", "tree" -> "/docs/EXPLANATIONS.md"
      "walkthrough", "walkthroughs" -> "/docs/WALKTHROUGH.md"
      else -> mcpFail("Unknown format '$format'. Use \"explanations\" or \"walkthrough\".")
    }
    return LearningToolset::class.java.getResourceAsStream(resource)?.use { String(it.readAllBytes(), Charsets.UTF_8) }
      ?: mcpFail("The $resource reference is missing from the plugin.")
  }

  @McpTool(name = "learning_highlight_code")
  @McpDescription(
    """
    Highlights a range of code in the editor, opening the file if needed, and optionally shows a
    Markdown explanation in a box directly above it. Replaces any explanation currently shown.
    Use it for a quick, one-off pointer. For anything the learner should explore, use learning_show_explanation_tree.

    Ranges are 1-based: "12" (one line), "12-15" (lines 12 to 15), or "12:5-15:20" (exact columns, end column inclusive).
    Explanation text can link to code with [label](12-15) or [label](other/File.java@3-5), which highlights it one level deeper.
    """
  )
  suspend fun learning_highlight_code(
    @McpDescription("Path to the file, relative to the project root (e.g. \"src/Main.java\"), or absolute.")
    path: String,
    @McpDescription("Code to highlight: \"12\", \"12-15\" or \"12:5-15:20\". 1-based; end column inclusive.")
    range: String,
    @McpDescription("Optional Markdown shown above the highlight. Omit to highlight only.")
    explanation: String? = null,
  ): String {
    val project = currentCoroutineContext().project
    requireFile(project, path)
    if (CodeRange.parse(range) == null) {
      mcpFail("Invalid range '$range'. Use \"12\", \"12-15\" or \"12:5-15:20\" (1-based).")
    }
    val node = ExplanationNode.builder(HIGHLIGHT_ID, range).file(path).text(explanation ?: "").build()
    onEdt {
      val store = ExplanationStore.getInstance(project)
      store.put(node)
      store.show(HIGHLIGHT_ID)
    }
    return "Highlighted $path at $range" + if (explanation.isNullOrBlank()) "." else " with an explanation."
  }

  @McpTool(name = "learning_show_explanation_tree")
  @McpDescription(
    """
    Loads an explanation tree into the editor and shows its root node: the code is highlighted and
    the node's Markdown text appears in a box above it. The learner clicks links in the text to open
    deeper nodes, so they choose how far to go.

    Nodes are merged by id with nodes loaded earlier, so you can send more nodes later.
    Shape: {"file": "src/Main.java", "root": "main", "nodes": {"main": {"range": "3-13", "text": "It [loops](#loop)."}, "loop": {"range": "8-12", "text": "..."}}}
    Only "nodes" and each node's "range" are required. Links: [label](#id) opens node id; [label](12-15) highlights code.
    Write the root plus up to 3 levels of links. Links to ids you don't define are generated on demand when clicked, if a provider is set.
    Optional node fields include title, kind (note, info, tip, warning, question, example), color, also, hints, answer and next.
    Call learning_get_format_reference with "explanations" for every field.

    On invalid JSON the error names the node and field at fault: fix that and call again with the whole tree.
    """
  )
  suspend fun learning_show_explanation_tree(
    @McpDescription("The explanation tree as a JSON string.")
    json: String,
    @McpDescription("Optional id of the node to show instead of the tree's root.")
    show_id: String? = null,
  ): String {
    val project = currentCoroutineContext().project
    return onEdt {
      val store = ExplanationStore.getInstance(project)
      val root = invalidAsMcpError { store.loadJson(json) } ?: mcpFail("The explanation JSON has no nodes.")
      val target = show_id ?: root
      invalidAsMcpError { store.show(target) }
      "Loaded the tree and showed node '$target'."
    }
  }

  @McpTool(name = "learning_show_explanation_node")
  @McpDescription(
    """
    Shows a node that is already loaded (from learning_show_explanation_tree) as the start of a new
    chain, replacing whatever is shown. Use it to bring the learner back to a node, or to start a
    guided tour from a particular step.
    """
  )
  suspend fun learning_show_explanation_node(
    @McpDescription("Id of a loaded node, as used in the tree's \"nodes\" map.")
    id: String,
  ): String {
    val project = currentCoroutineContext().project
    onEdt { invalidAsMcpError { ExplanationStore.getInstance(project).show(id) } }
    return "Showed node '$id'."
  }

  @McpTool(name = "learning_clear_explanations")
  @McpDescription("Removes every explanation highlight and box from the editors. Loaded nodes stay loaded, so learning_show_explanation_node still works.")
  suspend fun learning_clear_explanations(): String {
    val project = currentCoroutineContext().project
    onEdt { ExplanationStore.getInstance(project).hide() }
    return "Cleared all explanations."
  }

  @McpTool(name = "learning_play_walkthrough")
  @McpDescription(
    """
    Plays a step-by-step walkthrough of a run of some code in the editor. Each step highlights its
    line(s) and shows a box with the step's note, variable values, the call stack and the output so far.
    The learner moves through it with the box's buttons, or you can drive it with learning_control_walkthrough.
    Replaces any walkthrough already playing.

    Shape: {"title": "Running main()", "file": "Main.java", "steps": [{"line": 3, "kind": "call", "note": "...", "stack": ["main()"]}, {"line": 8, "vars": {"i": "1"}, "substitute": ["i"]}]}
    Only "steps" and each step's "line" are required. "line" is 8 or "8-12".
    Important rules: "vars" are values BEFORE the line runs, as display strings. "output" is only what this step prints;
    it is added to earlier output. "stack" carries over to later steps until changed. "kind" is line, call, return or error.
    Step through every line and call for beginners; one step per call for advanced learners.
    Call learning_get_format_reference with "walkthrough" for every field and the levels of detail.

    On invalid JSON the error starts with "Step N:": fix that step and call again with the whole walkthrough.
    """
  )
  suspend fun learning_play_walkthrough(
    @McpDescription("The walkthrough as a JSON string.")
    json: String,
  ): String {
    val project = currentCoroutineContext().project
    onEdt { invalidAsMcpError { WalkthroughService.getInstance(project).showJson(json) } }
    return "Playing the walkthrough from step 1."
  }

  @McpTool(name = "learning_control_walkthrough")
  @McpDescription(
    """
    Controls the walkthrough that is playing: move between steps, start or pause auto-play, or close it.
    Use it to point the learner at a particular step while you talk about it.
    """
  )
  suspend fun learning_control_walkthrough(
    @McpDescription("One of: \"next\", \"previous\", \"go_to\", \"play_pause\" (toggles auto-play), \"stop\" (closes the walkthrough).")
    action: String,
    @McpDescription("For \"go_to\": the 1-based step number.")
    step: Int? = null,
  ): String {
    val project = currentCoroutineContext().project
    return onEdt {
      val service = WalkthroughService.getInstance(project)
      when (action.trim().lowercase()) {
        "next" -> service.next().let { "Moved to the next step." }
        "previous" -> service.previous().let { "Moved to the previous step." }
        "go_to" -> {
          val number = step ?: mcpFail("\"go_to\" needs a step number.")
          service.goTo(number - 1)
          "Moved to step $number."
        }
        "play_pause" -> service.togglePlay().let { "Toggled auto-play." }
        "stop" -> service.stop().let { "Closed the walkthrough." }
        else -> mcpFail("Unknown action '$action'. Use next, previous, go_to, play_pause or stop.")
      }
    }
  }

  private fun requireFile(project: Project, path: String) {
    val resolved = ExplanationStore.getInstance(project).resolvePath(path)
    if (!Files.isRegularFile(Path.of(resolved))) mcpFail("No file at '$path' (looked for $resolved).")
  }

  private suspend fun <T> onEdt(block: () -> T): T = withContext(Dispatchers.EDT) { block() }

  /** Turns the plugin's validation errors into MCP errors, so the client sees the message and can fix its input. */
  private inline fun <T> invalidAsMcpError(block: () -> T): T =
    try {
      block()
    } catch (e: IllegalArgumentException) {
      mcpFail(e.message ?: "Invalid input.")
    }

  private companion object {
    const val HIGHLIGHT_ID = "mcp-highlight"
  }
}
