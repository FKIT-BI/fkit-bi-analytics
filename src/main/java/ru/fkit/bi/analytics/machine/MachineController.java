package ru.fkit.bi.analytics.machine;

import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.fkit.bi.analytics.machine.MachineModels.*;

@RestController
class MachineController {
  private final MachineService service;
  MachineController(MachineService service){this.service=service;}
  @GetMapping("/api/v1/enterprises/{enterpriseId}/machines") Page<Machine> enterprisePage(@PathVariable UUID enterpriseId,@RequestParam(required=false) UUID lineId,@RequestParam(defaultValue="ALL") String assignment,@RequestParam(defaultValue="ACTIVE") String status,@RequestParam(required=false) String search,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){return service.enterprisePage(enterpriseId,lineId,assignment,status,search,page,size);}
  @PostMapping("/api/v1/enterprises/{enterpriseId}/machines") ResponseEntity<Machine> create(@PathVariable UUID enterpriseId,@Valid @RequestBody Create in){Machine m=service.create(enterpriseId,in);return ResponseEntity.created(URI.create("/api/v1/machines/"+m.id())).body(m);}
  @GetMapping("/api/v1/lines/{lineId}/machines") Page<Machine> linePage(@PathVariable UUID lineId,@RequestParam(defaultValue="ACTIVE") String status,@RequestParam(required=false) String search,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){return service.linePage(lineId,status,search,page,size);}
  @PostMapping("/api/v1/lines/{lineId}/machine-assignments") Map<String,List<Machine>> assign(@PathVariable UUID lineId,@Valid @RequestBody Assignments in){return Map.of("items",service.assign(lineId,in));}
  @GetMapping("/api/v1/machines/{machineId}") Machine get(@PathVariable UUID machineId){return service.get(machineId);}
  @PutMapping("/api/v1/machines/{machineId}") Machine update(@PathVariable UUID machineId,@Valid @RequestBody Update in){return service.update(machineId,in);}
  @PutMapping("/api/v1/machines/{machineId}/status") Machine status(@PathVariable UUID machineId,@Valid @RequestBody StatusUpdate in){return service.status(machineId,in);}
  @GetMapping("/api/v1/machines/{machineId}/activation-readiness") Map<String,Object> readiness(@PathVariable UUID machineId){return Map.of("machineId",machineId,"ready",service.ready(machineId),"errors",service.readinessIssues(machineId),"warnings",service.readinessWarnings(machineId),"checkedAt",Instant.now());}
}
