/*
 * MapApp : Simple offline\online map application, made by Hisham Ghosheh for tutorial purposes only
 * Tutorial on my blog
 * http://ghoshehsoft.wordpress.com/2012/03/09/building-a-map-app-for-android/
 * 
 * Class tutorial:
 * http://ghoshehsoft.wordpress.com/2012/08/16/mapapp6-web-support/
 */

package com.example.unzi.offlinemaps.web;

import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.util.Log;

public class WebTilesProvider implements DownloadTaskFinishedCallback
{
	private static final String TAG = WebTilesProvider.class.getSimpleName();

	// Max number of active download threads
	// A large number of threads might get
	// you blocked from the tiles server.
	final int threadsCount;

	// Keeping track of current non-finished tasks
	// to avoid downloading a tile more than once
	HashSet<String> pendingRequests = new HashSet<String>();

	ExecutorService pool; // Handles requests

	// A callback to be called by finished\failed tasks
	DownloadTaskFinishedCallback handler;

	public WebTilesProvider(int threadsCount, DownloadTaskFinishedCallback handler)
	{
		this.threadsCount = threadsCount;
		pool = Executors.newFixedThreadPool(threadsCount);

		this.handler = handler;
	}

	public void downloadTile(int x, int y, int z)
	{
		Log.d(TAG, "dowloading tile" + x + " " + y + " " +z);
		// Get the url in the right format
		//String url = formatUrl(x, y, z);
		String url = String.format("http://api.tiles.mapbox.com/v3/afrodrigues.i35a783b/%s/%s/%s.png", z,x,y);
		// Whenever using the HashSet pendingRequests we must
		// make sure that no other thread is using it, we do that by
		// using synchronized on the set whenever a code block uses the set 
		synchronized (pendingRequests)
		{
			// If tile isn't being downloaded then add it
			if (!pendingRequests.contains(url))
			{
				pendingRequests.add(url);
 
				// Create a new task and execute it in a separate thread
				TileDownloadTask task = new TileDownloadTask(url, this, x, y, z);
				pool.execute(task);
			}
		}
	}

	String formatUrl(int x, int y, int z)
	{
		// Here we're using open street map tiles, you can replace it with the
		// server you want
		// Just make sure you have the right to download the tiles
		// Also note the zxy order for the tiles!
		String result = String.format("http://a.tile.openstreetmap.org/%s/%s/%s.png", z, x, y);
		
		return result;
	}

	// This function should be called when the TilesProvider has
	// received and processed the tile, it should be called even when the
	// download fails, otherwise
	// the request will be stuck in pendingRequest without actually being
	// executed!
	// leaving the tile blank.
	private void removeRequestFromPending(String url)
	{
		// Making sure no other thread is using the set
		synchronized (pendingRequests)
		{
			pendingRequests.remove(url);
		}
	}

	// Called by a TileDownloadTask when finished
	@Override
	public synchronized void handleDownload(TileDownloadTask task)
	{
		int state = task.getState();

		// If downloaded successfully
		if (state == TileDownloadTask.TASK_COMPLETE)
		{
			// Pass the task to the TilesProvider
			if (handler != null) handler.handleDownload(task);
		}
		else if (state == TileDownloadTask.TASK_FAILED)
		{
			// Do nothing!!
		}

		// It's necessary to remove the request from pending list
		// We only remove it when we are done with it, otherwise the MapView
		// could request the tile while it's being inserted in the database for
		// example.
		// This way we make sure we download the tile only once.
		removeRequestFromPending(task.getUrl());
	}

	// Hopefully kills the active download tasks and clears all pending tasks
	public void cancelDownloads()
	{
		pool.shutdownNow();
		synchronized (pendingRequests)
		{
			pendingRequests.clear();
		}

		// Cannot reuse ExecutorService after calling shutdownNow
		// Create a new executor
		pool = Executors.newFixedThreadPool(threadsCount);
	}
}
