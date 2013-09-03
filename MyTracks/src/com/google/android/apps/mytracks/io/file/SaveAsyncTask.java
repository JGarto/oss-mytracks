/*
 * Copyright 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.apps.mytracks.io.file;

import com.google.android.apps.mytracks.content.MyTracksProviderUtils;
import com.google.android.apps.mytracks.content.Track;
import com.google.android.apps.mytracks.content.TracksColumns;
import com.google.android.apps.mytracks.util.FileUtils;
import com.google.android.apps.mytracks.util.PreferencesUtils;
import com.google.android.apps.mytracks.util.SystemUtils;
import com.google.android.maps.mytracks.R;

import android.content.Context;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.PowerManager.WakeLock;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;

/**
 * Async Task to save tracks to the external storage.
 * 
 * @author Jimmy Shih
 */
public class SaveAsyncTask extends AsyncTask<Void, Integer, Boolean> {

  private static final String TAG = SaveAsyncTask.class.getSimpleName();

  private SaveActivity saveActivity;
  private final TrackFileFormat trackFileFormat;
  private final long[] trackIds;
  private final File directory;
  private final boolean playTrack;
  private final Context context;
  private final MyTracksProviderUtils myTracksProviderUtils;

  private WakeLock wakeLock;
  private TrackExporter trackExporter;

  // true if the AsyncTask has completed
  private boolean completed;

  // the number of tracks successfully saved
  private int successCount;

  // the number of tracks to save
  private int totalCount;

  // the last successfully saved path
  private String savedPath;

  /**
   * Creates an AsyncTask.
   * 
   * @param saveActivity the activity currently associated with this task
   * @param trackFileFormat the track file format
   * @param trackIds the track ids to save. To save all, set to size 1 with
   *          trackIds[0] == -1L
   * @param directory the directory to save to
   * @param playTrack true to play track
   */
  public SaveAsyncTask(SaveActivity saveActivity, TrackFileFormat trackFileFormat, long[] trackIds,
      File directory, boolean playTrack) {
    this.saveActivity = saveActivity;
    this.trackFileFormat = trackFileFormat;
    this.trackIds = trackIds;
    this.directory = directory;
    this.playTrack = playTrack;
    context = saveActivity.getApplicationContext();
    myTracksProviderUtils = MyTracksProviderUtils.Factory.get(context);

    completed = false;
    successCount = 0;
    totalCount = 0;
    savedPath = null;
  }

  /**
   * Sets the current activity associated with this AyncTask.
   * 
   * @param saveActivity the current activity, can be null
   */
  public void setActivity(SaveActivity saveActivity) {
    this.saveActivity = saveActivity;
    if (completed && saveActivity != null) {
      saveActivity.onAsyncTaskCompleted(successCount, totalCount, savedPath);
    }
  }

  @Override
  protected void onPreExecute() {
    if (saveActivity != null) {
      saveActivity.showProgressDialog();
    }
  }

  @Override
  protected Boolean doInBackground(Void... params) {
    try {
      boolean isRecording = PreferencesUtils.getLong(saveActivity, R.string.recording_track_id_key)
          != PreferencesUtils.RECORDING_TRACK_ID_DEFAULT;
      boolean isPaused = PreferencesUtils.getBoolean(saveActivity,
          R.string.recording_track_paused_key, PreferencesUtils.RECORDING_TRACK_PAUSED_DEFAULT);
      // Get the wake lock if not recording or paused
      if (!isRecording || isPaused) {
        wakeLock = SystemUtils.acquireWakeLock(saveActivity, wakeLock);
      }
      if (trackIds.length == 1 && trackIds[0] == -1L) {
        return saveAllTracks();
      } else {
        totalCount = 1;
        Track[] tracks = new Track[trackIds.length];
        for (int i = 0; i < trackIds.length; i++) {
          tracks[i] = myTracksProviderUtils.getTrack(trackIds[i]);
          if (tracks[i] == null) {
            Log.d(TAG, "No track for " + trackIds[i]);
            return false;
          }
        }
        if (saveTracks(tracks)) {
          successCount = 1;
          return true;
        } else {
          return false;
        }
      }
    } finally {
      if (wakeLock != null && wakeLock.isHeld()) {
        wakeLock.release();
      }
    }
  }

  @Override
  protected void onProgressUpdate(Integer... values) {
    if (saveActivity != null) {
      saveActivity.setProgressDialogValue(values[0], values[1]);
    }
  }

  @Override
  protected void onPostExecute(Boolean result) {
    completed = true;
    if (saveActivity != null) {
      saveActivity.onAsyncTaskCompleted(successCount, totalCount, savedPath);
    }
  }

  @Override
  protected void onCancelled() {
    completed = true;
    if (saveActivity != null) {
      saveActivity.onAsyncTaskCompleted(successCount, totalCount, null);
    }
  }

  /**
   * Saves tracks to one file.
   * 
   * @param tracks the tracks
   */
  private Boolean saveTracks(Track[] tracks) {
    if (tracks.length == 0) {
      return false;
    }
    Track track = tracks[0];
    boolean useKmz = !playTrack && trackFileFormat == TrackFileFormat.KML;
    String extension = useKmz ? KmzTrackExporter.KMZ_EXTENSION : trackFileFormat.getExtension();
    
    // Make sure the file doesn't exist yet (possibly by changing the filename)
    String fileName = FileUtils.buildUniqueFileName(directory, track.getName(), extension);
    if (fileName == null) {
      Log.d(TAG, "Unable to get a unique filename for " + track.getName());
      return false;
    }

    FileTrackExporter fileTrackExporter = new FileTrackExporter(myTracksProviderUtils, tracks,
        trackFileFormat, context, useKmz, new TrackExporterListener() {

            @Override
          public void onProgressUpdate(int number, int max) {
            /*
             * If only saving one track, update the progress dialog once every
             * 500 points
             */
            if (trackIds.length == 1 && trackIds[0] != -1L && number % 500 == 0) {
              publishProgress(number, max);
            }
          }
        });

    trackExporter = useKmz ? new KmzTrackExporter(myTracksProviderUtils, fileTrackExporter, tracks)
        : fileTrackExporter;

    File file = new File(directory, fileName);
    FileOutputStream fileOutputStream;
    try {
      fileOutputStream = new FileOutputStream(file);
    } catch (FileNotFoundException e) {
      Log.e(TAG, "Unable to open file " + file.getName(), e);
      return false;
    }
    trackExporter.writeTrack(fileOutputStream);

    if (trackExporter.isSuccess()) {
      savedPath = file.getAbsolutePath();
      return true;
    } else {
      if (!file.delete()) {
        Log.w(TAG, "Failed to delete file " + file.getAbsolutePath());
      }
      return false;
    }
  }

  /**
   * Saves all the tracks.
   */
  private Boolean saveAllTracks() {
    Cursor cursor = null;
    try {
      cursor = myTracksProviderUtils.getTrackCursor(null, null, TracksColumns._ID);
      if (cursor == null) {
        return false;
      }
      totalCount = cursor.getCount();
      for (int i = 0; i < totalCount; i++) {
        if (isCancelled()) {
          return false;
        }
        cursor.moveToPosition(i);
        Track track = myTracksProviderUtils.createTrack(cursor);
        if (track != null && saveTracks(new Track[] { track })) {
          successCount++;
        }
        publishProgress(i + 1, totalCount);
      }
      return true;
    } finally {
      if (cursor != null) {
        cursor.close();
      }
    }
  }
}
