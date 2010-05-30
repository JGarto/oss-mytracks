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
package com.google.android.apps.mytracks.io;

import com.google.android.apps.mytracks.content.MyTracksProviderUtils;
import com.google.android.apps.mytracks.content.Track;
import com.google.android.apps.mytracks.stats.TripStatistics;
import com.google.android.apps.mytracks.util.MyTracksUtils;

import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.SimpleTimeZone;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Imports GPX XML files to the my tracks provider
 * 
 * @author Leif Hendrik Wilden
 * @author Steffen Horlacher
 */
public class GpxImporter extends DefaultHandler {

  /**
   * Different date formats used in GPX files
   */
  static final SimpleDateFormat DATE_FORMAT1 = new SimpleDateFormat(
          "yyyy-MM-dd'T'hh:mm:ssZ");
  static final SimpleDateFormat DATE_FORMAT2 = new SimpleDateFormat(
          "yyyy-MM-dd'T'hh:mm:ss'Z'");
  static final SimpleDateFormat DATE_FORMAT3 = new SimpleDateFormat(
          "yyyy-MM-dd'T'HH:mm:ss.SSSZ");
  static final SimpleTimeZone UTC_TIMEZONE = new SimpleTimeZone(0, "UTC");
  
  /**
   * GPX-XML tag names and attributes
   */
  private static final String TAG_TRACK = "trk";
  private static final String TAG_TRACK_POINT = "trkpt";
  private static final String TAG_NAME = "name";
  private static final String TAG_DESCRIPTION = "desc";
  private static final String TAG_ALTITUDE = "ele";
  private static final String TAG_TIME = "time";
  private static final String ATT_LAT = "lat";
  private static final String ATT_LON = "lon";

  final private MyTracksProviderUtils providerUtils;

  /**
   * List of track ids written in the database. Only contains successfully
   * written tracks.
   */
  final private List<Long> tracksWritten;

  /**
   * Contains the current elements content
   */
  private StringBuilder content;

  /**
   * Currently reading location
   */
  private Location location;

  /**
   * Previous location, required for calculations
   */
  private Location lastLocation;

  /**
   * Currently reading track
   */
  private Track track;

  /**
   * Statistics object for the current track
   */
  private TripStatistics stats;

  /**
   * Number of locations already processed
   */
  private int numberOfLocations;

  /**
   * Used to identify if a track was written to the database but not yet
   * finished successfully.
   */
  private boolean isCurrentTrackRollbackable;

  /**
   * Flag to indicate if we in a track xml element some sub elements like name
   * may be used in other parts of the gpx file - ignore them
   */
  private boolean isInTrackElement;

  /**
   * Counter to find out which child level of track we are processing 
   */
  private int trackChildDepth;

  /**
   * SAX-Locator to get current line information
   */
  private Locator locator;

  /**
   * Reads GPS tracks from a GPX file and writes tracks and their coordinates to
   * the database.
   * 
   * @param tracks
   *          a list of tracks
   * @param is
   *          a input steam with gpx-xml data
   * @return long[] array of track ids written in the database
   * @throws SAXException
   *           a parsing error
   * @throws ParserConfigurationException
   *           internal error
   * @throws IOException
   *           a file reading problem
   */
  public static long[] importGPXFile(final InputStream is,
          final MyTracksProviderUtils providerUtils)
          throws ParserConfigurationException, SAXException, IOException {

    SAXParserFactory factory = SAXParserFactory.newInstance();
    GpxImporter handler = new GpxImporter(providerUtils);
    SAXParser parser = factory.newSAXParser();
    long[] trackIds = null;

    try {
      parser.parse(is, handler);
      trackIds = handler.getImportedTrackIds();
    } finally {
      // delete track if not finished
      handler.rollbackUnfinishedTracks();
    }

    return trackIds;
  }

  /**
   * Constructor, requires providerUtils for writing tracks the database.
   */
  public GpxImporter(MyTracksProviderUtils providerUtils) {
    this.providerUtils = providerUtils;
    tracksWritten = new ArrayList<Long>();
    content = new StringBuilder();
  }

  @Override
  public void characters(char[] ch, int start, int length) throws SAXException {
    content.append(ch, start, length);
  }

