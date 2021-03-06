/*
 * Copyright 2014 Uwe Trottmann
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.battlelancer.seriesguide.thetvdbapi;

import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.sax.Element;
import android.sax.EndElementListener;
import android.sax.EndTextElementListener;
import android.sax.RootElement;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Xml;
import com.battlelancer.seriesguide.R;
import com.battlelancer.seriesguide.dataliberation.JsonExportTask.ShowStatusExport;
import com.battlelancer.seriesguide.dataliberation.model.Show;
import com.battlelancer.seriesguide.items.SearchResult;
import com.battlelancer.seriesguide.provider.SeriesGuideContract.Episodes;
import com.battlelancer.seriesguide.provider.SeriesGuideContract.Seasons;
import com.battlelancer.seriesguide.provider.SeriesGuideContract.Shows;
import com.battlelancer.seriesguide.settings.DisplaySettings;
import com.battlelancer.seriesguide.util.DBUtils;
import com.battlelancer.seriesguide.util.ImageProvider;
import com.battlelancer.seriesguide.util.ServiceUtils;
import com.battlelancer.seriesguide.util.TimeTools;
import com.battlelancer.seriesguide.util.TraktTools;
import com.battlelancer.seriesguide.util.Utils;
import com.jakewharton.trakt.Trakt;
import com.jakewharton.trakt.entities.TvShow;
import com.jakewharton.trakt.enumerations.Extended;
import com.uwetrottmann.androidutils.AndroidUtils;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.zip.ZipInputStream;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import retrofit.RetrofitError;
import timber.log.Timber;

/**
 * Provides access to the TheTVDb.com XML API throwing in some additional data from trakt.tv here
 * and there.
 */
public class TheTVDB {

    public interface ShowStatus {

        int CONTINUING = 1;
        int ENDED = 0;
        int UNKNOWN = -1;
    }

    public static final String TVDB_MIRROR_BANNERS = "http://thetvdb.com/banners/";

    private static final String TVDB_API_URL = "http://thetvdb.com/api/";

    /**
     * Returns true if the given show has not been updated in the last 12 hours.
     */
    public static boolean isUpdateShow(Context context, int showTvdbId) {
        final Cursor show = context.getContentResolver().query(Shows.buildShowUri(showTvdbId),
                new String[] {
                        Shows._ID, Shows.LASTUPDATED
                }, null, null, null
        );
        boolean isUpdate = false;
        if (show != null) {
            if (show.moveToFirst()) {
                long lastUpdateTime = show.getLong(1);
                if (System.currentTimeMillis() - lastUpdateTime > DateUtils.HOUR_IN_MILLIS * 12) {
                    isUpdate = true;
                }
            }
            show.close();
        }
        return isUpdate;
    }

    /**
     * Adds a show and its episodes to the database. If the show already exists, does nothing.
     *
     * @return whether the show and its episodes were added
     */
    public static boolean addShow(int showTvdbId, List<TvShow> seenShows,
            List<TvShow> collectedShows, Context context) throws TvdbException {
        boolean isShowExists = DBUtils.isShowExists(context, showTvdbId);
        if (isShowExists) {
            return false;
        }

        String language = DisplaySettings.getContentLanguage(context);
        final ArrayList<ContentProviderOperation> batch = new ArrayList<>();

        // get show and episode info from TVDb
        Show show = fetchShow(showTvdbId, language, context);
        batch.add(DBUtils.buildShowOp(show, context, true));

        getEpisodesAndUpdateDatabase(context, show, language, batch);

        // try to set watched and collected flags from trakt
        storeTraktFlags(showTvdbId, seenShows, context, true);
        storeTraktFlags(showTvdbId, collectedShows, context, false);

        // calculate the next episode to display
        DBUtils.updateLatestEpisode(context, showTvdbId);

        return true;
    }

    /**
     * Updates show. Adds new, updates changed and removes orphaned episodes.
     */
    public static void updateShow(Context context, int showTvdbId) throws TvdbException {
        String language = DisplaySettings.getContentLanguage(context);
        final ArrayList<ContentProviderOperation> batch = new ArrayList<>();

        Show show = fetchShow(showTvdbId, language, context);
        batch.add(DBUtils.buildShowOp(show, context, false));

        getEpisodesAndUpdateDatabase(context, show, language, batch);
    }

