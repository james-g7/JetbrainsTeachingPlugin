# Code walkthroughs

A walkthrough plays a run of some code step by step, right in the editor. The AI writes it as JSON,
so it works for any language. The plugin only displays it.

At each step the editor shows:

- the step's line, or lines, highlighted: blue for a line, green for a call, purple for a return,
  red for an error;
- a box above the code with "Step 3 of 9 · title", ◀ ⏵ ▶ ✕ buttons, the note (Markdown), the
  call stack, and the output so far (this step's output in bold);
- variable values at the end of the line, with changed ones in bold orange;
- optionally, variables in the code replaced by their values, e.g. `"i = " + i` shown as
  `"i = " + 1`. The file isn't changed.

**Tools → Learning Agent → Walk Through Function…** (also on the editor's right-click menu) asks for the function,
the inputs and a level (beginner, intermediate or advanced), then asks the registered AI provider for
a walkthrough. **Tools → Learning Agent → Show Walkthrough** plays a JSON file.

## JSON format

```json
{
  "title": "Running main()",
  "file": "Main.java",
  "delay": 2000,
  "steps": [
    { "line": 3, "kind": "call", "title": "The program starts", "note": "Java runs `main()` first.", "stack": ["main()"] },
    { "line": 8, "note": "`i` starts at **1**.", "vars": { "i": "1" }, "substitute": ["i"] },
    { "line": 11, "vars": { "i": "1" }, "substitute": ["i"], "output": "i = 1\n", "pause": true },
    { "line": "8-12", "title": "Three more passes", "vars": { "i": "5" }, "output": "i = 3\ni = 4\ni = 5\n" },
    { "line": 13, "kind": "return", "note": "Done." }
  ]
}
```

Top level:

| Field | Meaning |
|---|---|
| `title` | Shown on the first step. |
| `file` | Default file for steps. Project-relative or absolute. |
| `delay` | Milliseconds per step when playing. Default 1800. |
| `steps` | **Required.** At least one step. |

Each step (only `line` is required):

| Field | Meaning |
|---|---|
| `line` | The code the step is about: `8`, or `"8-12"` for a block (e.g. a whole loop or call). 1-based. |
| `file` | File for this and later steps. |
| `kind` | `line` (default), `call`, `return` or `error`. Sets the colour. |
| `title` | Bold heading in the box. |
| `note` | Markdown explanation. |
| `vars` | Variable name → value **before** the line runs, as display strings: `{"i": "1", "name": "\"Bo\""}`. |
| `changed` | Variables to emphasise. If missing, worked out by comparing with the previous step that has the same `stack`. |
| `substitute` | Variable names to show as their values on the step's lines: `["i"]`, or `"*"` for all of `vars`. |
| `replace` | Exact ranges to show as other text, when `substitute` isn't precise enough, e.g. `{"11:29-11:29": "1"}`. |
| `highlight` | Extra ranges to outline, e.g. `["11:20-11:29"]`. |
| `stack` | Call stack, outermost first: `["main()", "sum(n = 3)"]`. Carries over to later steps until changed. |
| `output` | What the program prints during this step. It's added to the output shown in the box. |
| `pause` | Auto-play stops here. |

Ranges are `line`, `line-line` or `line:col-line:col` (1-based, end column inclusive).

`substitute` matches names as whole words. It skips matches inside string literals, after `.`
(member access), on the left of assignments (`x = …`, `x += …`) and next to `++`/`--`, since the
value before the line isn't what those mean. Use `replace` for anything else.

## Levels of detail

The AI chooses how many steps to write, based on the level the learner picked:

| Level | Write |
|---|---|
| Beginner | Every line, stepping into each function call (push onto `stack`, step through, `return`). Explain each change, and use `substitute` often. |
| Intermediate | The function's own lines. Each call it makes is one `call` step plus one `return` step. |
| Advanced | Function by function: one step per call (`"line": "start-end"` of the function) with its arguments and what it returns. |

Repetitive parts (later loop passes) can be collapsed into one step with a range `line`.

## Hooking up the AI

```java
WalkthroughService.getInstance(project).setProvider((project, request) ->
    callModel(instructions, request)                  // this doc + the request's fields
        .thenApply(Walkthrough::fromJson));           // throws with "Step N: …" messages to send back
```

`request` has `file`, `language`, `code` (the whole file, to count lines in), `function`,
`inputs` (free text from the learner), `detail` (`BEGINNER`, `INTERMEDIATE` or `ADVANCED`, with
`detail().description()` for the prompt), and `selection` if the learner selected some code.

To play JSON from anywhere, use `WalkthroughService.getInstance(project).showJson(json)`. `stop()`
ends a walkthrough.

## Example

`docs/examples/main_walkthrough.json` walks through the test project's `Main.java`. Play it with
**Tools → Learning Agent → Show Walkthrough** and its absolute path.
