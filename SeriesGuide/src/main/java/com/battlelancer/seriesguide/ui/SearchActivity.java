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

package com.battlelancer.seriesguide.ui;

import android.app.ActionBar;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.SearchView;
import com.battlelancer.seriesguide.R;
import com.battlelancer.seriesguide.util.Utils;

/**
 * Handles search intents and displays a {@link SearchFragment} when needed or
 * redirects directly to an {@link EpisodeDetailsActivity}.
 */
public class SearchActivity extends BaseNavDrawerActivity {

    private static final String TAG = "Search";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.search);
        setupNavDrawer();

        final ActionBar actionBar = getActionBar();
        actionBar.setHomeButtonEnabled(true);
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setTitle(R.string.search_hint);

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            Bundle extras = getIntent().getExtras();

            // set query as subtitle
            String query = intent.getStringExtra(SearchManager.QUERY);
            final ActionBar actionBar = getActionBar();
            actionBar.setSubtitle("\"" + query + "\"");

            // searching within a show?
            Bundle appData = extras.getBundle(SearchManager.APP_DATA);
            if (appData != null) {
                String showTitle = appData.getString(SearchFragment.InitBundle.SHOW_TITLE);
                if (!TextUtils.isEmpty(showTitle)) {
                    actionBar.setTitle(getString(R.string.search_within_show, showTitle));
                }
            }

            // display results in a search fragment
            SearchFragment searchFragment = (SearchFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.search_fragment);
            if (searchFragment == null) {
                SearchFragment newFragment = new SearchFragment();
                newFragment.setArguments(extras);
                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.content_frame, newFragment).commit();
            } else {
                searchFragment.onPerformSearch(extras);
            }
            Utils.trackCustomEvent(this, TAG, "Search action", "Search");
        } else if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri data = intent.getData();
            String id = data.getLastPathSegment();
            onShowEpisodeDetails(id);
            Utils.trackCustomEvent(this, TAG, "Search action", "View");
            finish();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.search_menu, menu);

        SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
        SearchView searchView = (SearchView) menu.findItem(R.id.menu_search).getActionView();
        searchView.setSearchableInfo(searchManager.getSearchableInfo(getComponentName()));
        searchView.setIconifiedByDefault(false);

        if (SeriesGuidePreferences.THEME == R.style.Theme_SeriesGuide_Light) {
            // override search view style for light theme (because we use dark actionbar theme)
            // search text
            int searchSrcTextId = getResources().getIdentifier("android:id/search_src_text", null, null);
            if (searchSrcTextId != 0) {
                EditText searchEditText = (EditText) searchView.findViewById(searchSrcTextId);
                searchEditText.setTextAppearance(this, R.style.TextAppearance_Inverse);
                searchEditText.setHintTextColor(getResources().getColor(R.color.text_dim));
            }
            // close button
            int closeButtonId = getResources().getIdentifier("android:id/search_close_btn", null, null);
            if (closeButtonId != 0) {
                ImageView closeButtonImage = (ImageView) searchView.findViewById(closeButtonId);
                closeButtonImage.setImageResource(R.drawable.ic_action_cancel);
            }
            // search button
            int searchIconId = getResources().getIdentifier("android:id/search_mag_icon", null, null);
            if (searchIconId != 0) {
                ImageView searchIcon = (ImageView) searchView.findViewById(searchIconId);
                searchIcon.setImageResource(R.drawable.ic_action_search);
            }
        }

        // set incoming query
        String query = getIntent().getStringExtra(SearchManager.QUERY);
        searchView.setQuery(query, false);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == android.R.id.home) {
            onBackPressed();
            return true;
        }
        if (itemId == R.id.menu_search) {
            fireTrackerEvent("Search");
            onSearchRequested();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    protected void fireTrackerEvent(String label) {
        Utils.trackAction(this, TAG, label);
    }

    private void onShowEpisodeDetails(String id) {
        Intent i = new Intent(this, EpisodesActivity.class);
        i.putExtra(EpisodesActivity.InitBundle.EPISODE_TVDBID, Integer.valueOf(id));
        startActivity(i);
    }

}
