/* == This file is part of Tomahawk Player - <http://tomahawk-player.org> ===
 *
 *   Copyright 2014, Enno Gottschalk <mrmaffen@googlemail.com>
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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import org.tomahawk.libtomahawk.infosystem.InfoSystemUtils;
import org.tomahawk.libtomahawk.resolver.Query;
import org.tomahawk.libtomahawk.resolver.Result;
import org.tomahawk.libtomahawk.resolver.ScriptAccount;
import org.tomahawk.libtomahawk.resolver.ScriptJob;
import org.tomahawk.libtomahawk.resolver.ScriptObject;
import org.tomahawk.libtomahawk.resolver.ScriptPlugin;
import org.tomahawk.libtomahawk.resolver.ScriptUtils;
import org.tomahawk.libtomahawk.resolver.models.ScriptResolverArtistResult;
import org.tomahawk.libtomahawk.utils.TomahawkUtils;
import org.tomahawk.tomahawk_android.TomahawkApp;

import android.util.Log;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import de.greenrobot.event.EventBus;

/**
 * This class represents a Collection which contains tracks/albums/artists retrieved by a
 * ScriptResolver.
 */
public class ScriptResolverCollection extends Collection implements ScriptPlugin {

    private final static String TAG = ScriptResolverCollection.class.getSimpleName();

    private ScriptObject mScriptObject;

    private ScriptAccount mScriptAccount;

    public ScriptResolverCollection(ScriptObject object, ScriptAccount account) {
        super(account.getScriptResolver().getId(),
                account.mCollectionMetaData.prettyname, true);

        mScriptObject = object;
        mScriptAccount = account;

        initializeCollection();
    }

    @Override
    public ScriptObject getScriptObject() {
        return mScriptObject;
    }

    @Override
    public ScriptAccount getScriptAccount() {
        return mScriptAccount;
    }

    @Override
    public void start(ScriptJob job) {
        mScriptAccount.startJob(job);
    }

    @Override
    public void loadIcon(ImageView imageView, boolean grayOut) {
        TomahawkUtils.loadDrawableIntoImageView(TomahawkApp.getContext(), imageView,
                mScriptAccount.mCollectionIconPath, grayOut);
    }

    /**
     * Initialize this {@link org.tomahawk.libtomahawk.collection.ScriptResolverCollection}.
     */
    protected void initializeCollection() {
        ScriptJob.start(mScriptObject, "artists", new ScriptJob.ResultsCallback() {
            @Override
            public void onReportResults(JsonNode results) {
                try {
                    ScriptResolverArtistResult result = InfoSystemUtils.getObjectMapper()
                            .treeToValue(results, ScriptResolverArtistResult.class);
                    if (result != null) {
                        // First parse the result
                        HashSet<String> updatedItemIds = new HashSet<>();
                        JsonNode artistsNode = results.get("artists");
                        if (artistsNode != null && artistsNode.isArray()
                                && artistsNode.size() > 0) {
                            for (JsonNode artistNode : artistsNode) {
                                Artist artist = Artist.get(artistNode.asText());
                                addArtist(artist);
                                updatedItemIds.add(artist.getCacheKey());
                            }

                            // And finally fire the UpdatedEvent
                            CollectionManager.UpdatedEvent event
                                    = new CollectionManager.UpdatedEvent();
                            event.mCollection = ScriptResolverCollection.this;
                            event.mUpdatedItemIds = updatedItemIds;
                            EventBus.getDefault().post(event);
                        }
                    }
                } catch (JsonProcessingException e) {
                    Log.e(TAG, "initializeCollection: " + e.getClass() + ": "
                            + e.getLocalizedMessage());
                }
            }
        });
    }

    @Override
    public ArrayList<Query> getAlbumTracks(Album album, boolean sorted) {
        if (mAlbumTracks.get(album) != null) {
            return super.getAlbumTracks(album, sorted);
        } else {
            HashMap<String, Object> args = new HashMap<>();
            args.put("artist", album.getArtist().getName());
            args.put("album", album.getName());
            ScriptJob.start(mScriptObject, "tracks", args, new ScriptJob.ResultsCallback() {
                @Override
                public void onReportResults(JsonNode results) {
                    if (results != null) {
                        // First parse the result
                        ArrayList<Result> parsedResults = ScriptUtils.parseResultList(
                                mScriptAccount.getScriptResolver(), results.get("results"));
                        Artist artist =
                                Artist.get(ScriptUtils.getNodeChildAsText(results, "artist"));
                        Album album = Album
                                .get(ScriptUtils.getNodeChildAsText(results, "album"), artist);

                        // Now create the queries
                        ArrayList<Query> queries = new ArrayList<>();
                        for (Result r : parsedResults) {
                            Query query = Query.get(r, isLocal());
                            float trackScore = query.howSimilar(r);
                            query.addTrackResult(r, trackScore);
                            queries.add(query);
                            addQuery(query, 0);
                        }
                        addAlbumTracks(album, queries);

                        // And finally fire the UpdatedEvent
                        CollectionManager.UpdatedEvent event
                                = new CollectionManager.UpdatedEvent();
                        event.mCollection = ScriptResolverCollection.this;
                        event.mUpdatedItemIds = new HashSet<>();
                        event.mUpdatedItemIds.add(album.getCacheKey());
                        EventBus.getDefault().post(event);
                    }
                }
            });
            return new ArrayList<>();
        }
    }

    @Override
    public ArrayList<Album> getArtistAlbums(Artist artist, boolean sorted) {
        if (mArtistAlbums.get(artist) != null) {
            return super.getArtistAlbums(artist, sorted);
        } else {
            HashMap<String, Object> args = new HashMap<>();
            args.put("artist", artist.getName());
            ScriptJob.start(mScriptObject, "albums", args, new ScriptJob.ResultsCallback() {
                @Override
                public void onReportResults(JsonNode results) {
                    if (results != null) {
                        // Get the Artist and add all albums to it
                        Artist artist =
                                Artist.get(ScriptUtils.getNodeChildAsText(results, "artist"));
                        JsonNode albumsNode = results.get("albums");
                        if (albumsNode != null && albumsNode.isArray() && albumsNode.size() > 0) {
                            for (JsonNode albumNode : albumsNode) {
                                Album album = Album.get(albumNode.asText(), artist);
                                addAlbum(album);
                                addArtistAlbum(album.getArtist(), album);
                            }

                            // And finally fire the UpdatedEvent
                            CollectionManager.UpdatedEvent event
                                    = new CollectionManager.UpdatedEvent();
                            event.mCollection = ScriptResolverCollection.this;
                            event.mUpdatedItemIds = new HashSet<>();
                            event.mUpdatedItemIds.add(artist.getCacheKey());
                            EventBus.getDefault().post(event);
                        }
                    }
                }
            });
            return new ArrayList<>();
        }
    }
}
