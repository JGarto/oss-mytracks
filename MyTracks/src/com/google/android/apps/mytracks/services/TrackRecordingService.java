/*
 * Copyright 2008 Google Inc.
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
package com.google.android.apps.mytracks.services;

import static com.google.android.apps.mytracks.MyTracksConstants.RESUME_TRACK_EXTRA_NAME;

import com.google.android.apps.mytracks.MyTracks;
import com.google.android.apps.mytracks.MyTracksConstants;
import com.google.android.apps.mytracks.MyTracksSettings;
import com.google.android.apps.mytracks.content.MyTracksProviderUtils;
import com.google.android.apps.mytracks.content.Track;
import com.google.android.apps.mytracks.content.TracksColumns;
import com.google.android.apps.mytracks.content.Waypoint;
import com.google.android.apps.mytracks.content.WaypointsColumns;
import com.google.android.apps.mytracks.stats.TripStatistics;
import com.google.android.apps.mytracks.stats.TripStatisticsBuilder;
import com.google.android.apps.mytracks.util.ApiFeatures;
import com.google.android.apps.mytracks.util.ApiPlatformAdapter;
import com.google.android.apps.mytracks.util.MyTracksUtils;
import com.google.android.apps.mytracks.util.StringUtils;
import com.google.android.maps.mytracks.R;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

import java.util.Timer;
import java.util.TimerTask;

/**
 * A background service that registers a location listener and records track
 * points. Track points are saved to the MyTracksProvider.
 *
 * @author Leif Hendrik Wilden
 */
public class TrackRecordingService extends Service implements LocationListener {

  private static final String STATISTICS_ICON_URL =
      "http://maps.google.com/mapfiles/ms/micons/ylw-pushpin.png";

  static final int MAX_AUTO_RESUME_TRACK_RETRY_ATTEMPTS = 3;
  
  private NotificationManager notificationManager;
  private LocationManager locationManager;
  private WakeLock wakeLock;

  private int minRecordingDistance =
      MyTracksSettings.DEFAULT_MIN_RECORDING_DISTANCE;
  private int maxRecordingDistance =
      MyTracksSettings.DEFAULT_MAX_RECORDING_DISTANCE;
  private int minRequiredAccuracy =
      MyTracksSettings.DEFAULT_MIN_REQUIRED_ACCURACY;
  private int autoResumeTrackTimeout =
      MyTracksSettings.DEFAULT_AUTO_RESUME_TRACK_TIMEOUT; 
  
  private long recordingTrackId = -1;

  private long currentWaypointId = -1;

  /** The timer posts a runnable to the main thread via this handler. */
  private final Handler handler = new Handler();

  /**
   * Utilities to deal with the database.
   */
  private MyTracksProviderUtils providerUtils;

  private TripStatisticsBuilder statsBuilder;
  private TripStatisticsBuilder waypointStatsBuilder;

  /**
   * Current length of the recorded track. This length is calculated from the
   * recorded points (as compared to each location fix). It's used to overlay
   * waypoints precisely in the elevation profile chart.
   */
  private double length = 0;

  /**
   * Status announcer executer.
   */
  private PeriodicTaskExecuter announcementExecuter;
  private TaskExecuterManager signalManager;
  private SplitManager splitManager;

  private PreferenceManager prefManager;
  
  /**
   * The interval in milliseconds that we have requested to be notified of gps
   * readings.
   */
  private long currentRecordingInterval = 0;

  /**
   * The policy used to decide how often we should request gps updates.
   */
  private LocationListenerPolicy locationListenerPolicy =
      new AbsoluteLocationListenerPolicy(0);

  /**
   * Task invoked by a timer periodically to make sure the location listener is
   * still registered.
   */
  private final TimerTask checkLocationListener = new TimerTask() {
    @Override
    public void run() {
      // It's always safe to assume that if isRecording() is true, it implies
      // that onCreate() has finished.
      if (isRecording()) {
        handler.post(new Runnable() {
          public void run() {
            Log.d(MyTracksConstants.TAG,
                "Re-registering location listener with TrackRecordingService.");
            unregisterLocationListener();
            registerLocationListener();
          }
        });
      }
    }
  };

  /**
   * This timer invokes periodically the checkLocationListener timer task.
   */
  private final Timer timer = new Timer();

  /**
   * Is the phone currently moving?
   */
  private boolean isMoving = true;

  /**
   * The most recent recording track.
   */
  private Track recordingTrack;
  
  /**
   * Is the service currently recording a track?
   */
  private boolean isRecording = false;

  /**
   * Last good location the service has received from the location listener
   */
  private Location lastLocation = null;

  /**
   * Last valid location (i.e. not a marker) that was recorded.
   */
  private Location lastValidLocation = null;

