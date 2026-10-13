package ru.fkit.bi.analytics.machine;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MachineModels {
  private MachineModels() {}
  public record Create(@NotBlank @Pattern(regexp="^[A-Z][A-Z0-9_]{0,63}$") String code,
      @NotBlank @Size(max=200) String name, UUID lineId, @Size(max=100) String machineType,
      @Size(max=100) String inventoryNo, @Size(max=200) String manufacturer, @Size(max=200) String model,
      @Min(1900) @Max(9999) Integer commissioningYear, @DecimalMin(value="0",inclusive=false) Double nominalRate,
      @Size(max=64) String productionUnit, @Min(0) Integer sortOrder, @NotNull @Size(max=100) List<JsonNode> parameters) {}
  public record Update(@NotBlank @Size(max=200) String name, @Size(max=100) String machineType,
      @Size(max=100) String inventoryNo, @Size(max=200) String manufacturer, @Size(max=200) String model,
      @Min(1900) @Max(9999) Integer commissioningYear, @DecimalMin(value="0",inclusive=false) Double nominalRate,
      @Size(max=64) String productionUnit, @Min(0) Integer sortOrder, @NotNull @Size(max=100) List<JsonNode> parameters) {}
  public record StatusUpdate(@NotBlank @Pattern(regexp="^(ACTIVE|DISABLED)$") String status) {}
  public record Assignments(@NotEmpty @Size(max=200) List<UUID> machineIds) {}
  public record Machine(UUID id, UUID enterpriseId, UUID lineId, String code, String name, String machineType,
      String inventoryNo, String manufacturer, String model, Integer commissioningYear, Double nominalRate,
      String productionUnit, Integer sortOrder, String status, Instant coverageFrom, List<JsonNode> parameters,
      Instant createdAt, Instant updatedAt) {}
  public record EquipmentTreeEnterprise(UUID id, String code, String name, String timezone, String status) {}
  public record EquipmentTreeMachine(UUID id, String code, String name, String inventoryNo, String machineType,
      Integer sortOrder, String status) {}
  public record EquipmentTreeLine(UUID id, String code, String name, int sortOrder, String status,
      List<EquipmentTreeMachine> machines) {}
  public record EquipmentTree(EquipmentTreeEnterprise enterprise, List<EquipmentTreeLine> lines,
      List<EquipmentTreeMachine> unassignedMachines, Instant generatedAt) {}
  public record Metadata(int number,int size,long totalElements,int totalPages) {}
  public record Page<T>(List<T> items, Metadata page) {}
}
