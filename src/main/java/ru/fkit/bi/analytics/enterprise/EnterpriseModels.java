package ru.fkit.bi.analytics.enterprise;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class EnterpriseModels {
  private EnterpriseModels() {}
  public record Create(@NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$") String code,
      @NotBlank @Size(max = 200) String name, @NotBlank @Size(max = 100) String timezone) {}
  public record Update(@NotBlank @Size(max = 200) String name, @NotBlank @Size(max = 100) String timezone) {}
  public record StatusUpdate(@NotBlank @Pattern(regexp = "^(ACTIVE|DISABLED)$") String status) {}
  public record Enterprise(UUID id, String code, String name, String timezone, String status,
      Instant createdAt, Instant updatedAt) {}
  public record Page<T>(List<T> items, Metadata page) {}
  public record Metadata(int number, int size, long totalElements, int totalPages) {}
}
