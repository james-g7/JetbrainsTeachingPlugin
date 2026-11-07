package com.github.jamesg7.hacktheburghxiii.explain;

import org.junit.Test;

import java.awt.Color;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ColorSpecTest {
  @Test
  public void parsesHexForms() {
    assertEquals(new Color(0xFF, 0xFF, 0xFF), ColorSpec.parse("#fff").light());
    assertEquals(new Color(0x12, 0x34, 0x56), ColorSpec.parse("#123456").dark());
    assertEquals(new Color(0x12, 0x34, 0x56, 0x78), ColorSpec.parse("12345678").light());
  }

  @Test
  public void parsesNames() {
    assertEquals(ColorSpec.named("blue"), ColorSpec.parse(" Blue "));
  }

  @Test
  public void rejectsInvalidColours() {
    assertThrows(IllegalArgumentException.class, () -> ColorSpec.parse("#12"));
    assertThrows(IllegalArgumentException.class, () -> ColorSpec.parse("chartreuse"));
  }
}
