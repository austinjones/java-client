package com.launchdarkly.client;

import org.easymock.EasyMockSupport;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.launchdarkly.client.TestUtil.flagWithValue;
import static com.launchdarkly.client.TestUtil.initedFeatureStore;
import static com.launchdarkly.client.TestUtil.jint;
import static com.launchdarkly.client.TestUtil.specificFeatureStore;
import static com.launchdarkly.client.VersionedDataKind.FEATURES;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import junit.framework.AssertionFailedError;

/**
 * See also LDClientEvaluationTest, etc. This file contains mostly tests for the startup logic.
 */
public class LDClientTest extends EasyMockSupport {
  private UpdateProcessor updateProcessor;
  private EventProcessor eventProcessor;
  private Future<Void> initFuture;
  private LDClientInterface client;

  @SuppressWarnings("unchecked")
  @Before
  public void before() {
    updateProcessor = createStrictMock(UpdateProcessor.class);
    eventProcessor = createStrictMock(EventProcessor.class);
    initFuture = createStrictMock(Future.class);
  }

  @Test
  public void clientHasDefaultEventProcessorIfSendEventsIsTrue() throws Exception {
    LDConfig config = new LDConfig.Builder()
        .stream(false)
        .baseURI(URI.create("/fake"))
        .startWaitMillis(0)
        .sendEvents(true)
        .build();
    try (LDClient client = new LDClient("SDK_KEY", config)) {
      assertEquals(DefaultEventProcessor.class, client.eventProcessor.getClass());
    }
  }
  
  @Test
  public void clientHasNullEventProcessorIfSendEventsIsFalse() throws IOException {
    LDConfig config = new LDConfig.Builder()
        .stream(false)
        .baseURI(URI.create("/fake"))
        .startWaitMillis(0)
        .sendEvents(false)
        .build();
    try (LDClient client = new LDClient("SDK_KEY", config)) {
      assertEquals(EventProcessor.NullEventProcessor.class, client.eventProcessor.getClass());
    }
  }
  
  @Test
  public void streamingClientHasStreamProcessor() throws Exception {
    LDConfig config = new LDConfig.Builder()
        .stream(true)
        .streamURI(URI.create("/fake"))
        .startWaitMillis(0)
        .build();
    try (LDClient client = new LDClient("SDK_KEY", config)) {
      assertEquals(StreamProcessor.class, client.updateProcessor.getClass());
    }
  }

  @Test
  public void pollingClientHasPollingProcessor() throws IOException {
    LDConfig config = new LDConfig.Builder()
        .stream(false)
        .baseURI(URI.create("/fake"))
        .startWaitMillis(0)
        .build();
    try (LDClient client = new LDClient("SDK_KEY", config)) {
      assertEquals(PollingProcessor.class, client.updateProcessor.getClass());
    }
  }

  @Test
  public void noWaitForUpdateProcessorIfWaitMillisIsZero() throws Exception {
    LDConfig.Builder config = new LDConfig.Builder()
        .startWaitMillis(0L);

    expect(updateProcessor.start()).andReturn(initFuture);
    expect(updateProcessor.initialized()).andReturn(false);
    replayAll();

    client = createMockClient(config);
    assertFalse(client.initialized());

    verifyAll();
  }

  @Test
  public void willWaitForUpdateProcessorIfWaitMillisIsNonZero() throws Exception {
    LDConfig.Builder config = new LDConfig.Builder()
        .startWaitMillis(10L);

    expect(updateProcessor.start()).andReturn(initFuture);
    expect(initFuture.get(10L, TimeUnit.MILLISECONDS)).andReturn(null);
    expect(updateProcessor.initialized()).andReturn(false).anyTimes();
    replayAll();

    client = createMockClient(config);
    assertFalse(client.initialized());

    verifyAll();
  }

  @Test
  public void updateProcessorCanTimeOut() throws Exception {
    LDConfig.Builder config = new LDConfig.Builder()
        .startWaitMillis(10L);

    expect(updateProcessor.start()).andReturn(initFuture);
    expect(initFuture.get(10L, TimeUnit.MILLISECONDS)).andThrow(new TimeoutException());
    expect(updateProcessor.initialized()).andReturn(false).anyTimes();
    replayAll();

    client = createMockClient(config);
    assertFalse(client.initialized());

    verifyAll();
  }
  