  /**
   * The frequency of status announcements.
   */
  private int announcementFrequency = -1;

  /*
   * Utility functions
   */

  /**
   * Inserts a new location in the track points db and updates the corresponding
   * track in the track db.
   *
   * @param recordingTrack the track that is currently being recorded
   * @param location the location to be inserted
   * @param lastRecordedLocation the last recorded location before this one (or
   *        null if none)
   * @param lastRecordedLocationId the id of the last recorded location (or -1
   *        if none)
   * @param trackId the id of the track
   * @return true if successful. False if SQLite3 threw an exception.
   */
  private boolean insertLocation(Track recordingTrack, Location location,
      Location lastRecordedLocation, long lastRecordedLocationId,
      long trackId) {

    // Keep track of length along recorded track (needed when a waypoint is
    // inserted):
    if (MyTracksUtils.isValidLocation(location)) {
      if (lastValidLocation != null) {
        length += location.distanceTo(lastValidLocation);
      }
      lastValidLocation = location;
    }

    // Insert the new location:
    try {
      Uri pointUri = providerUtils.insertTrackPoint(location, trackId);
      int pointId = Integer.parseInt(pointUri.getLastPathSegment());

      // Update the current track:
      if (lastRecordedLocation != null
          && lastRecordedLocation.getLatitude() < 90) {
        ContentValues values = new ContentValues();
        TripStatistics stats = statsBuilder.getStatistics();
        if (recordingTrack.getStartId() < 0) {
          values.put(TracksColumns.STARTID, pointId);
          recordingTrack.setStartId(pointId);
        }
        values.put(TracksColumns.STOPID, pointId);
        values.put(TracksColumns.STOPTIME, System.currentTimeMillis());
        values.put(TracksColumns.NUMPOINTS,
            recordingTrack.getNumberOfPoints() + 1);
        values.put(TracksColumns.MINLAT, stats.getBottom());
        values.put(TracksColumns.MAXLAT, stats.getTop());
        values.put(TracksColumns.MINLON, stats.getLeft());
        values.put(TracksColumns.MAXLON, stats.getRight());
        values.put(TracksColumns.TOTALDISTANCE, stats.getTotalDistance());
        values.put(TracksColumns.TOTALTIME, stats.getTotalTime());
        values.put(TracksColumns.MOVINGTIME, stats.getMovingTime());
        values.put(TracksColumns.AVGSPEED, stats.getAverageSpeed());
        values.put(TracksColumns.AVGMOVINGSPEED, stats.getAverageMovingSpeed());
        values.put(TracksColumns.MAXSPEED, stats.getMaxSpeed());
        values.put(TracksColumns.MINELEVATION, stats.getMinElevation());
        values.put(TracksColumns.MAXELEVATION, stats.getMaxElevation());
        values.put(TracksColumns.ELEVATIONGAIN, stats.getTotalElevationGain());
        values.put(TracksColumns.MINGRADE, stats.getMinGrade());
        values.put(TracksColumns.MAXGRADE, stats.getMaxGrade());
        getContentResolver().update(TracksColumns.CONTENT_URI,
            values, "_id=" + recordingTrack.getId(), null);
        updateCurrentWaypoint();
      }
    } catch (SQLiteException e) {
      // Insert failed, most likely because of SqlLite error code 5
      // (SQLite_BUSY). This is expected to happen extremely rarely (if our
      // listener gets invoked twice at about the same time).
      Log.w(MyTracksConstants.TAG,
          "Caught SQLiteException: " + e.getMessage(), e);
      return false;
    }
    splitManager.updateSplits();
    return true;
  }

  private void updateCurrentWaypoint() {
    if (currentWaypointId >= 0) {
      ContentValues values = new ContentValues();
      TripStatistics waypointStats = waypointStatsBuilder.getStatistics();
      values.put(WaypointsColumns.STARTTIME, waypointStats.getStartTime());
      values.put(WaypointsColumns.LENGTH, length);
      values.put(WaypointsColumns.DURATION, System.currentTimeMillis()
          - statsBuilder.getStatistics().getStartTime());
      values.put(WaypointsColumns.TOTALDISTANCE,
          waypointStats.getTotalDistance());
      values.put(WaypointsColumns.TOTALTIME, waypointStats.getTotalTime());
      values.put(WaypointsColumns.MOVINGTIME, waypointStats.getMovingTime());
      values.put(WaypointsColumns.AVGSPEED, waypointStats.getAverageSpeed());
      values.put(WaypointsColumns.AVGMOVINGSPEED,
          waypointStats.getAverageMovingSpeed());
      values.put(WaypointsColumns.MAXSPEED, waypointStats.getMaxSpeed());
      values.put(WaypointsColumns.MINELEVATION,
          waypointStats.getMinElevation());
      values.put(WaypointsColumns.MAXELEVATION,
          waypointStats.getMaxElevation());
      values.put(WaypointsColumns.ELEVATIONGAIN,
          waypointStats.getTotalElevationGain());
      values.put(WaypointsColumns.MINGRADE, waypointStats.getMinGrade());
      values.put(WaypointsColumns.MAXGRADE, waypointStats.getMaxGrade());
      getContentResolver().update(WaypointsColumns.CONTENT_URI,
          values, "_id=" + currentWaypointId, null);
    }
  }

