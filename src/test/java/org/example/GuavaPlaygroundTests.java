package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.google.common.cache.LoadingCache;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
public class GuavaPlaygroundTests {
  private static final int THREAD_COUNT = 3;
  private static final ObjectMapper mapper = new ObjectMapper();

  private static WireMockServer wireMockServer;

  private static final Thread.UncaughtExceptionHandler handler =
      (t, e) -> log.error("Uncaught exception");

  @Test
  @SneakyThrows
  void givenEmptyCache_whenRemoteServiceTimeout_shouldThrowException() {
    var cache = CacheFactory.buildCacheWithoutExceptionHandling(wireMockServer.baseUrl());

    setupRemoteResponse(new Data("value"), 2 * Constants.TIMEOUT_IN_SECONDS);

    var thread = new Thread(new CacheReader(cache));
    thread.start();
    thread.join();
  }

  @Test
  @SneakyThrows
  void givenEmptyCache_whenRemoteServiceReturns500_shouldThrowException() {
    var cache = CacheFactory.buildCacheWithoutExceptionHandling(wireMockServer.baseUrl());

    setupFailedResponse();

    var thread = new Thread(new CacheReader(cache));
    thread.start();
    thread.join();
  }

  @Test
  @SneakyThrows
  void givenCacheDueForRefresh_whenRemoteServiceTimeout_shouldGetOldValue() {
    var cache = CacheFactory.buildCacheWithoutExceptionHandling(wireMockServer.baseUrl());

    setupRemoteResponse(new Data("old_value"));

    var thread = new Thread(new CacheReader(cache));
    thread.start();
    thread.join();

    waitForCacheToExpire();

    setupRemoteResponse(new Data("new_value"), 2 * Constants.TIMEOUT_IN_SECONDS);

    thread = new Thread(new CacheReader(cache));
    thread.start();
    thread.join();
  }

  @Test
  @SneakyThrows
  void givenCacheDueForRefresh_whenRemoteServiceTimeout_allThreadsShouldGetOldValue() {
    var cache = CacheFactory.buildCacheWithoutExceptionHandling(wireMockServer.baseUrl());

    setupRemoteResponse(new Data("old_value"));

    var thread = new Thread(new CacheReader(cache));
    thread.start();
    thread.join();

    waitForCacheToExpire();

    setupRemoteResponse(new Data("new_value"), 2 * Constants.TIMEOUT_IN_SECONDS);

    runManyAndWait(cache, 3);
  }

  @Test
  @SneakyThrows
  void solution_whenFutureResponsesTimeoutAndAsyncLoading_returnOldValue() {
    ExecutorService pool = Executors.newFixedThreadPool(10);
    try {
      var cache = CacheFactory.buildCacheWithAsyncLoading(wireMockServer.baseUrl(), pool);

      setupRemoteResponse(new Data("old_value"));

      runAndWait(cache);

      waitForCacheToExpire();

      setupRemoteResponse(new Data("new_value"), 2 * Constants.TIMEOUT_IN_SECONDS);

      runManyAndWait(cache, THREAD_COUNT);
    } finally {
      Thread.sleep(10000);
      pool.shutdown();
    }
  }

  @Test
  @SneakyThrows
  void happyPath() {
    var cache = CacheFactory.buildCache(wireMockServer.baseUrl());

    setupRemoteResponse(new Data("old_value"));

    runManyAndWait(cache, 1);

    waitForCacheToExpire();

    setupRemoteResponse(new Data("new_value"));

    runManyAndWait(cache, THREAD_COUNT);
  }

  private void runManyAndWait(LoadingCache<String, Data> cache, int threadCount)
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

  private void runAndWait(LoadingCache<String, Data> cache) throws InterruptedException {
    runManyAndWait(cache, 1);
  }

  @SneakyThrows
  private static void setupRemoteResponse(Data data, int delayInSeconds) {
    wireMockServer.resetAll();
    wireMockServer.stubFor(
        get("/")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody(mapper.writeValueAsString(data))
                    .withFixedDelay(delayInSeconds * 1000)));
  }

  private static void setupFailedResponse() {
    wireMockServer.resetAll();
    wireMockServer.stubFor(get("/").willReturn(aResponse().withStatus(500)));
  }

  @SneakyThrows
  private static void setupRemoteResponse(Data data) {
    wireMockServer.stubFor(
        get("/").willReturn(aResponse().withStatus(200).withBody(mapper.writeValueAsString(data))));
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
