# Explanation trees

Format for code explanations shown in the editor. Each **node** highlights a range of code and
shows a box above it with Markdown text. Text links to other nodes by id, so every node is
written once.

## Minimal example

```json
{
  "file": "src/Main.java",
  "nodes": {
    "main": { "range": "3-13", "text": "Entry point. It [loops five times](#loop)." },
    "loop": { "range": "8-12", "text": "Counts `i` from 1 to 5." }
  }
}
```

Only `nodes` and each node's `range` are required. Everything else is optional.

## Tree fields

| Field | Meaning |
|---|---|
| `file` | Default file for nodes that don't set one. Project-relative. |
| `root` | Node to show first. Defaults to the first node. |
| `theme` | Appearance for the whole tree (below). |
| `nodes` | Map of id → node. Keep ids short. |

`theme`:

| Field | Default | Meaning |
|---|---|---|
| `palette` | yellow, blue, green, pink, orange | Highlight colours by depth (root first), cycling. |
| `maxWidth` | `520` | Max width of box text in pixels. |
| `fontSize` | IDE font | Box font size in points. |
| `highlightStyle` | `fill` | `fill`, `outline` or `bar` (a bar down the left of the code). |

## Node fields

| Field | Meaning |
|---|---|
| `range` | **Required.** `12` (line), `12-15` (lines), `12:5-15:20` (exact; end column inclusive). 1-based. A `path@` prefix (`data.txt@1-3`) overrides `file`. |
| `file` | File for this node. If neither the node nor the tree sets one, the linking node's file is used. |
| `text` | Markdown: bold, italics, `code`, code blocks, lists, headings. Links are described below. |
| `title` | Bold heading for the box. |
| `kind` | `note` (default), `info`, `tip`, `warning`, `question` or `example`. Sets an icon and a default colour. |
| `color` | Highlight colour. The box background and border are tinted from it. |
| `style` | Overrides: `highlight`, `background`, `border`, `text` (colours) and `highlightStyle`. |
| `also` | Extra ranges in the same file, outlined in the same colour, e.g. `["11", "15:9-15:9"]` for other uses of a variable. |
| `hints` | Markdown hints, revealed one at a time with "Show hint". |
| `answer` | Markdown, revealed with "Show answer". Pair it with a question in `text`. |
| `next` | Id of the next node in a guided tour. Adds "Next →". "← Back" appears on the next node automatically. |

**Colours** can be a hex string (`"#FFF59D"`, `"#FFF59D80"` with alpha), a name (`yellow`, `blue`,
`green`, `pink`, `orange`, `purple`, `red`, `teal`, `gray`, which have tuned dark-theme
variants), or `{ "light": "#FFF59D", "dark": "#6B5B00" }`.

The highlight colour is chosen in this order: `style.highlight`, then `color`, then the
`kind`'s colour, then the theme palette for the node's depth.

**Links** in `text`, `hints` and `answer`:

- `[label](#id)` shows node `id` one level deeper.
- `[label](12-15)` highlights a range one level deeper, with no box.
- `[label](https://...)` opens the browser.

Clicking a link again while what it opened is still showing hides it, and everything below it.

## Patterns for teaching

- **Explore:** a root node that links to its parts, and each part links to its details.
- **Guided tour:** chain nodes with `next`, e.g. `intro` → `step1` → `step2`.
- **Check understanding:** put a question in `text` with `"kind": "question"`, then add `hints`
  and an `answer`.
- **Point at related code:** use `also` to outline every use of a variable while the node
  explains its declaration.

## How deep to go

Write the root plus **up to 3 links deep**. Deeper links can point to ids that don't exist yet.
When one is clicked, the registered `ExplanationProvider` generates it. The result is stored, so
it's only generated once. With no provider set, links to missing ids are greyed out.

## Java API

```java
ExplanationStore store = ExplanationStore.getInstance(project);

store.showJson(json);                         // load (merge by id) + show root
String root = store.loadJson(json);           // or just load
store.loadFile(".explanations/lesson.json");
store.put(ExplanationNode.builder("loop", "8-12")
    .title("The loop").kind(Kind.TIP).color("green")
    .text("Counts `i` from 1 to 5.").hint("Look at `i++`").next("print")
    .build());
store.show("main");
store.hide();                                 // remove highlights and boxes

// Generate missing nodes and answer "Ask a question…".
store.setProvider(new ExplanationProvider() {
  public CompletableFuture<ExplanationNode> explain(Project p, ExplanationRequest r) {
    // r.id(), r.label() (link text), r.parent() (node containing the link), r.depth()
    return askModel(r).thenApply(json -> ExplanationNode.fromJson(r.id(), json));
  }
  public boolean canAnswerQuestions() { return true; }
  public CompletableFuture<ExplanationNode> answerQuestion(Project p, ExplanationNode node, String q) { ... }
});

// See what the learner does.
store.addListener(new ExplanationListener() {
  public void nodeShown(ExplanationNode node, int depth) { ... }
  public void answerRevealed(ExplanationNode node) { ... }
}, disposable);
```

`ExplanationNode.fromJson(id, json)` takes one node in the same shape as an entry of `nodes`,
so a model can reply with e.g. `{"range": "11", "text": "..."}`.

Invalid JSON throws `IllegalArgumentException`. The message names the node and the field at
fault, so it can be passed back to the agent to fix.

From the IDE: **Tools → Learning Agent → Show Explanation Tree** loads a JSON file. **Tools → Learning Agent → Clear Explanations**
removes everything shown.