  @Override
  public void startElement(String uri, String localName, String name,
          Attributes attributes) throws SAXException {

    // reset element content
    content.setLength(0);

    if (localName.equalsIgnoreCase(TAG_TRACK)) {

      // test if we are already in a track element - abort in this case
      if (isInTrackElement) {
        String msg = createErrorMessage("Invalid GPX-XML detected");
        throw new SAXException(msg);
      }

      isInTrackElement = true;
      trackChildDepth = 0;
      onTrackElementStart();

      // process this element only as sub-elements of track
    } else if (isInTrackElement) {

      trackChildDepth++;
      if (localName.equalsIgnoreCase(TAG_TRACK_POINT)) {
        onTrackPointElementStart(attributes);
      }
    }
  }

  @Override
  public void endElement(String uri, String localName, String name)
          throws SAXException {

    if (localName.equalsIgnoreCase(TAG_TRACK)) {
      onTrackElementEnd();
      isInTrackElement = false;
      trackChildDepth = 0;

      // process these elements only as sub-elements of track
    } else if (isInTrackElement) {

      if (localName.equalsIgnoreCase(TAG_TRACK_POINT)) {
        onTrackPointElementEnd();
      } else if (localName.equalsIgnoreCase(TAG_ALTITUDE)) {
        onAltitudeElementEnd();
      } else if (localName.equalsIgnoreCase(TAG_TIME)) {
        onTimeElementEnd();
      } else if (localName.equalsIgnoreCase(TAG_NAME)) {
        // we are only interested in the first level name element
        if (trackChildDepth == 1) {
          onNameElementEnd();
        }
      } else if (localName.equalsIgnoreCase(TAG_DESCRIPTION)) {
        // we are only interested in the first level description element
        if (trackChildDepth == 1) {
          onDescriptionElementEnd();
        }
      }
      trackChildDepth--;
    }

    // reset element content
    content.setLength(0);
  }

  @Override
  public void setDocumentLocator(Locator locator) {
    this.locator = locator;
  }

  /**
   * Create a new Track object and insert empty track in database. Track will be
   * updated with missing values later.
   */
  private void onTrackElementStart() {

    track = new Track();
    numberOfLocations = 0;

    Uri trackUri = providerUtils.insertTrack(track);
    long trackId = Long.parseLong(trackUri.getLastPathSegment());
    track.setId(trackId);
    isCurrentTrackRollbackable = true;
  }

  /**
   * Reads trackpoint attributes and assigns them to the current location
   * 
   * @param attributes
   *          xml attributes
   */
  private void onTrackPointElementStart(Attributes attributes) {
    location = createLocationFromAttributes(attributes);
  }

  private Location createLocationFromAttributes(Attributes attributes) {
    String latitude = null;
    String longitude = null;

    for (int i = 0; i < attributes.getLength(); i++) {
      if (attributes.getLocalName(i).equals(ATT_LAT)) {
        latitude = attributes.getValue(i);
      } else if (attributes.getLocalName(i).equals(ATT_LON)) {
        longitude = attributes.getValue(i);
      }
    }

    // create new location and set attributes
    Location loc = new Location(LocationManager.GPS_PROVIDER);
    loc.setLatitude(Double.parseDouble(latitude));
    loc.setLongitude(Double.parseDouble(longitude));
    return loc;
  }

  private void onDescriptionElementEnd() {
    track.setDescription(content.toString().trim());
  }

  private void onNameElementEnd() {
    track.setName(content.toString().trim());
  }

  /**
   * Track point finished, write in database
   * 
   * @throws SAXException
   *           - thrown if track point is invalid
   */
  private void onTrackPointElementEnd() throws SAXException {

    if (MyTracksUtils.isValidLocation(location)) {

      stats.addLocation(location, location.getTime());

      // insert in db
      Uri trackPointIdUri = providerUtils.insertTrackPoint(location, track
              .getId());

      // set start and stop id for track
      long trackPointId = Long.parseLong(trackPointIdUri.getLastPathSegment());

      // first track point?
      if (lastLocation == null) {
        track.setStartId(trackPointId);
      }
      // location has no setId method
      // updating stop id on track every time...
      track.setStopId(trackPointId);

      lastLocation = location;
      numberOfLocations++;
    } else {

      // invalid location - abort import
      String msg = createErrorMessage("Invalid location detected: " + location);
      throw new SAXException(msg);
    }
  }

