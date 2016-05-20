/*
 * MapApp : Simple offline\online map application, made by Hisham Ghosheh for tutorial purposes only
 * Tutorial on my blog
 * http://ghoshehsoft.wordpress.com/2012/03/09/building-a-map-app-for-android/
 * 
 * Class tutorial:
 * http://ghoshehsoft.wordpress.com/2012/08/16/mapapp6-web-support/
 */

package com.example.unzi.offlinemaps.web;

public interface DownloadTaskFinishedCallback
{
	public void handleDownload(TileDownloadTask task);
}
