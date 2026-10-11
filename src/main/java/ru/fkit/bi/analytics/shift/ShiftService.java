package ru.fkit.bi.analytics.shift;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.fkit.bi.analytics.api.BusinessException;
import ru.fkit.bi.analytics.enterprise.EnterpriseModels.Metadata;
import ru.fkit.bi.analytics.enterprise.EnterpriseRepository;
import ru.fkit.bi.analytics.shift.ShiftModels.*;
import ru.fkit.bi.analytics.shift.ShiftCalendar.Definition;

@Service
public class ShiftService {
  private final ShiftRepository shifts;
  private final EnterpriseRepository enterprises;
  private final TransactionTemplate transactions;
  ShiftService(ShiftRepository shifts,EnterpriseRepository enterprises,TransactionTemplate transactions){this.shifts=shifts;this.enterprises=enterprises;this.transactions=transactions;}
  public Page page(UUID enterpriseId,String status,LocalDate validOn,int page,int size){
    pagination(status,page,size); if(enterprises.find(enterpriseId).isEmpty())throw notFound("Enterprise");
    long count=shifts.count(enterpriseId,status,validOn);
    return new Page(shifts.page(enterpriseId,status,validOn,page,size),new Metadata(page,size,count,(int)((count+size-1)/size)));
  }
  public Shift get(UUID id){return shifts.find(id).orElseThrow(()->notFound("Shift"));}
  public Shift create(UUID enterpriseId,Create input){
    LocalDate from=requireDate(input.validFrom(),"validFrom"); LocalDate to=input.validTo(); validateDates(from,to);
    LocalTime start=time(input.startLocalTime()),end=time(input.endLocalTime());
    return transactions.execute(tx->{
      shifts.lockEnterprise(enterpriseId);
      if(enterprises.find(enterpriseId).isEmpty())throw notFound("Enterprise");
      if(shifts.codeExists(enterpriseId,input.code(),null))throw codeConflict();
      Definition candidate=new Definition(UUID.randomUUID(),start,end,from,to);
      checkOverlap(enterpriseId,candidate,null);
      return shifts.create(candidate.id(),enterpriseId,input.code(),input.name().trim(),start,end,from,to,input.sortOrder()==null?0:input.sortOrder());
    });
  }
  public Shift update(UUID id,Update input){
    LocalDate from=requireDate(input.validFrom(),"validFrom"),to=input.validTo(); validateDates(from,to);
    LocalTime start=time(input.startLocalTime()),end=time(input.endLocalTime());
    Shift initial=get(id);
    return transactions.execute(tx->{
      shifts.lockEnterprise(initial.enterpriseId()); Shift current=get(id);
      boolean used=shifts.isUsed(id);
      boolean boundaryChanged=!current.startLocalTime().equals(start)||!current.endLocalTime().equals(end)||!current.validFrom().equals(from);
      if(used&&boundaryChanged)throw locked();
      if(used&&to!=null&&shifts.hasDateAtOrAfter(id,to))throw locked();
      Definition candidate=new Definition(id,start,end,from,to);
      checkOverlap(current.enterpriseId(),candidate,id);
      shifts.update(id,input.name().trim(),start,end,from,to,input.sortOrder());
      return get(id);
    });
  }
  public Shift status(UUID id,StatusUpdate input){return transactions.execute(tx->{get(id);shifts.updateStatus(id,input.status());return get(id);});}
  private void checkOverlap(UUID enterpriseId,Definition candidate,UUID except){
    for(Shift existing:shifts.all(enterpriseId)){
      if(existing.id().equals(except))continue;
      Definition other=new Definition(existing.id(),existing.startLocalTime(),existing.endLocalTime(),existing.validFrom(),existing.validTo());
      if(ShiftCalendar.overlaps(candidate,other))throw new BusinessException(409,"SHIFT_SCHEDULE_CONFLICT","Shift schedule overlaps an existing shift");
    }
  }
  private static void validateDates(LocalDate from,LocalDate to){if(to!=null&&!to.isAfter(from))throw field("validTo","validTo must be later than validFrom");}
  private static LocalDate requireDate(LocalDate date,String path){if(date==null)throw field(path,path+" is required");return date;}
  private static LocalTime time(String text){try{return LocalTime.parse(text);}catch(RuntimeException e){throw field("time","Local time must use HH:mm");}}
  private static BusinessException field(String path,String message){return new BusinessException(422,"VALIDATION_FAILED",message,List.of(new ru.fkit.bi.analytics.api.ErrorResponse.FieldError(path,"INVALID",message)),java.util.Map.of());}
  private static BusinessException notFound(String entity){return new BusinessException(404,"NOT_FOUND",entity+" not found");}
  private static BusinessException codeConflict(){return new BusinessException(409,"SHIFT_CODE_CONFLICT","Shift code already exists in this enterprise");}
  private static BusinessException locked(){return new BusinessException(409,"SHIFT_SCHEDULE_LOCKED","Used shift history cannot be reinterpreted");}
  private static void pagination(String status,int page,int size){
    if(!"ACTIVE".equals(status)&&!"DISABLED".equals(status))throw new BusinessException(400,"INVALID_STATUS_FILTER","status must be ACTIVE or DISABLED");
    if(page<0||size<1||size>200)throw new BusinessException(400,"INVALID_PAGINATION","page must be non-negative and size must be 1..200");
  }
}
