package org.example;

import com.google.common.cache.CacheLoader;
import com.google.common.util.concurrent.ListenableFuture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class CacheLoaderFactory {
  public static CacheLoader<String, String> buildCacheLoaderWithoutExceptionHandling(String baseUrl) {
    return new CacheLoader<>() {
      @Override
      public String load(final String environment) throws Exception {
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1));

        var client = builder.build();
        final HttpRequest request =
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(1))
                .build();

        final HttpResponse<String> response =
            client.send(request, HttpResponse.BodyHandlers.ofString());

        return response.body();
      }
    };
  }

  public static CacheLoader<String, String> buildCacheLoader(String baseUrl) {
    return new CacheLoader<>() {
      @Override
      public String load(final String environment) throws Exception {
        try {
          System.out.printf("Loading on thread %s\n", Thread.currentThread().getName());
          var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1));

          var client = builder.build();
          final HttpRequest request =
              HttpRequest.newBuilder()
                  .uri(URI.create(baseUrl))
                  .timeout(Duration.ofSeconds(1))
                  .build();

          final HttpResponse<String> response =
              client.send(request, HttpResponse.BodyHandlers.ofString());

          return response.body();
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
}
