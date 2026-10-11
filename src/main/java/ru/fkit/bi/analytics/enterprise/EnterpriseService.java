package ru.fkit.bi.analytics.enterprise;

import java.time.ZoneId;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.fkit.bi.analytics.api.BusinessException;
import ru.fkit.bi.analytics.enterprise.EnterpriseModels.*;

@Service
public class EnterpriseService {
  private final EnterpriseRepository repository;
  private final TransactionTemplate transactions;
  EnterpriseService(EnterpriseRepository repository, TransactionTemplate transactions) { this.repository=repository; this.transactions=transactions; }
  public Page<Enterprise> page(String status, String search, int page, int size) {
    pagination(status,page,size);
    long count=repository.count(status,search);
    return new Page<>(repository.page(status,search,page,size), new Metadata(page,size,count,(int)((count+size-1)/size)));
  }
  public Enterprise get(UUID id) { return repository.find(id).orElseThrow(()->new BusinessException(404,"NOT_FOUND","Enterprise not found")); }
  public Enterprise create(Create input) {
    validateTimezone(input.timezone());
    try { return repository.create(UUID.randomUUID(),input.code(),input.name().trim(),input.timezone()); }
    catch (DuplicateKeyException ex) { throw new BusinessException(409,"ENTERPRISE_CODE_CONFLICT","Enterprise code already exists"); }
  }
  public Enterprise update(UUID id, Update input) {
    validateTimezone(input.timezone());
    return transactions.execute(status -> {
      repository.lock(id); Enterprise current=get(id);
      if (!current.timezone().equals(input.timezone()) && repository.hasTemporalData(id))
        throw new BusinessException(409,"ENTERPRISE_TIMEZONE_LOCKED","Enterprise timezone cannot change after temporal data exists");
      repositoryJdbcUpdate(id,input);
      return get(id);
    });
  }
  private void repositoryJdbcUpdate(UUID id, Update input) { repository.update(id,input.name().trim(),input.timezone()); }
  public Enterprise status(UUID id, StatusUpdate input) { return transactions.execute(s->{get(id);repository.updateStatus(id,input.status());return get(id);}); }
  private static void validateTimezone(String value) {
    try {
      ZoneId zone=ZoneId.of(value);
      if (zone instanceof java.time.ZoneOffset || !ZoneId.getAvailableZoneIds().contains(value)) throw new java.time.zone.ZoneRulesException("Not IANA");
    } catch (java.time.DateTimeException ex) {
      throw new BusinessException(422,"INVALID_TIMEZONE","timezone must be an IANA timezone");
    }
  }
  private static void pagination(String status,int page,int size) {
    if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) throw new BusinessException(400,"INVALID_STATUS_FILTER","status must be ACTIVE or DISABLED");
    if (page<0 || size<1 || size>200) throw new BusinessException(400,"INVALID_PAGINATION","page must be non-negative and size must be 1..200");
  }
}
