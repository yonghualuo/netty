/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * 用于计算下次读循环应该分配多少内存的接口
 * 读循环是因为分配的初始ByteBuf不一定能够容纳所有读取到的数据，因此可能会多次读取，直到读完客户端发送的数据。
 *
 * Allocates a new receive buffer whose capacity is probably large enough to read all inbound data and small enough
 * not to waste its space.
 */
public interface RecvByteBufAllocator {

    /**
     * Creates a new handle.  The handle provides the actual operations and keeps the internal information which is
     * required for predicting an optimal buffer capacity.
     */
    Handle newHandle();

    interface Handle {
        /**
         * 创建一个空间合理的缓冲，在不浪费空间的情况下能够容纳需要读取的所有inbound的数据，内部由alloc来进行实际的分配
         * Creates a new receive buffer whose capacity is probably large enough to read all inbound data and small
         * enough not to waste its space.
         */
        ByteBuf allocate(ByteBufAllocator alloc);

        /**
         * 猜测所需的缓冲区大小，不进行实际的分配
         * Similar to {@link #allocate(ByteBufAllocator)} except that it does not allocate anything but just tells the
         * capacity.
         */
        int guess();

        /**
         * Records the the actual number of read bytes in the previous read operation so that the allocator allocates
         * the buffer with potentially more correct capacity.
         *
         * @param actualReadBytes the actual number of read bytes in the previous read operation
         */
        void record(int actualReadBytes);
    }
}
