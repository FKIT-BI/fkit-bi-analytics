package ru.fkit.bi.analytics.shift;

import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.fkit.bi.analytics.shift.ShiftModels.*;

@RestController
public class ShiftController {
  private final ShiftService service;
  ShiftController(ShiftService service){this.service=service;}
  @GetMapping("/api/v1/enterprises/{enterpriseId}/shifts")
  public Page page(@PathVariable UUID enterpriseId,@RequestParam(defaultValue="ACTIVE") String status,
      @RequestParam(required=false) LocalDate validOn,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){return service.page(enterpriseId,status,validOn,page,size);}
  @PostMapping("/api/v1/enterprises/{enterpriseId}/shifts")
  public ResponseEntity<Shift> create(@PathVariable UUID enterpriseId,@Valid @RequestBody Create input){
    Shift value=service.create(enterpriseId,input);return ResponseEntity.created(URI.create("/api/v1/shifts/"+value.id())).body(value);
  }
  @GetMapping("/api/v1/shifts/{shiftId}") public Shift get(@PathVariable UUID shiftId){return service.get(shiftId);}
  @PutMapping("/api/v1/shifts/{shiftId}") public Shift update(@PathVariable UUID shiftId,@Valid @RequestBody Update input){return service.update(shiftId,input);}
  @PutMapping("/api/v1/shifts/{shiftId}/status") public Shift status(@PathVariable UUID shiftId,@Valid @RequestBody StatusUpdate input){return service.status(shiftId,input);}
}
