package ru.fkit.bi.analytics.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import ru.fkit.bi.analytics.stateconfiguration.MachineStateEvaluator;

import java.time.Instant;
import java.util.*;

/** Deterministic event-time reconstruction from an explicit preceding state context. */
final class HistoricalStateReconstructor {
  private final MachineStateEvaluator evaluator=new MachineStateEvaluator();
  record Event(UUID signalId,String valueType,Object value,Instant observedAt,Instant receivedAt,long staleAfterMs,int quality,int flags) { }
  record Interval(Instant from,Instant to,String state) { }
  record SignalSpec(String valueType,long staleAfterMs) { }
  private static final class Sample { final SignalSpec spec; Object value; Instant observed; Sample(SignalSpec spec){this.spec=spec;} }

  List<Interval> reconstruct(JsonNode config,Map<UUID,SignalSpec> specs,List<Event> carryIn,List<Event> events,
      Instant from,Instant to,Instant coverageFrom,String precedingState) {
    if(!from.isBefore(to))return List.of();
    Map<UUID,Sample> samples=new HashMap<>();specs.forEach((id,spec)->samples.put(id,new Sample(spec)));
    Map<UUID,MachineStateEvaluator.Timer> timers=new HashMap<>();
    List<Interval> intervals=new ArrayList<>();String state=precedingState;Instant stateFrom=from;
    MachineStateEvaluator.Current current=new MachineStateEvaluator.Current(state,null,from);
    Instant lastReceived=null,lastObserved=null;MachineStateEvaluator.Deadline deadline=null;
    for(Event event:carryIn){Sample sample=samples.get(event.signalId());if(sample!=null&&event.quality()==0&&(event.flags()&1)==0){sample.value=event.value();sample.observed=event.observedAt();}
      lastObserved=lastObserved==null||event.observedAt().isAfter(lastObserved)?event.observedAt():lastObserved;
      lastReceived=lastReceived==null||event.receivedAt().isAfter(lastReceived)?event.receivedAt():lastReceived;}
    Map<UUID,MachineStateEvaluator.Signal> seedSignals=signals(samples,from);
    UUID activeRule=null;
    List<JsonNode> orderedRules=new ArrayList<>();config.path("rules").forEach(orderedRules::add);
    orderedRules.sort(Comparator.comparingInt((JsonNode n)->n.path("priority").asInt()).reversed());
    for(JsonNode rule:orderedRules){UUID id=UUID.fromString(rule.path("id").asText());
      MachineStateEvaluator.Truth enter=evaluator.condition(rule.path("enterCondition"),seedSignals);
      MachineStateEvaluator.Truth exit=rule.hasNonNull("exitCondition")?evaluator.condition(rule.path("exitCondition"),seedSignals):enter==MachineStateEvaluator.Truth.TRUE?MachineStateEvaluator.Truth.FALSE:enter==MachineStateEvaluator.Truth.FALSE?MachineStateEvaluator.Truth.TRUE:MachineStateEvaluator.Truth.UNKNOWN;
      timers.put(id,new MachineStateEvaluator.Timer(enter==MachineStateEvaluator.Truth.TRUE?(lastObserved==null?from:lastObserved):null,
          exit==MachineStateEvaluator.Truth.TRUE?(lastObserved==null?from:lastObserved):null));
      if(activeRule==null&&Objects.equals(rule.path("targetState").asText(),state))activeRule=id;
    }
    current=new MachineStateEvaluator.Current(state,activeRule,from);
    MachineStateEvaluator.Result initial=evaluate(config,samples,from,lastObserved,lastReceived,coverageFrom,current,timers);
    timers=new HashMap<>(initial.timers());current=new MachineStateEvaluator.Current(initial.state(),initial.ruleId(),initial.effectiveFrom());
    // The evaluator may identify a transition whose activation/release deadline is
    // later than the replay anchor. Keep the anchor state until that exact instant.
    if(!Objects.equals(state,initial.state())&&!initial.effectiveFrom().isAfter(from)){
      state=initial.state();stateFrom=from;
    }
    deadline=initial.deadline();
    int index=0,steps=0;
    while(index<events.size()||deadline!=null&&deadline.at().isBefore(to)){
      Instant eventAt=index<events.size()?events.get(index).observedAt():to;
      if(deadline!=null&&!deadline.at().isAfter(eventAt)&&deadline.at().isBefore(to)){
        Instant at=deadline.at();MachineStateEvaluator.Result result=evaluate(config,samples,at,lastObserved,lastReceived,coverageFrom,current,timers);
        timers=new HashMap<>(result.timers());current=new MachineStateEvaluator.Current(result.state(),result.ruleId(),result.effectiveFrom());
        if(!Objects.equals(state,result.state())){Instant boundary=clip(result.effectiveFrom(),from,to);if(boundary.isAfter(stateFrom)){intervals.add(new Interval(stateFrom,boundary,state));state= result.state();stateFrom=boundary;}}
        deadline=result.deadline();if(++steps>500_000)throw new IllegalArgumentException("REPLAY_EVENT_LIMIT");continue;
      }
      if(index>=events.size())break;
      Event event=events.get(index++);Instant at=event.observedAt();if(at.isBefore(from)||!at.isBefore(to))continue;
      do {
        lastObserved=lastObserved==null||events.get(index-1).observedAt().isAfter(lastObserved)?events.get(index-1).observedAt():lastObserved;
        lastReceived=lastReceived==null||events.get(index-1).receivedAt().isAfter(lastReceived)?events.get(index-1).receivedAt():lastReceived;
        Event sameTime=events.get(index-1);Sample sample=samples.get(sameTime.signalId());if(sample!=null&&sameTime.quality()==0&&(sameTime.flags()&1)==0){sample.value=sameTime.value();sample.observed=sameTime.observedAt();}
        if(index>=events.size()||!events.get(index).observedAt().equals(at))break;
        index++;
      } while(true);
      MachineStateEvaluator.Result result=evaluate(config,samples,at,lastObserved,lastReceived,coverageFrom,current,timers);
      timers=new HashMap<>(result.timers());current=new MachineStateEvaluator.Current(result.state(),result.ruleId(),result.effectiveFrom());
      if(!Objects.equals(state,result.state())){Instant boundary=clip(result.effectiveFrom(),from,to);if(boundary.isAfter(stateFrom)){intervals.add(new Interval(stateFrom,boundary,state));state=result.state();stateFrom=boundary;}}
      deadline=result.deadline();if(++steps>500_000)throw new IllegalArgumentException("REPLAY_EVENT_LIMIT");
    }
    MachineStateEvaluator.Result last=evaluate(config,samples,to,lastObserved,lastReceived,coverageFrom,current,timers);
    if(!Objects.equals(state,last.state())){Instant boundary=clip(last.effectiveFrom(),from,to);if(boundary.isAfter(stateFrom)){intervals.add(new Interval(stateFrom,boundary,state));state=last.state();stateFrom=boundary;}}
    intervals.add(new Interval(stateFrom,null,state));return List.copyOf(intervals);
  }

  private MachineStateEvaluator.Result evaluate(JsonNode config,Map<UUID,Sample> samples,Instant at,Instant observed,Instant received,Instant coverageFrom,
      MachineStateEvaluator.Current current,Map<UUID,MachineStateEvaluator.Timer> timers){
    return evaluator.evaluate(config,signals(samples,at),observed,received,coverageFrom,at,current,timers);
  }
  private Map<UUID,MachineStateEvaluator.Signal> signals(Map<UUID,Sample> samples,Instant at){Map<UUID,MachineStateEvaluator.Signal> signals=new HashMap<>();samples.forEach((id,s)->{String freshness=s.value==null||s.observed==null?"NO_DATA":at.isBefore(s.observed.plusMillis(s.spec.staleAfterMs()))?"FRESH":"STALE";signals.put(id,new MachineStateEvaluator.Signal(s.spec.valueType(),s.value,s.observed,s.spec.staleAfterMs(),freshness));});return signals;}
  private Instant clip(Instant value,Instant from,Instant to){if(value.isBefore(from))return from;if(value.isAfter(to))return to;return value;}
}
