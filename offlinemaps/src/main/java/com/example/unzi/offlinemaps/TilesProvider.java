/*
 * MapApp : Simple offline map application, made by Hisham Ghosheh for tutorial purposes only
 * Tutorial on my blog
 * http://ghoshehsoft.wordpress.com/2012/03/09/building-a-map-app-for-android/
 * 
 * Class tutorial:
 * http://ghoshehsoft.wordpress.com/2012/03/23/mapapp4-tilesprovider/
 */

package com.example.unzi.offlinemaps;



import java.util.Hashtable;

import android.app.Notification.Builder;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Handler;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.Tile;

import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;
import com.google.android.gms.maps.model.TileProvider;
import com.example.unzi.offlinemaps.web.DownloadTaskFinishedCallback;
import com.example.unzi.offlinemaps.web.TileDownloadTask;
import com.example.unzi.offlinemaps.web.WebTilesProvider;

public class TilesProvider implements DownloadTaskFinishedCallback,
		TileProvider {
	WebTilesProvider webProvider;

	// The database that holds the map
	protected SQLiteDatabase tilesDB;

	// Tiles will be stored here, the index\key will be in this format x:y
	protected Hashtable<String, Tile> tiles = new Hashtable<String, Tile>();

	// An object to use with synchronized to lock tiles hashtable
	public Object tilesLock = new Object();

	// A handler from the outside to be informed of new downloaded tiles
	// Used to redraw the map view whenever a new tile arrives
	Handler newTileHandler;

	private int countTiles = 0;
	private int countDownloads = 0;
	private int countNulls = 0;
	private int countFounds = 0;

	public TilesProvider(GoogleMap googleMap, String dbPath) {
		googleMap.setMapType(GoogleMap.MAP_TYPE_NONE);

		/*
		 * Create WebTileProvider with max number of thread equal to five We
		 * also pass this class as a DownloadTaskFinishedCallback This way when
		 * the web provider downloads a tile we get it and insert it into the
		 * database and the hashtable
		 */
		webProvider = new WebTilesProvider(5, this);

		// This time we are opening the database as read\write
		tilesDB = SQLiteDatabase.openDatabase(dbPath, null,
				SQLiteDatabase.OPEN_READWRITE);

		// Create new TileOverlayOptions instance.
		TileOverlayOptions opts = new TileOverlayOptions();
		opts.tileProvider(this);
		// Add the tile overlay to the map.
		TileOverlay overlay = googleMap.addTileOverlay(opts);

		// This handler is to be notified when a new tile is downloaded
		// and available for rendering
		this.newTileHandler = newTileHandler;
	}

	// Updates the tiles in the hashtable
	public Tile fetchTiles(int x, int y, int zoom) {
		// We are using a separate object here for synchronizing
		// Using the hashtable tiles will cause problems when we swap the
		// pointers temp and tiles
		countTiles++;
		synchronized (tilesLock) {
			// Max tile index for x and y

			// Perpare the query for the database
			String query = "SELECT x,y,image FROM tiles WHERE x == " + x
					+ " AND y ==" + y + " AND z == " + (17 - zoom);
			Cursor cursor;
			cursor = tilesDB.rawQuery(query, null);

			// Now cursor contains a table with these columns
			/*
			 * x(int) y(int) image(byte[])
			 */

			// Prepare an empty hash table to fill with the tiles we fetched
			Hashtable<String, Tile> temp = new Hashtable<String, Tile>();

			// Loop through all the rows(tiles) of the table returned by the
			// query
			// MUST call moveToFirst
			if (cursor.moveToFirst()) {
				do {
					// Getting the index of this tile
					int xs = 256;
					int ys = 256;

					// Try to get this tile from the hashtable we have
					Tile tile = tiles.get(xs + ":" + ys);

					// If This is a new tile, we didn't fetch it in the
					// previous
					// fetchTiles call.
					if (tile == null) {
						// Get the binary image data from the third cursor
						// column
						byte[] img = cursor.getBlob(2);

						// Create a bitmap (expensive operation)
						byte[] tileBitmap = img;

						// Create the new tile
						tile = new Tile(xs, ys, tileBitmap);

						countFounds++;
						return tile;
					}

					// The object "tile" should now be ready for rendering

					// Add the tile to the temp hashtable
					temp.put(xs + ":" + ys, tile);

				} while (cursor.moveToNext()); // Move to next tile in the
				// query
				cursor.close();
				// The hashtable "tiles" is now outdated,
				// so clear it and set it to the new hashtable temp.

				/*
				 * Swapping here sometimes creates an exception if we use tiles
				 * for synchronizing
				 */
				tiles.clear();
				tiles = temp;
			}
			countDownloads++;
			webProvider.downloadTile(x, y, zoom);

		}
		// Log.d("gcm", "d tiles:" + countDownloads);
		countNulls++;
		return null;
	}

	// Gets the hashtable where the tiles are stored
	public Hashtable<String, Tile> getTiles() {
		return tiles;
	}

	public void close() {
		// If fetchTiles is used after closing it will not work, it will throw
		// an exception
		tilesDB.close();
	}

	public void clear() {
		// Make sure no other thread is using the hashtable before clearing it
		synchronized (tilesLock) {
			tiles.clear();
		}

		// Cancel all download operations
		webProvider.cancelDownloads();
	}

	// Called by the WebTilesProvider when a tile was downloaded successfully
	// Also note that it's marked as synchronized to make sure that we only
	// handle one
	// finished task at a time, since the WebTilesProvider will call this method
	// whenever
	// a task is finished
	@Override
	public synchronized void handleDownload(TileDownloadTask task) {
		byte[] tile = task.getFile();
		int x = task.getX();
		int y = task.getY();

		// Log.d("TAG", "Downloaded " + x + ":" + y);

		// Insert tile into database as an array of bytes
		insertTileToDB(x, y, 17 - task.getZ(), tile);

		// Creating bitmaps may throw OutOfMemoryError
		try {
			Bitmap bm = BitmapFactory.decodeByteArray(tile, 0, tile.length);
			Tile t = new Tile(x, y, tile);

			// Add the new tile to our tiles memory cache
			synchronized (tilesLock) {
				tiles.put(x + ":" + y, t);
			}

			// Here we inform who ever interested that we have a new tile
			// ready to be rendered!
			// The handler is in the MapAppActivity and sending it a message
			// will cause it to redraw the MapView
			if (newTileHandler != null)
				newTileHandler.sendEmptyMessage(0);
		} catch (OutOfMemoryError e) {
			// At least we got the tile as byte array and saved it in the
			// database
		}
	}

	// Marked as synchronized to prevent to insert operations at the same time
	synchronized void insertTileToDB(int x, int y, int z, byte[] tile) {
		ContentValues vals = new ContentValues();
		vals.put("x", x);
		vals.put("y", y);
		vals.put("z", z);
		vals.put("image", tile);
		tilesDB.insert("tiles", null, vals);
	}

	private double originShift = (2 * Math.PI * 6378137 / 2.0);
	private double initialResolution = 2 * Math.PI * 6378137 / 256;

	private int getTileX(double lon, int zoom) {
		double mx = lon * originShift / 180;

		double res = initialResolution / (Math.pow(2, zoom));

		double px = (mx + originShift) / res;

		int tx = (int) (Math.ceil(px / (float) (256)) - 1);

		return tx;
	}

	private int getTileY(double lat, int zoom) {
		double my = Math.log(Math.tan((90 + lat) * Math.PI / 360))
				/ (Math.PI / 180);
		my = my * originShift / 180.0;

		double res = initialResolution / (Math.pow(2, zoom));
		double py = (my + originShift) / res;

		int ty = (int) (Math.ceil(py / (float) (256)) - 1);
		return (int) ((Math.pow(2, zoom) - 1) - ty);
	}

	public void downloadTilesInBound(final double minLat, final double minLong,
									 final double maxLat, final double maxLong, final int minZoom,
									 final int maxZoom, Context c) {
		final NotificationManager mNotifyManager = (NotificationManager) c
				.getSystemService(c.NOTIFICATION_SERVICE);
		final android.support.v4.app.NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(
				c);
		mBuilder.setContentTitle("Downloading Map")
				.setContentText("Download in progress")
				.setSmallIcon(R.drawable.service_logo);
		// Start a lengthy operation in a background thread
		new Thread(new Runnable() {
			@Override
			public void run() {
				double incr=0;
				mBuilder.setProgress(100, (int)incr, false);
				// Displays the progress bar for the first time.
				mNotifyManager.notify(1, mBuilder.build());
				int progress_aux=0;
				for (int zoom = minZoom; zoom <= maxZoom; zoom++) {

					int min_tx = getTileX(minLong, zoom);
					int min_ty = getTileY(minLat, zoom);
					int max_tx = getTileX(maxLong, zoom);
					int max_ty = getTileY(maxLat, zoom);
					for (int y = min_ty; y < max_ty + 1; y++) {
						for (int x = min_tx; x < max_tx + 1; x++) {
							fetchTiles(x, y, zoom);
							Log.d("gcm", "x:" + x + " y:" + y + " z:" + zoom);

						}
						if(zoom==19){
							if(progress_aux==0){
								incr+=5;
								mBuilder.setProgress(100, (int)incr, false);
								// Displays the progress bar for the first time.
								mNotifyManager.notify(1, mBuilder.build());
								progress_aux = (max_ty-min_ty)/20;
							}
							progress_aux--;

						}
					}

				}

				// When the loop is finished, updates the notification
				mBuilder.setContentText("Download complete")
						// Removes the progress bar
						.setProgress(0, 0, false);
				mNotifyManager.notify(1, mBuilder.build());
			}
		}
				// Starts the thread by calling the run() method in its Runnable
		).start();

		Log.d("gcm", "START download!!!!!!!!!!!!!!!!!");
		new AsyncTask<Void, Void, String>() {
			@Override
			protected String doInBackground(Void... params) {

				return null;

			}
		}.execute(null, null, null);
	}



	@Override
	public Tile getTile(int x, int y, int z) {
		Tile t = fetchTiles(x, y, z);

		return t;
	}
}