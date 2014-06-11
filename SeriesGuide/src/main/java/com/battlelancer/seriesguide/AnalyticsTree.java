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

package com.battlelancer.seriesguide;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import retrofit.RetrofitError;
import retrofit.client.Response;
import timber.log.Timber;

/**
 * Mostly copy of {@link timber.log.Timber.DebugTree}, but logs to Crashlytics. Drops debug and
 * verbose logs.
 */
public class AnalyticsTree extends Timber.HollowTree implements Timber.TaggedTree {
    private static final Pattern ANONYMOUS_CLASS = Pattern.compile("\\$\\d+$");
    private static final ThreadLocal<String> NEXT_TAG = new ThreadLocal<String>();

    @Override
    public void i(String message, Object... args) {
        logToCrashlytics("INFO", createTag(), message, args);
    }

    @Override
    public void i(Throwable t, String message, Object... args) {
        logToCrashlytics("INFO", createTag(), message, args);
    }

    @Override
    public void w(String message, Object... args) {
        logToCrashlytics("WARN", createTag(), message, args);
    }

    @Override
    public void w(Throwable t, String message, Object... args) {
        logToCrashlytics("WARN", createTag(), message, args);
    }

    @Override
    public void e(String message, Object... args) {
        logToCrashlytics("ERROR", createTag(), message, args);
    }

    @Override
    public void e(Throwable t, String message, Object... args) {
        logToCrashlytics("ERROR", createTag(), message, args);

        // special treatment for retrofit errors
        if (t instanceof RetrofitError) {
            RetrofitError e = (RetrofitError) t;

            Response response = e.getResponse();
            if (response == null) {
                // do NOT log anything
                return;
            }
            int statusCode = response.getStatus();
            // log url and status code
            if (statusCode < 500) {
                // non-server errors are more interesting
                i("URL: %s", e.getUrl());
                i("Status Code: %d", statusCode);
            } else {
                d("URL: %s", e.getUrl());
                d("Status Code: %d", statusCode);
            }
        }

    }

    @Override
    public void tag(String tag) {
        NEXT_TAG.set(tag);
    }

    private static String createTag() {
        String tag = NEXT_TAG.get();
        if (tag != null) {
            NEXT_TAG.remove();
            return tag;
        }

        tag = new Throwable().getStackTrace()[4].getClassName();
        Matcher m = ANONYMOUS_CLASS.matcher(tag);
        if (m.find()) {
            tag = m.replaceAll("");
        }
        return tag.substring(tag.lastIndexOf('.') + 1);
    }

    private void logToCrashlytics(String level, String tag, String message, Object... args) {
        if (message == null) {
            return;
        }
    }
}
