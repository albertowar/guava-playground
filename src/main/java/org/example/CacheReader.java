package org.example;

import com.google.common.cache.LoadingCache;
import java.util.concurrent.ExecutionException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CacheReader implements Runnable {
  private LoadingCache<String, Data> cache;

  public CacheReader(LoadingCache<String, Data> cache) {
    this.cache = cache;
  }

  @Override
  public void run() {
    try {
      log.info("Reading value at {}", System.currentTimeMillis());
      log.info("Value {} at {}", this.cache.get("something"), System.currentTimeMillis());
    } catch (ExecutionException e) {
      log.error("Exception while loading value at {}", System.currentTimeMillis(), e);
    }
  }
}
