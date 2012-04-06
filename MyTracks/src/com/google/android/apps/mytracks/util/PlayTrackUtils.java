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

package com.google.android.apps.mytracks.util;

import com.google.android.apps.mytracks.io.file.SaveActivity;
import com.google.android.apps.mytracks.io.file.TrackWriterFactory.TrackFileFormat;
import com.google.android.maps.mytracks.R;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Parcelable;

import java.util.List;

/**
 * Utilities for playing a track on Google Earth.
 *
 * @author Jimmy Shih
 */
public class PlayTrackUtils {

  /**
   * KML mime type.
   */
  public static final String KML_MIME_TYPE = "application/vnd.google-earth.kml+xml";
  public static final String TOUR_FEATURE_ID = "com.google.earth.EXTRA.tour_feature_id";
  
  public static final String GOOGLE_EARTH_PACKAGE = "com.google.earth";
  public static final String GOOGLE_EARTH_CLASS = "com.google.earth.EarthActivity";
  private static final String EARTN_MARKET_URI = "market://details?id=" + GOOGLE_EARTH_PACKAGE;

  private PlayTrackUtils() {}

  /**
   * Returns true if a Google Earth app that can handle KML mine type is
   * installed.
   *
   * @param context the context
   */
  public static boolean isEarthInstalled(Context context) {
    List<ResolveInfo> infos = context.getPackageManager().queryIntentActivities(
        new Intent().setType(KML_MIME_TYPE), PackageManager.MATCH_DEFAULT_ONLY);
    for (ResolveInfo info : infos) {
      if (info.activityInfo != null && info.activityInfo.packageName != null
          && info.activityInfo.packageName.equals(GOOGLE_EARTH_PACKAGE)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Plays a track by sending an intent to {@link SaveActivity}.
   *
   * @param context the context
   * @param trackId the track id
   */
  public static void playTrack(Context context, long trackId) {
    AnalyticsUtils.sendPageViews(context, "/action/play");

    Intent intent = new Intent(context, SaveActivity.class)
        .putExtra(SaveActivity.EXTRA_TRACK_ID, trackId)
        .putExtra(SaveActivity.EXTRA_TRACK_FILE_FORMAT, (Parcelable) TrackFileFormat.KML)
        .putExtra(SaveActivity.EXTRA_PLAY_TRACK, true);
    context.startActivity(intent);
  }

  /**
   * Creates a dialog to install Google Earth from the Android Market.
   * 
   * @param context the context
   */
  public static Dialog createInstallEarthDialog(final Context context) {
    return new AlertDialog.Builder(context)
        .setCancelable(true)
        .setMessage(R.string.track_detail_install_earth_message)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            Intent intent = new Intent();
            intent.setData(Uri.parse(EARTN_MARKET_URI));
            context.startActivity(intent);
          }
        })
        .create();
  }
}
