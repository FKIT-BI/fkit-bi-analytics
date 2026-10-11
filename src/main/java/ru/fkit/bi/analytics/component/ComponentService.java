package ru.fkit.bi.analytics.component;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.fkit.bi.analytics.api.BusinessException;
import ru.fkit.bi.analytics.component.ComponentModels.*;

@Service
class ComponentService {
  private static final Set<String> KINDS=Set.of("LOGICAL_GROUP","ASSEMBLY","MODULE","MECHANISM","MOTOR","DRIVE","GEARBOX","SPINDLE","BEARING","COUPLING","ACTUATOR","PUMP","COMPRESSOR","FAN","BLOWER","VALVE","HEATER","COOLER","MIXER","DOSER","CONVEYOR","FEEDER","ROBOT","MANIPULATOR","TOOL","FIXTURE","SENSOR","INSTRUMENT","CONTROLLER","SAFETY_DEVICE","POWER_SUPPLY","LUBRICATION_SYSTEM","HYDRAULIC_SYSTEM","PNEUMATIC_SYSTEM","COOLING_SYSTEM","OTHER");
  private final ComponentRepository repository;private final TransactionTemplate transactions;
  ComponentService(ComponentRepository repository,TransactionTemplate transactions){this.repository=repository;this.transactions=transactions;}
  List<Component> list(UUID machine,String status){requireMachine(machine);validStatus(status);return repository.list(machine,status);}
  Component create(UUID machine,Create in){requireMachine(machine);validateKind(in.kind());List<JsonNode> parameters=validateParameters(in.parameters());if(in.parentComponentId()!=null&&!repository.parentOwned(machine,in.parentComponentId()))throw new BusinessException(409,"COMPONENT_PARENT_OWNER_CONFLICT","Parent must belong to the same machine");Create normalized=new Create(in.parentComponentId(),in.code(),in.name(),in.kind(),in.sortOrder(),parameters);try{return repository.create(UUID.randomUUID(),machine,normalized);}catch(DuplicateKeyException e){throw new BusinessException(409,"COMPONENT_CODE_CONFLICT","Component code already exists in machine");}}
  Component get(UUID id){return repository.find(id).orElseThrow(()->missing());}
  Component update(UUID id,Update in){get(id);validateKind(in.kind());List<JsonNode> parameters=validateParameters(in.parameters());repository.update(id,new Update(in.name(),in.kind(),in.sortOrder(),parameters));return get(id);}
  Component parent(UUID id,ParentUpdate in){return transactions.execute(s->{Component current=get(id);repository.lockMachine(current.machineId());if(in.parentComponentId()!=null&&!repository.parentOwned(current.machineId(),in.parentComponentId()))throw new BusinessException(409,"COMPONENT_PARENT_OWNER_CONFLICT","Parent must belong to the same machine");if(in.parentComponentId()!=null&&repository.createsCycle(current.machineId(),id,in.parentComponentId()))throw new BusinessException(409,"COMPONENT_TREE_CYCLE","Component parent change would create a cycle");repository.parent(id,in.parentComponentId());return get(id);});}
  Component status(UUID id,StatusUpdate in){get(id);repository.status(id,in.status());return get(id);}
  private void requireMachine(UUID id){if(!repository.machineExists(id))throw new BusinessException(404,"NOT_FOUND","Machine not found");}
  private static void validStatus(String s){if(!Set.of("ACTIVE","DISABLED").contains(s))throw new BusinessException(400,"INVALID_STATUS_FILTER","status must be ACTIVE or DISABLED");}
  private static void validateKind(String k){if(!KINDS.contains(k))throw new BusinessException(422,"INVALID_COMPONENT_KIND","kind is not supported");}
  private List<JsonNode> validateParameters(List<JsonNode> values){if(values==null||values.size()>100)throw new BusinessException(422,"INVALID_PARAMETERS","parameters must contain at most 100 entries");Set<String> codes=new HashSet<>();for(JsonNode v:values){if(v==null||!v.isObject()||!v.hasNonNull("code")||!v.hasNonNull("name")||!v.hasNonNull("valueType")||!v.has("value")||!v.hasNonNull("sortOrder"))throw new BusinessException(422,"INVALID_PARAMETER","Each parameter requires code, name, valueType, value and sortOrder");String code=v.get("code").asText();if(!code.matches("^[A-Z][A-Z0-9_]{0,63}$")||!codes.add(code))throw new BusinessException(422,"INVALID_PARAMETER_CODE","Parameter codes must be valid and unique");String type=v.get("valueType").asText();boolean ok=switch(type){case "STRING"->v.get("value").isTextual();case "NUMBER"->v.get("value").isNumber();case "BOOLEAN"->v.get("value").isBoolean();default->false;};if(!ok||!v.get("sortOrder").canConvertToInt()||v.get("sortOrder").asInt()<0||v.get("name").asText().isBlank()||v.get("name").asText().length()>200)throw new BusinessException(422,"INVALID_PARAMETER_VALUE","Parameter value, name and sortOrder must match their type constraints");if(!"NUMBER".equals(type)&&v.has("unitCode"))throw new BusinessException(422,"INVALID_PARAMETER_UNIT","unitCode is allowed only for NUMBER parameters");if(v.hasNonNull("unitCode")&&(!v.get("unitCode").isTextual()||v.get("unitCode").asText().length()>50||!repository.unitExists(v.get("unitCode").asText())))throw new BusinessException(422,"INVALID_PARAMETER_UNIT","unitCode must identify an active measurement unit");}return values.stream().sorted(Comparator.comparingInt((JsonNode v)->v.path("sortOrder").asInt()).thenComparing(v->v.path("code").asText())).toList();}
  private static BusinessException missing(){return new BusinessException(404,"NOT_FOUND","Component not found");}
}
