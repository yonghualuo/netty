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
package io.netty.channel.socket.nio;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.FileRegion;
import io.netty.channel.nio.AbstractNioByteChannel;
import io.netty.channel.socket.DefaultSocketChannelConfig;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannelConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * {@link io.netty.channel.socket.SocketChannel} which uses NIO selector based implementation.
 */
public class NioSocketChannel extends AbstractNioByteChannel implements io.netty.channel.socket.SocketChannel {

    private static final ChannelMetadata METADATA = new ChannelMetadata(false);

    private static SocketChannel newSocket() {
        try {
            return SocketChannel.open();
        } catch (IOException e) {
            throw new ChannelException("Failed to open a socket.", e);
        }
    }

    private final SocketChannelConfig config;

    /**
     * Create a new instance
     */
    public NioSocketChannel(EventLoop eventLoop) {
        this(eventLoop, newSocket());
    }

    /**
     * Create a new instance using the given {@link SocketChannel}.
     */
    public NioSocketChannel(EventLoop eventLoop, SocketChannel socket) {
        this(null, eventLoop, socket);
    }

    /**
     * Create a new instance
     *
     * @param parent    the {@link Channel} which created this instance or {@code null} if it was created by the user
     * @param socket    the {@link SocketChannel} which will be used
     */
    public NioSocketChannel(Channel parent, EventLoop eventLoop, SocketChannel socket) {
        super(parent, eventLoop, socket);
        config = new DefaultSocketChannelConfig(this, socket.socket());
    }

