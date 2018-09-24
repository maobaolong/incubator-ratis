/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ratis.server.storage;

import org.apache.log4j.Level;
import org.apache.ratis.BaseTest;
import org.apache.ratis.RaftTestUtil.SimpleOperation;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.protocol.ClientId;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.impl.RaftServerConstants;
import org.apache.ratis.server.impl.RetryCacheTestUtil;
import org.apache.ratis.server.impl.RetryCache;
import org.apache.ratis.server.impl.RaftServerImpl;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.util.FileUtils;
import org.apache.ratis.util.LogUtils;
import org.apache.ratis.util.ProtoUtils;
import org.apache.ratis.util.SizeInBytes;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TestSegmentedRaftLog extends BaseTest {
  static {
    LogUtils.setLogLevel(RaftLogWorker.LOG, Level.DEBUG);
  }

  private static final RaftPeerId peerId = RaftPeerId.valueOf("s0");
  private static final ClientId clientId = ClientId.randomId();
  private static final long callId = 0;

  static class SegmentRange {
    final long start;
    final long end;
    final long term;
    final boolean isOpen;

    SegmentRange(long s, long e, long term, boolean isOpen) {
      this.start = s;
      this.end = e;
      this.term = term;
      this.isOpen = isOpen;
    }
  }

  private File storageDir;
  private RaftProperties properties;
  private RaftStorage storage;
  private long segmentMaxSize;
  private long preallocatedSize;
  private int bufferSize;

  @Before
  public void setup() throws Exception {
    storageDir = getTestDir();
    properties = new RaftProperties();
    RaftServerConfigKeys.setStorageDir(properties, storageDir);
    storage = new RaftStorage(storageDir, RaftServerConstants.StartupOption.REGULAR);
    this.segmentMaxSize =
        RaftServerConfigKeys.Log.segmentSizeMax(properties).getSize();
    this.preallocatedSize =
        RaftServerConfigKeys.Log.preallocatedSize(properties).getSize();
    this.bufferSize =
        RaftServerConfigKeys.Log.writeBufferSize(properties).getSizeInt();
  }

  @After
  public void tearDown() throws Exception {
    if (storageDir != null) {
      FileUtils.deleteFully(storageDir.getParentFile());
    }
  }

  private LogEntryProto[] prepareLog(List<SegmentRange> list) throws IOException {
    List<LogEntryProto> entryList = new ArrayList<>();
    for (SegmentRange range : list) {
      File file = range.isOpen ?
          storage.getStorageDir().getOpenLogFile(range.start) :
          storage.getStorageDir().getClosedLogFile(range.start, range.end);

      final int size = (int) (range.end - range.start + 1);
      LogEntryProto[] entries = new LogEntryProto[size];
      try (LogOutputStream out = new LogOutputStream(file, false,
          segmentMaxSize, preallocatedSize, bufferSize)) {
        for (int i = 0; i < size; i++) {
          SimpleOperation m = new SimpleOperation("m" + (i + range.start));
          entries[i] = ProtoUtils.toLogEntryProto(m.getLogEntryContent(),
              range.term, i + range.start, clientId, callId);
          out.write(entries[i]);
        }
      }
      Collections.addAll(entryList, entries);
    }
    return entryList.toArray(new LogEntryProto[entryList.size()]);
  }

  static List<SegmentRange> prepareRanges(int startTerm, int endTerm, int segmentSize,
      long startIndex) {
    List<SegmentRange> list = new ArrayList<>(endTerm - startTerm);
    for (int i = startTerm; i < endTerm; i++) {
      list.add(new SegmentRange(startIndex, startIndex + segmentSize - 1, i,
          i == endTerm - 1));
      startIndex += segmentSize;
    }
    return list;
  }

  private LogEntryProto getLastEntry(SegmentedRaftLog raftLog)
      throws IOException {
    return raftLog.get(raftLog.getLastEntryTermIndex().getIndex());
  }

  @Test
  public void testLoadLogSegments() throws Exception {
    // first generate log files
    List<SegmentRange> ranges = prepareRanges(0, 5, 100, 0);
    LogEntryProto[] entries = prepareLog(ranges);

    // create RaftLog object and load log file
    try (SegmentedRaftLog raftLog =
             new SegmentedRaftLog(peerId, null, storage, -1, properties)) {
      raftLog.open(RaftServerConstants.INVALID_LOG_INDEX, null);
      // check if log entries are loaded correctly
      for (LogEntryProto e : entries) {
        LogEntryProto entry = raftLog.get(e.getIndex());
        Assert.assertEquals(e, entry);
      }

      TermIndex[] termIndices = raftLog.getEntries(0, 500);
      LogEntryProto[] entriesFromLog = Arrays.stream(termIndices)
          .map(ti -> {
            try {
              return raftLog.get(ti.getIndex());
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          })
          .toArray(LogEntryProto[]::new);
      Assert.assertArrayEquals(entries, entriesFromLog);
      Assert.assertEquals(entries[entries.length - 1], getLastEntry(raftLog));
    }
  }

  List<LogEntryProto> prepareLogEntries(List<SegmentRange> slist,
      Supplier<String> stringSupplier) {
    List<LogEntryProto> eList = new ArrayList<>();
    for (SegmentRange range : slist) {
      for (long index = range.start; index <= range.end; index++) {
        SimpleOperation m = stringSupplier == null ?
            new SimpleOperation("m" + index) :
            new SimpleOperation(stringSupplier.get());
        eList.add(ProtoUtils.toLogEntryProto(m.getLogEntryContent(),
            range.term, index, clientId, index));
      }
    }
    return eList;
  }

  /**
   * Append entry one by one and check if log state is correct.
   */
  @Test
  public void testAppendEntry() throws Exception {
    List<SegmentRange> ranges = prepareRanges(0, 5, 200, 0);
    List<LogEntryProto> entries = prepareLogEntries(ranges, null);

    try (SegmentedRaftLog raftLog =
             new SegmentedRaftLog(peerId, null, storage, -1, properties)) {
      raftLog.open(RaftServerConstants.INVALID_LOG_INDEX, null);
      // append entries to the raftlog
      entries.stream().map(raftLog::appendEntry).forEach(CompletableFuture::join);
    }

    try (SegmentedRaftLog raftLog =
             new SegmentedRaftLog(peerId, null, storage, -1, properties)) {
      raftLog.open(RaftServerConstants.INVALID_LOG_INDEX, null);
      // check if the raft log is correct
      checkEntries(raftLog, entries, 0, entries.size());
    }

    try (SegmentedRaftLog raftLog =
        new SegmentedRaftLog(peerId, null, storage, -1, properties)) {
      raftLog.open(RaftServerConstants.INVALID_LOG_INDEX, null);
      TermIndex lastTermIndex  = raftLog.getLastEntryTermIndex();
      IllegalStateException ex = null;
      try {
        // append entry fails if append entry term is lower than log's last entry term
        raftLog.appendEntry(LogEntryProto.newBuilder(entries.get(0))
            .setTerm(lastTermIndex.getTerm() - 1)
            .setIndex(lastTermIndex.getIndex() + 1).build());
      } catch (IllegalStateException e) {
        ex = e;
      }
      Assert.assertTrue(ex.getMessage().contains("term less than RaftLog's last term"));
      try {
        // append entry fails if difference between append entry index and log's last entry index is greater than 1
        raftLog.appendEntry(LogEntryProto.newBuilder(entries.get(0))
            .setTerm(lastTermIndex.getTerm())
            .setIndex(lastTermIndex.getIndex() + 2).build());
      } catch (IllegalStateException e) {
        ex = e;
      }
      Assert.assertTrue(ex.getMessage().contains("and RaftLog's last index " + lastTermIndex.getIndex() + " greater than 1"));
    }
  }

  /**
   * Keep appending entries, make sure the rolling is correct.
   */
  @Test
  public void testAppendAndRoll() throws Exception {
    RaftServerConfigKeys.Log.setPreallocatedSize(properties, SizeInBytes.valueOf("16KB"));
    RaftServerConfigKeys.Log.setSegmentSizeMax(properties, SizeInBytes.valueOf("128KB"));

    List<SegmentRange> ranges = prepareRanges(0, 1, 1024, 0);
    final byte[] content = new byte[1024];
    List<LogEntryProto> entries = prepareLogEntries(ranges,
        () -> new String(content));

    try (SegmentedRaftLog raftLog =
             new SegmentedRaftLog(peerId, null, storage, -1, properties)) {
      raftLog.open(RaftServerConstants.INVALID_LOG_INDEX, null);
      // append entries to the raftlog
      entries.stream().map(raftLog::appendEntry).forEach(CompletableFuture::join);
    }

    try (SegmentedRaftLog raftLog =
             new SegmentedRaftLog(peerId, null, storage, -1, properties)) {
      raftLog.open(RaftServerConstants.INVALID_LOG_INDEX, null);
      // check if the raft log is correct
      checkEntries(raftLog, entries, 0, entries.size());
      Assert.assertEquals(9, raftLog.getRaftLogCache().getNumOfSegments());
    }
  }

  @Test
  public void testTruncate() throws Exception {
    // prepare the log for truncation
    List<SegmentRange> ranges = prepareRanges(0, 5, 200, 0);
    List<LogEntryProto> entries = prepareLogEntries(ranges, null);

    try (SegmentedRaftLog raftLog =
             new SegmentedRaftLog(peerId, null, storage, -1, properties)) {
      raftLog.open(RaftServerConstants.INVALID_LOG_INDEX, null);
      // append entries to the raftlog
      entries.stream().map(raftLog::appendEntry).forEach(CompletableFuture::join);
    }

    for (long fromIndex = 900; fromIndex >= 0; fromIndex -= 150) {
      testTruncate(entries, fromIndex);
    }
  }

  private void testTruncate(List<LogEntryProto> entries, long fromIndex)
      throws Exception {
    try (SegmentedRaftLog raftLog =
             new SegmentedRaftLog(peerId, null, storage, -1, properties)) {
      raftLog.open(RaftServerConstants.INVALID_LOG_INDEX, null);
      // truncate the log
      raftLog.truncate(fromIndex).join();


      checkEntries(raftLog, entries, 0, (int) fromIndex);
    }

    try (SegmentedRaftLog raftLog =
             new SegmentedRaftLog(peerId, null, storage, -1, properties)) {
      raftLog.open(RaftServerConstants.INVALID_LOG_INDEX, null);
      // check if the raft log is correct
      if (fromIndex > 0) {
        Assert.assertEquals(entries.get((int) (fromIndex - 1)),
            getLastEntry(raftLog));
      } else {
        Assert.assertNull(raftLog.getLastEntryTermIndex());
      }
      checkEntries(raftLog, entries, 0, (int) fromIndex);
    }
  }

  private void checkEntries(RaftLog raftLog, List<LogEntryProto> expected,
      int offset, int size) throws IOException {
    if (size > 0) {
      for (int i = offset; i < size + offset; i++) {
        LogEntryProto entry = raftLog.get(expected.get(i).getIndex());
        Assert.assertEquals(expected.get(i), entry);
      }
      TermIndex[] termIndices = raftLog.getEntries(
          expected.get(offset).getIndex(),
          expected.get(offset + size - 1).getIndex() + 1);
      LogEntryProto[] entriesFromLog = Arrays.stream(termIndices)
          .map(ti -> {
            try {
              return raftLog.get(ti.getIndex());
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          })
          .toArray(LogEntryProto[]::new);
      LogEntryProto[] expectedArray = expected.subList(offset, offset + size)
          .stream().toArray(LogEntryProto[]::new);
      Assert.assertArrayEquals(expectedArray, entriesFromLog);
    }
  }

  private void checkFailedEntries(List<LogEntryProto> entries, long fromIndex, RetryCache retryCache) {
    for (int i = 0; i < entries.size(); i++) {
      if (i < fromIndex) {
        RetryCacheTestUtil.assertFailure(retryCache, entries.get(i), false);
      } else {
        RetryCacheTestUtil.assertFailure(retryCache, entries.get(i), true);
      }
    }
  }

  /**
   * Test append with inconsistent entries
   */
  @Test
  public void testAppendEntriesWithInconsistency() throws Exception {
    // prepare the log for truncation
    List<SegmentRange> ranges = prepareRanges(0, 5, 200, 0);
    List<LogEntryProto> entries = prepareLogEntries(ranges, null);

    RaftServerImpl server = mock(RaftServerImpl.class);
    RetryCache retryCache = RetryCacheTestUtil.createRetryCache();
    when(server.getRetryCache()).thenReturn(retryCache);
    doCallRealMethod().when(server).failClientRequest(any(LogEntryProto.class));
    try (SegmentedRaftLog raftLog =
             new SegmentedRaftLog(peerId, server, storage, -1, properties)) {
      raftLog.open(RaftServerConstants.INVALID_LOG_INDEX, null);
      entries.stream().forEach(entry -> RetryCacheTestUtil.createEntry(retryCache, entry));
      // append entries to the raftlog
      entries.stream().map(raftLog::appendEntry).forEach(CompletableFuture::join);
    }

    // append entries whose first 100 entries are the same with existing log,
    // and the next 100 are with different term
    SegmentRange r1 = new SegmentRange(550, 599, 2, false);
    SegmentRange r2 = new SegmentRange(600, 649, 3, false);
    SegmentRange r3 = new SegmentRange(650, 749, 10, false);
    List<LogEntryProto> newEntries = prepareLogEntries(
        Arrays.asList(r1, r2, r3), null);

    try (SegmentedRaftLog raftLog =
             new SegmentedRaftLog(peerId, server, storage, -1, properties)) {
      raftLog.open(RaftServerConstants.INVALID_LOG_INDEX, null);
      raftLog.append(newEntries.toArray(new LogEntryProto[newEntries.size()])).forEach(CompletableFuture::join);

      checkFailedEntries(entries, 650, retryCache);
      checkEntries(raftLog, entries, 0, 650);
      checkEntries(raftLog, newEntries, 100, 100);
      Assert.assertEquals(newEntries.get(newEntries.size() - 1),
          getLastEntry(raftLog));
      Assert.assertEquals(newEntries.get(newEntries.size() - 1).getIndex(),
          raftLog.getLatestFlushedIndex());
    }

    // load the raftlog again and check
    try (SegmentedRaftLog raftLog =
             new SegmentedRaftLog(peerId, server, storage, -1, properties)) {
      raftLog.open(RaftServerConstants.INVALID_LOG_INDEX, null);
      checkEntries(raftLog, entries, 0, 650);
      checkEntries(raftLog, newEntries, 100, 100);
      Assert.assertEquals(newEntries.get(newEntries.size() - 1),
          getLastEntry(raftLog));
      Assert.assertEquals(newEntries.get(newEntries.size() - 1).getIndex(),
          raftLog.getLatestFlushedIndex());

      RaftLogCache cache = raftLog.getRaftLogCache();
      Assert.assertEquals(5, cache.getNumOfSegments());
    }
  }
}
