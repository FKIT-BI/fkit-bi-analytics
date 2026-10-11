package ru.fkit.bi.analytics.line;

import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import ru.fkit.bi.analytics.api.BusinessException;
import ru.fkit.bi.analytics.line.LineModels.*;

@Service
class LineService {
  private final LineRepository repository;
  LineService(LineRepository repository){this.repository=repository;}
  Page<Line> page(UUID enterpriseId,String status,String search,int page,int size){validatePage(status,page,size);if(!repository.enterpriseExists(enterpriseId))throw notFound("Enterprise");long count=repository.count(enterpriseId,status,search);return new Page<>(repository.page(enterpriseId,status,search,page,size),new Metadata(page,size,count,(int)((count+size-1)/size)));}
  Line create(UUID enterpriseId,Create in){if(!repository.enterpriseExists(enterpriseId))throw notFound("Enterprise");try{return repository.create(UUID.randomUUID(),enterpriseId,in.code(),in.name().trim(),in.sortOrder()==null?repository.nextOrder(enterpriseId):in.sortOrder());}catch(DuplicateKeyException e){throw new BusinessException(409,"LINE_CODE_CONFLICT","Line code already exists in enterprise");}}
  Line get(UUID id){return repository.find(id).orElseThrow(()->notFound("Line"));}
  Line update(UUID id,Update in){get(id);repository.update(id,in.name().trim(),in.sortOrder());return get(id);}
  Line status(UUID id,StatusUpdate in){get(id);repository.status(id,in.status());return get(id);}
  private static void validatePage(String s,int p,int z){if(!Set.of("ACTIVE","DISABLED").contains(s))throw new BusinessException(400,"INVALID_STATUS_FILTER","status must be ACTIVE or DISABLED");if(p<0||z<1||z>200)throw new BusinessException(400,"INVALID_PAGINATION","page must be non-negative and size must be 1..200");}
  private static BusinessException notFound(String name){return new BusinessException(404,"NOT_FOUND",name+" not found");}
}
