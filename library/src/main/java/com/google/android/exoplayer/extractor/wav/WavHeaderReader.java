/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.google.android.exoplayer.extractor.wav;

import com.google.android.exoplayer.ParserException;
import com.google.android.exoplayer.extractor.ExtractorInput;
import com.google.android.exoplayer.util.Assertions;
import com.google.android.exoplayer.util.ParsableByteArray;
import com.google.android.exoplayer.util.Util;

import android.util.Log;

import java.io.IOException;

/** Reads a {@code WavHeader} from an input stream; supports resuming from input failures. */
/*package*/ final class WavHeaderReader {

  private static final String TAG = "WavHeaderReader";

  /** Integer PCM audio data. */
  private static final int TYPE_PCM = 0x0001;
  /** Extended WAVE format. */
  private static final int TYPE_WAVE_FORMAT_EXTENSIBLE = 0xFFFE;

  /**
   * Peeks and returns a {@code WavHeader}.
   *
   * @param input Input stream to peek the WAV header from.
   * @throws IOException If peeking from the input fails.
   * @throws InterruptedException If interrupted while peeking from input.
   * @throws ParserException If the input file is an incorrect RIFF WAV.
   * @return A new {@code WavHeader} peeked from {@code input}, or null if the input is not a
   *     supported WAV format.
   */
  public static WavHeader peek(ExtractorInput input)
      throws IOException, InterruptedException, ParserException {
    Assertions.checkNotNull(input);

    // Allocate a scratch buffer large enough to store the format chunk.
    ParsableByteArray scratch = new ParsableByteArray(16);

    // Attempt to read the RIFF chunk.
    ChunkHeader chunkHeader = ChunkHeader.peek(input, scratch);
    if (chunkHeader.id != Util.getIntegerCodeForString("RIFF")) {
      return null;
    }

    input.peekFully(scratch.data, 0, 4);
    scratch.setPosition(0);
    int riffFormat = scratch.readInt();
    if (riffFormat != Util.getIntegerCodeForString("WAVE")) {
      Log.e(TAG, "Unsupported RIFF format: " + riffFormat);
      return null;
    }

    // If a bext chunk is present, skip it. Otherwise we expect a format chunk.
    chunkHeader = ChunkHeader.peek(input, scratch);
    if (chunkHeader.id == Util.getIntegerCodeForString("bext")) {
      input.advancePeekPosition((int) chunkHeader.size);
      chunkHeader = ChunkHeader.peek(input, scratch);
    }

    if (chunkHeader.id != Util.getIntegerCodeForString("fmt ")) {
      throw new ParserException(
          "Expected format chunk; found: " + chunkHeader.id);
    }
    Assertions.checkState(chunkHeader.size >= 16);

    input.peekFully(scratch.data, 0, 16);
    scratch.setPosition(0);
    int type = scratch.readLittleEndianUnsignedShort();
    int numChannels = scratch.readLittleEndianUnsignedShort();
    int sampleRateHz = scratch.readLittleEndianUnsignedIntToInt();
    int averageBytesPerSecond = scratch.readLittleEndianUnsignedIntToInt();
    int blockAlignment = scratch.readLittleEndianUnsignedShort();
    int bitsPerSample = scratch.readLittleEndianUnsignedShort();

    int expectedBlockAlignment = numChannels * bitsPerSample / 8;
    if (blockAlignment != expectedBlockAlignment) {
      throw new ParserException(
          "Expected WAV block alignment of: "
              + expectedBlockAlignment
              + "; got: "
              + blockAlignment);
    }
    if (bitsPerSample != 16) {
      Log.e(TAG, "Only 16-bit WAVs are supported; got: " + bitsPerSample);
      return null;
    }

    if (type != TYPE_PCM && type != TYPE_WAVE_FORMAT_EXTENSIBLE) {
      Log.e(TAG, "Unsupported WAV format type: " + type);
      return null;
    }

    // If present, skip extensionSize, validBitsPerSample, channelMask, subFormatGuid, ...
    input.advancePeekPosition((int) chunkHeader.size - 16);

    return new WavHeader(
        numChannels, sampleRateHz, averageBytesPerSecond, blockAlignment, bitsPerSample);
  }

  /**
   * Skips to the data in the given WAV input stream and returns its data size. After calling, the
   * input stream's position will point to the start of sample data in the WAV.
   * <p>
   * If an exception is thrown, the input position will be left pointing to a chunk header.
   *
   * @param input Input stream to skip to the data chunk in. Its peek position must be pointing to
   *     a valid chunk header.
   * @param wavHeader WAV header to populate with data bounds.
   * @throws IOException If reading from the input fails.
   * @throws InterruptedException If interrupted while reading from input.
   * @throws ParserException If an error occurs parsing chunks.
   */
  public static void skipToData(ExtractorInput input, WavHeader wavHeader)
      throws IOException, InterruptedException, ParserException {
    Assertions.checkNotNull(input);
    Assertions.checkNotNull(wavHeader);

    ParsableByteArray scratch = new ParsableByteArray(ChunkHeader.SIZE_IN_BYTES);
    // Skip all chunks until we hit the data header.
    ChunkHeader chunkHeader = ChunkHeader.peek(input, scratch);
    while (chunkHeader.id != Util.getIntegerCodeForString("data")) {
      Log.w(TAG, "Ignoring unknown WAV chunk: " + chunkHeader.id);
      long bytesToSkip = ChunkHeader.SIZE_IN_BYTES + chunkHeader.size;
      // Override size of RIFF chunk, since it describes its size as the entire file.
      if (chunkHeader.id == Util.getIntegerCodeForString("RIFF")) {
        bytesToSkip = ChunkHeader.SIZE_IN_BYTES + 4;
      }
      if (bytesToSkip > Integer.MAX_VALUE) {
        throw new ParserException("Chunk is too large (~2GB+) to skip; id: " + chunkHeader.id);
      }
      input.skipFully((int) bytesToSkip);
      chunkHeader = ChunkHeader.peek(input, scratch);
    }
    // Skip past the "data" header.
    input.skipFully(ChunkHeader.SIZE_IN_BYTES);

    wavHeader.setDataBounds(input.getPosition(), chunkHeader.size);
  }

  /** Container for a WAV chunk header. */
  private static final class ChunkHeader {

    /** Size in bytes of a WAV chunk header. */
    public static final int SIZE_IN_BYTES = 8;

    /** 4-character identifier, stored as an integer, for this chunk. */
    public final int id;
    /** Size of this chunk in bytes. */
    public final long size;

    private ChunkHeader(int id, long size) {
      this.id = id;
      this.size = size;
    }

    /**
     * Peeks and returns a {@link ChunkHeader}.
     *
     * @param input Input stream to peek the chunk header from.
     * @param scratch Buffer for temporary use.
     * @throws IOException If peeking from the input fails.
     * @throws InterruptedException If interrupted while peeking from input.
     * @return A new {@code ChunkHeader} peeked from {@code input}.
     */
    public static ChunkHeader peek(ExtractorInput input, ParsableByteArray scratch)
        throws IOException, InterruptedException {
      input.peekFully(scratch.data, 0, SIZE_IN_BYTES);
      scratch.setPosition(0);

      int id = scratch.readInt();
      long size = scratch.readLittleEndianUnsignedInt();

      return new ChunkHeader(id, size);
    }
  }
}
