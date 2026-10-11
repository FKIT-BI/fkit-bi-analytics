package ru.fkit.bi.analytics.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Guards the stable Analytics API operation inventory consumed by the separately-owned Web client. */
class AnalyticsOpenApiContractTest {
  private static final Set<String> OPERATIONS = Set.of(
      "listEnterprises", "createEnterprise", "getEnterprise", "updateEnterprise", "updateEnterpriseStatus",
      "listShifts", "createShift", "getShift", "updateShift", "updateShiftStatus",
      "listLossReasons", "createLossReason", "getLossReason", "updateLossReason", "updateLossReasonStatus",
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
  }
}
