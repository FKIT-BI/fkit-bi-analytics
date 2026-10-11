package ru.fkit.bi.analytics.component;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.fkit.bi.analytics.component.ComponentModels.*;

@RestController
class ComponentController {
  private final ComponentService service;
  ComponentController(ComponentService service){this.service=service;}
  @GetMapping("/api/v1/machines/{machineId}/components") Map<String,List<Component>> list(@PathVariable UUID machineId,@RequestParam(defaultValue="ACTIVE") String status){return Map.of("items",service.list(machineId,status));}
  @PostMapping("/api/v1/machines/{machineId}/components") ResponseEntity<Component> create(@PathVariable UUID machineId,@Valid @RequestBody Create in){Component c=service.create(machineId,in);return ResponseEntity.created(URI.create("/api/v1/components/"+c.id())).body(c);}
  @GetMapping("/api/v1/components/{componentId}") Component get(@PathVariable UUID componentId){return service.get(componentId);}
  @PutMapping("/api/v1/components/{componentId}") Component update(@PathVariable UUID componentId,@Valid @RequestBody Update in){return service.update(componentId,in);}
  @PutMapping("/api/v1/components/{componentId}/parent") Component parent(@PathVariable UUID componentId,@Valid @RequestBody ParentUpdate in){return service.parent(componentId,in);}
  @PutMapping("/api/v1/components/{componentId}/status") Component status(@PathVariable UUID componentId,@Valid @RequestBody StatusUpdate in){return service.status(componentId,in);}
}
