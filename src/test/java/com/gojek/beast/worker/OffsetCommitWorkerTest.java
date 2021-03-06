package com.gojek.beast.worker;

import com.gojek.beast.Clock;
import com.gojek.beast.commiter.Acknowledger;
import com.gojek.beast.commiter.KafkaCommitter;
import com.gojek.beast.commiter.OffsetAcknowledger;
import com.gojek.beast.commiter.OffsetState;
import com.gojek.beast.config.QueueConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class OffsetCommitWorkerTest {

    @Captor
    private ArgumentCaptor<Map<TopicPartition, OffsetAndMetadata>> commitPartitionsOffsetCaptor;
    @Mock
    private Map<TopicPartition, OffsetAndMetadata> commitPartitionsOffset;
    @Mock
    private KafkaCommitter kafkaCommitter;
    private QueueConfig queueConfig;
    private int pollTimeout;
    private Set<Map<TopicPartition, OffsetAndMetadata>> acknowledgements;
    private OffsetState offsetState;
    private Acknowledger offsetAcknowledger;
    private WorkerState workerState;
    private long offsetBatchDuration;
    private long ackTimeoutTime;
    @Mock
    private Clock clock;

    @Before
    public void setUp() {
        pollTimeout = 200;
        offsetBatchDuration = 1000;
        ackTimeoutTime = 2000;
        queueConfig = new QueueConfig(pollTimeout);
        commitPartitionsOffset = new HashMap<TopicPartition, OffsetAndMetadata>() {{
            put(new TopicPartition("topic", 0), new OffsetAndMetadata(1));
        }};
        CopyOnWriteArraySet<Map<TopicPartition, OffsetAndMetadata>> ackSet = new CopyOnWriteArraySet<>();
        acknowledgements = Collections.synchronizedSet(ackSet);
        offsetState = new OffsetState(acknowledgements, ackTimeoutTime, offsetBatchDuration);
        workerState = new WorkerState();
        offsetAcknowledger = new OffsetAcknowledger(acknowledgements);
    }

    @After
    public void tearDown() {
        acknowledgements.clear();
    }


    @Test
    public void shouldCommitFirstOffsetWhenAcknowledged() throws InterruptedException {
        when(clock.currentEpochMillis()).thenReturn(0L, 0L, 0L, 1001L);
        BlockingQueue<Map<TopicPartition, OffsetAndMetadata>> commitQueue = spy(new LinkedBlockingQueue<>());
        OffsetCommitWorker committer = new OffsetCommitWorker("committer", new QueueConfig(200), kafkaCommitter, offsetState, commitQueue, workerState, clock);
        committer.setDefaultSleepMs(10);
        commitQueue.put(commitPartitionsOffset);
        offsetAcknowledger.acknowledge(commitPartitionsOffset);

        Thread commitThread = new Thread(committer);
        commitThread.start();

        await().until(commitQueue::isEmpty);
        workerState.closeWorker();
        commitThread.join();

        verify(commitQueue, atLeast(1)).poll(queueConfig.getTimeout(), queueConfig.getTimeoutUnit());
        verify(kafkaCommitter).commitSync(commitPartitionsOffsetCaptor.capture());
        Map<TopicPartition, OffsetAndMetadata> committedOffsets = commitPartitionsOffsetCaptor.getValue();
        assertEquals(1, committedOffsets.size());
        Map.Entry<TopicPartition, OffsetAndMetadata> offset = committedOffsets.entrySet().iterator().next();
        assertEquals(offset.getKey().topic(), "topic");
        assertEquals(offset.getKey().partition(), 0);
        assertEquals(offset.getValue().offset(), 1);
        assertTrue(commitQueue.isEmpty());
    }

    @Test
    public void shouldBatchCommitOffsets() throws InterruptedException {
        Map<TopicPartition, OffsetAndMetadata> record1CommitOffset = new HashMap<TopicPartition, OffsetAndMetadata>() {{
            put(new TopicPartition("topic", 0), new OffsetAndMetadata(1));
        }};
        Map<TopicPartition, OffsetAndMetadata> record2CommitOffset = new HashMap<TopicPartition, OffsetAndMetadata>() {{
            put(new TopicPartition("topic", 0), new OffsetAndMetadata(2));
        }};
        Map<TopicPartition, OffsetAndMetadata> record3CommitOffset = new HashMap<TopicPartition, OffsetAndMetadata>() {{
            put(new TopicPartition("topic", 0), new OffsetAndMetadata(3));
        }};
        when(clock.currentEpochMillis()).thenReturn(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 1001L);
        BlockingQueue<Map<TopicPartition, OffsetAndMetadata>> commitQueue = spy(new LinkedBlockingQueue<>());
        OffsetCommitWorker committer = new OffsetCommitWorker("committer", new QueueConfig(200), kafkaCommitter, offsetState, commitQueue, workerState, clock);

        for (Map<TopicPartition, OffsetAndMetadata> rs : Arrays.asList(record1CommitOffset, record2CommitOffset, record3CommitOffset)) {
            commitQueue.offer(rs, 1, TimeUnit.SECONDS);
        }
        offsetAcknowledger.acknowledge(record3CommitOffset);
        offsetAcknowledger.acknowledge(record1CommitOffset);
        offsetAcknowledger.acknowledge(record2CommitOffset);
        Thread committerThread = new Thread(committer, "commiter");

        committerThread.start();
        await().until(() -> acknowledgements.isEmpty());
        workerState.closeWorker();
        committerThread.join();

        verify(commitQueue, atLeast(3)).poll(queueConfig.getTimeout(), queueConfig.getTimeoutUnit());
        verify(kafkaCommitter).commitSync(commitPartitionsOffsetCaptor.capture());
        Map<TopicPartition, OffsetAndMetadata> committedOffsets = commitPartitionsOffsetCaptor.getValue();
        assertEquals(1, committedOffsets.size());
        Map.Entry<TopicPartition, OffsetAndMetadata> offset = committedOffsets.entrySet().iterator().next();
        assertEquals(offset.getKey().topic(), "topic");
        assertEquals(offset.getKey().partition(), 0);
        assertEquals(offset.getValue().offset(), 3);
        assertTrue(commitQueue.isEmpty());
    }

    @Test
    public void shouldCommitInSequenceWithParallelAcknowledgements() throws InterruptedException {
        Map<TopicPartition, OffsetAndMetadata> record1CommitOffset = new HashMap<TopicPartition, OffsetAndMetadata>() {{
            put(new TopicPartition("topic", 0), new OffsetAndMetadata(1));
        }};
        Map<TopicPartition, OffsetAndMetadata> record2CommitOffset = new HashMap<TopicPartition, OffsetAndMetadata>() {{
            put(new TopicPartition("topic", 0), new OffsetAndMetadata(2));
        }};
        Map<TopicPartition, OffsetAndMetadata> record3CommitOffset = new HashMap<TopicPartition, OffsetAndMetadata>() {{
            put(new TopicPartition("topic", 0), new OffsetAndMetadata(3));
        }};
        int ackDelayRandomMs = 100;
        when(clock.currentEpochMillis()).thenReturn(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 1001L);
        BlockingQueue<Map<TopicPartition, OffsetAndMetadata>> commitQueue = spy(new LinkedBlockingQueue<>());
        OffsetCommitWorker committer = new OffsetCommitWorker("committer", new QueueConfig(200), kafkaCommitter, offsetState, commitQueue, workerState, clock);
        committer.setDefaultSleepMs(50);
        List<Map<TopicPartition, OffsetAndMetadata>> incomingRecords = Arrays.asList(record1CommitOffset, record2CommitOffset, record3CommitOffset);
        for (Map<TopicPartition, OffsetAndMetadata> incomingRecord : incomingRecords) {
            commitQueue.offer(incomingRecord, 1, TimeUnit.SECONDS);
        }

        Thread commiterThread = new Thread(committer);
        commiterThread.start();

        AcknowledgeHelper acknowledger1 = new AcknowledgeHelper(record2CommitOffset, offsetAcknowledger, new Random().nextInt(ackDelayRandomMs));
        AcknowledgeHelper acknowledger2 = new AcknowledgeHelper(record1CommitOffset, offsetAcknowledger, new Random().nextInt(ackDelayRandomMs));
        AcknowledgeHelper acknowledger3 = new AcknowledgeHelper(record3CommitOffset, offsetAcknowledger, new Random().nextInt(ackDelayRandomMs));
        List<AcknowledgeHelper> acknowledgers = Arrays.asList(acknowledger1, acknowledger2, acknowledger3);
        acknowledgers.forEach(Thread::start);

        await().until(commitQueue::isEmpty);
        workerState.closeWorker();
        commiterThread.join();
        verify(kafkaCommitter).commitSync(commitPartitionsOffsetCaptor.capture());
        Map<TopicPartition, OffsetAndMetadata> committedOffsets = commitPartitionsOffsetCaptor.getValue();
        assertEquals(1, committedOffsets.size());
        Map.Entry<TopicPartition, OffsetAndMetadata> offset = committedOffsets.entrySet().iterator().next();
        assertEquals(offset.getKey().topic(), "topic");
        assertEquals(offset.getKey().partition(), 0);
        assertEquals(offset.getValue().offset(), 3);
    }

    @Test
    public void shouldStopProcessWhenCommitterGetException() throws InterruptedException {
        doThrow(ConcurrentModificationException.class).when(kafkaCommitter).commitSync(anyMap());
        offsetAcknowledger.acknowledge(commitPartitionsOffset);
        BlockingQueue<Map<TopicPartition, OffsetAndMetadata>> commitQueue = spy(new LinkedBlockingQueue<>());
        when(clock.currentEpochMillis()).thenReturn(0L, 0L, 0L, 1001L);
        OffsetCommitWorker committer = new OffsetCommitWorker("committer", new QueueConfig(200), kafkaCommitter, offsetState, commitQueue, workerState, clock);
        commitQueue.put(commitPartitionsOffset);

        Thread commiterThread = new Thread(committer);
        commiterThread.start();

        commiterThread.join();

        verify(kafkaCommitter).commitSync(anyMap());
        verify(kafkaCommitter, atLeastOnce()).wakeup(anyString());
    }
}

class AcknowledgeHelper extends Thread {

    private Map<TopicPartition, OffsetAndMetadata> offset;
    private Acknowledger acknowledger;
    private long sleepMs;

    AcknowledgeHelper(Map<TopicPartition, OffsetAndMetadata> offset, Acknowledger acknowledger, long sleepMs) {
        this.offset = offset;
        this.acknowledger = acknowledger;
        this.sleepMs = sleepMs;
    }

    @Override
    public void run() {
        try {
            sleep(sleepMs);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        acknowledger.acknowledge(offset);
    }
}
