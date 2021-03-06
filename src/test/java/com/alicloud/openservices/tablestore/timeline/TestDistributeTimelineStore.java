package com.alicloud.openservices.tablestore.timeline;

import com.alicloud.openservices.tablestore.SyncClient;
import com.alicloud.openservices.tablestore.model.DeleteTableRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class TestDistributeTimelineStore {
    private final static String endpoint = "<your endpoing>";
    private final static String accessKeyID = "<your access key id>";
    private final static String accessKeySecret = "<your access key secret>";
    private final static String instanceName = "<your instance name>";
    private final static String testTablePrefix = "__timelinetest_ts_";
    private static SyncClient ots = null;
    private static DistributeTimelineConfig config = null;

    @Before
    public void setUp() throws Exception {
        ots = new SyncClient(endpoint, accessKeyID, accessKeySecret, instanceName);

        config = new DistributeTimelineConfig(endpoint, accessKeyID,
                accessKeySecret, instanceName, "base_timeline_test_table");
        config.setTtl(-1);
        config.setMessageInstance(new StringMessage());
    }

    @After
    public void after() throws Exception {
        List<String> tables = ots.listTable().getTableNames();
        for (String table: tables) {
            if (table.startsWith(testTablePrefix)) {
                ots.deleteTable(new DeleteTableRequest(table));
            }
        }
    }

    @Test
    public void testCreate() {
        config.setTableName(testTablePrefix + "testCreate");
        IStore store = new DistributeTimelineStore(config);

        assertTrue(!store.exist());

        try {
            store.drop();
        } catch (RuntimeException ex) {
            fail();
        }

        store.create();
        sleep(5);

        assertTrue(store.exist());

        try {
            store.create();
        } catch (RuntimeException ex) {
            fail();
        }

        store.drop();

        assertTrue(!store.exist());

        try {
            store.drop();
        } catch (RuntimeException ex) {
            fail();
        }
    }

    @Test
    public void testWrite_Exception() {
        config.setTableName(testTablePrefix + "testWrite_Exception");
        IStore store = new DistributeTimelineStore(config);
        String timelineID = "00001";
        String content = String.valueOf(new Date().getTime());
        IMessage message = new StringMessage(content);

        try {
            store.write(timelineID, message);
            fail();
        } catch (RuntimeException ex) {
            assertEquals("Store is not create, please create before write", ex.getMessage());
        }
    }

    @Test
    public void testWriteAsyncByFuture_Exception() {
        config.setTableName(testTablePrefix + "testWriteAsyncByFuture");
        IStore store = new DistributeTimelineStore(config);
        String timelineID = "00001";
        String content = String.valueOf(new Date().getTime());
        IMessage message = new StringMessage(content);

        Future<TimelineEntry> future = store.writeAsync(timelineID, message, null);
        try {
            future.get();
            fail();
        } catch (RuntimeException ex) {
            assertEquals("Store is not create, please create before write", ex.getMessage());
        } catch (Exception ex) {
            fail();
        }
    }

    @Test
    public void testWriteAsyncByCallback_Exception() {
        config.setTableName(testTablePrefix + "testWriteAsyncByCallback");
        IStore store = new DistributeTimelineStore(config);
        String timelineID = "00001";
        String content = String.valueOf(new Date().getTime());
        IMessage message = new StringMessage(content);
        final AtomicBoolean isDone = new AtomicBoolean(false);
        final AtomicReference<Exception> e = new AtomicReference<Exception>();

        store.writeAsync(timelineID, message, new TimelineCallback<IMessage>() {
            @Override
            public void onCompleted(String timelineID, IMessage request, TimelineEntry timelineEntry) {
                fail();
            }

            @Override
            public void onFailed(String timelineID, IMessage request, Exception ex) {
                e.set(ex);
                isDone.set(true);
            }
        });
        while (!isDone.get()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
               fail();
            }
        }

        assertTrue(e.get() instanceof RuntimeException);
        assertEquals("Store is not create, please create before write", e.get().getMessage());
    }

    @Test
    public void testWriteRead() {
        config.setTableName(testTablePrefix + "testWriteRead");
        IStore store = new DistributeTimelineStore(config);
        store.create();
        sleep(5);

        String timelineID = "00001";
        String content = String.valueOf(new Date().getTime());
        IMessage message = new StringMessage(content);
        TimelineEntry entry = store.write(timelineID, message);

        assertEquals(message, entry.getMessage());

        Long sequenceID = entry.getSequenceID();
        assertTrue(sequenceID> 0);

        TimelineEntry entry2 = store.read(timelineID, sequenceID);
        assertEquals(sequenceID, entry2.getSequenceID());
        assertEquals(new String(message.serialize()), new String(entry2.getMessage().serialize()));
    }

    @Test
    public void testWriteRead_Future() {
        config.setTableName(testTablePrefix + "testWriteRead_Future");
        IStore store = new DistributeTimelineStore(config);
        store.create();
        sleep(5);

        String timelineID = "00001";
        String content = String.valueOf(new Date().getTime());
        IMessage message = new StringMessage(content);
        Future<TimelineEntry> future = store.writeAsync(timelineID, message, null);
        assertTrue(future != null);

        Long sequenceID = 0L;
        try {
            TimelineEntry entry = future.get();
            assertEquals(message, entry.getMessage());
            sequenceID = entry.getSequenceID();
            assertTrue(sequenceID > 0);
        } catch (Exception ex) {
            fail();
        }

        Future<TimelineEntry> future2 = store.readAsync(timelineID, sequenceID, null);
        assertTrue(future2 != null);

        try {
            TimelineEntry entry2 = future2.get();
            assertEquals(sequenceID, entry2.getSequenceID());
            assertEquals(new String(message.serialize()), new String(entry2.getMessage().serialize()));
        } catch (Exception ex) {
            fail();
        }
    }

    @Test
    public void testWriteRead_Callback() {
        config.setTableName(testTablePrefix + "testWriteRead_Callback");
        IStore store = new DistributeTimelineStore(config);
        store.create();
        sleep(5);

        String timelineID = "00001";
        String content = String.valueOf(new Date().getTime());
        IMessage message = new StringMessage(content);
        final AtomicReference<TimelineEntry> entryRef = new AtomicReference<TimelineEntry>();

        Future<TimelineEntry> future = store.writeAsync(timelineID, message, new TimelineCallback<IMessage>() {
            @Override
            public void onCompleted(String timelineID, IMessage request, TimelineEntry timelineEntry) {
                entryRef.set(timelineEntry);
            }

            @Override
            public void onFailed(String timelineID, IMessage request, Exception ex) {
                fail();
            }
        });

        while (entryRef.get() == null) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                fail();
            }
        }

        TimelineEntry entry = entryRef.get();
        assertEquals(message, entry.getMessage());
        Long sequenceID = entry.getSequenceID();
        assertTrue(sequenceID> 0);

        final AtomicReference<TimelineEntry> entryRef2 = new AtomicReference<TimelineEntry>();
        Future<TimelineEntry> future2 = store.readAsync(timelineID, sequenceID, new TimelineCallback<Long>() {
            @Override
            public void onCompleted(String timelineID, Long request, TimelineEntry timelineEntry) {
                entryRef2.set(timelineEntry);
            }

            @Override
            public void onFailed(String timelineID, Long request, Exception ex) {
                fail();
            }
        });

        while (entryRef2.get() == null) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                fail();
            }
        }

        TimelineEntry entry2 = entryRef2.get();
        assertEquals(sequenceID, entry2.getSequenceID());
        assertEquals(new String(message.serialize()), new String(entry2.getMessage().serialize()));
    }

    @Test
    public void testScan_Exception() {
        config.setTableName(testTablePrefix + "testScan_Exception");
        IStore store = new DistributeTimelineStore(config);
        String timelineID = "00001";

        ScanParameter parameter = ScanParameterBuilder.scanForward().maxCount(100).from(0).to(100).build();

        try {
            store.scan(timelineID, parameter);
            fail();
        } catch (RuntimeException ex) {
            assertEquals("Store is not create, please create before scan", ex.getMessage());
        }
    }

    @Test
    public void testScanForward_NoData() {
        config.setTableName(testTablePrefix + "testScanForward_NoData");
        IStore store = new DistributeTimelineStore(config);
        store.create();
        sleep(5);

        String timelineID = "00001";
        ScanParameter parameter = ScanParameterBuilder.scanForward().maxCount(100).from(0).to(100).build();

        try {
            Iterator<TimelineEntry> iterator = store.scan(timelineID, parameter);
            assertTrue(!iterator.hasNext());
        } catch (Exception ex) {
            fail();
        }
    }

    @Test
    public void testScanBackward_NoData() {
        config.setTableName(testTablePrefix + "testScanForward_NoData");
        IStore store = new DistributeTimelineStore(config);
        store.create();
        sleep(5);

        String timelineID = "00001";
        ScanParameter parameter = ScanParameterBuilder.scanBackward().maxCount(100).from(110).to(100).build();

        try {
            Iterator<TimelineEntry> iterator = store.scan(timelineID, parameter);
            assertTrue(!iterator.hasNext());
        } catch (Exception ex) {
            fail();
        }
    }

    @Test
    public void testScanForward_HasData() {
        config.setTableName(testTablePrefix + "testScanForward_HasData");
        IStore store = new DistributeTimelineStore(config);
        store.create();
        sleep(5);

        String timelineID = "00001";
        String content1 = String.valueOf(new Date().getTime());
        IMessage message1 = new StringMessage(content1);
        TimelineEntry entry1 = store.write(timelineID, message1);
        Long sequence1 = entry1.getSequenceID();

        String content2 = String.valueOf(new Date().getTime() + 1);
        IMessage message2 = new StringMessage(content2);
        TimelineEntry entry2 = store.write(timelineID, message2);
        Long sequence2 = entry2.getSequenceID();

        ScanParameter parameter = ScanParameterBuilder.scanForward().maxCount(100).from(0).to(Long.MAX_VALUE).build();

        try {
            Iterator<TimelineEntry> iterator = store.scan(timelineID, parameter);

            assertTrue(iterator.hasNext());
            TimelineEntry entry = iterator.next();
            assertEquals(sequence1, entry.getSequenceID());
            assertEquals(new String(message1.serialize()), new String(entry.getMessage().serialize()));

            assertTrue(iterator.hasNext());
            entry = iterator.next();
            assertEquals(sequence2, entry.getSequenceID());
            assertEquals(new String(message2.serialize()), new String(entry.getMessage().serialize()));

            assertTrue(!iterator.hasNext());
        } catch (Exception ex) {
            fail();
        }
    }

    @Test
    public void testScanBackward_HasData() {
        config.setTableName(testTablePrefix + "testScanBackward_HasData");
        IStore store = new DistributeTimelineStore(config);
        store.create();
        sleep(5);

        String timelineID = "00001";
        String content1 = String.valueOf(new Date().getTime());
        IMessage message1 = new StringMessage(content1);
        TimelineEntry entry1 = store.write(timelineID, message1);
        Long sequence1 = entry1.getSequenceID();

        String content2 = String.valueOf(new Date().getTime() + 1);
        IMessage message2 = new StringMessage(content2);
        TimelineEntry entry2 = store.write(timelineID, message2);
        Long sequence2 = entry2.getSequenceID();

        ScanParameter parameter = ScanParameterBuilder.scanBackward().maxCount(100).from(Long.MAX_VALUE).to(0).build();

        try {
            Iterator<TimelineEntry> iterator = store.scan(timelineID, parameter);

            assertTrue(iterator.hasNext());
            TimelineEntry entry = iterator.next();
            assertEquals(sequence2, entry.getSequenceID());
            assertEquals(new String(message2.serialize()), new String(entry.getMessage().serialize()));

            assertTrue(iterator.hasNext());
            entry = iterator.next();
            assertEquals(sequence1, entry.getSequenceID());
            assertEquals(new String(message1.serialize()), new String(entry.getMessage().serialize()));

            assertTrue(!iterator.hasNext());
        } catch (Exception ex) {
            fail();
        }
    }

    @Test
    public void testBatchScan() {
        config.setTableName(testTablePrefix + "testBatchScan");
        IStore store = new DistributeTimelineStore(config);
        store.create();
        sleep(5);

        String timelineID = "00001";
        String content = String.valueOf(new Date().getTime());
        IMessage message = new StringMessage(content);
        store.batch(timelineID, message);

        ScanParameter parameter = ScanParameterBuilder.scanBackward().maxCount(100).from(Long.MAX_VALUE).to(0).build();
        Iterator<TimelineEntry> iterator = store.scan(timelineID, parameter);

        assertTrue(!iterator.hasNext());

        sleep(config.getWriterConfig().getFlushInterval());

        iterator = store.scan(timelineID, parameter);
        assertTrue(iterator.hasNext());
        TimelineEntry entry = iterator.next();
        assertTrue(entry.getSequenceID() > 0);
        assertEquals(message.getMessageID(), entry.getMessage().getMessageID());
        assertEquals(new String(message.serialize()), new String(entry.getMessage().serialize()));
    }

    private void sleep(int seconds) {
        try {
            Thread.sleep(seconds);
        } catch (InterruptedException e) {
            fail();
        }
    }

}
