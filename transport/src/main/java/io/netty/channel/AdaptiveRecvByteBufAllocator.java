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

import java.util.ArrayList;
import java.util.List;

/**
 * 使用slab的思想记录了不同的内存块大小，按照分配需要的大小寻找最合适的内存块
 * 指的是缓冲区大小可以动态调整的ByteBuf分配器
 * 1） 通用NIO框架，适应不同场景
 * 2）性能更高
 * 3）更节约内存
 * The {@link RecvByteBufAllocator} that automatically increases and
 * decreases the predicted buffer size on feed back.
 * <p>
 * It gradually increases the expected number of readable bytes if the previous
 * read fully filled the allocated buffer.  It gradually decreases the expected
 * number of readable bytes if the read operation was not able to fill a certain
 * amount of the allocated buffer two times consecutively.  Otherwise, it keeps
 * returning the same prediction.
 */
public class AdaptiveRecvByteBufAllocator implements RecvByteBufAllocator {

    // 最小缓冲区长度
    static final int DEFAULT_MINIMUM = 64;
    // 初始容量
    static final int DEFAULT_INITIAL = 1024;
    // 最大容量
    static final int DEFAULT_MAXIMUM = 65536;

    // 扩张的步进索引
    private static final int INDEX_INCREMENT = 4;
    // 收缩的步进索引
    private static final int INDEX_DECREMENT = 1;

    /**
     * 向量数组的每个值对应一个Buffer容量
     * 当容量小于512，由于缓冲区已经比较小，需要降低步进值，容量每次下调的幅度要小
     * 当容量大于512，说明需要解码的消息码流比较大，这时采取调大步进幅度的方式，减少动态扩张的频率
     */
    private static final int[] SIZE_TABLE;

    static {
        List<Integer> sizeTable = new ArrayList<Integer>();
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i);
        }

        for (int i = 512; i > 0; i <<= 1) {
            sizeTable.add(i);
        }

        SIZE_TABLE = new int[sizeTable.size()];
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();

    /**
     * 二分查找法
     * 根据容量size查找容量向量表对应的索引
     * @param size
     * @return
     */
    private static int getSizeTableIndex(final int size) {
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }

            int mid = low + high >>> 1;
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
            } else if (size == a) {
                return mid;
            } else {
                return mid + 1;
            }
        }
    }

    private static final class HandleImpl implements Handle {
        private final int minIndex;
        private final int maxIndex;
        private int index;
        private int nextReceiveBufferSize;
        private boolean decreaseNow;

        HandleImpl(int minIndex, int maxIndex, int initial) {
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;

            index = getSizeTableIndex(initial);
            nextReceiveBufferSize = SIZE_TABLE[index];
        }

        @Override
        public ByteBuf allocate(ByteBufAllocator alloc) {
            return alloc.ioBuffer(nextReceiveBufferSize);
        }

        @Override
        public int guess() {
            return nextReceiveBufferSize;
        }

        /**
         * 当Niosocketchannel执行完读操作后，会计算获取本次轮询读取的总字节数，它就是actualReadBytes
         *
         *
         * @param actualReadBytes the actual number of read bytes in the previous read operation
         */
        @Override
        public void record(int actualReadBytes) {
            /**
             * 对当前索引做步进缩减， 然后获取收缩后索引对应的容量
             */
            if (actualReadBytes <= SIZE_TABLE[Math.max(0, index - INDEX_DECREMENT - 1)]) {
                if (decreaseNow) {
                    index = Math.max(index - INDEX_DECREMENT, minIndex);
                    nextReceiveBufferSize = SIZE_TABLE[index];
                    decreaseNow = false;
                } else {
                    decreaseNow = true;
                }
            } else if (actualReadBytes >= nextReceiveBufferSize) {
                index = Math.min(index + INDEX_INCREMENT, maxIndex);
                nextReceiveBufferSize = SIZE_TABLE[index];
                decreaseNow = false;
            }
        }
    }

    private final int minIndex;
    private final int maxIndex;
    private final int initial;

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 1024}, does not
     * go down below {@code 64}, and does not go up above {@code 65536}.
     */
    private AdaptiveRecvByteBufAllocator() {
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);
    }

    /**
     * Creates a new predictor with the specified parameters.
     *
     * @param minimum  the inclusive lower bound of the expected buffer size
     * @param initial  the initial buffer size when no feed back was received
     * @param maximum  the inclusive upper bound of the expected buffer size
     */
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) {
        if (minimum <= 0) {
            throw new IllegalArgumentException("minimum: " + minimum);
        }
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }

        int minIndex = getSizeTableIndex(minimum);
        if (SIZE_TABLE[minIndex] < minimum) {
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex = minIndex;
        }

        int maxIndex = getSizeTableIndex(maximum);
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;
        } else {
            this.maxIndex = maxIndex;
        }

        this.initial = initial;
    }

    @Override
    public Handle newHandle() {
        return new HandleImpl(minIndex, maxIndex, initial);
    }
}
