package com.github.jamesg7.hacktheburghxiii.explain;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class CodeRangeTest {
  @Test
  public void parsesSingleLine() {
    assertEquals(new CodeRange(null, 12, CodeRange.WHOLE_LINE, 12, CodeRange.WHOLE_LINE), CodeRange.parse("12"));
  }

  @Test
  public void parsesLineRange() {
    assertEquals(new CodeRange(null, 12, CodeRange.WHOLE_LINE, 15, CodeRange.WHOLE_LINE), CodeRange.parse(" 12-15 "));
  }

  @Test
  public void parsesExactRangeWithPath() {
    assertEquals(new CodeRange("src/Foo.java", 12, 5, 15, 20), CodeRange.parse("src/Foo.java@12:5-15:20"));
  }

  @Test
  public void rejectsMalformedRanges() {
    assertNull(CodeRange.parse(null));
    assertNull(CodeRange.parse(""));
    assertNull(CodeRange.parse("abc"));
    assertNull(CodeRange.parse("12-"));
    assertNull(CodeRange.parse("99999999999"));
  }
}
