package com.alicloud.openservices.tablestore.timeline;

import com.alicloud.openservices.tablestore.*;
import com.alicloud.openservices.tablestore.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.*;

/**
 * 基于表格存储（Table Store）的分布式存储层实现.
 * DistributeTimelineStore可用于存储系统和同步系统。
 */
public class DistributeTimelineStore implements IStore {
    private Logger logger = LoggerFactory.getLogger(DistributeTimelineStore.class);

    private DistributeTimelineConfig config = null;
    private AsyncClient tableStore = null;
    private TableStoreWriter tableStoreWriter = null;

    /**
     * TableStoreStore的构造函数。
     * @param config    TableStore的配置参数。
     */
    public DistributeTimelineStore(DistributeTimelineConfig config) {
        this.config = config;

        tableStore = new AsyncClient(config.getEndpoint(), config.getAccessKeyID(), config.getAccessKeySecret(),
                config.getInstanceName(), config.getClientConfiguration());
    }

    @Override
    public TimelineEntry write(String timelineID, IMessage message) {
        try {
            Future<TimelineEntry> res = writeAsync(timelineID, message, null);
            return Utils.waitForFuture(res);
        } catch (TableStoreException ex) {
            throw handleTableStoreException(ex, timelineID, "write");
        } catch (ClientException ex) {
            throw new TimelineException(TimelineExceptionType.TET_INVALID_USE,
                    "Parameter is invalid, reason:" + ex.getMessage(), ex);
        }
    }

    @Override
    public void batch(String timelineID, IMessage message) {
        if (tableStoreWriter == null) {
            ExecutorService executor = Executors.newFixedThreadPool(config.getClientConfiguration().getIoThreadCount());
            tableStoreWriter = new DefaultTableStoreWriter(tableStore, config.getTableName(),
                    config.getWriterConfig(), null, executor);
        }

        tableStoreWriter.addRowChange(createPutRowRequest(timelineID, message).getRowChange());
    }

    @Override
    public Future<TimelineEntry> writeAsync(final String timelineID,
                                            final IMessage message,
                                            final TimelineCallback<IMessage> callback) {
        try {
            PutRowRequest request = createPutRowRequest(timelineID, message);
            return doWriteAsync(timelineID, message, callback, request);
        } catch (TableStoreException ex) {
            throw handleTableStoreException(ex, timelineID, "write");
        } catch (ClientException ex) {
            throw new TimelineException(TimelineExceptionType.TET_INVALID_USE,
                    "Parameter is invalid, reason:" + ex.getMessage(), ex);
        }
    }

    @Override
    public TimelineEntry read(String timelineID, Long sequenceID) {
        try {
            Future<TimelineEntry> res = readAsync(timelineID, sequenceID, null);
            return Utils.waitForFuture(res);
        } catch (TableStoreException ex) {
            throw handleTableStoreException(ex, timelineID, "read");
        } catch (ClientException ex) {
            throw new TimelineException(TimelineExceptionType.TET_INVALID_USE,
                    "Parameter is invalid, reason:" + ex.getMessage(), ex);
        }
    }

    @Override
    public Future<TimelineEntry> readAsync(final String timelineID,
                                           final Long sequenceID,
                                           final TimelineCallback<Long> callback) {
        try {
            GetRowRequest request = createGetRowRequest(timelineID, sequenceID);
            return doReadAsync(timelineID, callback, request);
        } catch (TableStoreException ex) {
            throw handleTableStoreException(ex, timelineID, "read");
        } catch (ClientException ex) {
            throw new TimelineException(TimelineExceptionType.TET_INVALID_USE,
                    "Parameter is invalid, reason:" + ex.getMessage(), ex);
        }
    }

    @Override
    public Iterator<TimelineEntry> scan(String timelineID, ScanParameter parameter) {
        try {
            RangeIteratorParameter iteratorParameter = createIteratorParameter(timelineID, parameter);
            return new DistributeTimelineIterator(tableStore, iteratorParameter, this.config);
        } catch (TableStoreException ex) {
            throw handleTableStoreException(ex, timelineID, "scan");
        } catch (ClientException ex) {
            throw new TimelineException(TimelineExceptionType.TET_INVALID_USE,
                    "Parameter is invalid, reason:" + ex.getMessage(), ex);
        }
    }

