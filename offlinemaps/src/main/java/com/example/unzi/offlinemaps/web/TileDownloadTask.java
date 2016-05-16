/*
 * MapApp : Simple offline\online map application, made by Hisham Ghosheh for tutorial purposes only
 * Tutorial on my blog
 * http://ghoshehsoft.wordpress.com/2012/03/09/building-a-map-app-for-android/
 * 
 * Class tutorial:
 * http://ghoshehsoft.wordpress.com/2012/08/16/mapapp6-web-support/
 */

package com.example.unzi.offlinemaps.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;

public class TileDownloadTask implements Runnable
{
	// Task state constants
	public final static int TASK_ONGOING = 0; // Starting or ongoing
	public final static int TASK_COMPLETE = 1; // Completed successfully
	public final static int TASK_FAILED = 2; // Failed for some reason

	private final String myUrl; // Url of the tile to download

	// Coordinates of the tile being downloaded, needed later
	private final int x, y, z;

	// Called when task finishes its work or when it fails.
	private final DownloadTaskFinishedCallback callback;

	// Current task state
	private int taskState = TASK_ONGOING;

	// Will contain the downloaded file
	private byte[] file = null;

	public TileDownloadTask(String myUrl, DownloadTaskFinishedCallback callback, int x, int y, int z)
	{
		this.myUrl = myUrl;
		this.callback = callback;

		this.x = x;
		this.y = y;
		this.z = z;
	}

	// Download code goes here
	public void run()
	{
		try
		{
			// See here for explanation
			// http://developer.android.com/training/basics/network-ops/connecting.html#download
			URL url = new URL(myUrl);
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();

			// Time in milliseconds the task has to download the tile
			conn.setReadTimeout(10000);
			conn.setConnectTimeout(15000);
			conn.setRequestMethod("GET");
			conn.setDoInput(true);
			conn.connect();

			int response = conn.getResponseCode();
			if (response == 404) // Not found
			{
				taskState = TASK_FAILED;
				return; // Go to finally block
			}

			// Reading file
			InputStream is = conn.getInputStream();
			byte[] buffer = new byte[1024 * 4];
			ByteArrayOutputStream out = new ByteArrayOutputStream();

			while (true)
			{
				int read = is.read(buffer);
				if (read == -1) break;

				out.write(buffer, 0, read);
			}

			out.flush();
			file = out.toByteArray();
			out.close();

			taskState = TASK_COMPLETE;
		}
		catch (SocketTimeoutException ste)
		{
			taskState = TASK_FAILED;
		}
		catch (MalformedURLException e)
		{
			e.printStackTrace();
			taskState = TASK_FAILED;
		}
		catch (IOException e)
		{
			e.printStackTrace();
			taskState = TASK_FAILED;
		}
		catch (Exception e)
		{
			taskState = TASK_FAILED;
		}
		finally
		{
			// Report the result by passing this task
			if (callback != null) callback.handleDownload(this);
		}
	}

	// Simple getters

	public String getUrl()
	{
		return myUrl;
	}

	public byte[] getFile()
	{
		return file;
	}

	public int getX()
	{
		return x;
	}

	public int getY()
	{
		return y;
	}

	public int getZ()
	{
		return z;
	}

	public int getState()
	{
		return taskState;
	}
}
