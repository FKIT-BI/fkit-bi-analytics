package ru.fkit.bi.analytics.health;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class LiquibaseMigrationState {
  public enum Status { PENDING, SUCCEEDED, FAILED }

  private final AtomicReference<Status> status = new AtomicReference<>(Status.PENDING);

  public Status status() {
    return status.get();
  }

  public void succeeded() {
    status.set(Status.SUCCEEDED);
  }

  public void failed() {
    status.set(Status.FAILED);
  }
}
