package ru.fkit.bi.analytics.line;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class LineModels {
  private LineModels() {}
  public record Create(@NotBlank @Pattern(regexp="^[A-Z][A-Z0-9_]{0,63}$") String code,
      @NotBlank @Size(max=200) String name, @Min(0) Integer sortOrder) {}
  public record Update(@NotBlank @Size(max=200) String name, @NotNull @Min(0) Integer sortOrder) {}
  public record StatusUpdate(@NotBlank @Pattern(regexp="^(ACTIVE|DISABLED)$") String status) {}
  public record Line(UUID id, UUID enterpriseId, String code, String name, int sortOrder, String status, Instant createdAt, Instant updatedAt) {}
  public record Metadata(int number,int size,long totalElements,int totalPages) {}
  public record Page<T>(List<T> items, Metadata page) {}
}
