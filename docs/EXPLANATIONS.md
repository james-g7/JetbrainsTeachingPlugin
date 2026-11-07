# Explanation trees

Format for code explanations shown in the editor. Each **node** highlights a range of code and
shows its text in a box above it. Text links to other nodes by id, so every node is written once.

## JSON

```json
{
  "file": "src/Main.java",
  "root": "main",
  "nodes": {
    "main": { "range": "3-13", "text": "Entry point. It [loops five times](#loop)." },
    "loop": { "range": "8-12", "text": "Counts i from 1 to 5, [printing it](#print) each time." },
    "print": { "range": "11", "text": "Prints i using [string concatenation](#concat)." },
    "data": { "file": "data.txt", "range": "1-3", "text": "Input data." }
  }
}
```

| Field | Required | Meaning |
|---|---|---|
| `file` (top level) | no | Default file for nodes that don't set their own. Project-relative. |
| `root` | no | Node to show first. Defaults to the first node. |
| `nodes` | yes | Map of id → node. Ids: any string; keep them short. |
| `node.range` | yes | `12` (line), `12-15` (lines), `12:5-15:20` (exact, end column inclusive). Lines/columns are 1-based. A `path@` prefix (`data.txt@1-3`) overrides `file`. |
| `node.file` | no | File for this node. If neither the node nor the tree sets one, the linking node's file is used. |
| `node.text` | no | The explanation. Plain text, line breaks allowed. |

Links inside `text`:

- `[label](#id)` shows node `id`: its highlight and its explanation.
- `[label](12-15)` only highlights a range, with no explanation.

## How deep to go

Write the root plus **up to 3 links deep**. Deeper links can point to ids that don't exist yet
(like `#concat` above). When the user clicks one, the plugin asks the registered
`ExplanationProvider` to generate it. The result is stored, so it's only generated once. With no
provider set, links to missing ids are shown greyed out and can't be clicked.

## Java API

```java
ExplanationStore store = ExplanationStore.getInstance(project);

// Load (merges into what's stored; same id replaces) and show.
String root = store.loadJson(json);          // or store.loadFile(".explanations/x.json")
store.show(root);

// Or build nodes directly.
store.put(ExplanationNode.of("loop", "8-12", "Counts to 5."));

// Generate missing nodes on the fly.
store.setProvider((project, request) -> CompletableFuture.supplyAsync(() ->
    ExplanationNode.of(request.id(), "11", "...")));
// request.id(), request.label() (link text), request.parent() (node containing the link), request.depth()
```

From the IDE: **Tools → Show Explanation Tree** loads a JSON file and shows its root.
