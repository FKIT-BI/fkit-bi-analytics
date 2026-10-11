package ru.fkit.bi.analytics.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HistoricalStateReconstructorTest {
  private static final UUID SIGNAL=UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID RULE=UUID.fromString("00000000-0000-0000-0000-000000000010");
  private static final Instant START=Instant.parse("2026-01-01T00:00:00Z");

  @Test void replaysActivationDeadlineIntoNonOverlappingStateIntervals() throws Exception {
    var config=new ObjectMapper().readTree("""
        {"defaultState":"IDLE","offlineAfterMs":60000,"rules":[{"id":"%s","name":"run","priority":10,
        "targetState":"RUN","enterCondition":{"type":"COMPARE","signalId":"%s","operator":"GTE","value":100},
        "activationDelayMs":2000,"releaseDelayMs":0}]}
        """.formatted(RULE,SIGNAL));
    var spec=Map.of(SIGNAL,new HistoricalStateReconstructor.SignalSpec("DOUBLE",30_000));
    var event=new HistoricalStateReconstructor.Event(SIGNAL,"DOUBLE",120d,START,START,30_000,0,0);
    var result=new HistoricalStateReconstructor().reconstruct(config,spec,List.of(),List.of(event),START,START.plusSeconds(10),START,"IDLE");

    assertThat(result).containsExactly(
        new HistoricalStateReconstructor.Interval(START,START.plusSeconds(2),"IDLE"),
        new HistoricalStateReconstructor.Interval(START.plusSeconds(2),null,"RUN"));
  }

  @Test void preservesAnchorStateUntilCarryInActivationBoundary() throws Exception {
    var config=new ObjectMapper().readTree("""
        {"defaultState":"IDLE","offlineAfterMs":60000,"rules":[{"id":"%s","name":"run","priority":10,
        "targetState":"RUN","enterCondition":{"type":"COMPARE","signalId":"%s","operator":"GTE","value":100},
        "activationDelayMs":2000,"releaseDelayMs":0}]}
        """.formatted(RULE,SIGNAL));
    var spec=Map.of(SIGNAL,new HistoricalStateReconstructor.SignalSpec("DOUBLE",30_000));
    var carry=new HistoricalStateReconstructor.Event(SIGNAL,"DOUBLE",120d,START,START,30_000,0,0);
    var result=new HistoricalStateReconstructor().reconstruct(config,spec,List.of(carry),List.of(),START,START.plusSeconds(10),START,"IDLE");
    assertThat(result).containsExactly(new HistoricalStateReconstructor.Interval(START,START.plusSeconds(2),"IDLE"),
        new HistoricalStateReconstructor.Interval(START.plusSeconds(2),null,"RUN"));
  }
}
