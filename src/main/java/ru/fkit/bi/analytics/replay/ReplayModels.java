package ru.fkit.bi.analytics.replay;

import java.time.Instant;
import java.util.UUID;

public final class ReplayModels {
  private ReplayModels() { }
  public record Create(String scope, Instant from, Instant to) { }
  public record Job(UUID id, UUID machineId, String scope, Instant requestedFrom, Instant requestedTo,
      Instant effectiveFrom, Instant effectiveTo, String status, double progress,
      Instant createdAt, Instant startedAt, Instant completedAt, JobError error) { }
  public record JobError(String code, String message) { }
}
