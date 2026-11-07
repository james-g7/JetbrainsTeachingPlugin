# htbxiii learning agent tools

![Build](https://github.com/james-g7/hacktheburghxiii/workflows/Build/badge.svg)

A JetBrains IDE plugin that lets **Air**, an AI learning agent, teach inside the editor.

Air is being built alongside this plugin. Its only job is to teach: it explains code so the
developer understands it, and does not write the code for them. This plugin is how Air shows those
explanations. It highlights code in the editor, puts an explanation box directly above the
highlight, and lets the learner click through linked explanations to go deeper, one concept at a
time.

Built by [James Gaskin](https://github.com/james-g7) and [Noah Barret](https://github.com/NoahB-07)
for the **JetBrains "Help the Developer" challenge** at **HackTheBurgh XIII (2026)**.

## What it does

- **Highlights code**: a single line, a block of lines, or an exact column range, in any file in
  the project.
- **Explains above the code**: each highlight gets an explanation box rendered in the editor right
  above it.
- **Linked explanation trees**: explanation text can link to other explanations
  (`[label](#id)`) or to bare ranges of code (`[label](12-15)`), including in other files. The
  learner follows the links that interest them, so they choose how deep to go.
- **Generated on demand**: Air writes the first few levels up front. When the learner clicks a
  link to an explanation that doesn't exist yet, the plugin asks Air to generate it and stores the
  result, so each one is generated only once.

## How Air uses it

Air describes an explanation as a small JSON tree. Every node is written once and referred to by
id:

```json
{
  "file": "src/Main.java",
  "root": "main",
  "nodes": {
    "main":  { "range": "3-13", "text": "The program's entry point. It [loops five times](#loop)." },
    "loop":  { "range": "8-12", "text": "Counts i from 1 to 5, [printing it](#print) each time." },
    "print": { "range": "11",   "text": "Prints i using [string concatenation](#concat)." }
  }
}
```

Here `#concat` is not defined, so clicking it asks Air to generate it.

From Java/Kotlin, the agent talks to the project's `ExplanationStore`
(package `com.github.jamesg7.hacktheburghxiii.explain`):

```java
ExplanationStore store = ExplanationStore.getInstance(project);

// Load a tree and show its root.
String root = store.loadJson(json);   // or store.loadFile(".explanations/x.json")
store.show(root);

// Generate missing nodes when the learner clicks a link to them.
store.setProvider((project, request) -> askAir(request));   // returns CompletableFuture<ExplanationNode>
```

The full format, range syntax and API are documented in [docs/EXPLANATIONS.md](docs/EXPLANATIONS.md).
There is an example tree in [docs/examples/test_java.json](docs/examples/test_java.json).

## Trying it in the IDE

The plugin adds a **Tools → Learning Agent** menu:

| Action | What it does |
|---|---|
| **Show Explanation Tree…** | Loads an explanation JSON file (default `.explanations/explanation.json`) and shows its root. |
| **Highlight with Explanation…** | Prompts for a file and range, then for explanation text, and shows it above the highlight. |
| **Highlight…** | Prompts for a file and range and highlights it. |
| **Clear Explanations** | Removes every explanation highlight and box. |
| **Walk Through Function…** | Asks the connected AI for a step-by-step walkthrough of the function at the caret (also in the editor's right-click menu). |
| **Show Walkthrough…** | Plays a walkthrough JSON file. See [docs/WALKTHROUGH.md](docs/WALKTHROUGH.md). |

These let you try the plugin by hand. In normal use, Air drives the plugin through the API above.

## Building from source

Requirements: a JDK matching the toolchain in `build.gradle.kts` (Gradle's toolchain resolver can
download one for you). The plugin targets IntelliJ Platform 2026.2.

```bash
./gradlew runIde        # start a sandbox IDE with the plugin installed
./gradlew buildPlugin   # build the installable zip into build/distributions/
./gradlew test          # run the tests
```

In IntelliJ IDEA you can use the bundled **Run Plugin**, **Run Tests** and **Run Verifications**
run configurations instead.

To install the zip you built, go to <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> >
<kbd>Install Plugin from Disk...</kbd> and choose the file in `build/distributions/`.

## Project layout

```
src/main/java/com/github/jamesg7/hacktheburghxiii/
  explain/      ExplanationStore, ExplanationNode, ExplanationProvider, JSON parsing, styling
  walkthrough/  WalkthroughService, Walkthrough, WalkthroughProvider and the step box UI
  actions/      The Tools → Learning Agent actions
  util/         HighlightUtil and ExplanationBox: editor highlights, boxes and link handling
docs/           Explanation and walkthrough formats, with examples
```

## Releasing

1. Move the `[Unreleased]` notes in `CHANGELOG.md` into shape and bump `version` in `gradle.properties`.
2. Push to `main`. The **Build** workflow builds, tests and verifies the plugin and creates a draft
   GitHub release.
3. Publish the draft. The **Release** workflow signs the plugin, publishes it to JetBrains Marketplace
   and opens a PR with the patched changelog. It needs the `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`,
   `PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD` repository secrets
   ([plugin signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)).

## Authors

- James Gaskin ([@james-g7](https://github.com/james-g7))
- Noah Barret ([@NoahB-07](https://github.com/NoahB-07))

Made for HackTheBurgh XIII (2026), JetBrains "Help the Developer" challenge.

---
Plugin scaffold based on the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
