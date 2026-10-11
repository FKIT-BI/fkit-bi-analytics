package ru.fkit.bi.analytics.incidents;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.fkit.bi.analytics.incidents.LossReasonModels.*;

@RestController
@RequestMapping("/api/v1")
class LossReasonController {
  private final IncidentService service;
  LossReasonController(IncidentService service){this.service=service;}
  @GetMapping("/enterprises/{enterpriseId}/loss-reasons") Page page(@PathVariable UUID enterpriseId,@RequestParam(defaultValue="ACTIVE") String status,
      @RequestParam(required=false) String category,@RequestParam(required=false) String search,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){return service.reasons(enterpriseId,status,category,search,page,size);}
  @PostMapping("/enterprises/{enterpriseId}/loss-reasons") ResponseEntity<LossReason> create(@PathVariable UUID enterpriseId,@Valid @RequestBody Create body){LossReason r=service.createReason(enterpriseId,body);return ResponseEntity.created(URI.create("/api/v1/loss-reasons/"+r.id())).body(r);}
  @GetMapping("/loss-reasons/{lossReasonId}") LossReason get(@PathVariable UUID lossReasonId){return service.reason(lossReasonId);}
  @PutMapping("/loss-reasons/{lossReasonId}") LossReason update(@PathVariable UUID lossReasonId,@Valid @RequestBody Update body){return service.updateReason(lossReasonId,body);}
  @PutMapping("/loss-reasons/{lossReasonId}/status") LossReason status(@PathVariable UUID lossReasonId,@Valid @RequestBody StatusUpdate body){return service.reasonStatus(lossReasonId,body);}
}
