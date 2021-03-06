/* == This file is part of Tomahawk Player - <http://tomahawk-player.org> ===
 *
 *   Copyright 2012, Christopher Reichert <creichert07@gmail.com>
 *   Copyright 2012, Enno Gottschalk <mrmaffen@googlemail.com>
 *
 *   Tomahawk is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Tomahawk is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Tomahawk. If not, see <http://www.gnu.org/licenses/>.
 */
package org.tomahawk.libtomahawk.collection;

import org.tomahawk.libtomahawk.authentication.AuthenticatorUtils;
import org.tomahawk.libtomahawk.database.UserPlaylistsDataSource;
import org.tomahawk.libtomahawk.infosystem.InfoRequestData;
import org.tomahawk.libtomahawk.infosystem.InfoSystem;
import org.tomahawk.libtomahawk.infosystem.hatchet.HatchetInfoPlugin;
import org.tomahawk.libtomahawk.resolver.Query;
import org.tomahawk.libtomahawk.resolver.QueryComparator;
import org.tomahawk.libtomahawk.resolver.Resolver;
import org.tomahawk.libtomahawk.resolver.Result;
import org.tomahawk.libtomahawk.utils.TomahawkUtils;
import org.tomahawk.tomahawk_android.TomahawkApp;
import org.tomahawk.tomahawk_android.services.TomahawkService;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class represents a user's local {@link Collection} of all his {@link Track}s.
 */
public class UserCollection extends Collection {

    public static final int Id = 0;

    private TomahawkApp mTomahawkApp;

    private HandlerThread mCollectionUpdateHandlerThread;

    private Handler mHandler;

    private ConcurrentHashMap<Long, Album> mAlbums = new ConcurrentHashMap<Long, Album>();

    private ConcurrentHashMap<String, Query> mQueries = new ConcurrentHashMap<String, Query>();

    private ConcurrentHashMap<String, UserPlaylist> mUserPlaylists
            = new ConcurrentHashMap<String, UserPlaylist>();

    private Runnable mUpdateRunnable = new Runnable() {
        /* 
         * (non-Javadoc)
         * @see java.lang.Runnable#run()
         */
        @Override
        public void run() {
            update();
            mCollectionUpdateHandlerThread.getLooper().quit();
        }
    };

