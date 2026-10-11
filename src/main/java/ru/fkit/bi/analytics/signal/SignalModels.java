package ru.fkit.bi.analytics.signal;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import java.util.UUID;

public final class SignalModels {
  private SignalModels(){}
  public record Create(UUID componentId,@NotBlank @Pattern(regexp="^[A-Z][A-Z0-9_]{0,63}$") String code,@NotBlank @Size(max=200) String name,@Size(max=500) String description,@NotBlank String valueType,@NotBlank String signalKind,@NotBlank @Size(max=64) String semanticCode,@Pattern(regexp="^[A-Z][A-Z0-9_]{0,63}$") String customSemanticCode,@Size(max=50) String unitCode,@Positive Long expectedIntervalMs,@Positive Long staleAfterMs,Double validMin,Double validMax,boolean featured,@Min(0) Integer displayOrder){}
  public record Update(UUID componentId,@NotBlank @Size(max=200) String name,@Size(max=500) String description,@NotBlank String valueType,@NotBlank String signalKind,@NotBlank @Size(max=64) String semanticCode,@Pattern(regexp="^[A-Z][A-Z0-9_]{0,63}$") String customSemanticCode,@Size(max=50) String unitCode,@Positive Long expectedIntervalMs,@Positive Long staleAfterMs,Double validMin,Double validMax,boolean featured,@Min(0) Integer displayOrder){}
  public record StatusUpdate(@NotBlank @Pattern(regexp="^(ACTIVE|DISABLED)$") String status){}
  public record Signal(UUID id,UUID machineId,UUID componentId,String code,String name,String description,String valueType,String signalKind,String semanticCode,String customSemanticCode,String unitCode,Long expectedIntervalMs,Long staleAfterMs,Double validMin,Double validMax,boolean featured,Integer displayOrder,String status,boolean hasRawData,Instant createdAt,Instant updatedAt){}
  public record Metadata(int number,int size,long totalElements,int totalPages){}
  public record Page<T>(List<T> items,Metadata page){}
}
