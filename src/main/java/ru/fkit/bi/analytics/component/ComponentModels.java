package ru.fkit.bi.analytics.component;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class ComponentModels {
  private ComponentModels(){}
  public record Create(UUID parentComponentId,@NotBlank @Pattern(regexp="^[A-Z][A-Z0-9_]{0,63}$") String code,@NotBlank @Size(max=200) String name,@NotBlank String kind,@NotNull @Min(0) Integer sortOrder,@NotNull @Size(max=100) List<JsonNode> parameters){}
  public record Update(@NotBlank @Size(max=200) String name,@NotBlank String kind,@NotNull @Min(0) Integer sortOrder,@NotNull @Size(max=100) List<JsonNode> parameters){}
  public record ParentUpdate(UUID parentComponentId){}
  public record StatusUpdate(@NotBlank @Pattern(regexp="^(ACTIVE|DISABLED)$") String status){}
  public record Component(UUID id,UUID machineId,UUID parentComponentId,String code,String name,String kind,int sortOrder,String status,List<JsonNode> parameters,Instant createdAt,Instant updatedAt){}
}
