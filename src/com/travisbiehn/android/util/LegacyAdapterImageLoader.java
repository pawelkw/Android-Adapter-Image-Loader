package com.travisbiehn.android.util;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.util.Log;
import android.widget.AdapterView;

/**
 * Legacy version for pre API 9 devices.
 * Provides multithreaded asynchronous image loading for AdapterViews,
 * optimized. Pass in only one LoadPair or List<LoadPair> per row!
 * 
 * This program is free software. It comes without any warranty, to the extent
 * permitted by applicable law. You can redistribute it and/or modify it under
 * the terms of the Do What The Fuck You Want To Public License, Version 2, as
 * published by Sam Hocevar. See http://sam.zoy.org/wtfpl/COPYING for more
 * details.
 * 
 * @author Travis Biehn
 * 
 */
public class LegacyAdapterImageLoader {
	/**
	 * Provides callback functionality for image grabs.
	 * 
	 * @author Travis Biehn
	 * 
	 */
	public static abstract class BitmapCallback {
		public abstract void callback(Bitmap x);
	}

	/**
	 * Data structure for keeping track of images and their callbacks for worker
	 * threads.
	 * 
	 * @author Travis Biehn
	 * 
	 */
	public static class LoadPair {
		public URL url;
		public BitmapCallback callback;

		public LoadPair(URL url, BitmapCallback callback) {
			this.url = url;
			this.callback = callback;
		}
	}

	public static class LoadEntry {
		public List<LoadPair> list = null;
	}

	/**
	 * FILO/Stack Queue bound by the visible rows in the parent adapterView,
	 * this oldest, or last entries are trimmed on inserts to avoid buildup
	 * during fast list manipulation.
	 * 
	 * @author Travis Biehn
	 * 
	 * @param <T>
	 */
	public class FixedStack<T> extends Stack<T> {
		private static final long serialVersionUID = 1L;
		private static final int KLUDGEFACTOR = 3; // Magical kludge constant

		private int maxEntries() {
			// Visibile portion of the adapterView, might break, might not.
			Log.v("AdapterImageLoader", "Visible Portion: "+(adapterView.getLastVisiblePosition() - adapterView
					.getFirstVisiblePosition())); // Starts from negative numbers....
			return (adapterView.getLastVisiblePosition() - adapterView
					.getFirstVisiblePosition()) + KLUDGEFACTOR;
		}

		public FixedStack() {
		}

		public boolean add(T x) {
			synchronized (this) {
				super.push(x);
				this.trimEntries();
				this.notify();
			}
			return true;

		}

		public void addFirst(T x) {
			synchronized (this) {
				super.push(x);
				this.trimEntries();
				this.notify();
			}
		}

		public void addLast(T x) {
			synchronized (this) {

				throw new UnsupportedOperationException(
						"addLast not supported by this stack.");
			}
		}

		/**
		 * Keeps the stack to a fixed size.
		 */
		private void trimEntries() {
			int toTrim = super.size() - maxEntries();
			if (toTrim > 0) {
				for (int i = 0; i < toTrim; i++) {
					super.remove(super.size() - 1); // Pop off the end (oldest)
													// entries.
				}
			}
		}

		/**
		 * Blocks until an item is available to grab.
		 * 
		 * @return
		 * @throws InterruptedException
		 */
		public T takeFirst() throws InterruptedException {
			synchronized (this) {
				T ret = null;
				while (ret == null) {
					try {
						ret = super.pop();
					} catch (Exception e) {
						// Wait, then rexecute.
						this.wait();
					}
				}
				return ret;
			}
		}

	}

	/**
	 * Low priority ThreadFactory for the image grab threads.
	 */
	private static ThreadFactory threadFactory = new ThreadFactory() {
		ThreadFactory tf = Executors.defaultThreadFactory();

		@Override
		public Thread newThread(final Runnable r) {
			final Thread out = this.tf.newThread(r);
			out.setPriority(Thread.NORM_PRIORITY / 2); // Low Priority

			return out;
		}
	};
	/**
	 * Number of threads to use for image grabbing.
	 */
	private static int imagePoolSize = 2;
	/**
	 * Threadpool for image grabbing.
	 */
	// It might make sense to change the implementation to static for your
	// application.
	// Note that you'll have to change this and the imageGrabber implementation
	// (to make it static.)
	private final ExecutorService imagePool = Executors.newFixedThreadPool(
			imagePoolSize, threadFactory);
	private final FixedStack<LoadEntry> workQueue;
	private final AdapterView<?> adapterView;
	private final Handler handler;

	/**
	 * Polls the blocking queue for work, executes on LoadEntries.
	 */
	private Runnable getImageGrabber() {
		return new Runnable() {

			@Override
			public void run() {
				while (true) {
					try {
						LoadEntry currentEntry = workQueue.takeFirst();
						for (LoadPair pair : currentEntry.list) {
							URL newurl = pair.url;
							final BitmapCallback callback = pair.callback;
							final URLConnection connection = newurl
									.openConnection();
							// If you have a cache implementation, use it.
							connection.setUseCaches(true);

							final Bitmap out = BitmapFactory
									.decodeStream(connection.getInputStream());
							// Post to callback in the UI thread.
							handler.post(new Runnable() {
								@Override
								public void run() {
									callback.callback(out);
								}
							});
						}
					} catch (InterruptedException e1) {
						// Skip along.
					} catch (final IOException e) {
						// Skip along.
					}

				}

			}
		};
	}

	/**
	 * 
	 * @param parentView
	 */
	public AdapterImageLoader(AdapterView<?> parentView) {
		handler = new Handler(); // Affinity to the UI thread.
		adapterView = parentView;
		workQueue = new FixedStack<LoadEntry>();

		// Load up threads
		for (int i = 0; i < imagePoolSize; i++) {
			imagePool.execute(getImageGrabber());
		}
	}

	/**
	 * You must make only one call to this class per row entry. Use this for a
	 * single image per row or use the addLoadPair() functions.
	 * 
	 * @param url
	 * @param callback
	 */
	public void addImage(URL url, BitmapCallback callback) {
		LoadEntry le = new LoadEntry();
		le.list = new ArrayList<LoadPair>();
		le.list.add(new LoadPair(url, callback));
		workQueue.add(le);
	}

	/**
	 * You must make only one call to this class per row entry! For multiple
	 * images per row use addLoadPairs()
	 * 
	 * @param loadPair
	 */
	public void addLoadPair(LoadPair loadPair) {
		LoadEntry le = new LoadEntry();
		le.list = new ArrayList<LoadPair>();
		le.list.add(loadPair);
		workQueue.add(le);
	}

	/**
	 * You must make only one call to this class per row entry.
	 * 
	 * @param loadPairs
	 */
	public void addLoadPairs(List<LoadPair> loadPairs) {
		LoadEntry le = new LoadEntry();
		le.list = loadPairs;
		workQueue.add(le);
	}
}