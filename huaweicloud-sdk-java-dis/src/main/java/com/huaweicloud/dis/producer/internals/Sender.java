/*
 * Copyright 2002-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huaweicloud.dis.producer.internals;

import com.huaweicloud.dis.DISAsync;
import com.huaweicloud.dis.core.handler.AsyncHandler;
import com.huaweicloud.dis.core.util.StringUtils;
import com.huaweicloud.dis.exception.DISClientException;
import com.huaweicloud.dis.iface.data.request.PutRecordsRequest;
import com.huaweicloud.dis.iface.data.response.PutRecordsResult;
import com.huaweicloud.dis.iface.data.response.PutRecordsResultEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;


/**
 * The background thread that handles the sending of produce requests to the dis server. 
 * TODO makes metadata requests to renew its view of the cluster
 * sends produce requests to the dis server.
 * 
 * TODO 增加配置，为了保序，使同一streamPartition的数据，同步发送
 */
public class Sender extends Thread
{
    
    private static final Logger log = LoggerFactory.getLogger(Sender.class);
    
    /* the state of each nodes connection */
    private final DISAsync client;
    
    /* the record accumulator that batches records */
    private final RecordAccumulator accumulator;
    
    
    /* true while the sender thread is still running */
    private volatile boolean running;
    
    /* true when the caller wants to ignore all unsent/inflight messages and force close. */
    private volatile boolean forceClose;
    
    private long retryBackoffMs;

    private static final int DEFAULT_SENDER_POLLING_MS = 50;
    
    private AtomicInteger flushId = new AtomicInteger();
    
    public static AtomicLong totalSendCount = new AtomicLong();
    public static AtomicLong totalSendSuccessCount = new AtomicLong();
    public static AtomicLong totalSendFailedCount = new AtomicLong();

    public static AtomicLong totalSendTimes = new AtomicLong();
    public static AtomicLong totalSendSuccessTimes = new AtomicLong();
    public static AtomicLong totalSendFailedTimes = new AtomicLong();

    public static AtomicLong totalQueryTimes = new AtomicLong();
    
    public Sender(DISAsync client, RecordAccumulator accumulator, long retryBackoffMs)
    {
        this.setName("Sender Thread");
        this.client = client;
        this.accumulator = accumulator;
        this.retryBackoffMs = retryBackoffMs;
        this.running = true;
    }
    
    /**
     * The main run loop for the sender thread
     */
    public void run()
    {
        log.debug("Starting DIS producer I/O thread.");
        
        // main loop, runs until close is called
        while (running)
        {
            try
            {
                totalQueryTimes.incrementAndGet();
                run(System.currentTimeMillis());
            }
            catch (Exception e)
            {
                log.error("Uncaught error in DIS producer I/O thread: ", e);
            }
        }
        
        log.debug("Beginning shutdown of DIS producer I/O thread, sending remaining records.");
        
        // okay we stopped accepting requests but there may still be
        // requests in the accumulator or waiting for acknowledgment,
        // wait until these are completed.
        // while (!forceClose && (this.accumulator.hasUndrained() || this.client.inFlightRequestCount() > 0)) {
        // try {
        // run(time.milliseconds());
        // } catch (Exception e) {
        // log.error("Uncaught error in kafka producer I/O thread: ", e);
        // }
        // }
        // if (forceClose) {
        // // We need to fail all the incomplete batches and wake up the threads waiting on
        // // the futures.
        // this.accumulator.abortIncompleteBatches();
        // }
        // try {
        // this.client.close();
        // } catch (Exception e) {
        // log.error("Failed to close network client", e);
        // }
        //
        // log.debug("Shutdown of Kafka producer I/O thread has completed.");
    }
    
    /**
     * Run a single iteration of sending
     *
     * @param now The current POSIX time in milliseconds
     */
    void run(long now)
    {
        boolean hasData = sendProducerData(now);
        
        if (!hasData)
        {
            if (retryBackoffMs > 0)
            {
                long remainWait = retryBackoffMs - (System.currentTimeMillis() - now);
                if (remainWait > 0)
                {
                    LockSupport.parkNanos(remainWait * 1000000L);
                }
            }
            else
            {
                LockSupport.parkNanos(DEFAULT_SENDER_POLLING_MS * 1000000L);
            }
        }
    }
    
