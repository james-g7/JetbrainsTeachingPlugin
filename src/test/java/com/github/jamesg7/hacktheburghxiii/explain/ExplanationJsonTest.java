package com.github.jamesg7.hacktheburghxiii.explain;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ExplanationJsonTest {
  @Test
  public void parsesTreeAndAppliesDefaultFile() {
    ExplanationJson.Tree tree = ExplanationJson.parseTree("""
        {"file": "src/Main.java", "root": "main", "nodes": {
          "main": {"range": "3-13", "text": "Entry point. It [loops](#loop)."},
          "loop": {"file": "Other.java", "range": "8-12", "kind": "tip"}
        }}""");
    assertEquals("main", tree.root());
    assertNull(tree.theme());
    assertEquals(2, tree.nodes().size());
    ExplanationNode main = tree.nodes().stream().filter(n -> n.id().equals("main")).findFirst().orElseThrow();
    ExplanationNode loop = tree.nodes().stream().filter(n -> n.id().equals("loop")).findFirst().orElseThrow();
    assertEquals("src/Main.java", main.file());
    assertEquals("Other.java", loop.file());
    assertEquals(Kind.TIP, loop.kind());
  }

  @Test
  public void reportsTheBadNode() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ExplanationJson.parseTree("{\"nodes\": {\"x\": {\"range\": \"nope\"}}}"));
    assertEquals("Node 'x': invalid range 'nope' (expected e.g. 12, 12-15 or 12:5-15:20)", e.getMessage());
  }

  @Test
  public void requiresNodes() {
    assertThrows(IllegalArgumentException.class, () -> ExplanationJson.parseTree("{}"));
    assertThrows(IllegalArgumentException.class, () -> ExplanationJson.parseTree("not json"));
  }

  @Test
  public void parsesSingleNode() {
    ExplanationNode node = ExplanationNode.fromJson("print", "{\"range\": \"11\", \"text\": \"Prints **i**.\", \"color\": \"#FFF59D\"}");
    assertEquals("print", node.id());
    assertEquals("Prints **i**.", node.text());
    assertTrue(node.hints().isEmpty());
  }

  @Test
  public void exampleTreeIsValid() throws Exception {
    String json = Files.readString(Path.of("docs/examples/test_java.json"), StandardCharsets.UTF_8);
    ExplanationJson.Tree tree = ExplanationJson.parseTree(json);
    assertEquals("main", tree.root());
    assertFalse(tree.nodes().isEmpty());
  }
}
