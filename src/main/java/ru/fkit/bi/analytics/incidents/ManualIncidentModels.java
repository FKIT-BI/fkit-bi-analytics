package ru.fkit.bi.analytics.incidents;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ManualIncidentModels {
  private ManualIncidentModels() {}
  public record Create(@NotNull Instant from,Instant to,String stateOverride,UUID lossReasonId,@Size(max=500) String comment,@Size(max=100) String author) {}
  public record Update(@NotNull Instant from,Instant to,String stateOverride,UUID lossReasonId,@Size(max=500) String comment) {}
  public record Close(@NotNull Instant to) {}
  public record LossReasonSummary(UUID id,String code,String name,String category,String status,String color) {}
  public record MachineReference(UUID id,String code,String name,UUID lineId,String lineCode,String lineName) {}
  public record Incident(UUID id,UUID machineId,MachineReference machine,Instant from,Instant to,String stateOverride,
      LossReasonSummary lossReason,String effectType,String comment,String author,Instant createdAt,Instant updatedAt,
      Instant cancelledAt,boolean recalculating) {}
  public record Cursor(int limit,boolean hasMore,String nextCursor) {}
  public record Page(List<Incident> items,Cursor cursor) {}
}