    private boolean sendProducerData(long now)
    {

        // create produce requests
        List<ProducerBatch> batches = this.accumulator.drain(now);

        if (batches.isEmpty())
        {
            log.trace("no data to send.");
            return false;
        }
        for (ProducerBatch batch : batches)
        {
            log.trace("begin to process batch {}, count {}, size {}B", batch.getTp(), batch.getRelativeOffset(), batch.getTotolByteSize());

            StreamPartition tp = batch.getTp();

            PutRecordsRequest putRecordsParam = new PutRecordsRequest();
            putRecordsParam.setStreamId(tp.topic());
            putRecordsParam.setRecords(batch.getBatchPutRecordsRequestEntrys());

            totalSendTimes.incrementAndGet();
            totalSendCount.addAndGet(batch.getRelativeOffset());
            Future<PutRecordsResult> resultFuture = client.putRecordsAsync(putRecordsParam, new AsyncHandler<PutRecordsResult>()
            {
                long start = System.currentTimeMillis();

                @Override
                public void onSuccess(PutRecordsResult result)
                {
                    totalSendSuccessTimes.incrementAndGet();
                    totalSendSuccessCount.addAndGet(result.getRecords().size() - result.getFailedRecordCount().get());
                    totalSendFailedCount.addAndGet(result.getFailedRecordCount().get());
                    if (result.getFailedRecordCount().get() > 0)
                    {
                        String errorMsg = null;
                        for (int i = 0; i < result.getRecords().size(); i++)
                        {
                            PutRecordsResultEntry putRecordsRequestEntry = result.getRecords().get(i);
                            if (!StringUtils.isNullOrEmpty(putRecordsRequestEntry.getErrorCode()))
                            {
                                errorMsg = putRecordsRequestEntry.getErrorCode() + " : " + putRecordsRequestEntry.getErrorMessage();
                                break;
                            }
                        }
                        log.error("Batch {} send partial successfully, cost {}ms, count {}, size {}B, failed count {}, failed info {}",
                                tp.toString(),
                                (System.currentTimeMillis() - start),
                                batch.getRelativeOffset(),
                                batch.getTotolByteSize(),
                                result.getFailedRecordCount().get(),
                                errorMsg);
                    }
                    else
                    {
                        log.debug("Batch {} send successfully, cost {}ms, count {}, size {}B",
                                tp.toString(),
                                (System.currentTimeMillis() - start),
                                batch.getRelativeOffset(),
                                batch.getTotolByteSize());
                    }
                    batch.done(result, null);
                    batchIsDone(batch);
                }

                @Override
                public void onError(Exception exception)
                {
                    totalSendFailedTimes.incrementAndGet();
                    totalSendFailedCount.addAndGet(putRecordsParam.getRecords().size());
                    log.error("Batch {} send failed, cost {}ms, count {}, size {}B, error info {}",
                            tp.toString(),
                            (System.currentTimeMillis() - start),
                            batch.getRelativeOffset(),
                            batch.getTotolByteSize(),
                            exception.getMessage(), exception);
                    if (exception instanceof DISClientException)
                    {
                        batch.done(null, (DISClientException) exception);
                    }
                    else
                    {
                        batch.done(null, new DISClientException(exception));
                    }
                    batchIsDone(batch);
                }
            });

            //accumulator.setOnSendingPartitionFuture(tp, resultFuture);
        }

        return true;
    }
    
    /**
     * Wake up the selector associated with this send thread
     */
    public void wakeup()
    {
        LockSupport.unpark(this);
//        this.wait(timeout);
//        this.notify();
    }

    public void close()
    {
        this.running = false;
    }

    //flush需要堵塞，把当前缓冲的数据发送到DIS服务端,多个线程分别调用flush的情况 TODO
    public void flush()
    {
        this.wakeup();
        // TODO Auto-generated method stub
        
    }
    
    public void batchIsDone(ProducerBatch batch)
    {
        accumulator.batchIsDone(batch);
    }
}
