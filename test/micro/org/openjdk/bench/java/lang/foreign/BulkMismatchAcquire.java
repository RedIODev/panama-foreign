/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package org.openjdk.bench.java.lang.foreign;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.MemorySession;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@BenchmarkMode(Mode.AverageTime)
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@State(org.openjdk.jmh.annotations.Scope.Thread)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 3, jvmArgsAppend = "--enable-preview")
public class BulkMismatchAcquire {

    public enum SessionKind {
        CONFINED(MemorySession::openConfined),
        SHARED(MemorySession::openShared),
        IMPLICIT(MemorySession::openImplicit);

        final Supplier<MemorySession> sessionFactory;

        SessionKind(Supplier<MemorySession> sessionFactory) {
            this.sessionFactory = sessionFactory;
        }

        MemorySession makeSession() {
            return sessionFactory.get();
        }
    }

    @Param({"CONFINED", "SHARED"})
    public BulkMismatchAcquire.SessionKind sessionKind;

    // large(ish) segments/buffers with same content, 0, for mismatch, non-multiple-of-8 sized
    static final int SIZE_WITH_TAIL = (1024 * 1024) + 7;

    MemorySession session;
    MemorySegment mismatchSegmentLarge1;
    MemorySegment mismatchSegmentLarge2;
    ByteBuffer mismatchBufferLarge1;
    ByteBuffer mismatchBufferLarge2;
    MemorySegment mismatchSegmentSmall1;
    MemorySegment mismatchSegmentSmall2;
    ByteBuffer mismatchBufferSmall1;
    ByteBuffer mismatchBufferSmall2;

    @Setup
    public void setup() {
        session = sessionKind.makeSession();
        mismatchSegmentLarge1 = session.allocate(SIZE_WITH_TAIL);
        mismatchSegmentLarge2 = session.allocate(SIZE_WITH_TAIL);
        mismatchBufferLarge1 = ByteBuffer.allocateDirect(SIZE_WITH_TAIL);
        mismatchBufferLarge2 = ByteBuffer.allocateDirect(SIZE_WITH_TAIL);

        // mismatch at first byte
        mismatchSegmentSmall1 = session.allocate(7);
        mismatchSegmentSmall2 = session.allocate(7);
        mismatchBufferSmall1 = ByteBuffer.allocateDirect(7);
        mismatchBufferSmall2 = ByteBuffer.allocateDirect(7);
        {
            mismatchSegmentSmall1.fill((byte) 0xFF);
            mismatchBufferSmall1.put((byte) 0xFF).clear();
            // verify expected mismatch indices
            long si = mismatchSegmentLarge1.mismatch(mismatchSegmentLarge2);
            if (si != -1)
                throw new AssertionError("Unexpected mismatch index:" + si);
            int bi = mismatchBufferLarge1.mismatch(mismatchBufferLarge2);
            if (bi != -1)
                throw new AssertionError("Unexpected mismatch index:" + bi);
            si = mismatchSegmentSmall1.mismatch(mismatchSegmentSmall2);
            if (si != 0)
                throw new AssertionError("Unexpected mismatch index:" + si);
            bi = mismatchBufferSmall1.mismatch(mismatchBufferSmall2);
            if (bi != 0)
                throw new AssertionError("Unexpected mismatch index:" + bi);
        }
    }

    @TearDown
    public void tearDown() {
        if (session.isCloseable()) {
            session.close();
        }
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public long mismatch_large_segment() {
        return mismatchSegmentLarge1.mismatch(mismatchSegmentLarge2);
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public long mismatch_large_segment_acquire() {
        long[] arr = new long[1];
        mismatchSegmentLarge1.session().whileAlive(() -> {
            arr[0] = mismatchSegmentLarge1.mismatch(mismatchSegmentSmall2);
        });
        return arr[0];
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public int mismatch_large_bytebuffer() {
        return mismatchBufferLarge1.mismatch(mismatchBufferLarge2);
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public long mismatch_small_segment() {
        return mismatchSegmentSmall1.mismatch(mismatchSegmentSmall2);
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public long mismatch_small_segment_acquire() {
        long[] arr = new long[1];
        mismatchSegmentLarge1.session().whileAlive(() -> {
            arr[0] = mismatchSegmentSmall1.mismatch(mismatchSegmentSmall2);
        });
        return arr[0];
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public int mismatch_small_bytebuffer() {
        return mismatchBufferSmall1.mismatch(mismatchBufferSmall2);
    }
}