    /**
     * This class watches for changes in the Media db.
     */
    private final ContentObserver mLocalMediaObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange) {
            mCollectionUpdateHandlerThread.start();
            mHandler.post(mUpdateRunnable);
        }
    };

    protected HashSet<String> mCorrespondingRequestIds = new HashSet<String>();

    private UserCollectionReceiver mUserCollectionReceiver;

    /**
     * Handles incoming {@link Collection} updated broadcasts.
     */
    private class UserCollectionReceiver extends BroadcastReceiver {

        /*
         * (non-Javadoc)
         *
         * @see
         * android.content.BroadcastReceiver#onReceive(android.content.Context,
         * android.content.Intent)
         */
        @Override
        public void onReceive(Context context, Intent intent) {
            if (InfoSystem.INFOSYSTEM_RESULTSREPORTED.equals(intent.getAction())) {
                final String requestId = intent
                        .getStringExtra(InfoSystem.INFOSYSTEM_RESULTSREPORTED_REQUESTID);
                if (mCorrespondingRequestIds.contains(requestId)) {
                    UserCollection.this.updateUserPlaylists();
                }
            } else if (InfoSystem.INFOSYSTEM_OPLOGISEMPTIED.equals(intent.getAction())) {
                UserCollection.this.fetchLovedItemsUserPlaylists();
            }
        }
    }

    /**
     * Construct a new {@link UserCollection} and initializes it.
     */
    public UserCollection(TomahawkApp tomahawkApp) {
        mTomahawkApp = tomahawkApp;
        mUserCollectionReceiver = new UserCollectionReceiver();
        mTomahawkApp.registerReceiver(mUserCollectionReceiver,
                new IntentFilter(InfoSystem.INFOSYSTEM_RESULTSREPORTED));
        mTomahawkApp.registerReceiver(mUserCollectionReceiver,
                new IntentFilter(InfoSystem.INFOSYSTEM_OPLOGISEMPTIED));

        TomahawkApp.getContext().getContentResolver()
                .registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, false,
                        mLocalMediaObserver);

        mCollectionUpdateHandlerThread = new HandlerThread("CollectionUpdate",
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
        mCollectionUpdateHandlerThread.start();

        mHandler = new Handler(mCollectionUpdateHandlerThread.getLooper());
        mHandler.postDelayed(mUpdateRunnable, 300);
    }

    /**
     * @return A {@link List} of all {@link UserPlaylist}s in this {@link UserCollection}
     */
    @Override
    public ArrayList<UserPlaylist> getLocalUserPlaylists() {
        ArrayList<UserPlaylist> userPlaylists = new ArrayList<UserPlaylist>();
        for (UserPlaylist userPlaylist : mUserPlaylists.values()) {
            if (!userPlaylist.isHatchetPlaylist()) {
                userPlaylists.add(userPlaylist);
            }
        }
        Collections.sort(userPlaylists,
                new TomahawkListItemComparator(TomahawkListItemComparator.COMPARE_ALPHA));
        return userPlaylists;
    }

    /**
     * Get an {@link UserPlaylist} from this {@link UserCollection} by providing an id
     */
    @Override
    public UserPlaylist getUserPlaylistById(String id) {
        if (UserPlaylistsDataSource.LOVEDITEMS_PLAYLIST_ID.equals(id)) {
            return getLovedItemsUserPlaylist();
        } else if (UserPlaylistsDataSource.CACHED_PLAYLIST_ID.equals(id)) {
            return getCachedUserPlaylist();
        }
        return mUserPlaylists.get(id);
    }

    /**
     * @return A {@link List} of all {@link UserPlaylist}s in this {@link UserCollection}
     */
    public ArrayList<UserPlaylist> getHatchetUserPlaylists() {
        ArrayList<UserPlaylist> userPlaylists = new ArrayList<UserPlaylist>();
        for (UserPlaylist userPlaylist : mUserPlaylists.values()) {
            if (userPlaylist.isHatchetPlaylist()) {
                userPlaylists.add(userPlaylist);
            }
        }
        Collections.sort(userPlaylists,
                new TomahawkListItemComparator(TomahawkListItemComparator.COMPARE_ALPHA));
        return userPlaylists;
    }

    /**
     * Store the PlaybackService's currentPlaylist
     */
    public void setCachedUserPlaylist(UserPlaylist userPlaylist) {
        mTomahawkApp.getUserPlaylistsDataSource().storeUserPlaylist(userPlaylist);
    }

    /**
     * @return the previously cached {@link UserPlaylist}
     */
    public UserPlaylist getCachedUserPlaylist() {
        return mTomahawkApp.getUserPlaylistsDataSource().getCachedUserPlaylist();
    }

    public boolean isQueryLoved(Query query) {
        return mTomahawkApp.getUserPlaylistsDataSource().isItemLoved(query);
    }

    /**
     * Remove or add a lovedItem-query from the LovedItems-UserPlaylist, depending on whether or not
     * it is already a lovedItem
     */
    public void toggleLovedItem(Query query) {
        boolean doSweetSweetLovin = !isQueryLoved(query);
        mTomahawkApp.getUserPlaylistsDataSource().setLovedItem(query, doSweetSweetLovin);
        AuthenticatorUtils hatchetAuthUtils = mTomahawkApp.getTomahawkService()
                .getAuthenticatorUtils(TomahawkService.AUTHENTICATOR_ID_HATCHET);
        mTomahawkApp.getInfoSystem().sendSocialActionPostStruct(hatchetAuthUtils, query,
                HatchetInfoPlugin.HATCHET_SOCIALACTION_TYPE_LOVE, doSweetSweetLovin);
    }

    /**
     * @return the previously stored {@link UserPlaylist} which stores all of the user's loved items
     */
    public UserPlaylist getLovedItemsUserPlaylist() {
        return mTomahawkApp.getUserPlaylistsDataSource().getLovedItemsUserPlaylist();
    }

    /**
     * Update the loved items user-playlist and the contained queries.
     */
    private void updateLovedItemsUserPlaylist() {
        UserPlaylist lovedItemsPlayList = mTomahawkApp.getUserPlaylistsDataSource()
                .getLovedItemsUserPlaylist();
        if (lovedItemsPlayList == null) {
            // If we don't yet have a UserPlaylist to store loved items, we create and store an
            // empty UserPlaylist here
            mTomahawkApp.getUserPlaylistsDataSource().storeUserPlaylist(
                    UserPlaylist.fromQueryList(UserPlaylistsDataSource.LOVEDITEMS_PLAYLIST_ID,
                            UserPlaylistsDataSource.LOVEDITEMS_PLAYLIST_NAME,
                            new ArrayList<Query>()));
        }
    }

    /**
     * Fetch the lovedItems UserPlaylist from the Hatchet API and store it in the local db, if the
     * log of pending operations is empty. Meaning if every love/unlove has already been delivered
     * to the API.
     */
    public void fetchLovedItemsUserPlaylists() {
        if (mTomahawkApp.getUserPlaylistsDataSource().getLoggedOps().isEmpty()) {
            mCorrespondingRequestIds.add(mTomahawkApp.getInfoSystem()
                    .resolve(InfoRequestData.INFOREQUESTDATA_TYPE_USERS_LOVEDITEMS, null));
        } else if (mTomahawkApp.getTomahawkService() != null) {
            AuthenticatorUtils hatchetAuthUtils = mTomahawkApp.getTomahawkService()
                    .getAuthenticatorUtils(TomahawkService.AUTHENTICATOR_ID_HATCHET);
            mTomahawkApp.getInfoSystem().sendLoggedOps(hatchetAuthUtils);
        }
    }

    /**
     * @return A {@link List} of all {@link Track}s in this {@link UserCollection}
     */
    @Override
    public ArrayList<Query> getQueries() {
        ArrayList<Query> queries = new ArrayList<Query>(mQueries.values());
        Collections.sort(queries, new QueryComparator(QueryComparator.COMPARE_ALPHA));
        return queries;
    }

    /**
     * @return always true
     */
    @Override
    public boolean isLocal() {
        return true;
    }

    /**
     * Initialize this {@link UserCollection}. Pull all local tracks from the {@link MediaStore} and
     * add them to our {@link UserCollection}
     */
    private void initializeCollection() {
        Resolver userCollectionResolver = mTomahawkApp.getPipeLine().getResolver(
                TomahawkApp.RESOLVER_ID_USERCOLLECTION);

        updateLovedItemsUserPlaylist();
        updateUserPlaylists();
        fetchHatchetUserPlaylists();

        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";

        String[] projection = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.ARTIST_ID,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.ALBUM};

        ContentResolver resolver = TomahawkApp.getContext().getContentResolver();

        Cursor cursor = resolver
                .query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null,
                        null);

        // Go through the complete set of data in the MediaStore
        while (cursor != null && cursor.moveToNext()) {
            Artist artist = Artist.get(cursor.getString(6));

            Album album = Album.get(cursor.getString(8), artist);
            String albumsel = MediaStore.Audio.Albums._ID + " == " + Long.toString(
                    cursor.getLong(7));
            String[] albumproj = {MediaStore.Audio.Albums.ALBUM_ART,
                    MediaStore.Audio.Albums.FIRST_YEAR, MediaStore.Audio.Albums.LAST_YEAR};
            Cursor albumcursor = resolver.query(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                    albumproj, albumsel, null, null);
            if (albumcursor != null && albumcursor.moveToNext()) {
                if (!TextUtils.isEmpty(albumcursor.getString(0))) {
                    album.setImage(Image.get(albumcursor.getString(0), false));
                }
                album.setFirstYear(albumcursor.getString(1));
                album.setLastYear(albumcursor.getString(2));
            }
            if (albumcursor != null) {
                albumcursor.close();
            }
            if (!mAlbums.containsKey(cursor.getLong(7))) {
                mAlbums.put(cursor.getLong(7), album);
            } else {
                album = mAlbums.get(cursor.getLong(7));
            }

            Track track = Track.get(cursor.getString(2), album, artist);
            track.setDuration(cursor.getLong(3));
            track.setAlbumPos(cursor.getInt(4));

            Query query = Query.get(track.getName(), album.getName(), artist.getName(), true);
            Result result = new Result(cursor.getString(1), track);
            result.setResolvedBy(userCollectionResolver);
            result.setTrackScore(1f);
            query.addTrackResult(result);
            mQueries.put(TomahawkUtils.getCacheKey(query), query);

            artist.addQuery(query);
            artist.addAlbum(album, true);
            album.addQuery(query);
        }

        if (cursor != null) {
            cursor.close();
        }
    }

    /**
     * Fetch all user {@link UserPlaylist} from the app's database via our helper class {@link
     * UserPlaylistsDataSource}
     */
    public void updateUserPlaylists() {
        mUserPlaylists.clear();
        ArrayList<UserPlaylist> userPlayListList = mTomahawkApp.getUserPlaylistsDataSource()
                .getLocalUserPlaylists();
        for (UserPlaylist userPlaylist : userPlayListList) {
            mUserPlaylists.put(userPlaylist.getId(), userPlaylist);
        }
        userPlayListList = mTomahawkApp.getUserPlaylistsDataSource()
                .getHatchetUserPlaylists();
        for (UserPlaylist userPlaylist : userPlayListList) {
            mUserPlaylists.put(userPlaylist.getId(), userPlaylist);
        }
        TomahawkApp.getContext().sendBroadcast(new Intent(COLLECTION_UPDATED));
    }

    /**
     * Fetch the UserPlaylists from the Hatchet API and store it in the local db.
     */
    public void fetchHatchetUserPlaylists() {
        mCorrespondingRequestIds.add(mTomahawkApp.getInfoSystem()
                .resolve(InfoRequestData.INFOREQUESTDATA_TYPE_USERS_PLAYLISTS_ALL, null));
    }

    /**
     * Reinitalize this {@link UserCollection} and send a broadcast letting everybody know.
     */
    @Override
    public void update() {
        initializeCollection();

        TomahawkApp.getContext().sendBroadcast(new Intent(COLLECTION_UPDATED));
    }

    /**
     * @return this {@link UserCollection}'s id
     */
    @Override
    public int getId() {
        return Id;
    }
}
