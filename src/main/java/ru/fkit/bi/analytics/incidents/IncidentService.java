package ru.fkit.bi.analytics.incidents;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.fkit.bi.analytics.api.BusinessException;
import ru.fkit.bi.analytics.enterprise.EnterpriseModels.Metadata;
import ru.fkit.bi.analytics.incidents.LossReasonModels.*;
import ru.fkit.bi.analytics.incidents.ManualIncidentModels.*;

@Service
class IncidentService {
  private final IncidentRepository repo; private final TransactionTemplate tx; private final ObjectMapper json;
  IncidentService(IncidentRepository repo,TransactionTemplate tx,ObjectMapper json){this.repo=repo;this.tx=tx;this.json=json;}

  LossReasonModels.Page reasons(UUID enterprise,String status,String category,String search,int page,int size){
    if(!repo.enterpriseExists(enterprise))throw notFound("Предприятие не найдено");
    if(status!=null&&!Set.of("ACTIVE","DISABLED").contains(status))throw bad("INVALID_STATUS_FILTER","status must be ACTIVE or DISABLED");
    if(category!=null&&!LossReasonModels.CATEGORIES.contains(category))throw bad("INVALID_LOSS_CATEGORY","category is invalid");
    if(page<0||size<1||size>200)throw bad("INVALID_PAGINATION","page must be non-negative and size must be 1..200");
    long count=repo.reasonCount(enterprise,status,category,search);return new LossReasonModels.Page(repo.reasonPage(enterprise,status,category,search,page,size),new Metadata(page,size,count,(int)((count+size-1)/size)));
  }
  LossReason reason(UUID id){return repo.reason(id).orElseThrow(()->notFound("Причина потерь не найдена"));}
  LossReason createReason(UUID enterprise,LossReasonModels.Create input){
    if(!repo.enterpriseExists(enterprise))throw notFound("Предприятие не найдено");validateCategory(input.category());
    try{return repo.create(UUID.randomUUID(),enterprise,input);}catch(DuplicateKeyException e){throw new BusinessException(409,"LOSS_REASON_DUPLICATE","Loss reason code or normalized name already exists in enterprise");}
  }
  LossReason updateReason(UUID id,LossReasonModels.Update in){return tx.execute(s->{repo.lockReason(id);LossReason old=reason(id);validateCategory(in.category());if(old.used()&&!old.category().equals(in.category()))throw new BusinessException(409,"LOSS_REASON_CATEGORY_IMMUTABLE","Category cannot change after a reason is used");try{repo.updateReason(id,in);}catch(DuplicateKeyException e){throw new BusinessException(409,"LOSS_REASON_DUPLICATE","Normalized name already exists in enterprise");}return reason(id);});}
  LossReason reasonStatus(UUID id,StatusUpdate in){return tx.execute(s->{repo.lockReason(id);reason(id);repo.reasonStatus(id,in.status());return reason(id);});}

