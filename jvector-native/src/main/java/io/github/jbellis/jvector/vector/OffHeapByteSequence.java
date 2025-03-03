/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.jbellis.jvector.vector;

import io.github.jbellis.jvector.vector.types.ByteSequence;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.Buffer;

/**
 * ByteSequence implementation backed by an off-heap MemorySegment.
 */
public class OffHeapByteSequence implements ByteSequence<MemorySegment> {
    private final MemorySegment segment;
    private final int length;

    OffHeapByteSequence(int length) {
        this.segment = Arena.ofAuto().allocate(length, 1);
        this.length = length;
    }

    OffHeapByteSequence(Buffer data) {
        this(data.remaining());
        segment.copyFrom(MemorySegment.ofBuffer(data));
    }

    OffHeapByteSequence(byte[] data) {
        this(data.length);
        segment.copyFrom(MemorySegment.ofArray(data));
    }

    @Override
    public long ramBytesUsed() {
        return MemoryLayout.sequenceLayout(length, ValueLayout.JAVA_BYTE).byteSize();
    }

    @Override
    public void copyFrom(ByteSequence<?> src, int srcOffset, int destOffset, int length) {
        OffHeapByteSequence csrc = (OffHeapByteSequence) src;
        segment.asSlice(destOffset, length).copyFrom(csrc.segment.asSlice(srcOffset));
    }

    @Override
    public MemorySegment get() {
        return segment;
    }

    @Override
    public byte get(int n) {
        return segment.get(ValueLayout.JAVA_BYTE, n);
    }

    @Override
    public void set(int n, byte value) {
        segment.set(ValueLayout.JAVA_BYTE, n, value);
    }

    @Override
    public void zero() {
        segment.fill((byte) 0);
    }

    @Override
    public int length() {
        return (int) segment.byteSize();
    }

    @Override
    public ByteSequence<MemorySegment> copy() {
        OffHeapByteSequence copy = new OffHeapByteSequence(length());
        copy.copyFrom(this, 0, 0, length());
        return copy;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < Math.min(length, 25); i++) {
            sb.append(get(i));
            if (i < length - 1) {
                sb.append(", ");
            }
        }
        if (length > 25) {
            sb.append("...");
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OffHeapByteSequence that = (OffHeapByteSequence) o;
        return segment.mismatch(that.segment) == -1;
    }

    @Override
    public int hashCode() {
        return segment.hashCode();
    }
}