    /**
     * Search for shows which include a certain keyword in their title. Dependent on the TheTVDB
     * search algorithms.
     *
     * @return a List with SearchResult objects, max 100
     */
    public static List<SearchResult> searchShow(String title, Context context)
            throws TvdbException {
        final List<SearchResult> series = new ArrayList<SearchResult>();
        final SearchResult currentShow = new SearchResult();

        RootElement root = new RootElement("Data");
        Element item = root.getChild("Series");
        // set handlers for elements we want to react to
        item.setEndElementListener(new EndElementListener() {
            public void end() {
                series.add(currentShow.copy());
            }
        });
        item.getChild("id").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                currentShow.tvdbid = Integer.valueOf(body);
            }
        });
        item.getChild("SeriesName").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                currentShow.title = body.trim();
            }
        });
        item.getChild("Overview").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                currentShow.overview = body.trim();
            }
        });

        String language = DisplaySettings.getContentLanguage(context);
        String url;
        try {
            url = TVDB_API_URL + "GetSeries.php?seriesname="
                    + URLEncoder.encode(title, "UTF-8")
                    + (language != null ? "&language=" + language : "");
        } catch (UnsupportedEncodingException e) {
            throw new TvdbException("Encoding show title failed", e);
        }

        try {
            InputStream in = null;
            try {
                in = AndroidUtils.downloadUrl(url);
                Xml.parse(in, Xml.Encoding.UTF_8, root.getContentHandler());
            } finally {
                if (in != null) {
                    in.close();
                }
            }
        } catch (IOException | SAXException e) {
            throw new TvdbException("Downloading or parsing search results failed", e);
        }

        return series;
    }

    // Values based on the assumption that sync runs about every 24 hours
    private static final long UPDATE_THRESHOLD_WEEKLYS_MS = 6 * DateUtils.DAY_IN_MILLIS +
            12 * DateUtils.HOUR_IN_MILLIS;
    private static final long UPDATE_THRESHOLD_DAILYS_MS = 1 * DateUtils.DAY_IN_MILLIS
            + 12 * DateUtils.HOUR_IN_MILLIS;

    /**
     * Return list of show TVDb ids hitting a x-day limit.
     */
    public static int[] deltaUpdateShows(long currentTime, Context context) {
        final List<Integer> updatableShowIds = new ArrayList<>();

        // get existing show ids
        final Cursor shows = context.getContentResolver().query(Shows.CONTENT_URI, new String[] {
                Shows._ID, Shows.LASTUPDATED, Shows.AIRSDAYOFWEEK
        }, null, null, null);

        if (shows != null) {
            while (shows.moveToNext()) {
                boolean isDailyShow = TimeTools.getDayOfWeek(shows.getString(2))
                        == TimeTools.RELEASE_DAY_DAILY;
                long lastUpdatedTime = shows.getLong(1);
                // update daily shows more frequently than weekly shows
                if (currentTime - lastUpdatedTime >
                        (isDailyShow ? UPDATE_THRESHOLD_DAILYS_MS : UPDATE_THRESHOLD_WEEKLYS_MS)) {
                    // add shows that are due for updating
                    updatableShowIds.add(shows.getInt(0));
                }
            }

            long showCount = (long) shows.getCount();
            Utils.trackCustomEvent(context, "Statistics", "Shows", String.valueOf(showCount));

            shows.close();
        }

        // copy to int array
        int[] showTvdbIds = new int[updatableShowIds.size()];
        for (int i = 0; i < updatableShowIds.size(); i++) {
            showTvdbIds[i] = updatableShowIds.get(i);
        }
        return showTvdbIds;
    }

    /**
     * Fetches episodes for the given show from TVDb, adds database ops for them. Then adds all
     * information to the database.
     */
    private static boolean getEpisodesAndUpdateDatabase(Context context, Show show,
            String language, final ArrayList<ContentProviderOperation> batch)
            throws TvdbException {
        // get ops for episodes of this show
        ArrayList<ContentValues> importShowEpisodes = fetchEpisodes(batch, show, language,
                context);
        ContentValues[] newEpisodesValues = new ContentValues[importShowEpisodes.size()];
        newEpisodesValues = importShowEpisodes.toArray(newEpisodesValues);

        try {
            DBUtils.applyInSmallBatches(context, batch);
        } catch (OperationApplicationException e) {
            throw new TvdbException("Problem applying batch operation for " + show.tvdbId, e);
        }

        // insert all new episodes in bulk
        context.getContentResolver().bulkInsert(Episodes.CONTENT_URI, newEpisodesValues);

        return true;
    }

    private static void storeTraktFlags(int showTvdbId, List<TvShow> shows, Context context,
            boolean isSeenFlags) {
        // try to find seen episodes from trakt of the given show
        for (TvShow tvShow : shows) {
            if (tvShow == null || tvShow.tvdb_id == null || tvShow.tvdb_id != showTvdbId) {
                // skip, does not match
                continue;
            }

            TraktTools.applyEpisodeFlagChanges(context, tvShow,
                    isSeenFlags ? Episodes.WATCHED : Episodes.COLLECTED, false);

            // done, found the show we were looking for
            return;
        }
    }

    /**
     * Get show details from TVDb in the user preferred language ({@link
     * DisplaySettings#getContentLanguage(android.content.Context)}).
     */
    public static Show getShow(Context context, int showTvdbId) throws TvdbException {
        String language = DisplaySettings.getContentLanguage(context);
        return downloadAndParseShow(context, showTvdbId, language);
    }

    /**
     * Get show details from TVDb. Tries to fetch additional information from trakt.
     *
     * @param language A TVDb language code (see <a href="http://www.thetvdb.com/wiki/index.php/API:languages.xml"
     *                 >TVDb wiki</a>).
     */
    private static Show fetchShow(int showTvdbId, String language, Context context)
            throws TvdbException {
        // get show details from TVDb
        Show show = downloadAndParseShow(context, showTvdbId, language);

        // get some more details from trakt
        TvShow traktShow = null;
        Trakt manager = ServiceUtils.getTrakt(context);
        if (manager != null) {
            try {
                traktShow = manager.showService().summary(showTvdbId, Extended.DEFAULT);
            } catch (RetrofitError e) {
                Timber.e(e, "Downloading summary failed");
            }
        }
        if (traktShow == null) {
            throw new TvdbException("Could not load show from trakt: " + showTvdbId);
        }

        show.airtime = TimeTools.parseShowReleaseTime(traktShow.airTime);
        show.country = traktShow.country;

        // try to download the show poster
        if (Utils.isAllowedLargeDataConnection(context, false)) {
            fetchArt(show.poster, true, context);
        }

        return show;
    }

    /**
     * Get a show from TVDb.
     */
    private static Show downloadAndParseShow(final Context context, int showTvdbId, String language)
            throws TvdbException {
        final Show currentShow = new Show();
        RootElement root = new RootElement("Data");
        Element show = root.getChild("Series");

        // set handlers for elements we want to react to
        show.getChild("id").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                // NumberFormatException may be thrown, will stop parsing
                currentShow.tvdbId = Integer.parseInt(body);
            }
        });
        show.getChild("SeriesName").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                currentShow.title = body;
            }
        });
        show.getChild("Overview").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                currentShow.overview = body;
            }
        });
        show.getChild("Actors").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                currentShow.actors = body.trim();
            }
        });
        show.getChild("Airs_DayOfWeek").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                currentShow.airday = body.trim();
            }
        });
        show.getChild("FirstAired").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                currentShow.firstAired = body;
            }
        });
        show.getChild("Genre").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                currentShow.genres = body.trim();
            }
        });
        show.getChild("Network").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                currentShow.network = body;
            }
        });
        show.getChild("Rating").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                try {
                    currentShow.rating = Double.parseDouble(body);
                } catch (NumberFormatException e) {
                    currentShow.rating = 0.0;
                }
            }
        });
        show.getChild("Runtime").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                try {
                    currentShow.runtime = Integer.parseInt(body);
                } catch (NumberFormatException e) {
                    // an hour is always a good estimate...
                    currentShow.runtime = 60;
                }
            }
        });
        show.getChild("Status").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                if (body.length() == 10) {
                    currentShow.status = ShowStatusExport.CONTINUING;
                } else if (body.length() == 5) {
                    currentShow.status = ShowStatusExport.ENDED;
                } else {
                    currentShow.status = ShowStatusExport.UNKNOWN;
                }
            }
        });
        show.getChild("ContentRating").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                currentShow.contentRating = body;
            }
        });
        show.getChild("poster").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                currentShow.poster = body != null ? body.trim() : "";
            }
        });
        show.getChild("IMDB_ID").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                currentShow.imdbId = body.trim();
            }
        });
        show.getChild("lastupdated").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                try {
                    currentShow.lastEdited = Long.parseLong(body);
                } catch (NumberFormatException e) {
                    currentShow.lastEdited = 0;
                }
            }
        });

        // build TVDb url, get localized content when possible
        String url = TVDB_API_URL + context.getResources().getString(R.string.tvdb_apikey)
                + "/series/" + showTvdbId + "/" + (language != null ? language + ".xml" : "");
        downloadAndParse(url, root.getContentHandler(), false);

        return currentShow;
    }

    private static ArrayList<ContentValues> fetchEpisodes(
            ArrayList<ContentProviderOperation> batch, Show show, String language, Context context)
            throws TvdbException {
        String url = TVDB_API_URL + context.getResources().getString(R.string.tvdb_apikey)
                + "/series/" + show.tvdbId + "/all/"
                + (language != null ? language + ".zip" : "en.zip");

        return parseEpisodes(batch, url, show, context);
    }

    /**
     * Loads the given zipped XML and parses containing episodes to create an array of {@link
     * ContentValues} for new episodes.<br> Adds update ops for updated episodes and delete ops for
     * local orphaned episodes to the given {@link ContentProviderOperation} batch.
     */
    private static ArrayList<ContentValues> parseEpisodes(
            final ArrayList<ContentProviderOperation> batch, String url, final Show show,
            Context context) throws TvdbException {
        final ArrayList<ContentValues> newEpisodesValues = new ArrayList<>();
        final long dateLastMonthEpoch = (System.currentTimeMillis()
                - (DateUtils.DAY_IN_MILLIS * 30)) / 1000;

        RootElement root = new RootElement("Data");
        Element episode = root.getChild("Episode");

        final HashMap<Integer, Long> localEpisodeIds = DBUtils
                .getEpisodeMapForShow(context, show.tvdbId);
        final HashMap<Integer, Long> removableEpisodeIds = new HashMap<>(
                localEpisodeIds); // just copy episodes list, then remove valid ones
        final HashSet<Integer> localSeasonIds = DBUtils.getSeasonIdsOfShow(context, show.tvdbId);
        // store updated seasons to avoid duplicate ops
        final HashSet<Integer> seasonIdsToUpdate = new HashSet<>();
        final ContentValues values = new ContentValues();

        // set handlers for elements we want to react to
        episode.setEndElementListener(new EndElementListener() {
            public void end() {
                Integer episodeId = values.getAsInteger(Episodes._ID);
                if (episodeId == null || episodeId <= 0) {
                    // invalid id, skip
                    return;
                }

                // don't clean up this episode
                removableEpisodeIds.remove(episodeId);

                // decide whether to insert or update
                if (localEpisodeIds.containsKey(episodeId)) {
                    /*
                     * Update uses provider ops which take a long time. Only
                     * update if episode was edited on TVDb or is not older than
                     * a month (ensures show air time changes get stored).
                     */
                    Long lastEditEpoch = localEpisodeIds.get(episodeId);
                    Long lastEditEpochNew = values.getAsLong(Episodes.LAST_EDITED);
                    if (lastEditEpoch != null && lastEditEpochNew != null
                            && (lastEditEpoch < lastEditEpochNew
                            || dateLastMonthEpoch < lastEditEpoch)) {
                        // complete update op for episode
                        batch.add(DBUtils.buildEpisodeUpdateOp(values));
                    }
                } else {
                    // episode does not exist, yet
                    newEpisodesValues.add(new ContentValues(values));
                }

                Integer seasonId = values.getAsInteger(Seasons.REF_SEASON_ID);
                if (seasonId != null && !seasonIdsToUpdate.contains(seasonId)) {
                    // add insert/update op for season
                    batch.add(DBUtils.buildSeasonOp(values, !localSeasonIds.contains(seasonId)));
                    seasonIdsToUpdate.add(values.getAsInteger(Seasons.REF_SEASON_ID));
                }

                values.clear();
            }
        });
        episode.getChild("id").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                values.put(Episodes._ID, body.trim());
            }
        });
        episode.getChild("EpisodeNumber").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                values.put(Episodes.NUMBER, body.trim());
            }
        });
        episode.getChild("absolute_number").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                values.put(Episodes.ABSOLUTE_NUMBER, body.trim());
            }
        });
        episode.getChild("SeasonNumber").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                values.put(Episodes.SEASON, body.trim());
            }
        });
        episode.getChild("DVD_episodenumber").setEndTextElementListener(
                new EndTextElementListener() {
                    public void end(String body) {
                        values.put(Episodes.DVDNUMBER, body.trim());
                    }
                }
        );
        episode.getChild("FirstAired").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                long episodeAirTime = TimeTools
                        .parseEpisodeReleaseTime(body, show.airtime, show.country);
                values.put(Episodes.FIRSTAIREDMS, episodeAirTime);
                values.put(Episodes.FIRSTAIRED, body.trim());
            }
        });
        episode.getChild("EpisodeName").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                values.put(Episodes.TITLE, body.trim());
            }
        });
        episode.getChild("Overview").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                values.put(Episodes.OVERVIEW, body.trim());
            }
        });
        episode.getChild("seasonid").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                values.put(Seasons.REF_SEASON_ID, body.trim());
            }
        });
        episode.getChild("seriesid").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                values.put(Shows.REF_SHOW_ID, body.trim());
            }
        });
        episode.getChild("Director").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                values.put(Episodes.DIRECTORS, body.trim());
            }
        });
        episode.getChild("GuestStars").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                values.put(Episodes.GUESTSTARS, body.trim());
            }
        });
        episode.getChild("Writer").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                values.put(Episodes.WRITERS, body.trim());
            }
        });
        episode.getChild("Rating").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                values.put(Episodes.RATING, body.trim());
            }
        });
        episode.getChild("filename").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                values.put(Episodes.IMAGE, body.trim());
            }
        });
        episode.getChild("IMDB_ID").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                values.put(Episodes.IMDBID, body.trim());
            }
        });
        episode.getChild("lastupdated").setEndTextElementListener(new EndTextElementListener() {
            public void end(String body) {
                // system populated field, trimming not necessary
                try {
                    values.put(Episodes.LAST_EDITED, Long.valueOf(body));
                } catch (NumberFormatException e) {
                    values.put(Episodes.LAST_EDITED, 0);
                }
            }
        });

        downloadAndParse(url, root.getContentHandler(), true);

        // add delete ops for leftover episodeIds in our db
        for (Integer episodeId : removableEpisodeIds.keySet()) {
            batch.add(ContentProviderOperation.newDelete(Episodes.buildEpisodeUri(episodeId))
                    .build());
        }

        return newEpisodesValues;
    }

    /**
     * Downloads the XML or ZIP file from the given URL, passing a valid response to {@link
     * Xml#parse(InputStream, android.util.Xml.Encoding, ContentHandler)} using the given {@link
     * ContentHandler}.
     */
    private static void downloadAndParse(String urlString,
            ContentHandler handler, boolean isZipFile) throws TvdbException {
        try {
            final InputStream input = AndroidUtils.downloadUrl(urlString);

            if (isZipFile) {
                // We downloaded the compressed file from TheTVDB
                final ZipInputStream zipin = new ZipInputStream(input);
                zipin.getNextEntry();
                try {
                    Xml.parse(zipin, Xml.Encoding.UTF_8, handler);
                } finally {
                    if (zipin != null) {
                        zipin.close();
                    }
                }
            } else {
                try {
                    Xml.parse(input, Xml.Encoding.UTF_8, handler);
                } finally {
                    if (input != null) {
                        input.close();
                    }
                }
            }
        } catch (SAXException e) {
            throw new TvdbException("Problem parsing " + urlString, e);
        } catch (IOException e) {
            throw new TvdbException("Problem downloading " + urlString, e);
        } catch (AssertionError ae) {
            // looks like Xml.parse is throwing AssertionErrors instead of IOExceptions
            throw new TvdbException("Problem parsing " + urlString);
        } catch (Exception e) {
            throw new TvdbException("Problem downloading and parsing " + urlString, e);
        }
    }

    /**
     * Tries to download art from the thetvdb banner TVDB_MIRROR. Ignores blank ("") or null paths
     * and skips existing images. Returns true even if there was no art downloaded.
     *
     * @param fileName of image
     * @return false if not all images could be fetched. true otherwise, even if nothing was
     * downloaded
     */
    public static boolean fetchArt(String fileName, boolean isPoster, Context context) {
        if (context == null || TextUtils.isEmpty(fileName)) {
            return true;
        }

        final ImageProvider imageProvider = ImageProvider.getInstance(context);

        if (!imageProvider.exists(fileName)) {
            final String imageUrl;
            if (isPoster) {
                // the cached version is a lot smaller, but still big enough for
                // our purposes
                imageUrl = TVDB_MIRROR_BANNERS + "_cache/" + fileName;
            } else {
                imageUrl = TVDB_MIRROR_BANNERS + fileName;
            }

            // try to download, decode and store the image
            final Bitmap bitmap = downloadBitmap(imageUrl, context);
            if (bitmap != null) {
                imageProvider.storeImage(fileName, bitmap, isPoster);
            } else {
                return false;
            }
        }

        return true;
    }

    private static Bitmap downloadBitmap(String url, Context context) {
        InputStream inputStream = null;
        HttpURLConnection urlConnection = null;
        try {
            urlConnection = AndroidUtils.buildHttpUrlConnection(url);
            urlConnection.connect();
            long imageSize = urlConnection.getContentLength();
            // allow images up to 300 kBytes (although size is always around
            // 30 kBytes for posters and 100 kBytes for episode images)
            if (imageSize > 300000 || imageSize < 1) {
                return null;
            } else {
                inputStream = urlConnection.getInputStream();
                // return BitmapFactory.decodeStream(inputStream);
                // Bug on slow connections, fixed in future release.
                try {
                    return BitmapFactory.decodeStream(new FlushedInputStream(inputStream));
                } catch (OutOfMemoryError e) {
                    Timber.e(e, "Out of memory while retrieving bitmap from " + url);
                }
            }
        } catch (IOException e) {
            Timber.e(e, "I/O error retrieving bitmap from " + url);
        } catch (IllegalStateException e) {
            Timber.e(e, "Incorrect URL: " + url);
        } catch (Exception e) {
            Timber.e(e, "Error while retrieving bitmap from " + url);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    Timber.e(e, "I/O error retrieving bitmap from " + url);
                }
            } else {
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
            }
        }
        return null;
    }

    /*
     * An InputStream that skips the exact number of bytes provided, unless it
     * reaches EOF.
     */
    static class FlushedInputStream extends FilterInputStream {

        public FlushedInputStream(InputStream inputStream) {
            super(inputStream);
        }

        @Override
        public long skip(long n) throws IOException {
            long totalBytesSkipped = 0L;
            while (totalBytesSkipped < n) {
                long bytesSkipped = in.skip(n - totalBytesSkipped);
                if (bytesSkipped == 0L) {
                    int b = read();
                    if (b < 0) {
                        break; // we reached EOF
                    } else {
                        bytesSkipped = 1; // we read one byte
                    }
                }
                totalBytesSkipped += bytesSkipped;
            }
            return totalBytesSkipped;
        }
    }
}
