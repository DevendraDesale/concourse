/*
 * The MIT License (MIT)
 * 
 * Copyright (c) 2013 Jeff Nelson, Cinchapi Software Collective
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.cinchapi.concourse.server.engine;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel.MapMode;
import java.text.NumberFormat;
import java.util.Iterator;

import javax.annotation.concurrent.ThreadSafe;

import org.cinchapi.common.annotate.PackagePrivate;
import org.cinchapi.common.io.ByteableCollections;
import org.cinchapi.common.io.Files;
import org.cinchapi.common.multithread.Lock;
import org.cinchapi.concourse.server.Constants;
import org.cinchapi.concourse.thrift.TObject;
import org.perf4j.aop.Profiled;
import org.slf4j.Logger;

import static org.cinchapi.concourse.util.Logging.getServerLog;

/**
 * A {@code Buffer} is a special implementation of {@link Limbo} that aims to
 * quickly accumulate writes in memory before performing a batch flush into some
 * {@link Destination}.
 * <p>
 * A Buffer enforces the durability guarantee because all writes are immediately
 * stored to disk. Even though there is some disk I/O, the overhead is minimal
 * and writes are fast because the entire backing store is memory mapped and the
 * writes are always appended.
 * </p>
 * 
 * @author jnelson
 */
@ThreadSafe
@PackagePrivate
final class Buffer extends Limbo implements Transportable {

	/**
	 * The average number of bytes used to store an arbitrary Write.
	 */
	private static final int AVG_WRITE_SIZE = 72; /* arbitrary */
	private static final Logger log = getServerLog();
	private static final NumberFormat pct;
	static {
		pct = NumberFormat.getPercentInstance();
		pct.setMaximumFractionDigits(2);
	}

	/**
	 * To guarantee data durability, the Buffer is backed by a file on disk that
	 * is memory mapped. The content is package protected, so other classes that
	 * access this member directly should handle with care.
	 */
	@PackagePrivate
	final MappedByteBuffer content;

	/**
	 * The amount of bytes that are currently occupied.
	 */
	private int occupied;

	/**
	 * Construct a Buffer that is backed by the default location, which is a
	 * file called "buffer" in the {@link Constants#DATA_HOME} directory.
	 * Existing content, if available, will be loaded from the file. Otherwise,
	 * a new and empty Buffer will be returned.
	 */
	public Buffer() {
		this(Constants.DATA_HOME + File.separator + "buffer");
	}

	/**
	 * 
	 * Construct a a Buffer that is backed by {@code backingStore}. Existing
	 * content, if available, will be loaded from the file. Otherwise, a new and
	 * empty Buffer will be returned.
	 * 
	 * @param backingStore
	 */
	public Buffer(String backingStore) {
		this(backingStore, Constants.BUFFER_SIZE_IN_BYTES);
	}

	/**
	 * Construct a Buffer that is backed by {@code backingStore} and is limited
	 * to {@code size} bytes.
	 * 
	 * @param backingStore
	 * @param size
	 */
	private Buffer(String backingStore, int size) {
		super(size / AVG_WRITE_SIZE);
		this.content = Files.map(backingStore, MapMode.READ_WRITE, 0, size);
		Iterator<ByteBuffer> it = ByteableCollections.iterator(content);
		while (it.hasNext()) {
			Write write = Write.fromByteBuffer(it.next());
			insert(write); // this will only record the write in memory and not
							// the backingStore (because its already there!)
			occupied += write.size() + 4;
			log.debug("Found existing write '{}' in the Buffer", write);
		}
		log.info("Using Buffer at '{}' with a total capacity of {} bytes. "
				+ "{} percent of the buffer is currently occupied.",
				backingStore, size, usage());
	}

	/**
	 * <p>
	 * <strong>
	 * <em>The caller of this method should catch a {@link BufferCapacityException}
	 * and call {@link #transport(Destination)}.</em></strong>
	 * </p>
	 * {@inheritDoc}
	 */
	@Override
	public boolean add(String key, TObject value, long record) {
		if(!verify(key, value, record)) {
			return append(Write.add(key, value, record));
		}
		return false;
	}

	/**
	 * <p>
	 * <strong>
	 * <em>The caller of this method should catch a {@link BufferCapacityException}
	 * and call {@link #transport(Destination)}.</em></strong>
	 * </p>
	 * {@inheritDoc}
	 */
	@Override
	public boolean addUnsafe(String key, TObject value, long record) {
		return append(Write.add(key, value, record));
	}

	/**
	 * <p>
	 * <strong>
	 * <em>The caller of this method should catch a {@link BufferCapacityException}
	 * and call {@link #transport(Destination)}.</em></strong>
	 * </p>
	 * {@inheritDoc}
	 */
	@Override
	public boolean remove(String key, TObject value, long record) {
		if(verify(key, value, record)) {
			return append(Write.remove(key, value, record));
		}
		return false;
	}

	/**
	 * <p>
	 * <strong>
	 * <em>The caller of this method should catch a {@link BufferCapacityException}
	 * and call {@link #transport(Destination)}.</em></strong>
	 * </p>
	 * {@inheritDoc}
	 */
	@Override
	public boolean removeUnsafe(String key, TObject value, long record) {
		return append(Write.remove(key, value, record));
	}

	/**
	 * {@inheritDoc} This method will permanently remove writes from the
	 * underlying {@link #content}.
	 */
	@Override
	@Profiled(tag = "Buffer.transport", logger = "org.cinchapi.concourse.server.engine.PerformanceLogger")
	public void transport(Destination destination) {
		log.debug("Starting a Buffer flush...");
		Lock lock = writeLock();
		try {
			Transporter transporter = new Transporter(this);
			while (transporter.hasNext()) {
				Write write = transporter.next();
				destination.accept(write);
				transporter.ack();
				log.info("Transported '{}' from the Buffer", write);
				occupied -= (write.size() + 4);
				log.info("{} percent of the Buffer is now occupied", usage());
			}
		}
		finally {
			lock.release();
		}
	}

	/**
	 * Append {@code write} to the underlying backingStore and perform the
	 * {@link #insert(Write)} function in memory.
	 * 
	 * @param write
	 * @return
	 * @throws BufferCapacityException - if the size of {@code write} is greater
	 *             than the remaining capacity of the Buffer
	 */
	@Profiled(tag = "Buffer.write_{$0}", logger = "org.cinchapi.concourse.server.engine.PerformanceLogger")
	private boolean append(Write write) throws BufferCapacityException {
		Lock lock = writeLock();
		try {
			if(content.remaining() >= write.size() + 4) {
				super.insert(write);
				content.putInt(write.size());
				content.put(write.getBytes());
				content.force();
				occupied += write.size() + 4;
				log.info("{} percent of the Buffer is now occupied", usage());
			}
			else {
				log.warn("Attempt to append '{}' to the Buffer failed "
						+ "because there is insufficient capacity. "
						+ "Please flush the Buffer.", write);
				throw new BufferCapacityException();
			}
		}
		finally {
			lock.release();
		}
		return true;
	}

	/**
	 * Return a string that describes the percent usage for the Buffer.
	 * 
	 * @return a formatted description of the usage
	 */
	private String usage() {
		Lock lock = readLock();
		try {
			return pct.format((double) occupied / content.capacity());
		}
		finally {
			lock.release();
		}
	}
}
