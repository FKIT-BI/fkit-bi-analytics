package ru.fkit.bi.analytics.telemetry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CurrentTelemetryModels {
  private CurrentTelemetryModels() { }

  public record Response(UUID machineId, List<SignalValue> items, Currency currency, boolean truncated) { }
  public record Currency(Instant dataThrough, Instant calculatedAt, boolean stale, boolean recalculating) { }
  public record SignalValue(UUID signalId, UUID componentId, String code, String name, String valueType,
      String signalKind, String semanticCode, String unitCode, boolean featured, Integer displayOrder,
      String status, Object lastReceivedValue, Instant lastReceivedObservedAt, Instant lastReceivedAt,
      String quality, List<String> validationFlags, Object lastUsableValue, Instant lastUsableObservedAt,
      String freshness, Instant dataThrough, Instant calculatedAt, boolean stale) { }
  public record CurrentState(String effectiveState, String calculatedState, String stateSource,
      Instant stateSince, UUID matchedRuleId, UUID stateIncidentId, Object lossReason,
      String evaluationQuality, Instant lastTelemetryObservedAt, boolean underlyingOffline,
      Instant dataThrough, Instant calculatedAt, boolean stale, boolean recalculating) { }
}
