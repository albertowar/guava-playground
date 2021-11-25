package org.example;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.ListenableFuture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class CacheFactory {
  public static LoadingCache<String, String> buildCache(String baseUrl) {
    return CacheBuilder.newBuilder()
        .refreshAfterWrite(Constants.CACHE_REFRESH_TIME_MS, TimeUnit.MILLISECONDS)
        .build(buildCacheLoader(baseUrl));
  }

  public static LoadingCache<String, String> buildCacheWithEviction(String baseUrl) {
    return CacheBuilder.newBuilder()
        .expireAfterWrite(Constants.CACHE_REFRESH_TIME_MS, TimeUnit.MILLISECONDS)
        .build(buildCacheLoader(baseUrl));
  }

  public static LoadingCache<String, String> buildCacheWithoutExceptionHandling(String baseUrl) {
    return CacheBuilder.newBuilder()
        .refreshAfterWrite(Constants.CACHE_REFRESH_TIME_MS, TimeUnit.MILLISECONDS)
        .build(buildCacheLoaderWithoutExceptionHandling(baseUrl));
  }

  public static LoadingCache<String, String> buildCacheWithAsyncLoading(
      String baseUrl, ExecutorService pool) {
    return CacheBuilder.newBuilder()
        .refreshAfterWrite(Constants.CACHE_REFRESH_TIME_MS, TimeUnit.MILLISECONDS)
        .build(CacheLoader.asyncReloading(CacheFactory.buildCacheLoader(baseUrl), pool));
  }

  private static CacheLoader<String, String> buildCacheLoaderWithoutExceptionHandling(
      String baseUrl) {
    return new CacheLoader<>() {
      @Override
      public String load(final String environment) throws Exception {
        return fetchData(baseUrl);
      }

      @Override
      public ListenableFuture<String> reload(String key, String oldValue) throws Exception {
        System.out.printf("Reloading on thread %s\n", Thread.currentThread().getName());
        return super.reload(key, oldValue);
      }
    };
  }

  private static CacheLoader<String, String> buildCacheLoader(String baseUrl) {
    return new CacheLoader<>() {
      @Override
      public String load(final String environment) throws Exception {
        try {
          return fetchData(baseUrl);
        } catch (Exception e) {
          System.out.printf(
              "Loading Exception on thread %s at %s\n",
              Thread.currentThread().getName(), System.currentTimeMillis());
          throw e;
        }
      }

      @Override
      public ListenableFuture<String> reload(String key, String oldValue) throws Exception {
        System.out.printf("Reloading on thread %s\n", Thread.currentThread().getName());
        return super.reload(key, oldValue);
      }
    };
  }

  private static String fetchData(String baseUrl) throws Exception {
    System.out.printf("Loading on thread %s\n", Thread.currentThread().getName());
    var builder =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Constants.TIMEOUT_IN_SECONDS));

    var client = builder.build();
    final HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .timeout(Duration.ofSeconds(Constants.TIMEOUT_IN_SECONDS))
            .build();

    final HttpResponse<String> response =
        client.send(request, HttpResponse.BodyHandlers.ofString());

    return response.body();
  }
}
