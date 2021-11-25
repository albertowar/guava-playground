package org.example;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.google.common.cache.LoadingCache;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

public class GuavaPlaygroundTests {
  private static final int THREAD_COUNT = 3;

  private static WireMockServer wireMockServer;

  /*
   The Exception is logged and shallowed. Our logger ignores that package (com.google.common)
  */
  @Test
  void
      example1_givenPopulatedCache_whenFutureResponsesTimeoutAndNoExceptionHandling_exceptionShallowed()
          throws InterruptedException {
    LoadingCache<String, String> cache =
        CacheFactory.buildCacheWithoutExceptionHandling(wireMockServer.baseUrl());

    setupRemoteResponse("old");

    var thread = new Thread(new CacheReader(cache));
    thread.start();

    waitForCacheToExpire();

    setupRemoteResponse("new", 2 * Constants.TIMEOUT_IN_SECONDS);

    runManyAndWait(cache, THREAD_COUNT);
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
    LoadingCache<String, String> cache = CacheFactory.buildCache(wireMockServer.baseUrl());

    wireMockServer.stubFor(get("/").willReturn(aResponse().withStatus(200).withBody("old")));

    var reader = new CacheReader(cache);
    reader.run();

    waitForCacheToExpire();

    setupRemoteResponse("new", 2 * Constants.TIMEOUT_IN_SECONDS);

    runManyAndWait(cache, THREAD_COUNT);
  }

  /*
  What if the cache was empty?
    That's ruled out because we would have seen an exception way sooner and in all threads
   */
  @Test
  void example3_sameSetupAsProduction_emptyCacheAndFutureResponsesTimeout_shouldThrowException()
      throws InterruptedException {
    LoadingCache<String, String> cache = CacheFactory.buildCache(wireMockServer.baseUrl());
    setupRemoteResponse("new", 2 * Constants.TIMEOUT_IN_SECONDS);

    runManyAndWait(cache, THREAD_COUNT);
  }

  /*
  What if we use eviction?
    We can't use eviction because we still want to get the old value while the new one is being fetched
   */
  @Test
  void example4_usingEvictionInstead_whenFutureResponsesTimeout_throwsException()
      throws InterruptedException {
    LoadingCache<String, String> cache =
        CacheFactory.buildCacheWithEviction(wireMockServer.baseUrl());

    setupRemoteResponse("old");

    var reader = new CacheReader(cache);
    reader.run();

    waitForCacheToExpire();

    setupRemoteResponse("new", 2 * Constants.TIMEOUT_IN_SECONDS);

    runManyAndWait(cache, THREAD_COUNT);
  }

  /*
  What happens if we try again? Will future threads get the old value?
    As long as the previous "loading" thread has finished, if there are more attempts, a new "loading" thread will attempt again and the rest will return the old value
    That means a thread will always block with the current implementation
   */
  @Test
  void example5_sameSetupAsProduction_whenFutureResponsesTimeoutAndMultipleAttempts_returnOldValue()
      throws InterruptedException {
    LoadingCache<String, String> cache = CacheFactory.buildCache(wireMockServer.baseUrl());
    setupRemoteResponse("old");

    var reader = new CacheReader(cache);
    reader.run();

    waitForCacheToExpire();

    setupRemoteResponse("new", 2 * Constants.TIMEOUT_IN_SECONDS);

    runManyAndWait(cache, THREAD_COUNT);
    runManyAndWait(cache, THREAD_COUNT);
  }

  @Test
  void solution_whenFutureResponsesTimeoutAndAsyncLoading_returnOldValue()
      throws InterruptedException {
    ExecutorService pool = Executors.newFixedThreadPool(10);
    try {
      LoadingCache<String, String> cache =
          CacheFactory.buildCacheWithAsyncLoading(wireMockServer.baseUrl(), pool);

      setupRemoteResponse("old");

      runAndWait(cache);

      waitForCacheToExpire();

      setupRemoteResponse("new", 2 * Constants.TIMEOUT_IN_SECONDS);

      runManyAndWait(cache, THREAD_COUNT);
    } finally {
      Thread.sleep(10000);
      pool.shutdown();
    }
  }

  /*
  What happens when everything is fine?
   */
  @Test
  void givenAnExistingValueInCache_when() throws InterruptedException {
    LoadingCache<String, String> cache = CacheFactory.buildCache(wireMockServer.baseUrl());

    setupRemoteResponse("old");

    runManyAndWait(cache, 1);

    waitForCacheToExpire();

    setupRemoteResponse("new");

    runManyAndWait(cache, THREAD_COUNT);
  }

  private void runManyAndWait(LoadingCache<String, String> cache, int threadCount)
      throws InterruptedException {
    List<Thread> threads = new LinkedList<>();

    for (int i = 0; i < threadCount; ++i) {
      var otherThread = new Thread(new CacheReader(cache));
      threads.add(otherThread);
      otherThread.start();
    }

    for (int i = 0; i < threadCount; ++i) {
      threads.get(i).join();
    }
  }

  private void runAndWait(LoadingCache<String, String> cache) throws InterruptedException {
    runManyAndWait(cache, 1);
  }

  private static void setupRemoteResponse(String body, int delay) {
    wireMockServer.resetAll();
    wireMockServer.stubFor(
        get("/").willReturn(aResponse().withStatus(200).withBody(body).withFixedDelay(delay)));
  }

  private static void setupRemoteResponse(String body) {
    wireMockServer.stubFor(get("/").willReturn(aResponse().withStatus(200).withBody(body)));
  }

  private static void waitForCacheToExpire() throws InterruptedException {
    Thread.sleep(2 * Constants.CACHE_REFRESH_TIME_MS);
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