    @Override
    public ServerSocketChannel parent() {
        return (ServerSocketChannel) super.parent();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    public SocketChannelConfig config() {
        return config;
    }

    @Override
    protected SocketChannel javaChannel() {
        return (SocketChannel) super.javaChannel();
    }

    @Override
    public boolean isActive() {
        SocketChannel ch = javaChannel();
        return ch.isOpen() && ch.isConnected();
    }

    @Override
    public boolean isInputShutdown() {
        return super.isInputShutdown();
    }

    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) super.localAddress();
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) super.remoteAddress();
    }

    @Override
    public boolean isOutputShutdown() {
        return javaChannel().socket().isOutputShutdown() || !isActive();
    }

    @Override
    public ChannelFuture shutdownOutput() {
        return shutdownOutput(newPromise());
    }

    @Override
    public ChannelFuture shutdownOutput(final ChannelPromise promise) {
        EventLoop loop = eventLoop();
        if (loop.inEventLoop()) {
            try {
                javaChannel().socket().shutdownOutput();
                promise.setSuccess();
            } catch (Throwable t) {
                promise.setFailure(t);
            }
        } else {
            loop.execute(new Runnable() {
                @Override
                public void run() {
                    shutdownOutput(promise);
                }
            });
        }
        return promise;
    }

    @Override
    protected SocketAddress localAddress0() {
        return javaChannel().socket().getLocalSocketAddress();
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return javaChannel().socket().getRemoteSocketAddress();
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        javaChannel().socket().bind(localAddress);
    }

    @Override
    protected boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        if (localAddress != null) {
            javaChannel().socket().bind(localAddress);
        }

        boolean success = false;
        try {
            /**
             * 1） 连接成功， 返回true
             * 2) 暂时没有连接上, 服务端没有返回ACK，连接结果不确定, 返回false
             * 3) 连接失败, 直接抛出I/O异常
             */
            boolean connected = javaChannel().connect(remoteAddress);
            if (!connected) {
                selectionKey().interestOps(SelectionKey.OP_CONNECT);
            }
            success = true;
            return connected;
        } finally {
            // 如果抛出了I/O异常，说明客户端的TCP握手请求直接被RST或者被拒绝, 此时需要关闭客户端连接
            if (!success) {
                doClose();
            }
        }
    }

    @Override
    protected void doFinishConnect() throws Exception {
        if (!javaChannel().finishConnect()) {
            throw new Error();
        }
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected void doClose() throws Exception {
        javaChannel().close();
    }

    @Override
    protected int doReadBytes(ByteBuf byteBuf) throws Exception {
        return byteBuf.writeBytes(javaChannel(), byteBuf.writableBytes());
    }

    @Override
    protected int doWriteBytes(ByteBuf buf) throws Exception {
        // 可读字节数
        final int expectedWrittenBytes = buf.readableBytes();
        /**
         * ByteBuf的readBytes方法功能是将当前BYtebuf的可写字节数组写入到指定的Channel中
         */
        final int writtenBytes = buf.readBytes(javaChannel(), expectedWrittenBytes);
        return writtenBytes;
    }

    @Override
    protected long doWriteFileRegion(FileRegion region) throws Exception {
        final long position = region.transfered();
        final long writtenBytes = region.transferTo(javaChannel(), position);
        return writtenBytes;
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        for (;;) {
            // Do non-gathering write for a single buffer case.
            final int msgCount = in.size();
            if (msgCount <= 1) {
                super.doWrite(in);
                return;
            }

            // Ensure the pending writes are made of ByteBufs only.
            ByteBuffer[] nioBuffers = in.nioBuffers();
            if (nioBuffers == null) {
                super.doWrite(in);
                return;
            }

            int nioBufferCnt = in.nioBufferCount();
            long expectedWrittenBytes = in.nioBufferSize();

            final SocketChannel ch = javaChannel();
            long writtenBytes = 0;
            boolean done = false;
            boolean setOpWrite = false;
            // 对循环次数做限制，防止其一直处于发送状态
            for (int i = config().getWriteSpinCount() - 1; i >= 0; i --) {
                final long localWrittenBytes = ch.write(nioBuffers, 0, nioBufferCnt);
                // 0说明TCP发送缓冲区已满, 很可能无法写入
                if (localWrittenBytes == 0) {
                    setOpWrite = true;
                    break;
                }
                // 需要发送的字节数要减去已经发送的字节数；发送的字节总数 + 已经发送的字节数
                expectedWrittenBytes -= localWrittenBytes;
                writtenBytes += localWrittenBytes;
                if (expectedWrittenBytes == 0) {
                    done = true;
                    break;
                }
            }

            // 发送完成
            if (done) {
                // Release all buffers
                for (int i = msgCount; i > 0; i --) {
                    in.remove();
                }

                // Finish the write loop if no new messages were flushed by in.remove().
                if (in.isEmpty()) {
                    clearOpWrite();
                    break;
                }
            } else {
                // Did not write all buffers completely.
                // Release the fully written buffers and update the indexes of the partially written buffer.

                /**
                 * 遍历发送缓冲区，对消息的发送结果进行判断
                 */
                for (int i = msgCount; i > 0; i --) {
                    final ByteBuf buf = (ByteBuf) in.current();
                    // 读索引
                    final int readerIndex = buf.readerIndex();
                    // 可读字节数
                    final int readableBytes = buf.writerIndex() - readerIndex;

                    //  发送的大于可写, 说明bytebuf已完全发送出去, 更新发送进度
                    if (readableBytes < writtenBytes) {
                        in.progress(readableBytes);
                        in.remove();
                        writtenBytes -= readableBytes;
                    } else if (readableBytes > writtenBytes) { // 可读的大于已经发送的字节数, 仅仅发送了部分数据报
                        buf.readerIndex(readerIndex + (int) writtenBytes);
                        in.progress(writtenBytes);
                        break;
                    } else { // readableBytes == writtenBytes, 说明最后一次发送的消息是个整包消息
                        in.progress(readableBytes);
                        in.remove();
                        break;
                    }
                }

                // 更新SocketChannel的操作位OP_WRITE,等待多路复用器触发继续处理半包
                incompleteWrite(setOpWrite);
                break;
            }
        }
    }
}
