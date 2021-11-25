package org.example;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.ListenableFuture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Test {
  private static WireMockServer wireMockServer = new WireMockServer(wireMockConfig().port(8089));

  public static void main(String[] args) throws InterruptedException {
    solution_whenFutureResponsesTimeoutAndAsyncLoading_returnOldValue();
  }

  /*
  What happens when everything is fine?
   */
  private static void example0_sameSetupAsProduction_happyPath_returnsNewValue()
      throws InterruptedException {
    try {
      LoadingCache<String, String> cache = CacheBuilder.newBuilder().refreshAfterWrite(500, TimeUnit.MILLISECONDS).build(loader);

      wireMockServer.start();
      wireMockServer.stubFor(get("/").willReturn(aResponse().withStatus(200).withBody("old")));

      var thread = new Thread(new CacheReader(cache));
      thread.run();

      Thread.sleep(1000);

      wireMockServer.resetAll();
      wireMockServer.stubFor(get("/").willReturn(aResponse().withStatus(200).withBody("new")));

      List<Thread> threads = new LinkedList<>();

      for (int i = 0; i < 3; ++i) {
        var otherThread = new Thread(new CacheReader(cache));
        threads.add(otherThread);
        otherThread.start();
      }

      for (int i = 0; i < 3; ++i) {
        threads.get(i).join();
      }
    } finally {
      wireMockServer.stop();
    }
  }

  /*
    The Exception is logged and shallowed. Our logger ignores that package (com.google.common)
   */
  private static void example1_givenPopulatedCache_whenFutureResponsesTimeoutAndNoExceptionHandling_exceptionShallowed()
      throws InterruptedException {
    try {
      LoadingCache<String, String> cache = CacheBuilder.newBuilder()
          .refreshAfterWrite(500, TimeUnit.MILLISECONDS).build(loaderWithoutExceptionHandling);

      wireMockServer.start();
      wireMockServer.stubFor(get("/").willReturn(aResponse().withStatus(200).withBody("old")));

      var thread = new Thread(new CacheReader(cache));
      thread.run();

      Thread.sleep(1000);

      wireMockServer.resetAll();
      wireMockServer.stubFor(get("/").willReturn(aResponse().withStatus(200).withBody("new").withFixedDelay(10000)));

      List<Thread> threads = new LinkedList<>();

      for (int i = 0; i < 3; ++i) {
        var otherThread = new Thread(new CacheReader(cache));
        threads.add(otherThread);
        otherThread.start();
      }

      for (int i = 0; i < 3; ++i) {
        threads.get(i).join();
      }
    } finally {
      wireMockServer.stop();
    }
  }

  /*
  What if the refresh timed out?
    If there is an old value, the loader thread will block fetching the new value. If it fails, a exception is thrown on the "loader" thread and it will get the old value.
    Any other threads that attempt to load the value at the same time than the loader, will get the old value instead

    In the event of a timeout, this is the behavior we should have seen. The website times out after 10 seconds, so maybe we hit it.
   */
  private static void example2_sameSetupAsProduction_SimulatedFailure_shouldReturnOldValue()
      throws InterruptedException {
    try {
      LoadingCache<String, String> cache = CacheBuilder.newBuilder()
          .refreshAfterWrite(500, TimeUnit.MILLISECONDS).build(loader);

      wireMockServer.start();
      wireMockServer.stubFor(get("/").willReturn(aResponse().withStatus(200).withBody("old")));

      var reader = new CacheReader(cache);
      reader.run();

      Thread.sleep(1000);

      wireMockServer.resetAll();
      wireMockServer.stubFor(get("/").willReturn(aResponse().withStatus(200).withBody("new").withFixedDelay(10000)));

      List<Thread> threads = new LinkedList<>();

      for (int i = 0; i < 3; ++i) {
        var otherThread = new Thread(new CacheReader(cache));
        threads.add(otherThread);
        otherThread.start();
      }

      for (int i = 0; i < 3; ++i) {
        threads.get(i).join();
      }
    } finally {
      wireMockServer.stop();
    }
  }

  /*
  What if the cache was empty?
    That's ruled out because we would have seen an exception way sooner and in all threads
   */
  private static void example3_sameSetupAsProduction_emptyCacheAndFutureResponsesTimeout_shouldThrowException()
      throws InterruptedException {
    try {
      LoadingCache<String, String> cache = CacheBuilder.newBuilder()
          .refreshAfterWrite(500, TimeUnit.MILLISECONDS).build(loader);
      wireMockServer.start();
      wireMockServer.stubFor(get("/").willReturn(aResponse().withStatus(200).withBody("new").withFixedDelay(10000)));

      List<Thread> threads = new LinkedList<>();

      for (int i = 0; i < 3; ++i) {
        var otherThread = new Thread(new CacheReader(cache));
        threads.add(otherThread);
        otherThread.start();
      }

      for (int i = 0; i < 3; ++i) {
        threads.get(i).join();
      }
    } finally {
      wireMockServer.stop();
    }
  }

  /*
  What if we use eviction?
    We can't use eviction because we still want to get the old value while the new one is being fetched
   */
  private static void example4_usingEvictionInstead_whenFutureResponsesTimeout_throwsException()
      throws InterruptedException {
    try {
      LoadingCache<String, String> cache = CacheBuilder.newBuilder().expireAfterWrite(500, TimeUnit.MILLISECONDS).build(loader);

      wireMockServer.start();
      wireMockServer.stubFor(get("/").willReturn(aResponse().withStatus(200).withBody("old")));

      var reader = new CacheReader(cache);
      reader.run();

      Thread.sleep(1000);

      wireMockServer.resetAll();
      wireMockServer.stubFor(get("/").willReturn(aResponse().withStatus(200).withBody("new").withFixedDelay(10000)));

      List<Thread> threads = new LinkedList<>();

      for (int i = 0; i < 3; ++i) {
        var otherThread = new Thread(new CacheReader(cache));
        threads.add(otherThread);
        otherThread.start();
      }

      for (int i = 0; i < 3; ++i) {
        threads.get(i).join();
      }
    } finally {
      wireMockServer.stop();
    }
  }

  /*
  What happens if we try again? Will future threads get the old value?
    As long as the previous "loading" thread has finished, if there are more attempts, a new "loading" thread will attempt again and the rest will return the old value
    That means a thread will always block with the current implementation
   */
  private static void example5_sameSetupAsProduction_whenFutureResponsesTimeoutAndMultipleAttempts_returnOldValue()
      throws InterruptedException {
    try {
      LoadingCache<String, String> cache = CacheBuilder.newBuilder()
          .refreshAfterWrite(500, TimeUnit.MILLISECONDS).build(loader);
      wireMockServer.start();
      wireMockServer.stubFor(get("/").willReturn(aResponse().withStatus(200).withBody("old")));

      var reader = new CacheReader(cache);
      reader.run();

      Thread.sleep(1000);

      wireMockServer.resetAll();
      wireMockServer.stubFor(get("/").willReturn(aResponse().withStatus(200).withBody("new").withFixedDelay(10000)));

      List<Thread> threads = new LinkedList<>();

      for (int i = 0; i < 3; ++i) {
        var otherThread = new Thread(new CacheReader(cache));
        threads.add(otherThread);
        otherThread.start();
      }

      for (int i = 0; i < 3; ++i) {
        threads.get(i).join();
      }

      threads = new LinkedList<>();

      for (int i = 0; i < 3; ++i) {
        var otherThread = new Thread(new CacheReader(cache));
        threads.add(otherThread);
        otherThread.start();
      }

      for (int i = 0; i < 3; ++i) {
        threads.get(i).join();
      }
    } finally {
      wireMockServer.stop();
    }
  }

  private static void solution_whenFutureResponsesTimeoutAndAsyncLoading_returnOldValue()
      throws InterruptedException {
    ExecutorService pool = Executors.newFixedThreadPool(10);
    try {
      LoadingCache<String, String> cache = CacheBuilder.newBuilder()
          .refreshAfterWrite(500, TimeUnit.MILLISECONDS).build(CacheLoader.asyncReloading(loader, pool));

      wireMockServer.start();
      wireMockServer.stubFor(get("/").willReturn(aResponse().withStatus(200).withBody("old")));

      var reader = new CacheReader(cache);
      reader.run();

      Thread.sleep(1000);

      wireMockServer.resetAll();
      wireMockServer.stubFor(get("/").willReturn(aResponse().withStatus(200).withBody("new").withFixedDelay(10000)));

      List<Thread> threads = new LinkedList<>();

      for (int i = 0; i < 3; ++i) {
        var otherThread = new Thread(new CacheReader(cache));
        threads.add(otherThread);
        otherThread.start();
      }

      for (int i = 0; i < 3; ++i) {
        threads.get(i).join();
      }
    } finally {
      Thread.sleep(10000);
      pool.shutdown();
      wireMockServer.stop();
    }
  }

  private static final CacheLoader loader =
      new CacheLoader<String, String>() {
        @Override
        public String load(final String environment)
            throws Exception {
          try {
            System.out.printf("Loading on thread %s\n", Thread.currentThread().getName());
            var builder =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1));

            var client = builder.build();
            final HttpRequest request =
                HttpRequest.newBuilder()
                    .uri(URI.create(wireMockServer.baseUrl()))
                    .timeout(Duration.ofSeconds(1))
                    .build();

            final HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

            return response.body();
          } catch (Exception e) {
            System.out.printf("Loading Exception on thread %s at %s\n", Thread.currentThread().getName(), System.currentTimeMillis());
            throw e;
          }
        }

        @Override
        public ListenableFuture<String> reload(String key, String oldValue) throws Exception {
          System.out.printf("Reloading on thread %s\n", Thread.currentThread().getName());
          return super.reload(key, oldValue);
        }
      };

  private static final CacheLoader loaderWithoutExceptionHandling =
      new CacheLoader<String, String>() {
        @Override
        public String load(final String environment)
            throws Exception {
          var builder =
              HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1));

          var client = builder.build();
          final HttpRequest request =
              HttpRequest.newBuilder()
                  .uri(URI.create(wireMockServer.baseUrl()))
                  .timeout(Duration.ofSeconds(1))
                  .build();

          final HttpResponse<String> response =
              client.send(request, HttpResponse.BodyHandlers.ofString());

          return response.body();
        }
      };

  /*
  Learnings:
  ==========
  /*
  Actions:
  - Async loading. Have old threads return an old value and load in the background
  - Log Exceptions being thrown during load method

  Root cause:
   - Thiago was hitting an instance with a cold cache and that thread was blocked waiting for a PMC response. Eventually timing out, but the exception was not logged properly.
   - It was easy to reproduce because we have 5 instances
   - This behavior repeats straight after the first loading thread finishes the execution. Thus, the only way to get an old cache would be hitting the exact same instance before the loading thread has finished
   - Restarting the instances was a red herring, or maybe it was fixing an underlying connectivity issue within rCluster. We saw some successful responses in the logs though
   - Having Synthetics executing this code path frequently is a way to keep the cache warm :)
   */
}
