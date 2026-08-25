package com.github.wechat.ilink.bot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.MathContext;

/** Safe arithmetic tool supporting decimal numbers, parentheses, and +, -, *, /. */
public final class CalculatorTool implements BotTool {
  private final ObjectMapper objectMapper;

  public CalculatorTool(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public String name() {
    return "calculator";
  }

  @Override
  public String description() {
    return "计算数学表达式。涉及加减乘除或括号计算时调用，禁止自行估算。";
  }

  @Override
  public ObjectNode parametersSchema() {
    return ToolSchemas.requiredString("expression", "只包含数字、括号以及 + - * / 的算术表达式");
  }

  @Override
  public String execute(JsonNode arguments) throws Exception {
    String expression = arguments.path("expression").asText();
    BigDecimal result = evaluate(expression);
    ObjectNode output = objectMapper.createObjectNode();
    output.put("expression", expression);
    output.put("result", result.stripTrailingZeros().toPlainString());
    return objectMapper.writeValueAsString(output);
  }

  static BigDecimal evaluate(String expression) {
    if (expression == null || expression.isBlank()) {
      throw new IllegalArgumentException("expression must not be blank");
    }
    if (expression.length() > 200) {
      throw new IllegalArgumentException("expression is too long");
    }
    return new Parser(expression).parse();
  }

  private static final class Parser {
    private final String input;
    private int position;

    private Parser(String input) {
      this.input = input;
    }

    private BigDecimal parse() {
      BigDecimal result = expression();
      skipWhitespace();
      if (position != input.length()) {
        throw error("unexpected character '" + input.charAt(position) + "'");
      }
      return result;
    }

    private BigDecimal expression() {
      BigDecimal value = term();
      while (true) {
        if (consume('+')) value = value.add(term(), MathContext.DECIMAL64);
        else if (consume('-')) value = value.subtract(term(), MathContext.DECIMAL64);
        else return value;
      }
    }

    private BigDecimal term() {
      BigDecimal value = factor();
      while (true) {
        if (consume('*')) value = value.multiply(factor(), MathContext.DECIMAL64);
        else if (consume('/')) {
          BigDecimal divisor = factor();
          if (divisor.compareTo(BigDecimal.ZERO) == 0) {
            throw error("division by zero");
          }
          value = value.divide(divisor, MathContext.DECIMAL64);
        } else return value;
      }
    }

    private BigDecimal factor() {
      skipWhitespace();
      if (consume('+')) return factor();
      if (consume('-')) return factor().negate(MathContext.DECIMAL64);
      if (consume('(')) {
        BigDecimal value = expression();
        if (!consume(')')) throw error("missing ')'");
        return value;
      }
      return number();
    }

    private BigDecimal number() {
      skipWhitespace();
      int start = position;
      boolean dotSeen = false;
      while (position < input.length()) {
        char current = input.charAt(position);
        if (Character.isDigit(current)) {
          position++;
        } else if (current == '.' && !dotSeen) {
          dotSeen = true;
          position++;
        } else {
          break;
        }
      }
      if (start == position || ".".equals(input.substring(start, position))) {
        throw error("number expected");
      }
      return new BigDecimal(input.substring(start, position), MathContext.DECIMAL64);
    }

    private boolean consume(char expected) {
      skipWhitespace();
      if (position < input.length() && input.charAt(position) == expected) {
        position++;
        return true;
      }
      return false;
    }

    private void skipWhitespace() {
      while (position < input.length() && Character.isWhitespace(input.charAt(position))) {
        position++;
      }
    }

    private IllegalArgumentException error(String message) {
      return new IllegalArgumentException(message + " at position " + position);
    }
  }
}
