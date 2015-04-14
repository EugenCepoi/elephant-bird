package com.twitter.elephantbird.mapreduce.io;

import com.google.protobuf.ByteString;

import com.twitter.elephantbird.util.Protobufs;
import com.twitter.elephantbird.util.StreamSearcher;
import com.twitter.elephantbird.util.TypeRef;

import java.io.InputStream;
import java.io.IOException;
import java.util.List;

import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.IOUtils;
import org.apache.thrift.TBase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A class to read blocks of binary objects like protobufs.
 */
public class GenericBlockReader {
  private static final Logger LOG = LoggerFactory.getLogger(GenericBlockReader.class);

  // though any type of objects can be stored, each block itself is
  // stored as a protocolbuf (SerializedBlock).

  private InputStream in_;
  private final StreamSearcher searcher_;
  private List<ByteString> curBlobs_;
  private int numLeftToReadThisBlock_ = 0;
  private boolean readNewBlocks_ = true;

  private byte[] lastBlock = null;


  public GenericBlockReader(InputStream in) {
    lastBlock = new byte[1];
    in_ = in;
    searcher_ = new StreamSearcher(Protobufs.KNOWN_GOOD_POSITION_MARKER);
  }

  public void close() throws IOException {
    if (in_ != null)
      in_.close();
  }

  /**
   * Sets input stream. Sometimes the actual input stream might be created
   * away from the constructor.
   */
  public void setInputStream(InputStream in) {
    in_ = in; // not closing existing in_, normally it is null
  }

  public ByteString readNextProtoByteString() throws IOException {
    while (true) {
      if (!setupNewBlockIfNeeded()) {
        return null;
      }

      int blobIndex = curBlobs_.size() - numLeftToReadThisBlock_;
      numLeftToReadThisBlock_--;
      ByteString blob = curBlobs_.get(blobIndex);
      if (blob.isEmpty()) {
        continue;
      }
      return blob;
    }
  }

  public void markNoMoreNewBlocks() {
    readNewBlocks_ = false;
  }

  public long skipToNextSyncPoint() throws IOException {
    return searcher_.search(in_);
  }

  /**
   * Finds next block marker and reads the block. If skipIfStartingOnBoundary is set
   * skips the the first block if the marker starts exactly at the current position.
   * (i.e. there were no bytes from previous block before the start of the marker).
   */
  public List<ByteString> parseNextBlock(boolean skipIfStartingOnBoundary) throws IOException {
    LOG.debug("BlockReader: none left to read, skipping to sync point");
    long skipped = skipToNextSyncPoint();
    if (skipped <= -1) {
      LOG.debug("BlockReader: SYNC point eof");
      // EOF if there are no more sync markers.
      return null;
    }

    int blockSize = readInt();
    if(LOG.isDebugEnabled()) {
      LOG.debug("BlockReader: found sync point, next block has size " + blockSize);
    }
    if (blockSize < 0) {
      LOG.debug("ProtobufReader: reading size after sync point eof");
      // EOF if the size cannot be read.
      return null;
    }

    byte[] byteArray;
    // Cache the allocation
    if(lastBlock.length >= blockSize) {
      byteArray = lastBlock;
    } else {
      lastBlock = new byte[blockSize*2];
      byteArray = lastBlock;
    }

    IOUtils.readFully(in_, byteArray, 0, blockSize);

    if (skipIfStartingOnBoundary && skipped == Protobufs.KNOWN_GOOD_POSITION_MARKER.length) {
      // skip the current current block
      return parseNextBlock(false);
    }

    SerializedBlock block = SerializedBlock.parseFrom(byteArray, blockSize);

    curBlobs_ = block.getProtoBlobs();
    numLeftToReadThisBlock_ = curBlobs_.size();
    LOG.debug("ProtobufReader: number in next block is " + numLeftToReadThisBlock_);
    return curBlobs_;
  }

  private boolean setupNewBlockIfNeeded() throws IOException {
    if (numLeftToReadThisBlock_ == 0) {
      if (!readNewBlocks_) {
        // If the reader has been told not to read more blocks, stop.
        // This happens when a map boundary has been crossed in a map job, for example.
        // The goal then is to finsh reading what has been parsed, but let the next split
        // handle everything starting at the next sync point.
        return false;
      }
      curBlobs_ = parseNextBlock(false);
      if (curBlobs_ == null) {
        // If there is nothing, it likely means EOF. Signal that processing is done.
        return false;
      }
    }

    return true;
  }

  private int readInt() throws IOException {
    int b = in_.read();
    if (b == -1) {
      return -1;
    }

    return b | (in_.read() << 8) | (in_.read() << 16) | (in_.read() << 24);
  }
}