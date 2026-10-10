package ru.fkit.bi.analytics.auth;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** Process-local fixed-window limiter for the single-instance MVP. State resets on restart. */
@Component
public class RateLimiter {
  private static final int MAX_CLIENTS = 10_000;
  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

  public synchronized long acquire(String key, int limit, long nowMillis) {
    long start = nowMillis / 60_000L * 60_000L;
    if (windows.size() >= MAX_CLIENTS) {
      windows.entrySet().removeIf(entry -> entry.getValue().startMillis < start);
      if (!windows.containsKey(key) && windows.size() >= MAX_CLIENTS) {
        return Math.max(1, (start + 60_000L - nowMillis + 999L) / 1000L);
      }
    }
    Window window = windows.compute(key, (ignored, previous) ->
        previous == null || previous.startMillis != start ? new Window(start) : previous);
    int count = window.count.incrementAndGet();
    return count <= limit ? 0 : Math.max(1, (start + 60_000L - nowMillis + 999L) / 1000L);
  }

  private static final class Window {
    private final long startMillis;
    private final AtomicInteger count = new AtomicInteger();
    private Window(long startMillis) { this.startMillis = startMillis; }
  }
}
