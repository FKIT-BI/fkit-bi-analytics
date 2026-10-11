package ru.fkit.bi.analytics.telemetry;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static ru.fkit.bi.analytics.telemetry.CurrentTelemetryModels.Response;

@RestController
public class CurrentTelemetryController {
  private final CurrentTelemetryRepository repository;
  public CurrentTelemetryController(CurrentTelemetryRepository repository) { this.repository = repository; }

  @GetMapping("/api/v1/machines/{machineId}/telemetry/current")
  public Response current(@PathVariable UUID machineId, @RequestParam(required = false) List<UUID> signalId,
      @RequestParam(defaultValue = "false") boolean featuredOnly,
      @RequestParam(defaultValue = "false") boolean includeDisabled) {
    if (signalId != null && signalId.size() > 100) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most 100 signalId values are allowed");
    }
    Response response = repository.read(machineId, signalId, featuredOnly, includeDisabled);
    if (response == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Machine not found");
    return response;
  }
}