    @Override
    public void create() {
        TableMeta tableMeta = new TableMeta(config.getTableName());
        tableMeta.addPrimaryKeyColumn(config.getFirstPKName(), PrimaryKeyType.STRING);
        tableMeta.addPrimaryKeyColumn(config.getSecondPKName(), PrimaryKeyType.INTEGER, PrimaryKeyOption.AUTO_INCREMENT);

        TableOptions tableOptions = new TableOptions();
        tableOptions.setTimeToLive(this.config.getTtl());
        tableOptions.setMaxVersions(1);
        CreateTableRequest request = new CreateTableRequest(tableMeta, tableOptions);
        try {
            Future<CreateTableResponse> res = tableStore.createTable(request, null);
            Utils.waitForFuture(res);
            logger.info("Create store {} succeeded.", config.getTableName());
        } catch (TableStoreException ex) {
            if (ex.getErrorCode().equals("OTSObjectAlreadyExist")) {
                logger.warn("Store has be created.");
            } else if (ex.getHttpStatus() >= 400 && ex.getHttpStatus() < 500) {
                throw new TimelineException(TimelineExceptionType.TET_INVALID_USE,
                        "Parameter is invalid, reason:" + ex.getMessage(), ex);
            } else if (ex.getHttpStatus() >= 500 && ex.getHttpStatus() < 600) {
                throw new TimelineException(TimelineExceptionType.TET_RETRY,
                        String.format("Create store failed, reason:%s.", ex.toString()), ex);
            } else {
                throw new TimelineException(TimelineExceptionType.TET_UNKNOWN,
                        String.format("Create store failed, reason:%s.", ex.toString()), ex);
            }
        } catch (ClientException ex) {
            throw new TimelineException(TimelineExceptionType.TET_INVALID_USE,
                    "Create store failed, reason:" + ex.getMessage(), ex);
        }
    }

    @Override
    public void drop() {
        DeleteTableRequest request = new DeleteTableRequest(config.getTableName());
        try {
            Future<DeleteTableResponse> response = tableStore.deleteTable(request, null);
            Utils.waitForFuture(response);
            logger.info("Drop store {} succeeded.", config.getTableName());
        } catch (TableStoreException ex) {
            if (ex.getErrorCode().equals("OTSObjectNotExist")) {
                logger.warn("Store has be drop.");
            } else if (ex.getHttpStatus() >= 400 && ex.getHttpStatus() < 500) {
                throw new TimelineException(TimelineExceptionType.TET_INVALID_USE,
                        "Parameter is invalid, reason:" + ex.getMessage(), ex);
            } else if (ex.getHttpStatus() >= 500 && ex.getHttpStatus() < 600) {
                throw new TimelineException(TimelineExceptionType.TET_RETRY,
                        String.format("Drop store failed, reason:%s.", ex.toString()), ex);
            } else {
                throw new TimelineException(TimelineExceptionType.TET_UNKNOWN,
                        String.format("Drop store failed, reason:%s.", ex.toString()), ex);
            }
        } catch (ClientException ex) {
            throw new TimelineException(TimelineExceptionType.TET_INVALID_USE,
                    "Drop store failed, reason:" + ex.getMessage(), ex);
        }
    }

    @Override
    public boolean exist() {
        DescribeTableRequest request = new DescribeTableRequest(config.getTableName());
        try {
            Future<DescribeTableResponse> response = tableStore.describeTable(request, null);
            Utils.waitForFuture(response);
            return true;
        } catch (TableStoreException ex) {
            if (ex.getErrorCode().equals("OTSObjectNotExist")) {
                return false;
            } else if (ex.getHttpStatus() >= 400 && ex.getHttpStatus() < 500) {
                throw new TimelineException(TimelineExceptionType.TET_INVALID_USE,
                        "Parameter is invalid, reason:" + ex.getMessage(), ex);
            } else if (ex.getHttpStatus() >= 500 && ex.getHttpStatus() < 600) {
                throw new TimelineException(TimelineExceptionType.TET_RETRY,
                        String.format("Exist store failed, reason:%s.", ex.toString()), ex);
            } else {
                throw new TimelineException(TimelineExceptionType.TET_UNKNOWN,
                        String.format("Exist store failed, reason:%s.", ex.toString()), ex);
            }
        } catch (ClientException ex) {
            throw new TimelineException(TimelineExceptionType.TET_INVALID_USE,
                    "Exist store failed, reason:" + ex.getMessage(), ex);
        }
    }

