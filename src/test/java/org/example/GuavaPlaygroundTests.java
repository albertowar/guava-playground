package org.example;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import org.junit.jupiter.api.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class GuavaPlaygroundTests {
  private static WireMockServer wireMockServer;

  /*
  What happens when everything is fine?
   */
  @Test
  void example0_sameSetupAsProduction_happyPath_returnsNewValue()
      throws InterruptedException {
    LoadingCache<String, String> cache =
            CacheBuilder.newBuilder()
                    .refreshAfterWrite(500, TimeUnit.MILLISECONDS)
                    .build(CacheLoaderFactory.buildCacheLoader(wireMockServer.baseUrl()));

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
  }

  /*
   The Exception is logged and shallowed. Our logger ignores that package (com.google.common)
  */
  @Test
  void
      example1_givenPopulatedCache_whenFutureResponsesTimeoutAndNoExceptionHandling_exceptionShallowed()
          throws InterruptedException {
    LoadingCache<String, String> cache =
            CacheBuilder.newBuilder()
                    .refreshAfterWrite(500, TimeUnit.MILLISECONDS)
                    .build(
                            CacheLoaderFactory.buildCacheLoaderWithoutExceptionHandling(
                                    wireMockServer.baseUrl()));

    wireMockServer.stubFor(get("/").willReturn(aResponse().withStatus(200).withBody("old")));

    var thread = new Thread(new CacheReader(cache));
    thread.run();

    Thread.sleep(1000);

    wireMockServer.resetAll();
    wireMockServer.stubFor(
            get("/").willReturn(aResponse().withStatus(200).withBody("new").withFixedDelay(10000)));

    List<Thread> threads = new LinkedList<>();

    for (int i = 0; i < 3; ++i) {
      var otherThread = new Thread(new CacheReader(cache));
      threads.add(otherThread);
      otherThread.start();
    }

    for (int i = 0; i < 3; ++i) {
      threads.get(i).join();
    }
  }

  /*
  What if the refresh timed out?
    If there is an old value, the loader thread will block fetching the new value. If it fails, a exception is thrown on the "loader" thread and it will get the old value.
    Any other threads that attempt to load the value at the same time than the loader, will get the old value instead

    In the event of a timeout, this is the behavior we should have seen. The website times out after 10 seconds, so maybe we hit it.
   */
  @Test
  void example2_sameSetupAsProduction_SimulatedFailure_shouldReturnOldValue()
      throws InterruptedException {
    LoadingCache<String, String> cache =
            CacheBuilder.newBuilder()
                    .refreshAfterWrite(500, TimeUnit.MILLISECONDS)
                    .build(CacheLoaderFactory.buildCacheLoader(wireMockServer.baseUrl()));

    wireMockServer.stubFor(get("/").willReturn(aResponse().withStatus(200).withBody("old")));

    var reader = new CacheReader(cache);
    reader.run();

    Thread.sleep(1000);

    wireMockServer.resetAll();
    wireMockServer.stubFor(
            get("/").willReturn(aResponse().withStatus(200).withBody("new").withFixedDelay(10000)));

    List<Thread> threads = new LinkedList<>();

    for (int i = 0; i < 3; ++i) {
      var otherThread = new Thread(new CacheReader(cache));
      threads.add(otherThread);
      otherThread.start();
    }

    for (int i = 0; i < 3; ++i) {
      threads.get(i).join();
    }
  }

  /*
  What if the cache was empty?
    That's ruled out because we would have seen an exception way sooner and in all threads
   */
  @Test void
      example3_sameSetupAsProduction_emptyCacheAndFutureResponsesTimeout_shouldThrowException()
          throws InterruptedException {
    LoadingCache<String, String> cache =
            CacheBuilder.newBuilder()
                    .refreshAfterWrite(500, TimeUnit.MILLISECONDS)
                    .build(CacheLoaderFactory.buildCacheLoader(wireMockServer.baseUrl()));
    wireMockServer.stubFor(
            get("/").willReturn(aResponse().withStatus(200).withBody("new").withFixedDelay(10000)));

    List<Thread> threads = new LinkedList<>();

    for (int i = 0; i < 3; ++i) {
      var otherThread = new Thread(new CacheReader(cache));
      threads.add(otherThread);
      otherThread.start();
    }

    for (int i = 0; i < 3; ++i) {
      threads.get(i).join();
    }
  }

  /*
  What if we use eviction?
    We can't use eviction because we still want to get the old value while the new one is being fetched
   */
  @Test
  void example4_usingEvictionInstead_whenFutureResponsesTimeout_throwsException()
      throws InterruptedException {
    LoadingCache<String, String> cache =
            CacheBuilder.newBuilder()
                    .expireAfterWrite(500, TimeUnit.MILLISECONDS)
                    .build(CacheLoaderFactory.buildCacheLoader(wireMockServer.baseUrl()));

    wireMockServer.stubFor(get("/").willReturn(aResponse().withStatus(200).withBody("old")));

    var reader = new CacheReader(cache);
    reader.run();

    Thread.sleep(1000);

    wireMockServer.resetAll();
    wireMockServer.stubFor(
            get("/").willReturn(aResponse().withStatus(200).withBody("new").withFixedDelay(10000)));

    List<Thread> threads = new LinkedList<>();

    for (int i = 0; i < 3; ++i) {
      var otherThread = new Thread(new CacheReader(cache));
      threads.add(otherThread);
      otherThread.start();
    }

    for (int i = 0; i < 3; ++i) {
      threads.get(i).join();
    }
  }

  /*
  What happens if we try again? Will future threads get the old value?
    As long as the previous "loading" thread has finished, if there are more attempts, a new "loading" thread will attempt again and the rest will return the old value
    That means a thread will always block with the current implementation
   */
  @Test
  void
      example5_sameSetupAsProduction_whenFutureResponsesTimeoutAndMultipleAttempts_returnOldValue()
          throws InterruptedException {
    LoadingCache<String, String> cache =
            CacheBuilder.newBuilder()
                    .refreshAfterWrite(500, TimeUnit.MILLISECONDS)
                    .build(CacheLoaderFactory.buildCacheLoader(wireMockServer.baseUrl()));
    wireMockServer.stubFor(get("/").willReturn(aResponse().withStatus(200).withBody("old")));

    var reader = new CacheReader(cache);
    reader.run();

    Thread.sleep(1000);

    wireMockServer.resetAll();
    wireMockServer.stubFor(
            get("/").willReturn(aResponse().withStatus(200).withBody("new").withFixedDelay(10000)));

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
  }

  @Test
  void solution_whenFutureResponsesTimeoutAndAsyncLoading_returnOldValue()
      throws InterruptedException {
    ExecutorService pool = Executors.newFixedThreadPool(10);
    try {
      LoadingCache<String, String> cache =
          CacheBuilder.newBuilder()
              .refreshAfterWrite(500, TimeUnit.MILLISECONDS)
              .build(
                  CacheLoader.asyncReloading(
                      CacheLoaderFactory.buildCacheLoader(wireMockServer.baseUrl()), pool));

      wireMockServer.stubFor(get("/").willReturn(aResponse().withStatus(200).withBody("old")));

      var reader = new CacheReader(cache);
      reader.run();

      Thread.sleep(1000);

      wireMockServer.resetAll();
      wireMockServer.stubFor(
          get("/").willReturn(aResponse().withStatus(200).withBody("new").withFixedDelay(10000)));

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
    }
  }

  @BeforeAll
  public static void beforeAll() {
    wireMockServer = new WireMockServer(wireMockConfig().port(8089));
    wireMockServer.start();
  }

  @AfterAll
  public static void afterAll() {
    wireMockServer.stop();
  }

  @AfterEach
  public void afterEach() {
    wireMockServer.resetAll();
  }
}