  Incident create(UUID machine,ManualIncidentModels.Create in,String key){
    if(key==null||key.length()<8||key.length()>128)throw bad("IDEMPOTENCY_KEY_REQUIRED","Idempotency-Key must contain 8..128 characters");
    String principal=principal();String hash=hash(machine,in);
    return tx.execute(s->{repo.lockMachine(machine);IncidentRepository.MachineData md=requireMachine(machine);repo.lockIdempotency(principal,"CREATE_MANUAL_INCIDENT",key);IncidentRepository.Key old=repo.key(principal,"CREATE_MANUAL_INCIDENT",key);if(old!=null){if(!old.hash().equals(hash))throw new BusinessException(409,"IDEMPOTENCY_KEY_REUSED","Idempotency-Key was already used with a different request");return repo.incident(old.incident());}
      if(in.lossReasonId()!=null)repo.lockReason(in.lossReasonId());
      validate(in.from(),in.to(),in.stateOverride(),in.lossReasonId(),in.comment(),in.author(),md);
      validateOngoingReasonCoverage(in.from(),in.to(),in.stateOverride(),in.lossReasonId(),md);
      if(in.lossReasonId()!=null&&!"SETUP".equals(in.stateOverride())&&!"PLANNED_STOP".equals(in.stateOverride())&&!repo.eligibleCoverage(machine,in.from(),in.to()))throw new BusinessException(409,"INCIDENT_HAS_NO_LOSS_INTERVAL","Incident does not overlap a ready eligible loss interval");
      ensureNoOverlap(machine,null,in.from(),in.to(),in.stateOverride()!=null,in.lossReasonId()!=null);
      UUID id=UUID.randomUUID();try{repo.insertIncident(id,machine,in.from(),in.to(),in.stateOverride(),in.lossReasonId(),in.comment(),in.author());}
      catch(DataIntegrityViolationException e){throw overlapDataError(e);}
      repo.saveKey(principal,"CREATE_MANUAL_INCIDENT",machine,key,hash,id);repo.queue(machine,in.from(),in.to());return repo.incident(id);
    });
  }
  Incident update(UUID id,ManualIncidentModels.Update in){return tx.execute(s->{Incident old=requiredIncident(id);repo.lockMachine(old.machineId());old=requiredIncident(id);var md=requireMachine(old.machineId());boolean sameReason=Objects.equals(old.lossReason()==null?null:old.lossReason().id(),in.lossReasonId());if(in.lossReasonId()!=null)repo.lockReason(in.lossReasonId());if(sameReason&&old.lossReason()!=null&&!repo.reasonActive(old.lossReason().id())&&!Objects.equals(old.stateOverride(),in.stateOverride()))throw new BusinessException(409,"DISABLED_REASON_INCIDENT_IMMUTABLE","Only boundaries and comment may change while an assigned reason is disabled");validate(in.from(),in.to(),in.stateOverride(),in.lossReasonId(),in.comment(),old.author(),md,sameReason);validateOngoingReasonCoverage(in.from(),in.to(),in.stateOverride(),in.lossReasonId(),md);if(in.lossReasonId()!=null&&!sameReason&&!repo.reasonActive(in.lossReasonId()))throw new BusinessException(409,"LOSS_REASON_NOT_ACTIVE","Disabled loss reasons cannot be newly assigned");if(in.lossReasonId()!=null&&!"SETUP".equals(in.stateOverride())&& !"PLANNED_STOP".equals(in.stateOverride())&&!repo.eligibleCoverage(old.machineId(),in.from(),in.to()))throw new BusinessException(409,"INCIDENT_HAS_NO_LOSS_INTERVAL","Incident does not overlap a ready eligible loss interval");ensureNoOverlap(old.machineId(),id,in.from(),in.to(),in.stateOverride()!=null,in.lossReasonId()!=null);try{repo.updateIncident(id,in.from(),in.to(),in.stateOverride(),in.lossReasonId(),in.comment());}catch(DataIntegrityViolationException e){throw overlapDataError(e);}queueUnion(old.machineId(),old.from(),old.to(),in.from(),in.to());return requiredIncident(id);});}
  Incident close(UUID id,Close in){return tx.execute(s->{Incident old=requiredIncident(id);repo.lockMachine(old.machineId());old=requiredIncident(id);if(old.to()!=null){if(!old.to().equals(in.to()))throw new BusinessException(409,"INCIDENT_ALREADY_CLOSED","Incident is already closed at a different boundary");return old;}var md=requireMachine(old.machineId());validateTime(old.from(),in.to(),md);ensureNoOverlap(old.machineId(),id,old.from(),in.to(),old.stateOverride()!=null,old.lossReason()!=null);repo.close(id,in.to());repo.queue(old.machineId(),old.from(),in.to());return requiredIncident(id);});}
  void cancel(UUID id){tx.execute(s->{Incident old=requiredIncident(id);if(old.cancelledAt()!=null)return null;repo.lockMachine(old.machineId());old=requiredIncident(id);if(old.cancelledAt()!=null)return null;repo.cancel(id);repo.queue(old.machineId(),old.from(),old.to());return null;});}
  Incident get(UUID id){return requiredIncident(id);}
  ManualIncidentModels.Page list(UUID machine,UUID enterprise,UUID line,Instant from,Instant to,String effect,UUID reason,Boolean ongoing,boolean cancelled,int limit,String cursor){if(machine!=null&&repo.machine(machine)==null)throw notFound("Машина не найдена");if(enterprise!=null&&!repo.enterpriseExists(enterprise))throw notFound("Предприятие не найдено");if(line!=null&&!Boolean.TRUE.equals(repo.jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM equipment.production_line WHERE id=? AND enterprise_id=?)",Boolean.class,line,enterprise)))throw notFound("Линия не найдена");if(machine!=null&&enterprise!=null&&!repo.machine(machine).enterprise().equals(enterprise))throw notFound("Машина не найдена");if(limit<1||limit>500)throw fieldBad("limit","OUT_OF_RANGE","limit должен быть от 1 до 500");if(from!=null&&to!=null&&!from.isBefore(to))throw fieldBad("to","INVALID_RANGE","Дата окончания должна быть позже даты начала");if(effect!=null&&!Set.of("STATE_OVERRIDE","LOSS_ANNOTATION","STATE_OVERRIDE_WITH_REASON").contains(effect))throw fieldBad("effectType","INVALID_VALUE","Недопустимый тип эффекта");if(cursor!=null)try{String[] c=new String(Base64.getUrlDecoder().decode(cursor)).split("\\|",2);Instant.parse(c[0]);UUID.fromString(c[1]);}catch(Exception e){throw fieldBad("cursor","INVALID_CURSOR","Некорректный cursor");}List<Incident> result=repo.page(machine,enterprise,line,from,to,effect,reason,ongoing,cancelled,limit,cursor);boolean more=result.size()>limit;if(more)result=result.subList(0,limit);return new ManualIncidentModels.Page(result,new ManualIncidentModels.Cursor(limit,more,more?IncidentRepository.encode(result.getLast()):null));}
  private Incident requiredIncident(UUID id){Incident i=repo.incident(id);if(i==null)throw notFound("Инцидент не найден");return i;}
  private IncidentRepository.MachineData requireMachine(UUID id){var md=repo.machine(id);if(md==null)throw notFound("Машина не найдена");return md;}
  private void validate(Instant from,Instant to,String state,UUID reason,String comment,String author,IncidentRepository.MachineData md){validate(from,to,state,reason,comment,author,md,false);}
  private void validate(Instant from,Instant to,String state,UUID reason,String comment,String author,IncidentRepository.MachineData md,boolean allowDisabled){if((state==null&&reason==null)||state!=null&&!Set.of("SETUP","PLANNED_STOP").contains(state))throw new BusinessException(422,"INVALID_INCIDENT_EFFECT","At least one valid incident effect is required");if(comment!=null&&comment.length()>500||author!=null&&author.length()>100)throw new BusinessException(422,"INVALID_INCIDENT_TEXT","Comment or author exceeds its maximum length");validateTime(from,to,md);if(reason!=null&&(!repo.reasonOwned(reason,md.enterprise())||!allowDisabled&&!repo.reasonActive(reason)))throw new BusinessException(409,"LOSS_REASON_NOT_ASSIGNABLE","Loss reason must be active and belong to the machine enterprise");}
  private void validateTime(Instant from,Instant to,IncidentRepository.MachineData md){Instant now=Instant.now().truncatedTo(ChronoUnit.MILLIS);if(from==null||from.isAfter(now)||md.coverage()==null||from.isBefore(md.coverage())||to!=null&&(!to.isAfter(from)||to.isAfter(now)))throw new BusinessException(422,"INVALID_INCIDENT_RANGE","Incident must be within machine coverage and cannot be in the future");}
  private void validateOngoingReasonCoverage(Instant from,Instant to,String state,UUID reason,IncidentRepository.MachineData md){if(reason!=null&&state==null&&to==null&&(md.through()==null||!from.isBefore(md.through())))throw new BusinessException(409,"INCIDENT_HAS_NO_LOSS_INTERVAL","An ongoing reason annotation must start before the machine dataThrough");}
  private void ensureNoOverlap(UUID machine,UUID id,Instant from,Instant to,boolean state,boolean reason){if(!repo.overlap(machine,id,from,to,state,false).isEmpty())throw new BusinessException(409,"INCIDENT_STATE_OVERLAP","Another active state override overlaps this interval");if(!repo.overlap(machine,id,from,to,false,reason).isEmpty())throw new BusinessException(409,"INCIDENT_REASON_OVERLAP","Another active loss reason overlaps this interval");}
  private BusinessException overlapDataError(DataIntegrityViolationException e){String message=String.valueOf(e.getMostSpecificCause().getMessage());if(message.contains("ex_manual_incident_state_overlap"))return new BusinessException(409,"INCIDENT_STATE_OVERLAP","Another active state override overlaps this interval");if(message.contains("ex_manual_incident_reason_overlap"))return new BusinessException(409,"INCIDENT_REASON_OVERLAP","Another active loss reason overlaps this interval");return new BusinessException(409,"INCIDENT_OVERLAP","Incident effects conflict with a persisted constraint");}
  private void queueUnion(UUID machine,Instant oldFrom,Instant oldTo,Instant newFrom,Instant newTo){repo.queue(machine,oldFrom.isBefore(newFrom)?oldFrom:newFrom,max(oldTo,newTo));}
  private static Instant max(Instant a,Instant b){return a==null||b==null?null:a.isAfter(b)?a:b;}
  private String hash(UUID machine,Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(List.of(machine,value))));}catch(Exception e){throw new IllegalStateException(e);}}
  private String principal(){Authentication authentication=SecurityContextHolder.getContext().getAuthentication();if(authentication==null||!authentication.isAuthenticated()||"anonymousUser".equals(authentication.getName()))throw new BusinessException(401,"AUTHENTICATION_REQUIRED","Authenticated principal is required");return authentication.getName();}
  private static void validateCategory(String v){if(!LossReasonModels.CATEGORIES.contains(v))throw new BusinessException(422,"INVALID_LOSS_CATEGORY","category must be a supported LossCategory");}
  private static BusinessException notFound(String x){return new BusinessException(404,"NOT_FOUND",x);}
  private static BusinessException bad(String c,String m){return new BusinessException(400,c,m);}
  private static BusinessException fieldBad(String path,String code,String message){return new BusinessException(400,"VALIDATION_FAILED","Проверьте параметры запроса",List.of(new ru.fkit.bi.analytics.api.ErrorResponse.FieldError(path,code,message)),Map.of());}
}
