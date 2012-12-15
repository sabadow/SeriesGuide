/*
 * Copyright 2012 Uwe Trottmann
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
 * 
 */

package com.battlelancer.seriesguide.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.ListFragment;
import android.support.v4.app.NavUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.uwetrottmann.seriesguide.R;

/**
 * Displays a menu to allow quick navigation within the app.
 */
public class SlidingMenuFragment extends ListFragment {

    private MenuAdapter mAdapter;

    private static final int MENU_ITEM_SHOWS_ID = 0;
    private static final int MENU_ITEM_LISTS_ID = 1;
    private static final int MENU_ITEM_CHECKIN_ID = 2;
    private static final int MENU_ITEM_ACTIVITY_ID = 3;
    private static final int MENU_ITEM_SEARCH_ID = 4;
    private static final int MENU_ITEM_ADD_SHOWS_ID = 5;
    private static final int MENU_ITEM_HELP_ID = 6;
    private static final int MENU_ITEM_SETTINGS_ID = 7;

    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        mAdapter = new MenuAdapter(getActivity());
        mAdapter.add(new MenuItem(getString(R.string.shows), R.drawable.ic_launcher,
                MENU_ITEM_SHOWS_ID));
        mAdapter.add(new MenuItem(getString(R.string.lists), R.drawable.ic_action_list,
                MENU_ITEM_LISTS_ID));
        mAdapter.add(new MenuItem(getString(R.string.activity), R.drawable.ic_action_upcoming,
                MENU_ITEM_ACTIVITY_ID));

        mAdapter.add(new MenuCategory());
        mAdapter.add(new MenuItem(getString(R.string.checkin), R.drawable.ic_action_checkin,
                MENU_ITEM_CHECKIN_ID));
        mAdapter.add(new MenuItem(getString(R.string.search), R.drawable.ic_action_search,
                MENU_ITEM_SEARCH_ID));

        mAdapter.add(new MenuCategory());
        mAdapter.add(new MenuItem(getString(R.string.add_show), R.drawable.ic_action_add,
                MENU_ITEM_ADD_SHOWS_ID));

        mAdapter.add(new MenuCategory());
        mAdapter.add(new MenuItem(getString(R.string.preferences), R.drawable.ic_action_settings,
                MENU_ITEM_SETTINGS_ID));
        mAdapter.add(new MenuItem(getString(R.string.help), R.drawable.ic_action_help,
                MENU_ITEM_HELP_ID));

        setListAdapter(mAdapter);
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        // close menu any way
        if (getActivity() instanceof BaseActivity) {
            BaseActivity activity = (BaseActivity) getActivity();
            activity.showContent();
        }

        switch (((MenuItem) mAdapter.getItem(position)).mId) {
            case MENU_ITEM_SHOWS_ID:
                if (getActivity() instanceof ShowsActivity) {
                    break;
                }
                NavUtils.navigateUpTo(getActivity(),
                        new Intent(Intent.ACTION_MAIN).setClass(getActivity(),
                                ShowsActivity.class));
                getActivity().overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
                break;
            case MENU_ITEM_LISTS_ID:
                startActivity(new Intent(getActivity(), ListsActivity.class));
                getActivity().overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
                break;
            case MENU_ITEM_ACTIVITY_ID:
                startActivity(new Intent(getActivity(), UpcomingRecentActivity.class));
                getActivity().overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
                break;
            case MENU_ITEM_CHECKIN_ID:
                startActivity(new Intent(getActivity(), CheckinActivity.class));
                getActivity().overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
                break;
            case MENU_ITEM_SEARCH_ID:
                startActivity(new Intent(getActivity(), SearchActivity.class));
                getActivity().overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
                break;
            case MENU_ITEM_ADD_SHOWS_ID:
                startActivity(new Intent(getActivity(), AddActivity.class));
                getActivity().overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
                break;
            case MENU_ITEM_SETTINGS_ID:
                startActivity(new Intent(getActivity(), SeriesGuidePreferences.class));
                getActivity().overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
                break;
            case MENU_ITEM_HELP_ID:
                Intent myIntent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse(SeriesGuidePreferences.HELP_URL));
                startActivity(myIntent);
                break;
        }
    }

    private class MenuItem {

        String mTitle;
        int mIconRes;
        int mId;

        public MenuItem(String title, int iconRes, int id) {
            mTitle = title;
            mIconRes = iconRes;
            mId = id;
        }
    }

    private class MenuCategory {

        public MenuCategory() {
        }
    }

    public class MenuAdapter extends ArrayAdapter<Object> {

        public MenuAdapter(Context context) {
            super(context, 0);
        }

        @Override
        public int getItemViewType(int position) {
            return getItem(position) instanceof MenuItem ? 0 : 1;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public boolean isEnabled(int position) {
            return getItem(position) instanceof MenuItem;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            Object item = getItem(position);

            if (item instanceof MenuItem) {
                ViewHolder holder;
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext()).inflate(
                            R.layout.sliding_menu_row_item, parent, false);
                    holder = new ViewHolder();
                    holder.attach(convertView);
                    convertView.setTag(holder);
                } else {
                    holder = (ViewHolder) convertView.getTag();
                }

                MenuItem menuItem = (MenuItem) item;
                holder.icon.setImageResource(menuItem.mIconRes);
                holder.title.setText(menuItem.mTitle);
            } else {
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext()).inflate(
                            R.layout.sliding_menu_row_category, parent, false);
                }
            }

            return convertView;
        }
    }

    static class ViewHolder {

        public TextView title;

        public ImageView icon;

        public void attach(View v) {
            icon = (ImageView) v.findViewById(R.id.menu_icon);
            title = (TextView) v.findViewById(R.id.menu_title);
        }
    }

}
