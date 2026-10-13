package ru.fkit.bi.analytics.incidents;

import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.fkit.bi.analytics.incidents.ManualIncidentModels.*;

@RestController
@RequestMapping("/api/v1")
class ManualIncidentController {
  private final IncidentService service;
  ManualIncidentController(IncidentService service){this.service=service;}
  @GetMapping("/machines/{machineId}/incidents") Page machine(@PathVariable UUID machineId,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,
      @RequestParam(required=false) String effectType,@RequestParam(required=false) UUID lossReasonId,@RequestParam(required=false) Boolean ongoing,
      @RequestParam(defaultValue="false") boolean includeCancelled,@RequestParam(defaultValue="100") int limit,@RequestParam(required=false) String cursor){return service.list(machineId,null,null,from,to,effectType,lossReasonId,ongoing,includeCancelled,limit,cursor);}
  @GetMapping("/enterprises/{enterpriseId}/incidents") Page enterprise(@PathVariable UUID enterpriseId,@RequestParam(required=false) UUID machineId,@RequestParam(required=false) UUID lineId,
      @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,@RequestParam(required=false) String effectType,
      @RequestParam(required=false) UUID lossReasonId,@RequestParam(required=false) Boolean ongoing,@RequestParam(defaultValue="false") boolean includeCancelled,
      @RequestParam(defaultValue="100") int limit,@RequestParam(required=false) String cursor){return service.list(machineId,enterpriseId,lineId,from,to,effectType,lossReasonId,ongoing,includeCancelled,limit,cursor);}
  @PostMapping("/machines/{machineId}/incidents") ResponseEntity<Incident> create(@PathVariable UUID machineId,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Create body){Incident i=service.create(machineId,body,key);return ResponseEntity.created(URI.create("/api/v1/incidents/"+i.id())).body(i);}
  @GetMapping("/incidents/{incidentId}") Incident get(@PathVariable UUID incidentId){return service.get(incidentId);}
  @PutMapping("/incidents/{incidentId}") Incident update(@PathVariable UUID incidentId,@Valid @RequestBody Update body){return service.update(incidentId,body);}
  @PutMapping("/incidents/{incidentId}/close") Incident close(@PathVariable UUID incidentId,@Valid @RequestBody Close body){return service.close(incidentId,body);}
  @DeleteMapping("/incidents/{incidentId}") ResponseEntity<Void> cancel(@PathVariable UUID incidentId){service.cancel(incidentId);return ResponseEntity.noContent().build();}
}