  @Test
  public void clientCatchesRuntimeExceptionFromUpdateProcessor() throws Exception {
    LDConfig.Builder config = new LDConfig.Builder()
        .startWaitMillis(10L);

    expect(updateProcessor.start()).andReturn(initFuture);
    expect(initFuture.get(10L, TimeUnit.MILLISECONDS)).andThrow(new RuntimeException());
    expect(updateProcessor.initialized()).andReturn(false).anyTimes();
    replayAll();

    client = createMockClient(config);
    assertFalse(client.initialized());

    verifyAll();
  }

  @Test
  public void isFlagKnownReturnsTrueForExistingFlag() throws Exception {
    FeatureStore testFeatureStore = initedFeatureStore();
    LDConfig.Builder config = new LDConfig.Builder()
            .startWaitMillis(0)
            .featureStoreFactory(specificFeatureStore(testFeatureStore));
    expect(updateProcessor.start()).andReturn(initFuture);
    expect(updateProcessor.initialized()).andReturn(true).times(1);
    replayAll();

    client = createMockClient(config);

    testFeatureStore.upsert(FEATURES, flagWithValue("key", jint(1)));
    assertTrue(client.isFlagKnown("key"));
    verifyAll();
  }

  @Test
  public void isFlagKnownReturnsFalseForUnknownFlag() throws Exception {
    FeatureStore testFeatureStore = initedFeatureStore();
    LDConfig.Builder config = new LDConfig.Builder()
            .startWaitMillis(0)
            .featureStoreFactory(specificFeatureStore(testFeatureStore));
    expect(updateProcessor.start()).andReturn(initFuture);
    expect(updateProcessor.initialized()).andReturn(true).times(1);
    replayAll();

    client = createMockClient(config);

    assertFalse(client.isFlagKnown("key"));
    verifyAll();
  }

  @Test
  public void isFlagKnownReturnsFalseIfStoreAndClientAreNotInitialized() throws Exception {
    FeatureStore testFeatureStore = new InMemoryFeatureStore();
    LDConfig.Builder config = new LDConfig.Builder()
            .startWaitMillis(0)
            .featureStoreFactory(specificFeatureStore(testFeatureStore));
    expect(updateProcessor.start()).andReturn(initFuture);
    expect(updateProcessor.initialized()).andReturn(false).times(1);
    replayAll();

    client = createMockClient(config);

    testFeatureStore.upsert(FEATURES, flagWithValue("key", jint(1)));
    assertFalse(client.isFlagKnown("key"));
    verifyAll();
  }

  @Test
  public void isFlagKnownUsesStoreIfStoreIsInitializedButClientIsNot() throws Exception {
    FeatureStore testFeatureStore = initedFeatureStore();
    LDConfig.Builder config = new LDConfig.Builder()
            .startWaitMillis(0)
            .featureStoreFactory(specificFeatureStore(testFeatureStore));
    expect(updateProcessor.start()).andReturn(initFuture);
    expect(updateProcessor.initialized()).andReturn(false).times(1);
    replayAll();

    client = createMockClient(config);

    testFeatureStore.upsert(FEATURES, flagWithValue("key", jint(1)));
    assertTrue(client.isFlagKnown("key"));
    verifyAll();
  }
  
  @Test
  public void evaluationUsesStoreIfStoreIsInitializedButClientIsNot() throws Exception {
    FeatureStore testFeatureStore = initedFeatureStore();
    LDConfig.Builder config = new LDConfig.Builder()
        .featureStoreFactory(specificFeatureStore(testFeatureStore))
        .startWaitMillis(0L);
    expect(updateProcessor.start()).andReturn(initFuture);
    expect(updateProcessor.initialized()).andReturn(false);
    expectEventsSent(1);
    replayAll();

    client = createMockClient(config);
    
    testFeatureStore.upsert(FEATURES, flagWithValue("key", jint(1)));
    assertEquals(new Integer(1), client.intVariation("key", new LDUser("user"), 0));
    
    verifyAll();
  }

  private void expectEventsSent(int count) {
    eventProcessor.sendEvent(anyObject(Event.class));
    if (count > 0) {
      expectLastCall().times(count);
    } else {
      expectLastCall().andThrow(new AssertionFailedError("should not have queued an event")).anyTimes();
    }
  }
  
  private LDClientInterface createMockClient(LDConfig.Builder config) {
    config.updateProcessorFactory(TestUtil.specificUpdateProcessor(updateProcessor));
    config.eventProcessorFactory(TestUtil.specificEventProcessor(eventProcessor));
    return new LDClient("SDK_KEY", config.build());
  }
}