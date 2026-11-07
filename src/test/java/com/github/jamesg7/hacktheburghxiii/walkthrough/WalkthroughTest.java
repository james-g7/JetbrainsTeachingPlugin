package com.github.jamesg7.hacktheburghxiii.walkthrough;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class WalkthroughTest {
  @Test
  public void carriesFileAndStackForward() {
    Walkthrough w = Walkthrough.fromJson("""
        {"title": "sum(3)", "file": "Main.java", "steps": [
          {"line": 3, "kind": "call", "stack": ["main()"]},
          {"line": "8-12", "vars": {"i": "1"}, "pause": true}
        ]}""");
    assertEquals("sum(3)", w.title());
    assertEquals(Walkthrough.DEFAULT_DELAY, w.delay());
    Walkthrough.Step second = w.steps().get(1);
    assertEquals("Main.java", second.file());
    assertEquals(List.of("main()"), second.stack());
    assertEquals(8, second.range().startLine());
    assertEquals(12, second.range().endLine());
    assertEquals(Walkthrough.Kind.LINE, second.kind());
    assertTrue(second.pause());
  }

  @Test
  public void reportsTheBadStep() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> Walkthrough.fromJson("{\"file\": \"A.java\", \"steps\": [{\"line\": 1}, {\"line\": 2, \"kind\": \"jump\"}]}"));
    assertTrue(e.getMessage(), e.getMessage().startsWith("Step 1: unknown kind 'jump'"));
  }

  @Test
  public void requiresFileAndSteps() {
    assertThrows(IllegalArgumentException.class, () -> Walkthrough.fromJson("{}"));
    assertThrows(IllegalArgumentException.class, () -> Walkthrough.fromJson("{\"steps\": []}"));
    assertThrows(IllegalArgumentException.class, () -> Walkthrough.fromJson("{\"steps\": [{\"line\": 1}]}"));
  }

  @Test
  public void exampleWalkthroughIsValid() throws Exception {
    String json = Files.readString(Path.of("docs/examples/main_walkthrough.json"), StandardCharsets.UTF_8);
    assertFalse(Walkthrough.fromJson(json).steps().isEmpty());
  }
}
