package ru.fkit.bi.analytics.incidents;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ru.fkit.bi.analytics.enterprise.EnterpriseModels.Metadata;

public final class LossReasonModels {
  private LossReasonModels() {}
  public static final List<String> CATEGORIES=List.of("EQUIPMENT","MATERIAL","PERSONNEL","PROCESS","QUALITY","CHANGEOVER","EXTERNAL","OTHER");
  public record Create(@NotBlank @Pattern(regexp="^[A-Z][A-Z0-9_]{0,63}$") String code,@NotBlank @Size(max=200) String name,
      @Size(max=500) String description,@NotBlank String category,@Pattern(regexp="^#[0-9A-Fa-f]{6}$") String color) {}
  public record Update(@NotBlank @Size(max=200) String name,@Size(max=500) String description,@NotBlank String category,
      @Pattern(regexp="^#[0-9A-Fa-f]{6}$") String color) {}
  public record StatusUpdate(@NotBlank @Pattern(regexp="^(ACTIVE|DISABLED)$") String status) {}
  public record LossReason(UUID id,UUID enterpriseId,String code,String name,String description,String category,String color,
      String status,boolean used,Instant createdAt,Instant updatedAt) {}
  public record Page(List<LossReason> items,Metadata page) {}
}
