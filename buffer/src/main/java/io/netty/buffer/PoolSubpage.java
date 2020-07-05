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

package io.netty.buffer;

final class PoolSubpage<T> implements PoolSubpageMetric {

    // 代表其子页属于哪个Chunk
    final PoolChunk<T> chunk;
    private final int memoryMapIdx;
    private final int runOffset;
    private final int pageSize;
    /**
     * 用于记录子页的内存分配情况
     * long型数组, 每个long有64位bit，比特位由低到高进行排列
      */
    private final long[] bitmap;

    // 双向关联
    PoolSubpage<T> prev;
    PoolSubpage<T> next;

    boolean doNotDestroy;
    // 代表子页是按照多大内存进行划分的，如果按照1KB划分，则可以划分出8个子页。
    int elemSize;
    private int maxNumElems;
    // 表示bitmap的实际大小，即所切分的子块数除以64.
    private int bitmapLength;
    // 下一个可用的bitmapIdx
    private int nextAvail;
    // 表示剩余可用的块数
    private int numAvail;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;
        /**
         * 每个long型数字，有64位，可以记录64个子块的数量
         */
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64
        init(head, elemSize);
    }

    void init(PoolSubpage<T> head, int elemSize) {
        doNotDestroy = true;
        // 表示保存当前分配的缓冲区大小
        this.elemSize = elemSize;
        if (elemSize != 0) {
            // 表示一个Page大小除以分配的缓冲区大小
            maxNumElems = numAvail = pageSize / elemSize;
            nextAvail = 0;
            bitmapLength = maxNumElems >>> 6;
            if ((maxNumElems & 63) != 0) { // 不是64的倍数
                bitmapLength ++;
            }

            for (int i = 0; i < bitmapLength; i ++) {
                /**
                 * bitmap标识哪个SubPage被分配
                 * 0表示未分配，1表示已分配
                 */
                bitmap[i] = 0;
            }
        }
        // 加到Arena里面
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    long allocate() {
        if (elemSize == 0) {
            return toHandle(0);
        }

        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        // 取一个bitmap中可用的id(绝对ID)
        final int bitmapIdx = getNextAvail();
        // 除以64（bitmap的相对下标）
        int q = bitmapIdx >>> 6;
        // 取余，当前绝对ID的偏移量
        // 除以64取余，其实就是当前绝对id的偏移量, 即当前元素最低位开始的第几个比特位。
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) == 0;
        // 当前位标记为1
        bitmap[q] |= 1L << r;
        // 可用于配置的数量减一
        if (-- numAvail == 0) {
            removeFromPool();
        }
        // bitmapIdx换成handle
        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        if (elemSize == 0) {
            return true;
        }
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        // 将其位图标记为0
        bitmap[q] ^= 1L << r;

        setNextAvail(bitmapIdx);

        if (numAvail ++ == 0) {
            addToPool(head);
            return true;
        }

        if (numAvail != maxNumElems) {
            return true;
        } else {
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            removeFromPool();
            return false;
        }
    }

    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        if (nextAvail >= 0) {
            /**
             * 一个子SubPage被释放之后，会记录当前SubPage的bitmapIdx的位置，
             * 下次分配可以直接通过bitmapIdx获取一个SubPage
             */
            this.nextAvail = -1;
            return nextAvail;
        }
        return findNextAvail();
    }

    private int findNextAvail() {
        // 当前long数组
        final long[] bitmap = this.bitmap;
        // 获取其长度
        final int bitmapLength = this.bitmapLength;
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            // != -1 说明64位没有全部占满
            if (~bits != 0) {
                // 找下一个节点
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    /**
     * 一个一个地往后找标记未使用的比特位
     * @param i bitmap[] idx
     * @param bits bitmap[i]
     * @return 返回当前bit为0的下标，即64*i + j
     */
    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        // 乘以64，代表当前long的第一个下标
        final int baseVal = i << 6;
        // 循环64次（指代当前的下标），从低位到高位
        for (int j = 0; j < 64; j ++) {
            // 2的倍数，即最后1位为0
            if ((bits & 1) == 0) {
                // 这里相当于加，将i*64,之后加上j，获取绝对下标
                int val = baseVal | j;
                // 小于块数（不能越界）
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            // 当前下标不为0，右移一位
            bits >>>= 1;
        }
        return -1;
    }

    /**
     * 一个long整数，其中低32位表示二叉树中的分配的节点，高32位表示subPage中分配的具体位置
     *     |<--   24   -->| <--   6      --> | <--         32         --> |
     *     |  long数组偏移 |  long的二进制位偏移|       所属Chunk标号         |
     * @param bitmapIdx
     * @return
     */
    private long toHandle(int bitmapIdx) {
        /**
         * 0x4000000000000000L , 最高位为1且最低位都是0的二进制数,即0b01000{60}
         *  bitmapIdx << 32 | memoryMapIdx，将memoryMapIdx保存在bitmapIdx << 32的低32中，
         */
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        synchronized (chunk.arena) {
            if (!this.doNotDestroy) {
                doNotDestroy = false;
                // Not used for creating the String.
                maxNumElems = numAvail = elemSize = -1;
            } else {
                doNotDestroy = true;
                maxNumElems = this.maxNumElems;
                numAvail = this.numAvail;
                elemSize = this.elemSize;
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
