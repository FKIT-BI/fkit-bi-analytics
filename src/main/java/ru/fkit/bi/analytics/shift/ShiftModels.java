package ru.fkit.bi.analytics.shift;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import ru.fkit.bi.analytics.enterprise.EnterpriseModels.Metadata;

public final class ShiftModels {
  private ShiftModels() {}
  public record Create(@NotBlank @Pattern(regexp="^[A-Z][A-Z0-9_]{0,63}$") String code,
      @NotBlank @Size(max=200) String name,@NotBlank @Pattern(regexp="^([01][0-9]|2[0-3]):[0-5][0-9]$") String startLocalTime,
      @NotBlank @Pattern(regexp="^([01][0-9]|2[0-3]):[0-5][0-9]$") String endLocalTime,
      @NotNull LocalDate validFrom, LocalDate validTo, @Min(0) Integer sortOrder) {}
  public record Update(@NotBlank @Size(max=200) String name,@NotBlank @Pattern(regexp="^([01][0-9]|2[0-3]):[0-5][0-9]$") String startLocalTime,
      @NotBlank @Pattern(regexp="^([01][0-9]|2[0-3]):[0-5][0-9]$") String endLocalTime,
      @NotNull LocalDate validFrom, LocalDate validTo, @NotNull @Min(0) Integer sortOrder) {}
  public record StatusUpdate(@NotBlank @Pattern(regexp="^(ACTIVE|DISABLED)$") String status) {}
  public record Shift(UUID id,UUID enterpriseId,String code,String name,LocalTime startLocalTime,LocalTime endLocalTime,
      LocalDate validFrom,LocalDate validTo,int sortOrder,String status,boolean used,Instant createdAt,Instant updatedAt) {}
  public record Page(List<Shift> items, Metadata page) {}
}
