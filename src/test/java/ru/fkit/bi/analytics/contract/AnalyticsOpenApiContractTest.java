package ru.fkit.bi.analytics.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Guards the stable Analytics API operation inventory consumed by the separately-owned Web client. */
class AnalyticsOpenApiContractTest {
  private static final Set<String> OPERATIONS = Set.of(
      "login", "createUser",
      "listEnterprises", "createEnterprise", "getEnterprise", "updateEnterprise", "updateEnterpriseStatus",
      "listShifts", "createShift", "getShift", "updateShift", "updateShiftStatus",
      "listLossReasons", "createLossReason", "getLossReason", "updateLossReason", "updateLossReasonStatus",
      "listMeasurementUnits",
      "listLines", "createLine", "getLine", "updateLine", "updateLineStatus",
      "listEnterpriseMachines", "createMachine", "listLineMachines", "getMachine", "updateMachine",
      "updateMachineStatus", "getMachineActivationReadiness", "assignMachinesToLine",
      "listMachineComponents", "createComponent", "getComponent", "updateComponent", "updateComponentParent",
      "updateComponentStatus", "listTelemetrySignals", "createTelemetrySignal", "getTelemetrySignal",
      "updateTelemetrySignal", "updateTelemetrySignalStatus", "getMachineStateConfiguration",
      "replaceMachineStateConfiguration", "validateMachineStateConfiguration", "getEquipmentTree",
      "getEnterpriseOperationsOverview", "getMachineOperationsCurrent", "getMachineTelemetryCurrent",
      "getMachineCurrentState", "getAnalyticsSummary", "getMetricTimeseries", "getTelemetrySeries",
      "getLossTimeseries", "getLossPareto", "getMachineChronometry", "listMachineStateIntervals",
      "listEnterpriseIncidents", "listMachineIncidents", "createManualIncident", "getManualIncident",
      "updateManualIncident", "cancelManualIncident", "closeManualIncident", "listMachineStateRecalculations",
      "createStateRecalculation", "getStateRecalculation", "cancelStateRecalculation");

  @Test
  void canonicalOpenApiHasUniqueStableSecuredOperationInventory() throws Exception {
    String yaml = Files.readString(Path.of("docs/contracts/analytics-api-draft.yaml"));
    Matcher matcher = Pattern.compile("(?m)^\\s+operationId: ([A-Za-z0-9]+)\\s*$").matcher(yaml);
    Set<String> actual = new HashSet<>();
    while (matcher.find()) assertTrue(actual.add(matcher.group(1)), "duplicate operationId " + matcher.group(1));
    assertEquals(OPERATIONS, actual);
    assertTrue(yaml.startsWith("openapi: 3.1.0"));
    assertTrue(yaml.contains("security:\n  - bearerAuth: []"), "Analytics operations require shared JWT authentication");
    assertTrue(yaml.contains("bearerAuth:\n      type: http\n      scheme: bearer\n      bearerFormat: JWT"));
    assertDocumentedOperationsAndBodies(yaml);
    assertParameterExamples(yaml);
  }

  @Test
  void contractDocumentsStableHarBehaviour() throws Exception {
    String yaml = Files.readString(Path.of("docs/contracts/analytics-api-draft.yaml"));
    assertTrue(yaml.contains("    Limit:\n      name: limit\n      in: query\n      example: 100\n      schema: { type: integer, minimum: 1, maximum: 500, default: 100 }"),
        "cursor limit must default to 100 and allow 500");
    assertTrue(yaml.contains("  /reference/measurement-units:"), "unit selectors need the active unit catalog");
    assertTrue(yaml.matches("(?s).*\\n    MeasurementUnit:\\n.*required: \\[code, name\\].*"), "MeasurementUnit schema is required");
    assertTrue(yaml.contains("productionUnit: [nominalRate]"), "nominalRate/productionUnit dependency must be explicit");
    assertTrue(yaml.contains("fieldErrors:\n          - { path: productionUnit, code: UNKNOWN_UNIT"), "ErrorResponse needs a field-level example");
    assertTrue(yaml.contains("будущая граница даёт partial=true"), "live periods must be documented as partial, not 422");
  }

  @Test
  void canonicalContractIsPackagedForStaticSwaggerUi() throws Exception {
    try (InputStream contract = getClass().getClassLoader().getResourceAsStream("static/openapi/analytics-api-draft.yaml")) {
      assertTrue(contract != null, "canonical contract must be included in the application jar");
      String packaged = new String(contract.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertEquals(Files.readString(Path.of("docs/contracts/analytics-api-draft.yaml")), packaged);
    }
  }

  private void assertDocumentedOperationsAndBodies(String yaml) {
    Matcher operations = Pattern.compile("(?ms)^      operationId: ([A-Za-z0-9]+)\\n(.*?)(?=^      operationId:|^components:)").matcher(yaml);
    while (operations.find()) {
      String operationId = operations.group(1);
      String operation = operations.group(2);
      assertTrue(operation.matches("(?sm).*^      description: .+"), "missing operation description: " + operationId);
      Matcher bodies = Pattern.compile("(?ms)^      requestBody:\\n(.*?)(?=^      responses:)").matcher(operation);
      while (bodies.find()) {
        String body = bodies.group(1);
        if (body.matches("(?sm).*^\\s+examples:\\n.*")) continue;
        Matcher reference = Pattern.compile("#/components/schemas/([A-Za-z0-9]+)").matcher(body);
        assertTrue(reference.find(), "request body has neither an inline example nor a schema example: " + operationId);
        String schemaName = reference.group(1);
        assertTrue(yaml.matches("(?sm).*^    " + Pattern.quote(schemaName) + ":\\n      type: object\\n      example:.*"),
            "request body schema has no complete example: " + operationId + " -> " + schemaName);
      }
    }
  }

  private void assertParameterExamples(String yaml) {
    Matcher references = Pattern.compile("(?m)^\\s*- \\$ref: '#/components/parameters/([A-Za-z0-9_-]+)'").matcher(yaml);
    while (references.find()) {
      String name = references.group(1);
      Matcher component = Pattern.compile("(?ms)^    " + Pattern.quote(name)
          + ":\\n(.*?)(?=^    [A-Za-z0-9_-]+:\\n|^  (?:responses|schemas|examples):)").matcher(yaml);
      assertTrue(component.find(), "missing parameter definition: " + name);
      assertHasExample(component.group(1), name);
    }

    Matcher inlineParameters = Pattern.compile("(?ms)^        - name: ([^\\n]+)\\n(.*?)(?=^        - (?:name:|\\$ref:)|^      (?:requestBody|responses):)").matcher(yaml);
    while (inlineParameters.find()) assertHasExample(inlineParameters.group(2), inlineParameters.group(1));

    Matcher pathParameters = Pattern.compile("(?ms)^      - name: ([^\\n]+)\\n(.*?)(?=^      - (?:name:|\\$ref:)|^    (?:get|post|put|delete|patch):)").matcher(yaml);
    while (pathParameters.find()) assertHasExample(pathParameters.group(2), pathParameters.group(1));
  }

  private void assertHasExample(String parameter, String name) {
    assertTrue(Pattern.compile("(?ms)^\\s*example:\\s*\\S.*$|[,\\{]\\s*example:\\s*[^\\s,}]+|^\\s+examples:\\s*\\n\\s+[A-Za-z0-9_-]+:\\s*\\n\\s+(?:summary|value|externalValue):\\s*\\S.*$")
        .matcher(parameter).find(), "missing non-empty parameter example: " + name);
  }
}