  /**
   * Track finished - update in database
   */
  private void onTrackElementEnd() {

    if (lastLocation != null) {

      // Calculate statistics for the imported track and update
      stats.pauseAt(lastLocation.getTime());
      track.setStopTime(lastLocation.getTime());
      track.setNumberOfPoints(numberOfLocations);
      stats.fillStatisticsForTrack(track);
      providerUtils.updateTrack(track);
      tracksWritten.add(track.getId());
      isCurrentTrackRollbackable = false;
      lastLocation = null;
      stats = null;

    } else {

      // track contains no track points makes not really
      // sense to import it as we have no location
      // information -> roll back
      rollbackUnfinishedTracks();
    }
  }

  /**
   * Setting time and doing additional calculations as this is the last value
   * required. Also sets the start time for track and statistics as there is no
   * start time in the track root element
   * 
   * @throws SAXException
   *           on parsing errors
   */
  private void onTimeElementEnd() throws SAXException {

    long time = parseTimeForAllFormats(content.toString().trim());

    if (location != null) {

      // check for negative time change
      if (lastLocation != null) {
        long timeDifference = time - lastLocation.getTime();
        if (timeDifference < 0) {
          String msg = createErrorMessage("Found negative time change.");
          throw new SAXException(msg);
        }
      }
      
      location.setTime(time);
      // initialize start time with time of first track point
      if (stats == null) {
        stats = new TripStatistics(time);
        track.setStartTime(time);
      }

      // We don't have a speed and bearing in GPX, make something up from
      // the last two points.
      // TODO GPS points tend to have some inherent imprecision,
      // speed and bearing will likely be off, so the statistics for things like
      // max speed will also be off.
      if (lastLocation != null) {
        final long dt = location.getTime() - lastLocation.getTime();
        if (dt > 0) {
          final float speed = location.distanceTo(lastLocation) / (dt / 1000);
          location.setSpeed(speed);
        }
        location.setBearing(lastLocation.bearingTo(location));
      }
    }
  }

  private void onAltitudeElementEnd() {
    if (location != null) {
      String altitude = content.toString().trim();
      location.setAltitude(Double.parseDouble(altitude));
    }

  }

  /**
   * If a exception is thrown during the import callers must execute this method
   * in the catch (also finally is ok) clause to avoid inconsistent data.
   */
  public void rollbackUnfinishedTracks() {
    if (isCurrentTrackRollbackable) {
      providerUtils.deleteTrack(track.getId());
      isCurrentTrackRollbackable = false;
    }
  }

  /**
   * Get all track ids of the tracks created by this importer run
   * 
   * @return array of track ids
   */
  private long[] getImportedTrackIds() {
    // Convert from java.lang.Long for convenience
    long[] result = new long[tracksWritten.size()];
    for (int i = 0; i < result.length; i++) {
      result[i] = tracksWritten.get(i);
    }
    return result;
  }

  /**
   * Parse time trying different formats used in GPX files
   * 
   * @param timeContents
   *          string with time information
   * @return time as long
   * @throws SAXException
   *           on time parsing errors
   */
  private long parseTimeForAllFormats(String timeContents) throws SAXException {

    long time = -1;
    
    // 1st try with time zone at end a la "+0000"
    time = parseTime(timeContents, DATE_FORMAT1);
    if(time > -1) {
      return time;
    }

    // if that fails, try with a literal "Z" at the end
    // (this is not according to xml standard, but some gpx files are like
    // that):
    time = parseTime(timeContents, DATE_FORMAT2);
    if(time > -1) {
      return time;
    }

    // some gpx timestamps have 3 additional digits at the end.
    time = parseTime(timeContents, DATE_FORMAT3);
    if(time > -1) {
      return time;
    }

    // everything failed - abort the import
    String msg = createErrorMessage("Invalid time format: " + timeContents);
    throw new SAXException(msg);
  }

  private long parseTime(String timeContents, SimpleDateFormat formatParam) {
    SimpleDateFormat format = formatParam;
    try {
      format.setTimeZone(UTC_TIMEZONE);
      return format.parse(timeContents).getTime();
    } catch (ParseException ex) {
      // do nothing
    }
    return -1;
  }

  /**
   * Builds an parsing error message with current line information
   * 
   * @param details
   *          details about the error, will be appended
   * @return error message string with current line information
   */
  private String createErrorMessage(String details) {
    StringBuffer msg = new StringBuffer();
    msg.append("Parsing error at line: ");
    msg.append(locator.getLineNumber());
    msg.append(" column: ");
    msg.append(locator.getColumnNumber());
    msg.append(". ");
    msg.append(details);
    return msg.toString();
  }

}
