package io.github.keemgdeok.metadq.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.keemgdeok.metadq.core.RuleSpec;
import io.github.keemgdeok.metadq.core.RuleSpec.LastCommitAgeRule;
import io.github.keemgdeok.metadq.core.RuleSpec.NullRatioRule;
import io.github.keemgdeok.metadq.core.RuleSpec.RowCountRule;
import io.github.keemgdeok.metadq.core.RuleSpec.SchemaColumnRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RuleConfigParser {
  private static final Pattern RULE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  private static final Pattern DURATION = Pattern.compile("([1-9][0-9]*)([smhd])");
  private static final Set<String> ROOT_KEYS = Set.of("version", "rules");
  private static final Set<String> ROW_COUNT_KEYS = Set.of("id", "type", "min", "max");
  private static final Set<String> NULL_RATIO_KEYS = Set.of("id", "type", "column", "max");
  private static final Set<String> COMMIT_AGE_KEYS = Set.of("id", "type", "max");
  private static final Set<String> SCHEMA_KEYS =
      Set.of("id", "type", "column", "data_type", "nullable");

  private final ObjectMapper mapper =
      new ObjectMapper(
          YAMLFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

  public List<RuleSpec> parse(Path path) throws IOException {
    if (!Files.isRegularFile(path)) {
      throw new IllegalArgumentException("Rules file not found: " + path);
    }
    JsonNode root;
    try {
      root = mapper.readTree(path.toFile());
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(
          "Invalid rules YAML: " + exception.getOriginalMessage(), exception);
    }
    return parse(root);
  }

  List<RuleSpec> parse(JsonNode root) {
    requireObject(root, "root");
    rejectUnknown(root, ROOT_KEYS, "root");
    JsonNode version = root.get("version");
    if (version == null || !version.isInt() || version.intValue() != 1) {
      throw invalid("version", "must be integer 1");
    }
    JsonNode rulesNode = root.get("rules");
    if (rulesNode == null || !rulesNode.isArray() || rulesNode.isEmpty()) {
      throw invalid("rules", "must be a non-empty array");
    }

    List<RuleSpec> rules = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (int index = 0; index < rulesNode.size(); index++) {
      String path = "rules[" + index + "]";
      JsonNode node = rulesNode.get(index);
      requireObject(node, path);
      String id = requiredText(node, "id", path);
      if (!RULE_ID.matcher(id).matches()) {
        throw invalid(path + ".id", "must match " + RULE_ID.pattern());
      }
      if (!ids.add(id)) {
        throw invalid(path + ".id", "duplicate rule id: " + id);
      }
      String type = requiredText(node, "type", path);
      rules.add(parseRule(node, id, type, path));
    }
    return List.copyOf(rules);
  }

  private RuleSpec parseRule(JsonNode node, String id, String type, String path) {
    return switch (type) {
      case "table.row_count" -> parseRowCount(node, id, path);
      case "column.null_ratio" -> parseNullRatio(node, id, path);
      case "table.last_commit_age" -> parseLastCommitAge(node, id, path);
      case "schema.column" -> parseSchemaColumn(node, id, path);
      default -> throw invalid(path + ".type", "unsupported rule type: " + type);
    };
  }

  private RowCountRule parseRowCount(JsonNode node, String id, String path) {
    rejectUnknown(node, ROW_COUNT_KEYS, path);
    Long min = optionalLong(node, "min", path);
    Long max = optionalLong(node, "max", path);
    if (min == null && max == null) {
      throw invalid(path, "table.row_count requires min, max, or both");
    }
    if ((min != null && min < 0) || (max != null && max < 0)) {
      throw invalid(path, "row-count bounds must be non-negative");
    }
    if (min != null && max != null && min > max) {
      throw invalid(path, "min must not exceed max");
    }
    return new RowCountRule(id, min, max);
  }

  private NullRatioRule parseNullRatio(JsonNode node, String id, String path) {
    rejectUnknown(node, NULL_RATIO_KEYS, path);
    String column = requiredText(node, "column", path);
    JsonNode maxNode = node.get("max");
    if (maxNode == null || !maxNode.isNumber()) {
      throw invalid(path + ".max", "must be a number between 0 and 1");
    }
    double max = maxNode.doubleValue();
    if (!Double.isFinite(max) || max < 0 || max > 1) {
      throw invalid(path + ".max", "must be between 0 and 1");
    }
    return new NullRatioRule(id, column, max);
  }

  private LastCommitAgeRule parseLastCommitAge(JsonNode node, String id, String path) {
    rejectUnknown(node, COMMIT_AGE_KEYS, path);
    String max = requiredText(node, "max", path);
    Matcher matcher = DURATION.matcher(max);
    if (!matcher.matches()) {
      throw invalid(path + ".max", "must be a positive integer followed by s, m, h, or d");
    }
    long amount;
    try {
      amount = Long.parseLong(matcher.group(1));
    } catch (NumberFormatException exception) {
      throw invalid(path + ".max", "duration is too large");
    }
    try {
      Duration duration =
          switch (matcher.group(2)) {
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> throw new IllegalStateException("validated duration unit");
          };
      return new LastCommitAgeRule(id, duration);
    } catch (ArithmeticException exception) {
      throw invalid(path + ".max", "duration is too large");
    }
  }

  private SchemaColumnRule parseSchemaColumn(JsonNode node, String id, String path) {
    rejectUnknown(node, SCHEMA_KEYS, path);
    String column = requiredText(node, "column", path);
    String dataType = optionalText(node, "data_type", path);
    Boolean nullable = optionalBoolean(node, "nullable", path);
    return new SchemaColumnRule(id, column, dataType, nullable);
  }

  private static void requireObject(JsonNode node, String path) {
    if (node == null || !node.isObject()) {
      throw invalid(path, "must be an object");
    }
  }

  private static String requiredText(JsonNode node, String key, String path) {
    String value = optionalText(node, key, path);
    if (value == null) {
      throw invalid(path + "." + key, "is required");
    }
    return value;
  }

  private static String optionalText(JsonNode node, String key, String path) {
    JsonNode value = node.get(key);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw invalid(path + "." + key, "must be a non-blank string");
    }
    return value.textValue();
  }

  private static Long optionalLong(JsonNode node, String key, String path) {
    JsonNode value = node.get(key);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw invalid(path + "." + key, "must be a 64-bit integer");
    }
    return value.longValue();
  }

  private static Boolean optionalBoolean(JsonNode node, String key, String path) {
    JsonNode value = node.get(key);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isBoolean()) {
      throw invalid(path + "." + key, "must be true or false");
    }
    return value.booleanValue();
  }

  private static void rejectUnknown(JsonNode node, Set<String> allowed, String path) {
    Iterator<String> fields = node.fieldNames();
    while (fields.hasNext()) {
      String field = fields.next();
      if (!allowed.contains(field)) {
        throw invalid(path + "." + field, "unknown property");
      }
    }
  }

  private static IllegalArgumentException invalid(String path, String message) {
    return new IllegalArgumentException(path + ": " + message);
  }
}
