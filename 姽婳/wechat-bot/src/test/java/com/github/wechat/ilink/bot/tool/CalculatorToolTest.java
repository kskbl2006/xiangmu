package com.github.wechat.ilink.bot.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CalculatorToolTest {
  @Test
  void evaluatesArithmeticWithPrecedenceAndParentheses() {
    assertEquals(new BigDecimal("60"), CalculatorTool.evaluate("(12 + 8) * 3"));
    assertEquals(new BigDecimal("7"), CalculatorTool.evaluate("1 + 2 * 3"));
    assertEquals(0, CalculatorTool.evaluate("1 / 4").compareTo(new BigDecimal("0.25")));
  }

  @Test
  void rejectsUnsafeOrInvalidExpressions() {
    assertThrows(IllegalArgumentException.class, () -> CalculatorTool.evaluate("System.exit(0)"));
    assertThrows(IllegalArgumentException.class, () -> CalculatorTool.evaluate("3 / 0"));
    assertThrows(IllegalArgumentException.class, () -> CalculatorTool.evaluate("(1 + 2"));
  }
}
