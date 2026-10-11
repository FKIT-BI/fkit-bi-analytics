package ru.fkit.bi.analytics.signal;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.fkit.bi.analytics.signal.SignalModels.*;

@RestController
class SignalController {
  private final SignalService service;SignalController(SignalService service){this.service=service;}
  @GetMapping("/api/v1/machines/{machineId}/telemetry-signals") Page<Signal> page(@PathVariable UUID machineId,@RequestParam(required=false) UUID componentId,@RequestParam(defaultValue="ACTIVE") String status,@RequestParam(required=false) String semanticCode,@RequestParam(required=false) String signalKind,@RequestParam(required=false) Boolean featured,@RequestParam(required=false) String search,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){return service.page(machineId,componentId,status,semanticCode,signalKind,featured,search,page,size);}
  @PostMapping("/api/v1/machines/{machineId}/telemetry-signals") ResponseEntity<Signal> create(@PathVariable UUID machineId,@Valid @RequestBody Create in){Signal s=service.create(machineId,in);return ResponseEntity.created(URI.create("/api/v1/telemetry-signals/"+s.id())).body(s);}
  @GetMapping("/api/v1/telemetry-signals/{signalId}") Signal get(@PathVariable UUID signalId){return service.get(signalId);}
  @PutMapping("/api/v1/telemetry-signals/{signalId}") Signal update(@PathVariable UUID signalId,@Valid @RequestBody Update in){return service.update(signalId,in);}
  @PutMapping("/api/v1/telemetry-signals/{signalId}/status") Signal status(@PathVariable UUID signalId,@Valid @RequestBody StatusUpdate in){return service.status(signalId,in);}
}