  /**
   * Tries to acquire a partial wake lock if not already acquired. Logs errors
   * and gives up trying in case the wake lock cannot be acquired.
   */
  public void acquireWakeLock() {
    try {
      PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
      if (pm == null) {
        Log.e(MyTracksConstants.TAG,
            "TrackRecordingService: Power manager not found!");
        return;
      }
      if (wakeLock == null) {
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            MyTracksConstants.TAG);
        if (wakeLock == null) {
          Log.e(MyTracksConstants.TAG,
              "TrackRecordingService: Could not create wake lock (null).");
          return;
        }
      }
      if (!wakeLock.isHeld()) {
        wakeLock.acquire();
        if (!wakeLock.isHeld()) {
          Log.e(MyTracksConstants.TAG,
              "TrackRecordingService: Could not acquire wake lock.");
        }
      }
    } catch (RuntimeException e) {
      Log.e(MyTracksConstants.TAG,
          "TrackRecordingService: Caught unexpected exception: "
          + e.getMessage(), e);
    }
  }

  /**
   * Shows the notification message and icon in the notification bar.
   */
  public void showNotification() {
    final ApiPlatformAdapter apiPlatformAdapter =
        ApiFeatures.getInstance().getApiPlatformAdapter();
    if (isRecording) {
      Notification notification = new Notification(
          R.drawable.arrow_320, null /* tickerText */,
          System.currentTimeMillis());
      PendingIntent contentIntent = PendingIntent.getActivity(
          this, 0 /* requestCode */, new Intent(this, MyTracks.class),
          0 /* flags */);
      notification.setLatestEventInfo(this, getString(R.string.app_name),
          getString(R.string.recording_your_track), contentIntent);
      notification.flags += Notification.FLAG_NO_CLEAR;
      apiPlatformAdapter.startForeground(this, notificationManager, 1,
          notification);
    } else {
      apiPlatformAdapter.stopForeground(this, notificationManager, 1);
    }
  }

  public void registerLocationListener() {
    if (locationManager == null) {
      Log.e(MyTracksConstants.TAG,
          "TrackRecordingService: Do not have any location manager.");
      return;
    }
    Log.d(MyTracksConstants.TAG,
        "Preparing to register location listener w/ TrackRecordingService...");
    try {
      long desiredInterval = locationListenerPolicy.getDesiredPollingInterval();
      locationManager.requestLocationUpdates(
          MyTracksConstants.GPS_PROVIDER, desiredInterval,
          locationListenerPolicy.getMinDistance(),
          // , 0 /* minDistance, get all updates to properly time pauses */
          TrackRecordingService.this);
      currentRecordingInterval = desiredInterval;
      Log.d(MyTracksConstants.TAG,
          "...location listener now registered w/ TrackRecordingService @ "
          + currentRecordingInterval);
    } catch (RuntimeException e) {
      Log.e(MyTracksConstants.TAG,
          "Could not register location listener: " + e.getMessage(), e);
    }
  }

  public void unregisterLocationListener() {
    if (locationManager == null) {
      Log.e(MyTracksConstants.TAG,
          "TrackRecordingService: Do not have any location manager.");
      return;
    }
    locationManager.removeUpdates(this);
    Log.d(MyTracksConstants.TAG,
        "Location listener now unregistered w/ TrackRecordingService.");
  }
  
  private Track getRecordingTrack() {
    if (recordingTrackId < 0) {
      return null;
    }

    return providerUtils.getTrack(recordingTrackId);
  }

  private void restoreStats(Track track) {
    Log.d(MyTracksConstants.TAG,
        "Restoring stats of track with ID: " + track.getId());
    
    TripStatistics stats = track.getStatistics();
    statsBuilder = new TripStatisticsBuilder(stats.getStartTime());
    setUpAnnouncer();

    signalManager.restore();
    splitManager.restore();
    length = 0;
    lastValidLocation = null;

    Waypoint waypoint = providerUtils.getFirstWaypoint(recordingTrackId);
    if (waypoint != null) {
      currentWaypointId = waypoint.getId();
      waypointStatsBuilder = new TripStatisticsBuilder(
          waypoint.getStatistics());
    } else {
      // This should never happen, but we got to do something so life goes on:
      waypointStatsBuilder = new TripStatisticsBuilder(stats.getStartTime());
      currentWaypointId = -1;
    }

    Cursor cursor = null;
    try {
      cursor = providerUtils.getLocationsCursor(
          recordingTrackId, -1, MyTracksConstants.MAX_LOADED_TRACK_POINTS,
          true);
      if (cursor != null) {
        if (cursor.moveToLast()) {
          do {
            Location location = providerUtils.createLocation(cursor);
            if (MyTracksUtils.isValidLocation(location)) {
              statsBuilder.addLocation(location, location.getTime());
              if (lastValidLocation != null) {
                length += location.distanceTo(lastValidLocation);
              }
              lastValidLocation = location;
            }
          } while (cursor.moveToPrevious());
        }
        statsBuilder.getStatistics().setMovingTime(stats.getMovingTime());
        statsBuilder.pauseAt(stats.getStopTime());
        statsBuilder.resumeAt(System.currentTimeMillis());
      } else {
        Log.e(MyTracksConstants.TAG, "Could not get track points cursor.");
      }
    } catch (RuntimeException e) {
      Log.e(MyTracksConstants.TAG, "Error while restoring track.", e);
    } finally {
      if (cursor != null) {
        cursor.close();
      }
    }

    splitManager.calculateNextSplit();
  }

  /*
   * Location listener implementation: =================================
   */

  @Override
  public void onLocationChanged(Location location) {
    Log.d(MyTracksConstants.TAG, "TrackRecordingService.onLocationChanged");

    try {
      // Don't record if the service has been asked to pause recording:
      if (!isRecording) {
        Log.w(MyTracksConstants.TAG,
            "Not recording because recording has been paused.");
        return;
      }

      // This should never happen, but just in case (we really don't want the
      // service to crash):
      if (location == null) {
        Log.w(MyTracksConstants.TAG,
            "Location changed, but location is null.");
        return;
      }

      // Don't record if the accuracy is too bad:
      if (location.getAccuracy() > minRequiredAccuracy) {
        Log.d(MyTracksConstants.TAG,
            "Not recording. Bad accuracy.");
        return;
      }

      // At least one track must be available for appending points:
      recordingTrack = getRecordingTrack();
      if (recordingTrack == null) {
        Log.d(MyTracksConstants.TAG,
            "Not recording. No track to append to available.");
        return;
      }

      if (MyTracksUtils.isValidLocation(location)) {
        long now = System.currentTimeMillis();
        statsBuilder.addLocation(location, now);
        waypointStatsBuilder.addLocation(location, now);
      }

      // Update the idle time if needed.
      locationListenerPolicy.updateIdleTime(statsBuilder.getIdleTime());
      if (currentRecordingInterval !=
          locationListenerPolicy.getDesiredPollingInterval()) {
        registerLocationListener();
      }

      Location lastRecordedLocation = providerUtils.getLastLocation();
      long lastRecordedLocationId =
          providerUtils.getLastLocationId(recordingTrackId);
      double distanceToLastRecorded = Double.POSITIVE_INFINITY;
      if (lastRecordedLocation != null) {
        distanceToLastRecorded = location.distanceTo(lastRecordedLocation);
      }
      double distanceToLast = Double.POSITIVE_INFINITY;
      if (lastLocation != null) {
        distanceToLast = location.distanceTo(lastLocation);
      }

      // If the user has been stationary for two recording just record the first
      // two and ignore the rest. This code will only have an effect if the
      // maxRecordingDistance = 0
      if (distanceToLast == 0) {
        if (isMoving) {
          Log.d(MyTracksConstants.TAG, "Found two identical locations.");
          isMoving = false;
          if (lastLocation != null && lastRecordedLocation != null
              && !lastRecordedLocation.equals(lastLocation)) {
            // Need to write the last location. This will happen when
            // lastRecordedLocation.distance(lastLocation) <
            // minRecordingDistance
            if (!insertLocation(recordingTrack, lastLocation,
                lastRecordedLocation, lastRecordedLocationId,
                recordingTrackId)) {
              return;
            }
            lastRecordedLocationId++;
          }
        } else {
          Log.d(MyTracksConstants.TAG,
              "Not recording. More than two identical locations.");
        }
      } else if (distanceToLastRecorded > minRecordingDistance) {
        if (lastLocation != null && !isMoving) {
          // Last location was the last stationary location. Need to go back and
          // add it.
          if (!insertLocation(recordingTrack, lastLocation,
              lastRecordedLocation, lastRecordedLocationId, recordingTrackId)) {
            return;
          }
          lastRecordedLocationId++;
          isMoving = true;
        }

        // If separation from last recorded point is too large insert a
        // separator to indicate end of a segment:
        boolean startNewSegment =
            lastRecordedLocation != null
                && lastRecordedLocation.getLatitude() < 90
                && distanceToLastRecorded > maxRecordingDistance
                && recordingTrack.getStartId() >= 0;
        if (startNewSegment) {
          // Insert a separator point to indicate start of new track:
          Log.d(MyTracksConstants.TAG, "Inserting a separator.");
          Location separator = new Location(MyTracksConstants.GPS_PROVIDER);
          separator.setLongitude(0);
          separator.setLatitude(100);
          separator.setTime(lastRecordedLocation.getTime());
          providerUtils.insertTrackPoint(separator, recordingTrackId);
        }

        if (!insertLocation(recordingTrack, location, lastRecordedLocation,
            lastRecordedLocationId, recordingTrackId)) {
          return;
        }
      } else {
        Log.d(MyTracksConstants.TAG, String.format(
            "Not recording. Distance to last recorded point (%f m) is less than"
            + " %d m.", distanceToLastRecorded, minRecordingDistance));
      }
    } catch (Error e) {
      // Probably important enough to rethrow.
      Log.e(MyTracksConstants.TAG, "Error in onLocationChanged", e);
      throw e;
    } catch (RuntimeException e) {
      // Safe usually to trap exceptions.
      Log.e(MyTracksConstants.TAG,
          "Trapping exception in onLocationChanged", e);
      throw e;
    }
    lastLocation = location;
  }

  @Override
  public void onProviderDisabled(String provider) {
    // Do nothing
  }

  @Override
  public void onProviderEnabled(String provider) {
    // Do nothing
  }

  @Override
  public void onStatusChanged(String provider, int status, Bundle extras) {
    // Do nothing
  }

  /*
   * SharedPreferencesChangeListener interface implementation. Note that
   * services don't currently receive this event (Android platform limitation).
   * This should be called from an activity whenever settings change.
   */

  /**
   * Notifies that preferences have changed.
   * Call this with key == null to update all preferences in one call.
   *
   * @param key the key that changed (may be null to update all preferences)
   */
  public void onSharedPreferenceChanged(String key) {
    Log.d(MyTracksConstants.TAG,
        "TrackRecordingService.onSharedPreferenceChanged");
    prefManager.onSharedPreferenceChanged(key);

    if (isRecording) {
      registerLocationListener();
    }
  }

  /*
   * Application lifetime events: ============================
   */

  @Override
  public void onCreate() {
    super.onCreate();
    Log.d(MyTracksConstants.TAG, "TrackRecordingService.onCreate");
    providerUtils = MyTracksProviderUtils.Factory.get(this);
    notificationManager =
        (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
    splitManager = new SplitManager(this);

    SignalStrengthTaskFactory strengthTaskFactory =
        new SignalStrengthTaskFactory(ApiFeatures.getInstance());
    signalManager =
        new TaskExecuterManager(-1, strengthTaskFactory.create(this), this);

    prefManager = new PreferenceManager(this);
    prefManager.onSharedPreferenceChanged(null);
    registerLocationListener();
    acquireWakeLock();
    /**
     * After 5 min, check every minute that location listener still is
     * registered and spit out additional debugging info to the logs:
     */
    timer.schedule(checkLocationListener, 1000 * 60 * 5, 1000 * 60);

    // Try to restore previous recording state in case this service has been
    // restarted by the system, which can sometimes happen.
    recordingTrack = getRecordingTrack();
    if (recordingTrack != null) {
      restoreStats(recordingTrack);
      isRecording = true;
    } else {
      if (recordingTrackId != -1) {
        // Make sure we have consistent state in shared preferences.
        Log.w(MyTracksConstants.TAG, "TrackRecordingService.onCreate: "
            + "Resetting an orphaned recording track = " + recordingTrackId);
      }
      prefManager.setRecordingTrack(recordingTrackId = -1);
    }
    showNotification();
  }

  /**
   * Creates an {@link Executer} and schedules {@class SafeStatusAnnouncerTask}.
   * The announcer requires a TTS service and user should have enabled
   * the announcements, otherwise this method is no-op.
   */
  private void setUpAnnouncer() {
    Log.d(MyTracksConstants.TAG, "TrackRecordingService.setUpAnnouncer: "
        + announcementExecuter);
    if (announcementFrequency == -1 || recordingTrackId == -1) {
      shutdownAnnouncer();
      return;
    }
    handler.post(new Runnable() {
      public void run() {
        if (announcementExecuter == null) {
          StatusAnnouncerFactory statusAnnouncerFactory =
              new StatusAnnouncerFactory(ApiFeatures.getInstance());
          PeriodicTask announcer = statusAnnouncerFactory.create(TrackRecordingService.this);
          if (announcer == null) return;
  
          announcer.start();
          announcementExecuter = new PeriodicTaskExecuter(announcer, TrackRecordingService.this);
        }
        announcementExecuter.scheduleTask(announcementFrequency * 60000);
      }
    });
  }
  
  private void shutdownAnnouncer() {
    Log.d(MyTracksConstants.TAG, "TrackRecordingService.shutdownAnnouncer: "
        + announcementExecuter);
    if (announcementExecuter != null) {
      try {
        announcementExecuter.shutdown();
      } finally {
        announcementExecuter = null;
      }
    }
  }

  @Override
  public void onDestroy() {
    Log.d(MyTracksConstants.TAG, "TrackRecordingService.onDestroy");
    checkLocationListener.cancel();
    timer.cancel();
    if (wakeLock != null && wakeLock.isHeld()) {
      wakeLock.release();
    }
    isRecording = false;
    showNotification();
    unregisterLocationListener();
    shutdownAnnouncer();
    splitManager.shutdown();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    Log.d(MyTracksConstants.TAG, "TrackRecordingService.onBind");
    return binder;
  }

  @Override
  public boolean onUnbind(Intent intent) {
    Log.d(MyTracksConstants.TAG, "TrackRecordingService.onUnbind");
    return super.onUnbind(intent);
  }

  @Override
  public boolean stopService(Intent name) {
    Log.d(MyTracksConstants.TAG, "TrackRecordingService.stopService");
    unregisterLocationListener();
    return super.stopService(name);
  }
  
  @Override
  public void onStart(Intent intent, int startId) {
    handleStartCommand(intent, startId);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    handleStartCommand(intent, startId);
    return START_STICKY;
  }

  private void handleStartCommand(Intent intent, int startId) {
    Log.d(MyTracksConstants.TAG,
        "TrackRecordingService.handleStartCommand: " + startId);

    // Check if called on phone reboot with resume intent.
    if (intent != null &&
        intent.getBooleanExtra(RESUME_TRACK_EXTRA_NAME, false)) {
      Log.d(MyTracksConstants.TAG, "TrackRecordingService: requested resume");
      
      // Make sure that the current track exists and is fresh enough.
      if (recordingTrack == null || !shouldResumeTrack(recordingTrack)) {
        Log.i(MyTracksConstants.TAG,
            "TrackRecordingService: Not resuming, because the previous track ("
            + recordingTrack + ") doesn't exist or is too old");
        isRecording = false;
        prefManager.setRecordingTrack(recordingTrackId = -1); 
        stopSelfResult(startId);
        return;
      }
      
      Log.i(MyTracksConstants.TAG, "TrackRecordingService: resuming");
    }
  }
  
  private void setAutoResumeTrackRetries(
      SharedPreferences sharedPreferences, int retryAttempts) {
    Log.d(MyTracksConstants.TAG,
        "Updating auto-resume retry attempts to: " + retryAttempts);
    prefManager.setAutoResumeTrackCurrentRetry(retryAttempts);
  }
  
  private boolean shouldResumeTrack(Track track) {
    Log.d(MyTracksConstants.TAG, "shouldResumeTrack: autoResumeTrackTimeout = "
        + autoResumeTrackTimeout);

    // Check if we haven't exceeded the maximum number of retry attempts.
    SharedPreferences sharedPreferences =
        getSharedPreferences(MyTracksSettings.SETTINGS_NAME, 0); 
    int retries = sharedPreferences.getInt(
        getString(R.string.auto_resume_track_current_retry_key), 0);
    Log.d(MyTracksConstants.TAG,
        "shouldResumeTrack: Attempting to auto-resume the track ("
        + (retries + 1) + "/" + MAX_AUTO_RESUME_TRACK_RETRY_ATTEMPTS + ")");
    if (retries >= MAX_AUTO_RESUME_TRACK_RETRY_ATTEMPTS) {
      Log.i(MyTracksConstants.TAG,
          "shouldResumeTrack: Not resuming because exceeded the maximum "
          + "number of auto-resume retries");
      return false;
    }

    // Increase number of retry attempts.
    setAutoResumeTrackRetries(sharedPreferences, retries + 1);

    // Check for special cases.
    if (autoResumeTrackTimeout == 0) {
      // Never resume.  
      Log.d(MyTracksConstants.TAG,
          "shouldResumeTrack: Auto-resume disabled (never resume)");
      return false;
    } else if (autoResumeTrackTimeout == -1) {
      // Always resume.
      Log.d(MyTracksConstants.TAG,
          "shouldResumeTrack: Auto-resume forced (always resume)");
      return true;
    }

    // Check if the last modified time is within the acceptable range.
    long lastModified =
        track.getStatistics() != null ? track.getStatistics().getStopTime() : 0;
    Log.d(MyTracksConstants.TAG,
        "shouldResumeTrack: lastModified = " + lastModified
        + ", autoResumeTrackTimeout: " + autoResumeTrackTimeout);
    return lastModified > 0 && System.currentTimeMillis() - lastModified <=
        autoResumeTrackTimeout * 60 * 1000;  
  }

  public boolean isRecording() {
    return isRecording;
  }

  public long insertWaypointMarker(Waypoint waypoint) {
    if (!isRecording()) {
      throw new IllegalStateException(
          "Unable to insert waypoint marker while not recording!");
    }
    
    if (waypoint.getLocation() != null) {
      waypoint.setLength(length);
      waypoint.setDuration(waypoint.getLocation().getTime()
          - statsBuilder.getStatistics().getStartTime());
      Uri uri = providerUtils.insertWaypoint(waypoint);
      return Long.parseLong(uri.getLastPathSegment());
    }
    return -1;
  }

  /**
   * Inserts a statistics marker. A statistics marker holds the stats for the
   * last segment up to this marker.
   *
   * @param location the location where to insert
   * @return the unique id of the inserted marker
   */
  public long insertStatisticsMarker(Location location) {
    if (!isRecording()) {
      throw new IllegalStateException(
          "Unable to insert statistics marker while not recording!");
    }
    
    StringUtils utils = new StringUtils(TrackRecordingService.this);

    // Create a new waypoint to save
    Waypoint waypoint = new Waypoint();

    // Set stop and total time in the stats data
    final long time = System.currentTimeMillis();
    waypointStatsBuilder.pauseAt(time);

    // Override the duration - it's not the duration from the last waypoint, but
    // the duration from the beginning of the whole track
    waypoint.setDuration(time - statsBuilder.getStatistics().getStartTime());

    // Set the rest of the waypoint data
    waypoint.setTrackId(recordingTrackId);
    waypoint.setType(Waypoint.TYPE_STATISTICS);
    waypoint.setName(TrackRecordingService.this.getString(R.string.statistics));
    waypoint.setStatistics(waypointStatsBuilder.getStatistics());
    waypoint.setDescription(utils.generateWaypointDescription(waypoint));
    waypoint.setLocation(location);
    waypoint.setIcon(STATISTICS_ICON_URL);
    waypoint.setLength(length);

    waypoint.setStartId(providerUtils.getLastLocationId(recordingTrackId));
    Uri uri = providerUtils.insertWaypoint(waypoint);

    // Create a new stats keeper for the next marker
    waypointStatsBuilder = new TripStatisticsBuilder(time);
    updateCurrentWaypoint();
    return Long.parseLong(uri.getLastPathSegment());
  }

  /**
   * The ITrackRecordingService is defined through IDL.
   */
  private final ITrackRecordingService.Stub binder =
      new ITrackRecordingService.Stub() {
        @Override
        public boolean isRecording() {
          return TrackRecordingService.this.isRecording();
        }

        @Override
        public long getRecordingTrackId() {
          return recordingTrackId;
        }

        @Override
        public boolean hasRecorded() {
          return providerUtils.getLastTrackId() >= 0;
        }

        @Override
        public long startNewTrack() {
          return TrackRecordingService.this.startNewTrack();
        }

        /**
         * Insert the given waypoint marker. Users can insert waypoint markers
         * to tag locations with a name, description, category etc.
         *
         * @param waypoint a waypoint
         * @return the unique id of the inserted marker
         */
        @Override
        public long insertWaypointMarker(Waypoint waypoint) {
          return TrackRecordingService.this.insertWaypointMarker(waypoint);
        }

        /**
         * Insert a statistics marker. A statistics marker holds the stats for
         * the last segment up to this marker.
         *
         * @param location the location where to insert
         * @return the unique id of the inserted marker
         */
        @Override
        public long insertStatisticsMarker(Location location) {
          return TrackRecordingService.this.insertStatisticsMarker(location);
        }

        @Override
        public void endCurrentTrack() {
          Log.d(MyTracksConstants.TAG, "TrackRecordingService.endCurrentTrack");
          if (recordingTrackId == -1 || !isRecording) {
            throw new IllegalStateException("No recording track in progress!");
          }
         
          isRecording = false;
          shutdownAnnouncer();
          Track recordingTrack = providerUtils.getTrack(recordingTrackId);
          if (recordingTrack != null) {
            TripStatistics stats = recordingTrack.getStatistics();
            stats.setStopTime(System.currentTimeMillis());
            stats.setTotalTime(stats.getStopTime() - stats.getStartTime());
            long lastRecordedLocationId =
                providerUtils.getLastLocationId(recordingTrackId);
            ContentValues values = new ContentValues();
            if (lastRecordedLocationId >= 0
                && recordingTrack.getStopId() >= 0) {
              values.put(TracksColumns.STOPID, lastRecordedLocationId);
            }
            values.put(TracksColumns.STOPTIME, stats.getStopTime());
            values.put(TracksColumns.TOTALTIME, stats.getTotalTime());
            getContentResolver().update(TracksColumns.CONTENT_URI, values,
                "_id=" + recordingTrack.getId(), null);
          }
          showNotification();
          prefManager.setRecordingTrack(recordingTrackId = -1);
        }

        @Override
        public void deleteAllTracks() {
          if (isRecording()) {
            throw new IllegalStateException(
                "Cannot delete all tracks while recording!");
          }
          providerUtils.deleteAllTracks();
        }

        @Override
        public void recordLocation(Location loc) {
          onLocationChanged(loc);
        }

        @Override
        public void sharedPreferenceChanged(String key) {
          Log.d(MyTracksConstants.TAG,
              "TrackRecordingService.sharedPreferenceChanged: " + key);
          onSharedPreferenceChanged(key);
        }
      };

  public long startNewTrack() {
    Log.d(MyTracksConstants.TAG, "TrackRecordingService.startNewTrack");
    if (recordingTrackId != -1 || isRecording) {
      throw new IllegalStateException("A track is already in progress!");
    }
    
    long startTime = System.currentTimeMillis();

    Track track = new Track();
    TripStatistics trackStats = track.getStatistics();
    trackStats.setStartTime(startTime);
    track.setStartId(-1);
    Uri trackUri = providerUtils.insertTrack(track);
    recordingTrackId = Long.parseLong(trackUri.getLastPathSegment());
    track.setId(recordingTrackId);
    track.setName(new DefaultTrackNameFactory(this).newTrackName(
        recordingTrackId, startTime));
    isRecording = true;
    isMoving = true;
    
    providerUtils.updateTrack(track);
    statsBuilder = new TripStatisticsBuilder(startTime);
    waypointStatsBuilder = new TripStatisticsBuilder(startTime);
    currentWaypointId = insertStatisticsMarker(null);
    setUpAnnouncer();
    length = 0;
    showNotification();
    registerLocationListener();
    splitManager.restore();
    signalManager.restore();

    // Reset the number of auto-resume retries.
    setAutoResumeTrackRetries(
        getSharedPreferences(MyTracksSettings.SETTINGS_NAME, 0), 0);
    // Persist the current recording track.
    prefManager.setRecordingTrack(recordingTrackId);
    
    return recordingTrackId;
  }

  public TripStatistics getTripStatistics() {
    return statsBuilder.getStatistics();
  }

  Location getLastLocation() {
    return lastLocation;
  }

  long getRecordingTrackId() {
    return recordingTrackId;
  }

  public void setRecordingTrackId(long recordingTrackId) {
    this.recordingTrackId = recordingTrackId;
  }

  public int getAnnouncementFrequency() {
    return announcementFrequency;
  }

  public void setAnnouncementFrequency(int announcementFrequency) {
    this.announcementFrequency = announcementFrequency;
    Log.d(MyTracksConstants.TAG, "TrackRecordingService.setAnnouncerFrequency:"
        + this.announcementFrequency);
    if (announcementFrequency == -1) {
      shutdownAnnouncer();
    } else {
      setUpAnnouncer();
    }
  }

  public int getMaxRecordingDistance() {
    return maxRecordingDistance;
  }

  public void setMaxRecordingDistance(int maxRecordingDistance) {
    this.maxRecordingDistance = maxRecordingDistance;
  }

  public int getMinRecordingDistance() {
    return minRecordingDistance;
  }

  public void setMinRecordingDistance(int minRecordingDistance) {
    this.minRecordingDistance = minRecordingDistance;
  }

  public int getMinRequiredAccuracy() {
    return minRequiredAccuracy;
  }

  public void setMinRequiredAccuracy(int minRequiredAccuracy) {
    this.minRequiredAccuracy = minRequiredAccuracy;
  }

  public LocationListenerPolicy getLocationListenerPolicy() {
    return locationListenerPolicy;
  }

  public void setLocationListenerPolicy(
      LocationListenerPolicy locationListenerPolicy) {
    this.locationListenerPolicy = locationListenerPolicy;
  }
  
  public int getAutoResumeTrackTimeout() {
    return autoResumeTrackTimeout;
  }
  
  public void setAutoResumeTrackTimeout(int autoResumeTrackTimeout) {
    this.autoResumeTrackTimeout = autoResumeTrackTimeout;
  }

  public SplitManager getSplitManager() {
    return splitManager;
  }

  public TaskExecuterManager getSignalManager() {
    return signalManager;
  }
}