    @Override
    public void close() {
        if (tableStoreWriter != null) {
            tableStoreWriter.close();
        }
        tableStore.shutdown();
    }

    private RangeIteratorParameter createIteratorParameter(String timelineID, ScanParameter parameter) {
        RangeIteratorParameter iteratorParameter = new RangeIteratorParameter(config.getTableName());
        iteratorParameter.setDirection(parameter.isForward() ? Direction.FORWARD : Direction.BACKWARD);
        PrimaryKeyColumn beginFirstPK = new PrimaryKeyColumn(config.getFirstPKName(), PrimaryKeyValue.fromString(timelineID));
        PrimaryKeyColumn beginSecondPK = new PrimaryKeyColumn(config.getSecondPKName(), PrimaryKeyValue.fromLong(parameter.getFrom()));
        PrimaryKey beginPK = PrimaryKeyBuilder.createPrimaryKeyBuilder().
                addPrimaryKeyColumn(beginFirstPK).addPrimaryKeyColumn(beginSecondPK).build();
        iteratorParameter.setInclusiveStartPrimaryKey(beginPK);

        PrimaryKeyColumn endFirstPK = new PrimaryKeyColumn(config.getFirstPKName(), PrimaryKeyValue.fromString(timelineID));
        PrimaryKeyColumn endSecondPK = new PrimaryKeyColumn(config.getSecondPKName(), PrimaryKeyValue.fromLong(parameter.getTo()));
        PrimaryKey endPK = PrimaryKeyBuilder.createPrimaryKeyBuilder().
                addPrimaryKeyColumn(endFirstPK).addPrimaryKeyColumn(endSecondPK).build();
        iteratorParameter.setExclusiveEndPrimaryKey(endPK);

        iteratorParameter.setMaxCount(parameter.getMaxCount());
        iteratorParameter.setMaxVersions(1);
        iteratorParameter.setBufferSize(parameter.getMaxCount());
        return iteratorParameter;
    }

    private PutRowRequest createPutRowRequest(String timelineID, IMessage message) {
        PutRowRequest request = new PutRowRequest();
        RowPutChange putChange = new RowPutChange(config.getTableName());

        PrimaryKeyColumn firstPK = new PrimaryKeyColumn(config.getFirstPKName(), PrimaryKeyValue.fromString(timelineID));
        PrimaryKeyColumn secondPK = new PrimaryKeyColumn(config.getSecondPKName(), PrimaryKeyValue.AUTO_INCREMENT);
        putChange.setPrimaryKey(PrimaryKeyBuilder.createPrimaryKeyBuilder().
                addPrimaryKeyColumn(firstPK).addPrimaryKeyColumn(secondPK).build());
        putChange.setReturnType(ReturnType.RT_PK);

        byte[] content = message.serialize();
        if (content.length > 1000 * 1024 * 1024) {
            throw new TimelineException(TimelineExceptionType.TET_INVALID_USE,
                    String.format("Message Content must less than 1GB, current:%s", String.valueOf(content.length)));
        }

        int pos = 0;
        int index = Utils.CONTENT_COLUMN_START_ID;
        while (pos < content.length) {
            byte[] columnValue;
            if (pos + config.getColumnMaxLength() < content.length) {
                columnValue = Arrays.copyOfRange(content, pos, pos + config.getColumnMaxLength());
            } else {
                columnValue = Arrays.copyOfRange(content, pos, content.length);
            }
            putChange.addColumn(String.valueOf(config.getMessageContentPrefix() + String.valueOf(index++)),
                    ColumnValue.fromBinary(columnValue));
            pos += columnValue.length;
        }

        if (config.getColumnNameOfMessageCrc32() != null && !config.getColumnNameOfMessageCrc32().isEmpty()) {
            long crc32 = Utils.crc32(content);
            putChange.addColumn(config.getColumnNameOfMessageCrc32(), ColumnValue.fromLong(crc32));
        }

        putChange.addColumn(config.getMessageIDColumnName(), ColumnValue.fromString(message.getMessageID()));

        request.setRowChange(putChange);
        return request;
    }

