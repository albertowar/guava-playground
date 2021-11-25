package org.example;

import com.google.common.cache.LoadingCache;
import java.util.concurrent.ExecutionException;

public class CacheReader implements Runnable {
  private LoadingCache<String, String> cache;

  public CacheReader(LoadingCache<String, String> cache) {
    this.cache = cache;
  }

  @Override
  public void run() {
    try {
      System.out.printf(
          "Thread %s. Reading value at %s\n",
          Thread.currentThread().getName(), System.currentTimeMillis());
      System.out.printf(
          "Thread %s. Value %s at %s\n",
          Thread.currentThread().getName(),
          this.cache.get("something"),
          System.currentTimeMillis());
    } catch (ExecutionException e) {
      System.out.printf("Thread %s. Exception %s\n", Thread.currentThread().getName(), e);
    }
  }
}
