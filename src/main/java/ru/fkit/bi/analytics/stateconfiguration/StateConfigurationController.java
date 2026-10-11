package ru.fkit.bi.analytics.stateconfiguration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
class StateConfigurationController {
  private final StateConfigurationService service;
  StateConfigurationController(StateConfigurationService service){this.service=service;}
  @GetMapping("/api/v1/machines/{machineId}/state-configuration") Object get(@PathVariable UUID machineId){return service.get(machineId);}
  @PutMapping("/api/v1/machines/{machineId}/state-configuration") Object replace(@PathVariable UUID machineId,@RequestBody JsonNode body){return service.replace(machineId,body);}
  @PostMapping("/api/v1/machines/{machineId}/state-configuration/validate") Object validate(@PathVariable UUID machineId,@RequestBody JsonNode body){return service.validate(machineId,body);}
}