    private GetRowRequest createGetRowRequest(String timelineID, Long sequenceID) {
        GetRowRequest request = new GetRowRequest();
        PrimaryKeyColumn firstPK = new PrimaryKeyColumn(config.getFirstPKName(), PrimaryKeyValue.fromString(timelineID));
        PrimaryKeyColumn secondPK = new PrimaryKeyColumn(config.getSecondPKName(), PrimaryKeyValue.fromLong(sequenceID));
        PrimaryKey pk = PrimaryKeyBuilder.createPrimaryKeyBuilder().
                addPrimaryKeyColumn(firstPK).addPrimaryKeyColumn(secondPK).build();
        SingleRowQueryCriteria criteria = new SingleRowQueryCriteria(config.getTableName(), pk);
        criteria.setMaxVersions(1);
        request.setRowQueryCriteria(criteria);
        return request;
    }

    private Future<TimelineEntry> doReadAsync(final String timelineID, final TimelineCallback<Long> callback, GetRowRequest request) {
        final TableStoreCallback<GetRowRequest, GetRowResponse> tablestoreCallback = new TableStoreCallback<GetRowRequest, GetRowResponse>() {
            @Override
            public void onCompleted(GetRowRequest request, GetRowResponse response) {
                Row row = response.getRow();
                TimelineEntry timelineEntry = Utils.toTimelineEntry(row, config);
                long sequenceID = response.getRow().getPrimaryKey().getPrimaryKeyColumn(config.getSecondPKName()).getValue().asLong();
                callback.onCompleted(timelineID, sequenceID, timelineEntry);
            }

            @Override
            public void onFailed(GetRowRequest getRowRequest, Exception e) {
                e = createException(e, timelineID, "read");

                long sequenceID = getRowRequest.getRowQueryCriteria().getPrimaryKey().
                        getPrimaryKeyColumn(config.getSecondPKName()).getValue().asLong();
                callback.onFailed(timelineID, sequenceID, e);
            }
        };

        final Future<GetRowResponse> future = tableStore.getRow(request, tablestoreCallback);
        return new Future<TimelineEntry>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return future.cancel(mayInterruptIfRunning);
            }

            @Override
            public boolean isCancelled() {
                return future.isCancelled();
            }

            @Override
            public boolean isDone() {
                return future.isDone();
            }

            @Override
            public TimelineEntry get() throws InterruptedException, ExecutionException {
                try {
                    GetRowResponse response = future.get();
                    return Utils.toTimelineEntry(response.getRow(), config);
                } catch (TableStoreException ex) {
                    throw handleTableStoreException(ex, timelineID, "read");
                } catch (ClientException ex) {
                    throw new TimelineException(TimelineExceptionType.TET_INVALID_USE,
                            "Drop store failed, reason:" + ex.getMessage(), ex);
                }
            }

