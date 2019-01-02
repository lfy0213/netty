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

import io.netty.util.ByteProcessor;
import io.netty.util.IllegalReferenceCountException;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.EmptyArrays;
import io.netty.util.internal.RecyclableArrayList;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import static io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 *
 * 将多个ByteBuf的实例组合到一起，形成一个统一的视图
 * 例如协议POJO对象有两部分，分别是请求头和请求体，分别对应两个Buffer，需要消息解码的时候组合一起进行解码，这个时候可以利用这个类进行组合。
 * A virtual buffer which shows multiple buffers as a single merged buffer.  It is recommended to use
 * {@link ByteBufAllocator#compositeBuffer()} or {@link Unpooled#wrappedBuffer(ByteBuf...)} instead of calling the
 * constructor explicitly.
 */
public class CompositeByteBuf extends AbstractReferenceCountedByteBuf implements Iterable<ByteBuf> {

    private static final ByteBuffer EMPTY_NIO_BUFFER = Unpooled.EMPTY_BUFFER.nioBuffer();
    private static final Iterator<ByteBuf> EMPTY_ITERATOR = Collections.<ByteBuf>emptyList().iterator();

    private final ByteBufAllocator alloc;
    private final boolean direct;
    private final int maxNumComponents;

    private int componentCount;
    private Component[] components; // resized when needed

    private boolean freed;

    private CompositeByteBuf(ByteBufAllocator alloc, boolean direct, int maxNumComponents, int initSize) {
        super(AbstractByteBufAllocator.DEFAULT_MAX_CAPACITY);
        if (alloc == null) {
            throw new NullPointerException("alloc");
        }
        if (maxNumComponents < 2) {
            throw new IllegalArgumentException(
                    "maxNumComponents: " + maxNumComponents + " (expected: >= 2)");
        }
        this.alloc = alloc;
        this.direct = direct;
        this.maxNumComponents = maxNumComponents;
        components = newCompArray(initSize, maxNumComponents);
    }

    public CompositeByteBuf(ByteBufAllocator alloc, boolean direct, int maxNumComponents) {
        this(alloc, direct, maxNumComponents, 0);
    }

    public CompositeByteBuf(ByteBufAllocator alloc, boolean direct, int maxNumComponents, ByteBuf... buffers) {
        this(alloc, direct, maxNumComponents, buffers, 0);
    }

    CompositeByteBuf(ByteBufAllocator alloc, boolean direct, int maxNumComponents,
            ByteBuf[] buffers, int offset) {
        this(alloc, direct, maxNumComponents, buffers.length - offset);

        addComponents0(false, 0, buffers, offset);
        consolidateIfNeeded();
        setIndex0(0, capacity());
    }

    public CompositeByteBuf(
            ByteBufAllocator alloc, boolean direct, int maxNumComponents, Iterable<ByteBuf> buffers) {
        this(alloc, direct, maxNumComponents,
                buffers instanceof Collection ? ((Collection<ByteBuf>) buffers).size() : 0);

        addComponents0(false, 0, buffers);
        consolidateIfNeeded();
        setIndex(0, capacity());
    }

    // support passing arrays of other types instead of having to copy to a ByteBuf[] first
    interface ByteWrapper<T> {
        ByteBuf wrap(T bytes);
        boolean isEmpty(T bytes);
    }

    static final ByteWrapper<byte[]> BYTE_ARRAY_WRAPPER = new ByteWrapper<byte[]>() {
        @Override
        public ByteBuf wrap(byte[] bytes) {
            return Unpooled.wrappedBuffer(bytes);
        }
        @Override
        public boolean isEmpty(byte[] bytes) {
            return bytes.length == 0;
        }
    };

    static final ByteWrapper<ByteBuffer> BYTE_BUFFER_WRAPPER = new ByteWrapper<ByteBuffer>() {
        @Override
        public ByteBuf wrap(ByteBuffer bytes) {
            return Unpooled.wrappedBuffer(bytes);
        }
        @Override
        public boolean isEmpty(ByteBuffer bytes) {
            return !bytes.hasRemaining();
        }
    };

    <T> CompositeByteBuf(ByteBufAllocator alloc, boolean direct, int maxNumComponents,
            ByteWrapper<T> wrapper, T[] buffers, int offset) {
        this(alloc, direct, maxNumComponents, buffers.length - offset);

        addComponents0(false, 0, wrapper, buffers, offset);
        consolidateIfNeeded();
        setIndex(0, capacity());
    }

    private static Component[] newCompArray(int initComponents, int maxNumComponents) {
        int capacityGuess = Math.min(AbstractByteBufAllocator.DEFAULT_MAX_COMPONENTS, maxNumComponents);
        return new Component[Math.max(initComponents, capacityGuess)];
    }

    // Special constructor used by WrappedCompositeByteBuf
    CompositeByteBuf(ByteBufAllocator alloc) {
        super(Integer.MAX_VALUE);
        this.alloc = alloc;
        direct = false;
        maxNumComponents = 0;
        components = null;
    }

    /**
     * Add the given {@link ByteBuf}.
     * <p>
     * Be aware that this method does not increase the {@code writerIndex} of the {@link CompositeByteBuf}.
     * If you need to have it increased use {@link #addComponent(boolean, ByteBuf)}.
     * <p>
     * {@link ByteBuf#release()} ownership of {@code buffer} is transfered to this {@link CompositeByteBuf}.
     * @param buffer the {@link ByteBuf} to add. {@link ByteBuf#release()} ownership is transfered to this
     * {@link CompositeByteBuf}.
     */
    public CompositeByteBuf addComponent(ByteBuf buffer) {
        return addComponent(false, buffer);
    }

    /**
     * Add the given {@link ByteBuf}s.
     * <p>
     * Be aware that this method does not increase the {@code writerIndex} of the {@link CompositeByteBuf}.
     * If you need to have it increased use {@link #addComponents(boolean, ByteBuf[])}.
     * <p>
     * {@link ByteBuf#release()} ownership of all {@link ByteBuf} objects in {@code buffers} is transfered to this
     * {@link CompositeByteBuf}.
     * @param buffers the {@link ByteBuf}s to add. {@link ByteBuf#release()} ownership of all {@link ByteBuf#release()}
     * ownership of all {@link ByteBuf} objects is transfered to this {@link CompositeByteBuf}.
     */
    public CompositeByteBuf addComponents(ByteBuf... buffers) {
        return addComponents(false, buffers);
    }

    /**
     * Add the given {@link ByteBuf}s.
     * <p>
     * Be aware that this method does not increase the {@code writerIndex} of the {@link CompositeByteBuf}.
     * If you need to have it increased use {@link #addComponents(boolean, Iterable)}.
     * <p>
     * {@link ByteBuf#release()} ownership of all {@link ByteBuf} objects in {@code buffers} is transfered to this
     * {@link CompositeByteBuf}.
     * @param buffers the {@link ByteBuf}s to add. {@link ByteBuf#release()} ownership of all {@link ByteBuf#release()}
     * ownership of all {@link ByteBuf} objects is transfered to this {@link CompositeByteBuf}.
     */
    public CompositeByteBuf addComponents(Iterable<ByteBuf> buffers) {
        return addComponents(false, buffers);
    }

    /**
     * Add the given {@link ByteBuf} on the specific index.
     * <p>
     * Be aware that this method does not increase the {@code writerIndex} of the {@link CompositeByteBuf}.
     * If you need to have it increased use {@link #addComponent(boolean, int, ByteBuf)}.
     * <p>
     * {@link ByteBuf#release()} ownership of {@code buffer} is transfered to this {@link CompositeByteBuf}.
     * @param cIndex the index on which the {@link ByteBuf} will be added.
     * @param buffer the {@link ByteBuf} to add. {@link ByteBuf#release()} ownership is transfered to this
     * {@link CompositeByteBuf}.
     */
    public CompositeByteBuf addComponent(int cIndex, ByteBuf buffer) {
        return addComponent(false, cIndex, buffer);
    }

    /**
     * Add the given {@link ByteBuf} and increase the {@code writerIndex} if {@code increaseWriterIndex} is
     * {@code true}.
     *
     * {@link ByteBuf#release()} ownership of {@code buffer} is transfered to this {@link CompositeByteBuf}.
     * @param buffer the {@link ByteBuf} to add. {@link ByteBuf#release()} ownership is transfered to this
     * {@link CompositeByteBuf}.
     */
    public CompositeByteBuf addComponent(boolean increaseWriterIndex, ByteBuf buffer) {
        checkNotNull(buffer, "buffer");
        addComponent0(increaseWriterIndex, componentCount, buffer);
        consolidateIfNeeded();
        return this;
    }

    /**
     * Add the given {@link ByteBuf}s and increase the {@code writerIndex} if {@code increaseWriterIndex} is
     * {@code true}.
     *
     * {@link ByteBuf#release()} ownership of all {@link ByteBuf} objects in {@code buffers} is transfered to this
     * {@link CompositeByteBuf}.
     * @param buffers the {@link ByteBuf}s to add. {@link ByteBuf#release()} ownership of all {@link ByteBuf#release()}
     * ownership of all {@link ByteBuf} objects is transfered to this {@link CompositeByteBuf}.
     */
    public CompositeByteBuf addComponents(boolean increaseWriterIndex, ByteBuf... buffers) {
        checkNotNull(buffers, "buffers");
        addComponents0(increaseWriterIndex, componentCount, buffers, 0);
        consolidateIfNeeded();
        return this;
    }

    /**
     * Add the given {@link ByteBuf}s and increase the {@code writerIndex} if {@code increaseWriterIndex} is
     * {@code true}.
     *
     * {@link ByteBuf#release()} ownership of all {@link ByteBuf} objects in {@code buffers} is transfered to this
     * {@link CompositeByteBuf}.
     * @param buffers the {@link ByteBuf}s to add. {@link ByteBuf#release()} ownership of all {@link ByteBuf#release()}
     * ownership of all {@link ByteBuf} objects is transfered to this {@link CompositeByteBuf}.
     */
    public CompositeByteBuf addComponents(boolean increaseWriterIndex, Iterable<ByteBuf> buffers) {
        addComponents0(increaseWriterIndex, componentCount, buffers);
        consolidateIfNeeded();
        return this;
    }

    /**
     * Add the given {@link ByteBuf} on the specific index and increase the {@code writerIndex}
     * if {@code increaseWriterIndex} is {@code true}.
     *
     * {@link ByteBuf#release()} ownership of {@code buffer} is transfered to this {@link CompositeByteBuf}.
     * @param cIndex the index on which the {@link ByteBuf} will be added.
     * @param buffer the {@link ByteBuf} to add. {@link ByteBuf#release()} ownership is transfered to this
     * {@link CompositeByteBuf}.
     */
    public CompositeByteBuf addComponent(boolean increaseWriterIndex, int cIndex, ByteBuf buffer) {
        checkNotNull(buffer, "buffer");
        addComponent0(increaseWriterIndex, cIndex, buffer);
        consolidateIfNeeded();
        return this;
    }

    /**
     * Precondition is that {@code buffer != null}.
     */
    private int addComponent0(boolean increaseWriterIndex, int cIndex, ByteBuf buffer) {
        assert buffer != null;
        boolean wasAdded = false;
        try {
            checkComponentIndex(cIndex);

            // No need to consolidate - just add a component to the list.
            Component c = newComponent(buffer, 0);
            int readableBytes = c.length();

            addComp(cIndex, c);
            wasAdded = true;
            if (readableBytes > 0 && cIndex < componentCount - 1) {
                updateComponentOffsets(cIndex);
            } else if (cIndex > 0) {
                c.reposition(components[cIndex - 1].endOffset);
            }
            if (increaseWriterIndex) {
                writerIndex(writerIndex() + readableBytes);
            }
            return cIndex;
        } finally {
            if (!wasAdded) {
                buffer.release();
            }
        }
    }

    // unwrap if already sliced
    @SuppressWarnings("deprecation")
    private Component newComponent(ByteBuf buf, int offset) {
        if (checkAccessible && buf.refCnt() == 0) {
            throw new IllegalReferenceCountException(0);
        }
        int srcIndex = buf.readerIndex(), len = buf.readableBytes();
        ByteBuf slice = null;
        if (buf instanceof AbstractUnpooledSlicedByteBuf) {
            srcIndex += ((AbstractUnpooledSlicedByteBuf) buf).idx(0);
            slice = buf;
            buf = buf.unwrap();
        } else if (buf instanceof PooledSlicedByteBuf) {
            srcIndex += ((PooledSlicedByteBuf) buf).adjustment;
            slice = buf;
            buf = buf.unwrap();
        }
        return new Component(buf.order(ByteOrder.BIG_ENDIAN), srcIndex, offset, len, slice);
    }

    /**
     * Add the given {@link ByteBuf}s on the specific index
     * <p>
     * Be aware that this method does not increase the {@code writerIndex} of the {@link CompositeByteBuf}.
     * If you need to have it increased you need to handle it by your own.
     * <p>
     * {@link ByteBuf#release()} ownership of all {@link ByteBuf} objects in {@code buffers} is transfered to this
     * {@link CompositeByteBuf}.
     * @param cIndex the index on which the {@link ByteBuf} will be added. {@link ByteBuf#release()} ownership of all
     * {@link ByteBuf#release()} ownership of all {@link ByteBuf} objects is transfered to this
     * {@link CompositeByteBuf}.
     * @param buffers the {@link ByteBuf}s to add. {@link ByteBuf#release()} ownership of all {@link ByteBuf#release()}
     * ownership of all {@link ByteBuf} objects is transfered to this {@link CompositeByteBuf}.
     */
    public CompositeByteBuf addComponents(int cIndex, ByteBuf... buffers) {
        checkNotNull(buffers, "buffers");
        addComponents0(false, cIndex, buffers, 0);
        consolidateIfNeeded();
        return this;
    }

    private int addComponents0(boolean increaseWriterIndex, final int cIndex, ByteBuf[] buffers, int arrOffset) {
        final int len = buffers.length, count = len - arrOffset;
        int ci = Integer.MAX_VALUE;
        try {
            checkComponentIndex(cIndex);
            shiftComps(cIndex, count); // will increase componentCount
            ci = cIndex; // only set this after we've shifted so that finally block logic is always correct
            int nextOffset = cIndex > 0 ? components[cIndex - 1].endOffset : 0;
            for (ByteBuf b; arrOffset < len && (b = buffers[arrOffset]) != null; arrOffset++, ci++) {
                Component c = newComponent(b, nextOffset);
                components[ci] = c;
                nextOffset = c.endOffset;
            }
            return ci;
        } finally {
            // ci is now the index following the last successfully added component
            if (ci < componentCount) {
                if (ci < cIndex + count) {
                    // we bailed early
                    removeCompRange(ci, cIndex + count);
                    for (; arrOffset < len; ++arrOffset) {
                        ReferenceCountUtil.safeRelease(buffers[arrOffset]);
                    }
                }
                updateComponentOffsets(ci); // only need to do this here for components after the added ones
            }
            if (increaseWriterIndex && ci > cIndex && ci <= componentCount) {
                writerIndex(writerIndex() + components[ci - 1].endOffset - components[cIndex].offset);
            }
        }
    }

    private <T> int addComponents0(boolean increaseWriterIndex, int cIndex,
            ByteWrapper<T> wrapper, T[] buffers, int offset) {
        checkNotNull(buffers, "buffers");
        checkComponentIndex(cIndex);

        // No need for consolidation
        for (int i = offset, len = buffers.length; i < len; i++) {
            T b = buffers[i];
            if (b == null) {
                break;
            }
            if (!wrapper.isEmpty(b)) {
                cIndex = addComponent0(increaseWriterIndex, cIndex, wrapper.wrap(b)) + 1;
                int size = componentCount;
                if (cIndex > size) {
                    cIndex = size;
                }
            }
        }
        return cIndex;
    }

    /**
     * Add the given {@link ByteBuf}s on the specific index
     *
     * Be aware that this method does not increase the {@code writerIndex} of the {@link CompositeByteBuf}.
     * If you need to have it increased you need to handle it by your own.
     * <p>
     * {@link ByteBuf#release()} ownership of all {@link ByteBuf} objects in {@code buffers} is transfered to this
     * {@link CompositeByteBuf}.
     * @param cIndex the index on which the {@link ByteBuf} will be added.
     * @param buffers the {@link ByteBuf}s to add.  {@link ByteBuf#release()} ownership of all
     * {@link ByteBuf#release()} ownership of all {@link ByteBuf} objects is transfered to this
     * {@link CompositeByteBuf}.
     */
    public CompositeByteBuf addComponents(int cIndex, Iterable<ByteBuf> buffers) {
        addComponents0(false, cIndex, buffers);
        consolidateIfNeeded();
        return this;
    }

    // TODO optimize further, similar to ByteBuf[] version
    // (difference here is that we don't know *always* know precise size increase in advance,
    // but we do in the most common case that the Iterable is a Collection)
    private int addComponents0(boolean increaseIndex, int cIndex, Iterable<ByteBuf> buffers) {
        if (buffers instanceof ByteBuf) {
            // If buffers also implements ByteBuf (e.g. CompositeByteBuf), it has to go to addComponent(ByteBuf).
            return addComponent0(increaseIndex, cIndex, (ByteBuf) buffers);
        }
        checkNotNull(buffers, "buffers");
        Iterator<ByteBuf> it = buffers.iterator();
        try {
            checkComponentIndex(cIndex);

            // No need for consolidation
            while (it.hasNext()) {
                ByteBuf b = it.next();
                if (b == null) {
                    break;
                }
                cIndex = addComponent0(increaseIndex, cIndex, b) + 1;
                cIndex = Math.min(cIndex, componentCount);
            }
            return cIndex;
        } finally {
            while (it.hasNext()) {
                ReferenceCountUtil.safeRelease(it.next());
            }
        }
    }

    /**
     * This should only be called as last operation from a method as this may adjust the underlying
     * array of components and so affect the index etc.
     */
    private void consolidateIfNeeded() {
        // Consolidate if the number of components will exceed the allowed maximum by the current
        // operation.
        int size = componentCount;
        if (size > maxNumComponents) {
            final int capacity = components[size - 1].endOffset;

            ByteBuf consolidated = allocBuffer(capacity);
            lastAccessed = null;

            // We're not using foreach to avoid creating an iterator.
            for (int i = 0; i < size; i ++) {
                components[i].transferTo(consolidated);
            }

            components[0] = new Component(consolidated, 0, 0, capacity, consolidated);
            removeCompRange(1, size);
        }
    }

    private void checkComponentIndex(int cIndex) {
        ensureAccessible();
        if (cIndex < 0 || cIndex > componentCount) {
            throw new IndexOutOfBoundsException(String.format(
                    "cIndex: %d (expected: >= 0 && <= numComponents(%d))",
                    cIndex, componentCount));
        }
    }

    private void checkComponentIndex(int cIndex, int numComponents) {
        ensureAccessible();
        if (cIndex < 0 || cIndex + numComponents > componentCount) {
            throw new IndexOutOfBoundsException(String.format(
                    "cIndex: %d, numComponents: %d " +
                    "(expected: cIndex >= 0 && cIndex + numComponents <= totalNumComponents(%d))",
                    cIndex, numComponents, componentCount));
        }
    }

    private void updateComponentOffsets(int cIndex) {
        int size = componentCount;
        if (size <= cIndex) {
            return;
        }

        int nextIndex = cIndex > 0 ? components[cIndex].endOffset : 0;
        for (; cIndex < size; cIndex++) {
            Component c = components[cIndex];
            c.reposition(nextIndex);
            nextIndex = c.endOffset;
        }
    }

    /**
     * Remove the {@link ByteBuf} from the given index.
     *
     * @param cIndex the index on from which the {@link ByteBuf} will be remove
     */
    public CompositeByteBuf removeComponent(int cIndex) {
        checkComponentIndex(cIndex);
        Component comp = components[cIndex];
        removeComp(cIndex);
        if (lastAccessed == comp) {
            lastAccessed = null;
        }
        comp.freeIfNecessary();
        if (comp.length() > 0) {
            // Only need to call updateComponentOffsets if the length was > 0
            updateComponentOffsets(cIndex);
        }
        return this;
    }

    /**
     * Remove the number of {@link ByteBuf}s starting from the given index.
     *
     * @param cIndex the index on which the {@link ByteBuf}s will be started to removed
     * @param numComponents the number of components to remove
     */
    public CompositeByteBuf removeComponents(int cIndex, int numComponents) {
        checkComponentIndex(cIndex, numComponents);

        if (numComponents == 0) {
            return this;
        }
        int endIndex = cIndex + numComponents;
        boolean needsUpdate = false;
        for (int i = cIndex; i < endIndex; ++i) {
            Component c = components[i];
            if (c.length() > 0) {
                needsUpdate = true;
            }
            if (lastAccessed == c) {
                lastAccessed = null;
            }
            c.freeIfNecessary();
        }
        removeCompRange(cIndex, endIndex);

        if (needsUpdate) {
            // Only need to call updateComponentOffsets if the length was > 0
            updateComponentOffsets(cIndex);
        }
        return this;
    }

    @Override
    public Iterator<ByteBuf> iterator() {
        ensureAccessible();
        return componentCount == 0 ? EMPTY_ITERATOR : new CompositeByteBufIterator();
    }

    @Override
    protected int forEachByteAsc0(int start, int end, ByteProcessor processor) throws Exception {
        if (end <= start) {
            return -1;
        }
        for (int i = toComponentIndex0(start), length = end - start; length > 0; i++) {
            Component c = components[i];
            if (c.offset == c.endOffset) {
                continue; // empty
            }
            ByteBuf s = c.buf;
            int localStart = c.idx(start);
            int localLength = Math.min(length, c.endOffset - start);
            // avoid additional checks in AbstractByteBuf case
            int result = s instanceof AbstractByteBuf
                ? ((AbstractByteBuf) s).forEachByteAsc0(localStart, localStart + localLength, processor)
                : s.forEachByte(localStart, localLength, processor);
            if (result != -1) {
                return result - c.adjustment;
            }
            start += localLength;
            length -= localLength;
        }
        return -1;
    }

    @Override
    protected int forEachByteDesc0(int rStart, int rEnd, ByteProcessor processor) throws Exception {
        if (rEnd > rStart) { // rStart *and* rEnd are inclusive
            return -1;
        }
        for (int i = toComponentIndex0(rStart), length = 1 + rStart - rEnd; length > 0; i--) {
            Component c = components[i];
            if (c.offset == c.endOffset) {
                continue; // empty
            }
            ByteBuf s = c.buf;
            int localRStart = c.idx(length + rEnd);
            int localLength = Math.min(length, localRStart), localIndex = localRStart - localLength;
            // avoid additional checks in AbstractByteBuf case
            int result = s instanceof AbstractByteBuf
                ? ((AbstractByteBuf) s).forEachByteDesc0(localRStart - 1, localIndex, processor)
                : s.forEachByteDesc(localIndex, localLength, processor);

            if (result != -1) {
                return result - c.adjustment;
            }
            length -= localLength;
        }
        return -1;
    }

    /**
     * Same with {@link #slice(int, int)} except that this method returns a list.
     */
    public List<ByteBuf> decompose(int offset, int length) {
        checkIndex(offset, length);
        if (length == 0) {
            return Collections.emptyList();
        }

        int componentId = toComponentIndex0(offset);
        int bytesToSlice = length;
        // The first component
        Component firstC = components[componentId];

        ByteBuf slice = firstC.buf.slice(firstC.idx(offset), Math.min(firstC.endOffset - offset, bytesToSlice));
        bytesToSlice -= slice.readableBytes();

        if (bytesToSlice == 0) {
            return Collections.singletonList(slice);
        }

        List<ByteBuf> sliceList = new ArrayList<ByteBuf>(componentCount - componentId);
        sliceList.add(slice);

        // Add all the slices until there is nothing more left and then return the List.
        do {
            Component component = components[++componentId];
            slice = component.buf.slice(component.idx(component.offset), Math.min(component.length(), bytesToSlice));
            bytesToSlice -= slice.readableBytes();
            sliceList.add(slice);
        } while (bytesToSlice > 0);

        return sliceList;
    }

    @Override
    public boolean isDirect() {
        int size = componentCount;
        if (size == 0) {
            return false;
        }
        for (int i = 0; i < size; i++) {
           if (!components[i].buf.isDirect()) {
               return false;
           }
        }
        return true;
    }

    @Override
    public boolean hasArray() {
        switch (componentCount) {
        case 0:
            return true;
        case 1:
            return components[0].buf.hasArray();
        default:
            return false;
        }
    }

    @Override
    public byte[] array() {
        switch (componentCount) {
        case 0:
            return EmptyArrays.EMPTY_BYTES;
        case 1:
            return components[0].buf.array();
        default:
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public int arrayOffset() {
        switch (componentCount) {
        case 0:
            return 0;
        case 1:
            Component c = components[0];
            return c.idx(c.buf.arrayOffset());
        default:
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public boolean hasMemoryAddress() {
        switch (componentCount) {
        case 0:
            return Unpooled.EMPTY_BUFFER.hasMemoryAddress();
        case 1:
            return components[0].buf.hasMemoryAddress();
        default:
            return false;
        }
    }

    @Override
    public long memoryAddress() {
        switch (componentCount) {
        case 0:
            return Unpooled.EMPTY_BUFFER.memoryAddress();
        case 1:
            Component c = components[0];
            return c.buf.memoryAddress() + c.adjustment;
        default:
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public int capacity() {
        int size = componentCount;
        return size > 0 ? components[size - 1].endOffset : 0;
    }

    @Override
    public CompositeByteBuf capacity(int newCapacity) {
        checkNewCapacity(newCapacity);

        final int size = componentCount, oldCapacity = capacity();
        if (newCapacity > oldCapacity) {
            final int paddingLength = newCapacity - oldCapacity;
            ByteBuf padding = allocBuffer(paddingLength).setIndex(0, paddingLength);
            addComponent0(false, size, padding);
            if (componentCount >= maxNumComponents) {
                // FIXME: No need to create a padding buffer and consolidate.
                // Just create a big single buffer and put the current content there.
                consolidateIfNeeded();
            }
        } else if (newCapacity < oldCapacity) {
            int i = size - 1;
            for (int bytesToTrim = oldCapacity - newCapacity; i >= 0; i--) {
                Component c = components[i];
                final int cLength = c.length();
                if (bytesToTrim < cLength) {
                    // Trim the last component
                    c.endOffset -= bytesToTrim;
                    c.slice = null;
                    break;
                }
                c.freeIfNecessary();
                bytesToTrim -= cLength;
            }
            removeCompRange(i + 1, size);

            if (readerIndex() > newCapacity) {
                setIndex(newCapacity, newCapacity);
            } else if (writerIndex() > newCapacity) {
                writerIndex(newCapacity);
            }
        }
        return this;
    }

    @Override
    public ByteBufAllocator alloc() {
        return alloc;
    }

    @Override
    public ByteOrder order() {
        return ByteOrder.BIG_ENDIAN;
    }

    /**
     * Return the current number of {@link ByteBuf}'s that are composed in this instance
     */
    public int numComponents() {
        return componentCount;
    }

    /**
     * Return the max number of {@link ByteBuf}'s that are composed in this instance
     */
    public int maxNumComponents() {
        return maxNumComponents;
    }

    /**
     * Return the index for the given offset
     */
    public int toComponentIndex(int offset) {
        checkIndex(offset);
        return toComponentIndex(offset);
    }

    private int toComponentIndex0(int offset) {
        int size = componentCount;
        if (offset == 0) { // fast-path zero offset
            for (int i = 0; i < size; i++) {
                if (components[i].endOffset > 0) {
                    return i;
                }
            }
        }
        for (int low = 0, high = size; low <= high;) {
            int mid = low + high >>> 1;
            Component c = components[mid];
            if (offset >= c.endOffset) {
                low = mid + 1;
            } else if (offset < c.offset) {
                high = mid - 1;
            } else {
                return mid;
            }
        }

        throw new Error("should not reach here");
    }

    public int toByteIndex(int cIndex) {
        checkComponentIndex(cIndex);
        return components[cIndex].offset;
    }

    @Override
    public byte getByte(int index) {
        Component c = findComponent(index);
        return c.buf.getByte(c.idx(index));
    }

    @Override
    protected byte _getByte(int index) {
        Component c = findComponent0(index);
        return c.buf.getByte(c.idx(index));
    }

    @Override
    protected short _getShort(int index) {
        Component c = findComponent0(index);
        if (index + 2 <= c.endOffset) {
            return c.buf.getShort(c.idx(index));
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            return (short) ((_getByte(index) & 0xff) << 8 | _getByte(index + 1) & 0xff);
        } else {
            return (short) (_getByte(index) & 0xff | (_getByte(index + 1) & 0xff) << 8);
        }
    }

    @Override
    protected short _getShortLE(int index) {
        Component c = findComponent0(index);
        if (index + 2 <= c.endOffset) {
            return c.buf.getShortLE(c.idx(index));
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            return (short) (_getByte(index) & 0xff | (_getByte(index + 1) & 0xff) << 8);
        } else {
            return (short) ((_getByte(index) & 0xff) << 8 | _getByte(index + 1) & 0xff);
        }
    }

    @Override
    protected int _getUnsignedMedium(int index) {
        Component c = findComponent0(index);
        if (index + 3 <= c.endOffset) {
            return c.buf.getUnsignedMedium(c.idx(index));
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            return (_getShort(index) & 0xffff) << 8 | _getByte(index + 2) & 0xff;
        } else {
            return _getShort(index) & 0xFFFF | (_getByte(index + 2) & 0xFF) << 16;
        }
    }

    @Override
    protected int _getUnsignedMediumLE(int index) {
        Component c = findComponent0(index);
        if (index + 3 <= c.endOffset) {
            return c.buf.getUnsignedMediumLE(c.idx(index));
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            return _getShortLE(index) & 0xffff | (_getByte(index + 2) & 0xff) << 16;
        } else {
            return (_getShortLE(index) & 0xffff) << 8 | _getByte(index + 2) & 0xff;
        }
    }

    @Override
    protected int _getInt(int index) {
        Component c = findComponent0(index);
        if (index + 4 <= c.endOffset) {
            return c.buf.getInt(c.idx(index));
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            return (_getShort(index) & 0xffff) << 16 | _getShort(index + 2) & 0xffff;
        } else {
            return _getShort(index) & 0xFFFF | (_getShort(index + 2) & 0xFFFF) << 16;
        }
    }

    @Override
    protected int _getIntLE(int index) {
        Component c = findComponent0(index);
        if (index + 4 <= c.endOffset) {
            return c.buf.getIntLE(c.idx(index));
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            return _getShortLE(index) & 0xffff | (_getShortLE(index + 2) & 0xffff) << 16;
        } else {
            return (_getShortLE(index) & 0xffff) << 16 | _getShortLE(index + 2) & 0xffff;
        }
    }

    @Override
    protected long _getLong(int index) {
        Component c = findComponent0(index);
        if (index + 8 <= c.endOffset) {
            return c.buf.getLong(c.idx(index));
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            return (_getInt(index) & 0xffffffffL) << 32 | _getInt(index + 4) & 0xffffffffL;
        } else {
            return _getInt(index) & 0xFFFFFFFFL | (_getInt(index + 4) & 0xFFFFFFFFL) << 32;
        }
    }

    @Override
    protected long _getLongLE(int index) {
        Component c = findComponent0(index);
        if (index + 8 <= c.endOffset) {
            return c.buf.getLongLE(c.idx(index));
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            return _getIntLE(index) & 0xffffffffL | (_getIntLE(index + 4) & 0xffffffffL) << 32;
        } else {
            return (_getIntLE(index) & 0xffffffffL) << 32 | _getIntLE(index + 4) & 0xffffffffL;
        }
    }

    @Override
    public CompositeByteBuf getBytes(int index, byte[] dst, int dstIndex, int length) {
        checkDstIndex(index, length, dstIndex, dst.length);
        if (length == 0) {
            return this;
        }

        int i = toComponentIndex0(index);
        while (length > 0) {
            Component c = components[i];
            int localLength = Math.min(length, c.endOffset - index);
            c.buf.getBytes(c.idx(index), dst, dstIndex, localLength);
            index += localLength;
            dstIndex += localLength;
            length -= localLength;
            i ++;
        }
        return this;
    }

    @Override
    public CompositeByteBuf getBytes(int index, ByteBuffer dst) {
        int limit = dst.limit();
        int length = dst.remaining();

        checkIndex(index, length);
        if (length == 0) {
            return this;
        }

        int i = toComponentIndex0(index);
        try {
            while (length > 0) {
                Component c = components[i];
                int localLength = Math.min(length, c.endOffset - index);
                dst.limit(dst.position() + localLength);
                c.buf.getBytes(c.idx(index), dst);
                index += localLength;
                length -= localLength;
                i ++;
            }
        } finally {
            dst.limit(limit);
        }
        return this;
    }

    @Override
    public CompositeByteBuf getBytes(int index, ByteBuf dst, int dstIndex, int length) {
        checkDstIndex(index, length, dstIndex, dst.capacity());
        if (length == 0) {
            return this;
        }

        int i = toComponentIndex0(index);
        while (length > 0) {
            Component c = components[i];
            int localLength = Math.min(length, c.endOffset - index);
            c.buf.getBytes(c.idx(index), dst, dstIndex, localLength);
            index += localLength;
            dstIndex += localLength;
            length -= localLength;
            i ++;
        }
        return this;
    }

    @Override
    public int getBytes(int index, GatheringByteChannel out, int length)
            throws IOException {
        int count = nioBufferCount();
        if (count == 1) {
            return out.write(internalNioBuffer(index, length));
        } else {
            long writtenBytes = out.write(nioBuffers(index, length));
            if (writtenBytes > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            } else {
                return (int) writtenBytes;
            }
        }
    }

    @Override
    public int getBytes(int index, FileChannel out, long position, int length)
            throws IOException {
        int count = nioBufferCount();
        if (count == 1) {
            return out.write(internalNioBuffer(index, length), position);
        } else {
            long writtenBytes = 0;
            for (ByteBuffer buf : nioBuffers(index, length)) {
                writtenBytes += out.write(buf, position + writtenBytes);
            }
            if (writtenBytes > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            return (int) writtenBytes;
        }
    }

    @Override
    public CompositeByteBuf getBytes(int index, OutputStream out, int length) throws IOException {
        checkIndex(index, length);
        if (length == 0) {
            return this;
        }

        int i = toComponentIndex0(index);
        while (length > 0) {
            Component c = components[i];
            int localLength = Math.min(length, c.endOffset - index);
            c.buf.getBytes(c.idx(index), out, localLength);
            index += localLength;
            length -= localLength;
            i ++;
        }
        return this;
    }

    @Override
    public CompositeByteBuf setByte(int index, int value) {
        Component c = findComponent(index);
        c.buf.setByte(c.idx(index), value);
        return this;
    }

    @Override
    protected void _setByte(int index, int value) {
        Component c = findComponent0(index);
        c.buf.setByte(c.idx(index), value);
    }

    @Override
    public CompositeByteBuf setShort(int index, int value) {
        super.setShort(index, value);
        return this;
    }

    @Override
    protected void _setShort(int index, int value) {
        Component c = findComponent0(index);
        if (index + 2 <= c.endOffset) {
            c.buf.setShort(c.idx(index), value);
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            _setByte(index, (byte) (value >>> 8));
            _setByte(index + 1, (byte) value);
        } else {
            _setByte(index, (byte) value);
            _setByte(index + 1, (byte) (value >>> 8));
        }
    }

    @Override
    protected void _setShortLE(int index, int value) {
        Component c = findComponent0(index);
        if (index + 2 <= c.endOffset) {
            c.buf.setShortLE(c.idx(index), value);
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            _setByte(index, (byte) value);
            _setByte(index + 1, (byte) (value >>> 8));
        } else {
            _setByte(index, (byte) (value >>> 8));
            _setByte(index + 1, (byte) value);
        }
    }

    @Override
    public CompositeByteBuf setMedium(int index, int value) {
        super.setMedium(index, value);
        return this;
    }

    @Override
    protected void _setMedium(int index, int value) {
        Component c = findComponent0(index);
        if (index + 3 <= c.endOffset) {
            c.buf.setMedium(c.idx(index), value);
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            _setShort(index, (short) (value >> 8));
            _setByte(index + 2, (byte) value);
        } else {
            _setShort(index, (short) value);
            _setByte(index + 2, (byte) (value >>> 16));
        }
    }

    @Override
    protected void _setMediumLE(int index, int value) {
        Component c = findComponent0(index);
        if (index + 3 <= c.endOffset) {
            c.buf.setMediumLE(c.idx(index), value);
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            _setShortLE(index, (short) value);
            _setByte(index + 2, (byte) (value >>> 16));
        } else {
            _setShortLE(index, (short) (value >> 8));
            _setByte(index + 2, (byte) value);
        }
    }

    @Override
    public CompositeByteBuf setInt(int index, int value) {
        super.setInt(index, value);
        return this;
    }

    @Override
    protected void _setInt(int index, int value) {
        Component c = findComponent0(index);
        if (index + 4 <= c.endOffset) {
            c.buf.setInt(c.idx(index), value);
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            _setShort(index, (short) (value >>> 16));
            _setShort(index + 2, (short) value);
        } else {
            _setShort(index, (short) value);
            _setShort(index + 2, (short) (value >>> 16));
        }
    }

    @Override
    protected void _setIntLE(int index, int value) {
        Component c = findComponent0(index);
        if (index + 4 <= c.endOffset) {
            c.buf.setIntLE(c.idx(index), value);
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            _setShortLE(index, (short) value);
            _setShortLE(index + 2, (short) (value >>> 16));
        } else {
            _setShortLE(index, (short) (value >>> 16));
            _setShortLE(index + 2, (short) value);
        }
    }

    @Override
    public CompositeByteBuf setLong(int index, long value) {
        super.setLong(index, value);
        return this;
    }

    @Override
    protected void _setLong(int index, long value) {
        Component c = findComponent0(index);
        if (index + 8 <= c.endOffset) {
            c.buf.setLong(c.idx(index), value);
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            _setInt(index, (int) (value >>> 32));
            _setInt(index + 4, (int) value);
        } else {
            _setInt(index, (int) value);
            _setInt(index + 4, (int) (value >>> 32));
        }
    }

    @Override
    protected void _setLongLE(int index, long value) {
        Component c = findComponent0(index);
        if (index + 8 <= c.endOffset) {
            c.buf.setLongLE(c.idx(index), value);
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            _setIntLE(index, (int) value);
            _setIntLE(index + 4, (int) (value >>> 32));
        } else {
            _setIntLE(index, (int) (value >>> 32));
            _setIntLE(index + 4, (int) value);
        }
    }

    @Override
    public CompositeByteBuf setBytes(int index, byte[] src, int srcIndex, int length) {
        checkSrcIndex(index, length, srcIndex, src.length);
        if (length == 0) {
            return this;
        }

        int i = toComponentIndex0(index);
        while (length > 0) {
            Component c = components[i];
            int localLength = Math.min(length, c.endOffset - index);
            c.buf.setBytes(c.idx(index), src, srcIndex, localLength);
            index += localLength;
            srcIndex += localLength;
            length -= localLength;
            i ++;
        }
        return this;
    }

    @Override
    public CompositeByteBuf setBytes(int index, ByteBuffer src) {
        int limit = src.limit();
        int length = src.remaining();

        checkIndex(index, length);
        if (length == 0) {
            return this;
        }

        int i = toComponentIndex0(index);
        try {
            while (length > 0) {
                Component c = components[i];
                int localLength = Math.min(length, c.endOffset - index);
                src.limit(src.position() + localLength);
                c.buf.setBytes(c.idx(index), src);
                index += localLength;
                length -= localLength;
                i ++;
            }
        } finally {
            src.limit(limit);
        }
        return this;
    }

    @Override
    public CompositeByteBuf setBytes(int index, ByteBuf src, int srcIndex, int length) {
        checkSrcIndex(index, length, srcIndex, src.capacity());
        if (length == 0) {
            return this;
        }

        int i = toComponentIndex0(index);
        while (length > 0) {
            Component c = components[i];
            int localLength = Math.min(length, c.endOffset - index);
            c.buf.setBytes(c.idx(index), src, srcIndex, localLength);
            index += localLength;
            srcIndex += localLength;
            length -= localLength;
            i ++;
        }
        return this;
    }

    @Override
    public int setBytes(int index, InputStream in, int length) throws IOException {
        checkIndex(index, length);
        if (length == 0) {
            return in.read(EmptyArrays.EMPTY_BYTES);
        }

        int i = toComponentIndex0(index);
        int readBytes = 0;

        do {
            Component c = components[i];
            int localLength = Math.min(length, c.endOffset - index);
            if (localLength == 0) {
                // Skip empty buffer
                i++;
                continue;
            }
            int localReadBytes = c.buf.setBytes(c.idx(index), in, localLength);
            if (localReadBytes < 0) {
                if (readBytes == 0) {
                    return -1;
                } else {
                    break;
                }
            }

            if (localReadBytes == localLength) {
                index += localLength;
                length -= localLength;
                readBytes += localLength;
                i ++;
            } else {
                index += localReadBytes;
                length -= localReadBytes;
                readBytes += localReadBytes;
            }
        } while (length > 0);

        return readBytes;
    }

    @Override
    public int setBytes(int index, ScatteringByteChannel in, int length) throws IOException {
        checkIndex(index, length);
        if (length == 0) {
            return in.read(EMPTY_NIO_BUFFER);
        }

        int i = toComponentIndex0(index);
        int readBytes = 0;
        do {
            Component c = components[i];
            int localLength = Math.min(length, c.endOffset - index);
            if (localLength == 0) {
                // Skip empty buffer
                i++;
                continue;
            }
            int localReadBytes = c.buf.setBytes(c.idx(index), in, localLength);

            if (localReadBytes == 0) {
                break;
            }

            if (localReadBytes < 0) {
                if (readBytes == 0) {
                    return -1;
                } else {
                    break;
                }
            }

            if (localReadBytes == localLength) {
                index += localLength;
                length -= localLength;
                readBytes += localLength;
                i ++;
            } else {
                index += localReadBytes;
                length -= localReadBytes;
                readBytes += localReadBytes;
            }
        } while (length > 0);

        return readBytes;
    }

    @Override
    public int setBytes(int index, FileChannel in, long position, int length) throws IOException {
        checkIndex(index, length);
        if (length == 0) {
            return in.read(EMPTY_NIO_BUFFER, position);
        }

        int i = toComponentIndex0(index);
        int readBytes = 0;
        do {
            Component c = components[i];
            int localLength = Math.min(length, c.endOffset - index);
            if (localLength == 0) {
                // Skip empty buffer
                i++;
                continue;
            }
            int localReadBytes = c.buf.setBytes(c.idx(index), in, position + readBytes, localLength);

            if (localReadBytes == 0) {
                break;
            }

            if (localReadBytes < 0) {
                if (readBytes == 0) {
                    return -1;
                } else {
                    break;
                }
            }

            if (localReadBytes == localLength) {
                index += localLength;
                length -= localLength;
                readBytes += localLength;
                i ++;
            } else {
                index += localReadBytes;
                length -= localReadBytes;
                readBytes += localReadBytes;
            }
        } while (length > 0);

        return readBytes;
    }

    @Override
    public ByteBuf copy(int index, int length) {
        checkIndex(index, length);
        ByteBuf dst = allocBuffer(length);
        if (length != 0) {
            copyTo(index, length, toComponentIndex0(index), dst);
        }
        return dst;
    }

    private void copyTo(int index, int length, int componentId, ByteBuf dst) {
        int dstIndex = 0;
        int i = componentId;

        while (length > 0) {
            Component c = components[i];
            int localLength = Math.min(length, c.endOffset - index);
            c.buf.getBytes(c.idx(index), dst, dstIndex, localLength);
            index += localLength;
            dstIndex += localLength;
            length -= localLength;
            i ++;
        }

        dst.writerIndex(dst.capacity());
    }

    /**
     * Return the {@link ByteBuf} on the specified index
     *
     * @param cIndex the index for which the {@link ByteBuf} should be returned
     * @return buf the {@link ByteBuf} on the specified index
     */
    public ByteBuf component(int cIndex) {
        checkComponentIndex(cIndex);
        return components[cIndex].duplicate();
    }

    /**
     * Return the {@link ByteBuf} on the specified index
     *
     * @param offset the offset for which the {@link ByteBuf} should be returned
     * @return the {@link ByteBuf} on the specified index
     */
    public ByteBuf componentAtOffset(int offset) {
        return findComponent(offset).duplicate();
    }

    /**
     * Return the internal {@link ByteBuf} on the specified index. Note that updating the indexes of the returned
     * buffer will lead to an undefined behavior of this buffer.
     *
     * @param cIndex the index for which the {@link ByteBuf} should be returned
     */
    public ByteBuf internalComponent(int cIndex) {
        checkComponentIndex(cIndex);
        return components[cIndex].slice();
    }

    /**
     * Return the internal {@link ByteBuf} on the specified offset. Note that updating the indexes of the returned
     * buffer will lead to an undefined behavior of this buffer.
     *
     * @param offset the offset for which the {@link ByteBuf} should be returned
     */
    public ByteBuf internalComponentAtOffset(int offset) {
        return findComponent(offset).slice();
    }

    // weak cache - check it first when looking for component
    private Component lastAccessed;

    private Component findComponent(int offset) {
        Component la = lastAccessed;
        if (la != null && offset >= la.offset && offset < la.endOffset) {
           ensureAccessible();
           return la;
        }
        checkIndex(offset);
        return findIt(offset);
    }

    private Component findComponent0(int offset) {
        Component la = lastAccessed;
        if (la != null && offset >= la.offset && offset < la.endOffset) {
           return la;
        }
        return findIt(offset);
    }

    private Component findIt(int offset) {
        for (int low = 0, high = componentCount; low <= high;) {
            int mid = low + high >>> 1;
            Component c = components[mid];
            if (offset >= c.endOffset) {
                low = mid + 1;
            } else if (offset < c.offset) {
                high = mid - 1;
            } else {
                lastAccessed = c;
                return c;
            }
        }

        throw new Error("should not reach here");
    }

    @Override
    public int nioBufferCount() {
        int size = componentCount;
        switch (size) {
        case 0:
            return 1;
        case 1:
            return components[0].buf.nioBufferCount();
        default:
            int count = 0;
            for (int i = 0; i < size; i++) {
                count += components[i].buf.nioBufferCount();
            }
            return count;
        }
    }

    @Override
    public ByteBuffer internalNioBuffer(int index, int length) {
        switch (componentCount) {
        case 0:
            return EMPTY_NIO_BUFFER;
        case 1:
            Component c = components[0];
            return c.buf.internalNioBuffer(c.idx(index), length);
        default:
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public ByteBuffer nioBuffer(int index, int length) {
        checkIndex(index, length);

        switch (componentCount) {
        case 0:
            return EMPTY_NIO_BUFFER;
        case 1:
            Component c = components[0];
            ByteBuf buf = c.buf;
            if (buf.nioBufferCount() == 1) {
                return buf.nioBuffer(c.idx(index), length);
            }
        }

        ByteBuffer[] buffers = nioBuffers(index, length);

        if (buffers.length == 1) {
            return buffers[0].duplicate();
        }

        ByteBuffer merged = ByteBuffer.allocate(length).order(order());
        for (ByteBuffer buf: buffers) {
            merged.put(buf);
        }

        merged.flip();
        return merged;
    }

    @Override
    public ByteBuffer[] nioBuffers(int index, int length) {
        checkIndex(index, length);
        if (length == 0) {
            return new ByteBuffer[] { EMPTY_NIO_BUFFER };
        }

        RecyclableArrayList buffers = RecyclableArrayList.newInstance(componentCount);
        try {
            int i = toComponentIndex0(index);
            while (length > 0) {
                Component c = components[i];
                ByteBuf s = c.buf;
                int localLength = Math.min(length, c.endOffset - index);
                switch (s.nioBufferCount()) {
                case 0:
                    throw new UnsupportedOperationException();
                case 1:
                    buffers.add(s.nioBuffer(c.idx(index), localLength));
                    break;
                default:
                    Collections.addAll(buffers, s.nioBuffers(c.idx(index), localLength));
                }

                index += localLength;
                length -= localLength;
                i ++;
            }

            return buffers.toArray(new ByteBuffer[0]);
        } finally {
            buffers.recycle();
        }
    }

    /**
     * Consolidate the composed {@link ByteBuf}s
     */
    public CompositeByteBuf consolidate() {
        ensureAccessible();
        final int numComponents = componentCount;
        if (numComponents <= 1) {
            return this;
        }

        final int capacity = components[numComponents - 1].endOffset;
        final ByteBuf consolidated = allocBuffer(capacity);

        for (int i = 0; i < numComponents; i ++) {
            components[i].transferTo(consolidated);
        }
        lastAccessed = null;
        components[0] = new Component(consolidated, 0, 0, capacity, consolidated);
        removeCompRange(1, numComponents);
        return this;
    }

    /**
     * Consolidate the composed {@link ByteBuf}s
     *
     * @param cIndex the index on which to start to compose
     * @param numComponents the number of components to compose
     */
    public CompositeByteBuf consolidate(int cIndex, int numComponents) {
        checkComponentIndex(cIndex, numComponents);
        if (numComponents <= 1) {
            return this;
        }

        final int endCIndex = cIndex + numComponents;
        final Component last = components[endCIndex - 1];
        final int capacity = last.endOffset - components[cIndex].offset;
        final ByteBuf consolidated = allocBuffer(capacity);

        for (int i = cIndex; i < endCIndex; i ++) {
            components[i].transferTo(consolidated);
        }
        lastAccessed = null;
        removeCompRange(cIndex + 1, endCIndex);
        components[cIndex] = new Component(consolidated, 0, 0, capacity, consolidated);
        updateComponentOffsets(cIndex);
        return this;
    }

    /**
     * Discard all {@link ByteBuf}s which are read.
     */
    public CompositeByteBuf discardReadComponents() {
        ensureAccessible();
        final int readerIndex = readerIndex();
        if (readerIndex == 0) {
            return this;
        }

        // Discard everything if (readerIndex = writerIndex = capacity).
        int writerIndex = writerIndex();
        if (readerIndex == writerIndex && writerIndex == capacity()) {
            for (int i = 0, size = componentCount; i < size; i++) {
                components[i].freeIfNecessary();
            }
            lastAccessed = null;
            clearComps();
            setIndex(0, 0);
            adjustMarkers(readerIndex);
            return this;
        }

        // Remove read components.
        int firstComponentId = toComponentIndex0(readerIndex);
        for (int i = 0; i < firstComponentId; i ++) {
            components[i].freeIfNecessary();
        }
        lastAccessed = null;
        removeCompRange(0, firstComponentId);

        // Update indexes and markers.
        Component first = components[0];
        int offset = first.offset;
        updateComponentOffsets(0);
        setIndex(readerIndex - offset, writerIndex - offset);
        adjustMarkers(offset);
        return this;
    }

    @Override
    public CompositeByteBuf discardReadBytes() {
        ensureAccessible();
        final int readerIndex = readerIndex();
        if (readerIndex == 0) {
            return this;
        }

        // Discard everything if (readerIndex = writerIndex = capacity).
        int writerIndex = writerIndex();
        if (readerIndex == writerIndex && writerIndex == capacity()) {
            for (int i = 0, size = componentCount; i < size; i++) {
                components[i].freeIfNecessary();
            }
            lastAccessed = null;
            clearComps();
            setIndex(0, 0);
            adjustMarkers(readerIndex);
            return this;
        }

        // Remove read components.
        int firstComponentId = toComponentIndex0(readerIndex);
        for (int i = 0; i < firstComponentId; i ++) {
            Component c = components[i];
            c.freeIfNecessary();
            if (lastAccessed == c) {
                lastAccessed = null;
            }
        }

        // Remove or replace the first readable component with a new slice.
        Component c = components[firstComponentId];
        if (readerIndex == c.endOffset) {
            // new slice would be empty, so remove instead
            c.freeIfNecessary();
            if (lastAccessed == c) {
                lastAccessed = null;
            }
            firstComponentId++;
        } else {
            c.offset = 0;
            c.endOffset -= readerIndex;
            c.adjustment += readerIndex;
            c.slice = null;
        }

        removeCompRange(0, firstComponentId);

        // Update indexes and markers.
        updateComponentOffsets(0);
        setIndex(0, writerIndex - readerIndex);
        adjustMarkers(readerIndex);
        return this;
    }

    private ByteBuf allocBuffer(int capacity) {
        return direct ? alloc().directBuffer(capacity) : alloc().heapBuffer(capacity);
    }

    @Override
    public String toString() {
        String result = super.toString();
        result = result.substring(0, result.length() - 1);
        return result + ", components=" + componentCount + ')';
    }

    private static final class Component {
        final ByteBuf buf;
        int adjustment;
        int offset;
        int endOffset;

        private ByteBuf slice; // cached slice, may be null

        Component(ByteBuf buf, int srcOffset, int offset, int len, ByteBuf slice) {
            this.buf = buf;
            this.offset = offset;
            this.endOffset = offset + len;
            this.adjustment = srcOffset - offset;
            this.slice = slice;
        }

        int idx(int index) {
            return index + adjustment;
        }

        int length() {
            return endOffset - offset;
        }

        void reposition(int newOffset) {
            int move = newOffset - offset;
            endOffset += move;
            adjustment -= move;
            offset = newOffset;
        }

        // copy then release
        void transferTo(ByteBuf dst) {
            dst.writeBytes(buf, idx(offset), length());
            freeIfNecessary();
        }

        ByteBuf slice() {
            return slice != null ? slice : (slice = buf.slice(idx(offset), length()));
        }

        ByteBuf duplicate() {
            return buf.duplicate().setIndex(idx(offset), idx(endOffset));
        }

        void freeIfNecessary() {
            buf.release(); // We should not get a NPE here. If so, it must be a bug.
            slice = null;
        }
    }

    @Override
    public CompositeByteBuf readerIndex(int readerIndex) {
        super.readerIndex(readerIndex);
        return this;
    }

    @Override
    public CompositeByteBuf writerIndex(int writerIndex) {
        super.writerIndex(writerIndex);
        return this;
    }

    @Override
    public CompositeByteBuf setIndex(int readerIndex, int writerIndex) {
        super.setIndex(readerIndex, writerIndex);
        return this;
    }

    @Override
    public CompositeByteBuf clear() {
        super.clear();
        return this;
    }

    @Override
    public CompositeByteBuf markReaderIndex() {
        super.markReaderIndex();
        return this;
    }

    @Override
    public CompositeByteBuf resetReaderIndex() {
        super.resetReaderIndex();
        return this;
    }

    @Override
    public CompositeByteBuf markWriterIndex() {
        super.markWriterIndex();
        return this;
    }

    @Override
    public CompositeByteBuf resetWriterIndex() {
        super.resetWriterIndex();
        return this;
    }

    @Override
    public CompositeByteBuf ensureWritable(int minWritableBytes) {
        super.ensureWritable(minWritableBytes);
        return this;
    }

    @Override
    public CompositeByteBuf getBytes(int index, ByteBuf dst) {
        return getBytes(index, dst, dst.writableBytes());
    }

    @Override
    public CompositeByteBuf getBytes(int index, ByteBuf dst, int length) {
        getBytes(index, dst, dst.writerIndex(), length);
        dst.writerIndex(dst.writerIndex() + length);
        return this;
    }

    @Override
    public CompositeByteBuf getBytes(int index, byte[] dst) {
        return getBytes(index, dst, 0, dst.length);
    }

    @Override
    public CompositeByteBuf setBoolean(int index, boolean value) {
        return setByte(index, value? 1 : 0);
    }

    @Override
    public CompositeByteBuf setChar(int index, int value) {
        return setShort(index, value);
    }

    @Override
    public CompositeByteBuf setFloat(int index, float value) {
        return setInt(index, Float.floatToRawIntBits(value));
    }

    @Override
    public CompositeByteBuf setDouble(int index, double value) {
        return setLong(index, Double.doubleToRawLongBits(value));
    }

    @Override
    public CompositeByteBuf setBytes(int index, ByteBuf src) {
        super.setBytes(index, src, src.readableBytes());
        return this;
    }

    @Override
    public CompositeByteBuf setBytes(int index, ByteBuf src, int length) {
        super.setBytes(index, src, length);
        return this;
    }

    @Override
    public CompositeByteBuf setBytes(int index, byte[] src) {
        return setBytes(index, src, 0, src.length);
    }

    @Override
    public CompositeByteBuf setZero(int index, int length) {
        super.setZero(index, length);
        return this;
    }

    @Override
    public CompositeByteBuf readBytes(ByteBuf dst) {
        super.readBytes(dst, dst.writableBytes());
        return this;
    }

    @Override
    public CompositeByteBuf readBytes(ByteBuf dst, int length) {
        super.readBytes(dst, length);
        return this;
    }

    @Override
    public CompositeByteBuf readBytes(ByteBuf dst, int dstIndex, int length) {
        super.readBytes(dst, dstIndex, length);
        return this;
    }

    @Override
    public CompositeByteBuf readBytes(byte[] dst) {
        super.readBytes(dst, 0, dst.length);
        return this;
    }

    @Override
    public CompositeByteBuf readBytes(byte[] dst, int dstIndex, int length) {
        super.readBytes(dst, dstIndex, length);
        return this;
    }

    @Override
    public CompositeByteBuf readBytes(ByteBuffer dst) {
        super.readBytes(dst);
        return this;
    }

    @Override
    public CompositeByteBuf readBytes(OutputStream out, int length) throws IOException {
        super.readBytes(out, length);
        return this;
    }

    @Override
    public CompositeByteBuf skipBytes(int length) {
        super.skipBytes(length);
        return this;
    }

    @Override
    public CompositeByteBuf writeBoolean(boolean value) {
        writeByte(value ? 1 : 0);
        return this;
    }

    @Override
    public CompositeByteBuf writeByte(int value) {
        ensureWritable0(1);
        _setByte(writerIndex++, value);
        return this;
    }

    @Override
    public CompositeByteBuf writeShort(int value) {
        super.writeShort(value);
        return this;
    }

    @Override
    public CompositeByteBuf writeMedium(int value) {
        super.writeMedium(value);
        return this;
    }

    @Override
    public CompositeByteBuf writeInt(int value) {
        super.writeInt(value);
        return this;
    }

    @Override
    public CompositeByteBuf writeLong(long value) {
        super.writeLong(value);
        return this;
    }

    @Override
    public CompositeByteBuf writeChar(int value) {
        super.writeShort(value);
        return this;
    }

    @Override
    public CompositeByteBuf writeFloat(float value) {
        super.writeInt(Float.floatToRawIntBits(value));
        return this;
    }

    @Override
    public CompositeByteBuf writeDouble(double value) {
        super.writeLong(Double.doubleToRawLongBits(value));
        return this;
    }

    @Override
    public CompositeByteBuf writeBytes(ByteBuf src) {
        super.writeBytes(src, src.readableBytes());
        return this;
    }

    @Override
    public CompositeByteBuf writeBytes(ByteBuf src, int length) {
        super.writeBytes(src, length);
        return this;
    }

    @Override
    public CompositeByteBuf writeBytes(ByteBuf src, int srcIndex, int length) {
        super.writeBytes(src, srcIndex, length);
        return this;
    }

    @Override
    public CompositeByteBuf writeBytes(byte[] src) {
        writeBytes(src, 0, src.length);
        return this;
    }

    @Override
    public CompositeByteBuf writeBytes(byte[] src, int srcIndex, int length) {
        super.writeBytes(src, srcIndex, length);
        return this;
    }

    @Override
    public CompositeByteBuf writeBytes(ByteBuffer src) {
        super.writeBytes(src);
        return this;
    }

    @Override
    public CompositeByteBuf writeZero(int length) {
        super.writeZero(length);
        return this;
    }

    @Override
    public CompositeByteBuf retain(int increment) {
        super.retain(increment);
        return this;
    }

    @Override
    public CompositeByteBuf retain() {
        super.retain();
        return this;
    }

    @Override
    public CompositeByteBuf touch() {
        return this;
    }

    @Override
    public CompositeByteBuf touch(Object hint) {
        return this;
    }

    @Override
    public ByteBuffer[] nioBuffers() {
        return nioBuffers(readerIndex(), readableBytes());
    }

    @Override
    public CompositeByteBuf discardSomeReadBytes() {
        return discardReadComponents();
    }

    @Override
    protected void deallocate() {
        if (freed) {
            return;
        }

        freed = true;
        // We're not using foreach to avoid creating an iterator.
        // see https://github.com/netty/netty/issues/2642
        for (int i = 0, size = componentCount; i < size; i++) {
            components[i].freeIfNecessary();
        }
    }

    @Override
    public ByteBuf unwrap() {
        return null;
    }

    private final class CompositeByteBufIterator implements Iterator<ByteBuf> {
        private final int size = numComponents();
        private int index;

        @Override
        public boolean hasNext() {
            return size > index;
        }

        @Override
        public ByteBuf next() {
            if (size != numComponents()) {
                throw new ConcurrentModificationException();
            }
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            try {
                return components[index++].slice();
            } catch (IndexOutOfBoundsException e) {
                throw new ConcurrentModificationException();
            }
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("Read-Only");
        }
    }

    // Component array manipulation - range checking omitted

    private void clearComps() {
        removeCompRange(0, componentCount);
    }

    private void removeComp(int i) {
        removeCompRange(i, i + 1);
    }

    private void removeCompRange(int from, int to) {
        if (from >= to) {
            return;
        }
        final int size = componentCount;
        assert from >= 0 && to <= size;
        if (to < size) {
            System.arraycopy(components, to, components, from, size - to);
        }
        int newSize = size - to + from;
        for (int i = newSize; i < size; i++) {
            components[i].slice = null;
            components[i] = null;
        }
        componentCount = newSize;
    }

    private void addComp(int i, Component c) {
        shiftComps(i, 1);
        components[i] = c;
    }

    private void shiftComps(int i, int count) {
        final int size = componentCount, newSize = size + count;
        assert i >= 0 && i <= size && count > 0;
        if (newSize > components.length) {
            // grow the array
            int newArrSize = Math.max(size + (size >> 1), newSize);
            Component[] newArr;
            if (i == size) {
                newArr = Arrays.copyOf(components, newArrSize, Component[].class);
            } else {
                newArr = new Component[newArrSize];
                if (i > 0) {
                    System.arraycopy(components, 0, newArr, 0, i);
                }
                if (i < size) {
                    System.arraycopy(components, i, newArr, i + count, size - i);
                }
            }
            components = newArr;
        } else if (i < size) {
            System.arraycopy(components, i, components, i + count, size - i);
        }
        componentCount = newSize;
    }
}
