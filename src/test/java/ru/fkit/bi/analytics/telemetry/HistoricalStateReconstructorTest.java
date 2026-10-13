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

  private static final UUID RUNNING=UUID.fromString("00000000-0000-0000-0000-000000000101");
  private static final UUID MODE=UUID.fromString("00000000-0000-0000-0000-000000000102");
  private static final String RUN_SETUP_CONFIG="""
      {"defaultState":"IDLE","offlineAfterMs":120000,"rules":[
       {"id":"00000000-0000-0000-0000-0000000000a2","name":"setup","priority":80,"targetState":"SETUP",
        "enterCondition":{"type":"COMPARE","signalId":"%2$s","operator":"IN","values":["SETUP","CIP"]},
        "exitCondition":null,"activationDelayMs":0,"releaseDelayMs":0},
       {"id":"00000000-0000-0000-0000-0000000000a4","name":"run","priority":50,"targetState":"RUN",
        "enterCondition":{"type":"COMPARE","signalId":"%1$s","operator":"EQ","value":true},
        "exitCondition":null,"activationDelayMs":5000,"releaseDelayMs":0}]}
      """;

  /** A replay anchored where its own events change the state must not keep the carry-in state label. */
  @Test void anchorAtTransitionTakesStateOfEventsAtThatInstant() throws Exception {
    var config=new ObjectMapper().readTree(RUN_SETUP_CONFIG.formatted(RUNNING,MODE));
    var specs=Map.of(RUNNING,new HistoricalStateReconstructor.SignalSpec("BOOLEAN",30_000),MODE,new HistoricalStateReconstructor.SignalSpec("STRING",30_000));
    var carry=List.of(event(RUNNING,"BOOLEAN",true,START.minusSeconds(10)),event(MODE,"STRING","AUTO",START.minusSeconds(10)));
    var events=new java.util.ArrayList<HistoricalStateReconstructor.Event>();
    for(int s=0;s<=20;s+=10){events.add(event(RUNNING,"BOOLEAN",false,START.plusSeconds(s)));events.add(event(MODE,"STRING","SETUP",START.plusSeconds(s)));}

    var result=new HistoricalStateReconstructor().reconstruct(config,specs,carry,events,START,START.plusSeconds(30),START,"SETUP");

    assertThat(result).containsExactly(new HistoricalStateReconstructor.Interval(START,null,"SETUP"));
  }

  /** Stopping exactly at the replay start yields IDLE from that instant, without a short carry-in RUN fragment. */
  @Test void stopAtReplayStartDoesNotLeaveRunFragment() throws Exception {
    var config=new ObjectMapper().readTree(RUN_SETUP_CONFIG.formatted(RUNNING,MODE));
    var specs=Map.of(RUNNING,new HistoricalStateReconstructor.SignalSpec("BOOLEAN",30_000),MODE,new HistoricalStateReconstructor.SignalSpec("STRING",30_000));
    var carry=List.of(event(RUNNING,"BOOLEAN",true,START.minusSeconds(10)),event(MODE,"STRING","AUTO",START.minusSeconds(10)));
    var events=new java.util.ArrayList<HistoricalStateReconstructor.Event>();
    for(int s=0;s<=20;s+=10){events.add(event(RUNNING,"BOOLEAN",false,START.plusSeconds(s)));events.add(event(MODE,"STRING","AUTO",START.plusSeconds(s)));}

    var result=new HistoricalStateReconstructor().reconstruct(config,specs,carry,events,START,START.plusSeconds(30),START,"IDLE");

    assertThat(result).containsExactly(new HistoricalStateReconstructor.Interval(START,null,"IDLE"));
  }

  private static HistoricalStateReconstructor.Event event(UUID signal,String type,Object value,Instant at){
    return new HistoricalStateReconstructor.Event(signal,type,value,at,at,30_000,0,0);
  }
}