            @Override
            public TimelineEntry get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
                try {
                    GetRowResponse response = future.get(timeout, unit);
                    return Utils.toTimelineEntry(response.getRow(), config);
                } catch (TableStoreException ex) {
                    throw handleTableStoreException(ex, timelineID, "read");
                } catch (ClientException ex) {
                    throw new TimelineException(TimelineExceptionType.TET_INVALID_USE,
                            "Drop store failed, reason:" + ex.getMessage(), ex);
                }
            }
        };
    }

    private Future<TimelineEntry> doWriteAsync(final String timelineID, final IMessage message, final TimelineCallback<IMessage> callback, PutRowRequest request) {
        final TableStoreCallback<PutRowRequest, PutRowResponse> tablestoreCallback = new TableStoreCallback<PutRowRequest, PutRowResponse>() {
            @Override
            public void onCompleted(PutRowRequest request, PutRowResponse response) {
                long sequenceID = response.getRow().getPrimaryKey().getPrimaryKeyColumn(config.getSecondPKName()).getValue().asLong();
                TimelineEntry timelineEntry = new TimelineEntry(sequenceID, message);
                callback.onCompleted(timelineID, message, timelineEntry);
            }

            @Override
            public void onFailed(PutRowRequest putRowRequest, Exception e) {
                e = createException(e, timelineID, "write");

                callback.onFailed(timelineID, message, e);
            }
        };

        final Future<PutRowResponse> future = tableStore.putRow(request, tablestoreCallback);
        return new Future<TimelineEntry>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return future.cancel(mayInterruptIfRunning);
            }

            @Override
            public boolean isCancelled() {
                return future.isCancelled();
            }

            @Override
            public boolean isDone() {
                return future.isDone();
            }

            @Override
            public TimelineEntry get() throws InterruptedException, ExecutionException {
                try {
                    PutRowResponse response = future.get();
                    return Utils.toTimelineEntry(response, message);
                } catch (TableStoreException ex) {
                    throw handleTableStoreException(ex, timelineID, "write");
                } catch (ClientException ex) {
                    throw new TimelineException(TimelineExceptionType.TET_INVALID_USE,
                            "Drop store failed, reason:" + ex.getMessage(), ex);
                }
            }

            @Override
            public TimelineEntry get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
                try {
                    PutRowResponse response = future.get(timeout, unit);
                    return Utils.toTimelineEntry(response, message);
                } catch (TableStoreException ex) {
                    throw handleTableStoreException(ex, timelineID, "write");
                } catch (ClientException ex) {
                    throw new TimelineException(TimelineExceptionType.TET_INVALID_USE,
                            "Drop store failed, reason:" + ex.getMessage(), ex);
                }
            }
        };
    }

    private TimelineException handleTableStoreException(TableStoreException ex, String timelineID, String type) {
        if (ex.getErrorCode().equals("OTSObjectNotExist")) {
            return new TimelineException(TimelineExceptionType.TET_INVALID_USE,
                    "Store is not create, please create before " + type, ex);
        } else if (ex.getHttpStatus() >= 400 && ex.getHttpStatus() < 500) {
            return new TimelineException(TimelineExceptionType.TET_INVALID_USE,
                    "Parameter is invalid, reason:" + ex.getMessage(), ex);
        } else if (ex.getHttpStatus() >= 500 && ex.getHttpStatus() < 600) {
            return new TimelineException(TimelineExceptionType.TET_RETRY,
                    String.format("%s timeline %s failed, reason:%s.", type, timelineID, ex.toString()), ex);
        } else {
            return new TimelineException(TimelineExceptionType.TET_UNKNOWN,
                    String.format("%s timeline %s failed, reason:%s.", type, timelineID, ex.toString()), ex);
        }
    }

    private Exception createException(Exception e, String timelineID, String type) {
        if (e instanceof TableStoreException) {
            TableStoreException ex = (TableStoreException)e;
            if (ex.getErrorCode().equals("OTSObjectNotExist")) {
                e = new TimelineException(TimelineExceptionType.TET_INVALID_USE,
                        "Store is not create, please create before " + type);
            } else if (ex.getHttpStatus() >= 400 && ex.getHttpStatus() < 500) {
                e = new TimelineException(TimelineExceptionType.TET_INVALID_USE,
                        "Parameter is invalid, reason:" + ex.getMessage(), ex);
            } else if (ex.getHttpStatus() >= 500 && ex.getHttpStatus() < 600) {
                e = new TimelineException(TimelineExceptionType.TET_RETRY,
                        "Store occur some error,can retry, reason:" + ex.getMessage(), ex);
            } else {
                e = new TimelineException(TimelineExceptionType.TET_UNKNOWN,
                        String.format("%s timeline %s failed, reason:%s.",type, timelineID, ex.toString()), ex);
            }
        } else if (e instanceof ClientException) {
            e = new TimelineException(TimelineExceptionType.TET_INVALID_USE,
                    "Parameter is invalid, reason:" + e.getMessage(), e);
        }
        return e;
    }
}
