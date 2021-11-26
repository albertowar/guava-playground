package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CacheFactory {
  private static final ObjectMapper mapper = new ObjectMapper();

  public static LoadingCache<String, Data> buildCache(String baseUrl) {
    return CacheBuilder.newBuilder()
        .refreshAfterWrite(Constants.CACHE_REFRESH_TIME_MS, TimeUnit.MILLISECONDS)
        .build(buildCacheLoader(baseUrl));
  }

  public static LoadingCache<String, Data> buildCacheWithoutExceptionHandling(String baseUrl) {
    return CacheBuilder.newBuilder()
        .refreshAfterWrite(Constants.CACHE_REFRESH_TIME_MS, TimeUnit.MILLISECONDS)
        .build(buildCacheLoaderWithoutExceptionHandling(baseUrl));
  }

  public static LoadingCache<String, Data> buildCacheWithAsyncLoading(
      String baseUrl, ExecutorService pool) {
    return CacheBuilder.newBuilder()
        .refreshAfterWrite(Constants.CACHE_REFRESH_TIME_MS, TimeUnit.MILLISECONDS)
        .build(CacheLoader.asyncReloading(CacheFactory.buildCacheLoader(baseUrl), pool));
  }

  private static CacheLoader<String, Data> buildCacheLoaderWithoutExceptionHandling(
      String baseUrl) {
    return new CacheLoader<>() {
      @Override
      public Data load(final String environment) throws Exception {
        return fetchData(baseUrl);
      }

      @Override
      public ListenableFuture<Data> reload(String key, Data oldValue) throws Exception {
        log.info("Reloading");
        return super.reload(key, oldValue);
      }
    };
  }

  private static CacheLoader<String, Data> buildCacheLoader(String baseUrl) {
    return new CacheLoader<>() {
      @Override
      public Data load(final String environment) throws Exception {
        try {
          return fetchData(baseUrl);
        } catch (Exception e) {
          log.error("Loading Exception at {}", System.currentTimeMillis(), e);
          throw e;
        }
      }

      @Override
      public ListenableFuture<Data> reload(String key, Data oldValue) throws Exception {
        log.info("Reloading");
        return super.reload(key, oldValue);
      }
    };
  }

  private static Data fetchData(String baseUrl) throws Exception {
    log.info("Fetching data");
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

    return mapper.readValue(response.body(), Data.class);
  }
}
