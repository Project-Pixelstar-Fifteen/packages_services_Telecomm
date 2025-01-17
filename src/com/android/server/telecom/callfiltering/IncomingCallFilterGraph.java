/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.telecom.callfiltering;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.telecom.Log;
import android.telecom.Logging.Runnable;

import com.android.server.telecom.Call;
import com.android.server.telecom.LoggedHandlerExecutor;
import com.android.server.telecom.LogUtils;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.Timeouts;
import com.android.server.telecom.flags.FeatureFlags;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class IncomingCallFilterGraph {
    //TODO: Add logging for control flow.
    public static final String TAG = "IncomingCallFilterGraph";
    public static final CallFilteringResult DEFAULT_RESULT =
            new CallFilteringResult.Builder()
                    .setShouldAllowCall(true)
                    .setShouldReject(false)
                    .setShouldAddToCallLog(true)
                    .setShouldShowNotification(true)
                    .setDndSuppressed(false)
                    .build();

    private final CallFilterResultCallback mListener;
    private final Call mCall;
    private final Handler mHandler;
    private final HandlerThread mHandlerThread;
    private final TelecomSystem.SyncRoot mLock;
    private List<CallFilter> mFiltersList;
    private CallFilter mCompletionSentinel;
    private boolean mFinished;
    private CallFilteringResult mCurrentResult;
    private Context mContext;
    private Timeouts.Adapter mTimeoutsAdapter;
    private final FeatureFlags mFeatureFlags;

    private class PostFilterTask {
        private final CallFilter mFilter;

        public PostFilterTask(final CallFilter filter) {
            mFilter = filter;
        }

        public CallFilteringResult whenDone(CallFilteringResult result) {
            Log.i(TAG, "Filter %s done, result: %s.", mFilter, result);
            mFilter.result = result;
            for (CallFilter filter : mFilter.getFollowings()) {
                if (filter.decrementAndGetIndegree() == 0) {
                    scheduleFilter(filter);
                }
            }
            if (mFilter.equals(mCompletionSentinel)) {
                synchronized (mLock) {
                    mFinished = true;
                    mListener.onCallFilteringComplete(mCall, result, false);
                    Log.addEvent(mCall, LogUtils.Events.FILTERING_COMPLETED, result);
                }
                mHandlerThread.quit();
            }
            return result;
        }
    }

    public IncomingCallFilterGraph(Call call, CallFilterResultCallback listener, Context context,
            Timeouts.Adapter timeoutsAdapter, FeatureFlags featureFlags,
            TelecomSystem.SyncRoot lock) {
        mListener = listener;
        mCall = call;
        mFiltersList = new ArrayList<>();
        mFeatureFlags = featureFlags;
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mLock = lock;
        mFinished = false;
        mContext = context;
        mTimeoutsAdapter = timeoutsAdapter;
        mCurrentResult = DEFAULT_RESULT;
    }

    public void addFilter(CallFilter filter) {
        mFiltersList.add(filter);
    }

    public void performFiltering() {
        Log.addEvent(mCall, LogUtils.Events.FILTERING_INITIATED);
        CallFilter dummyStart = new CallFilter();
        mCompletionSentinel = new CallFilter();

        for (CallFilter filter : mFiltersList) {
            addEdge(dummyStart, filter);
        }
        for (CallFilter filter : mFiltersList) {
            addEdge(filter, mCompletionSentinel);
        }
        addEdge(dummyStart, mCompletionSentinel);

        scheduleFilter(dummyStart);
        mHandler.postDelayed(new Runnable("ICFG.pF", mLock) {
            @Override
            public void loggedRun() {
                if (!mFinished) {
                    Log.addEvent(mCall, LogUtils.Events.FILTERING_TIMED_OUT);
                    mCurrentResult = onTimeoutCombineFinishedFilters(mFiltersList, mCurrentResult);
                    mListener.onCallFilteringComplete(mCall, mCurrentResult, true);
                    mFinished = true;
                    mHandlerThread.quit();
                }
                for (CallFilter filter : mFiltersList) {
                    // unbind timed out call screening service
                    if (filter instanceof CallScreeningServiceFilter) {
                        ((CallScreeningServiceFilter) filter).unbindCallScreeningService();
                    }
                }
            }
        }.prepare(), mTimeoutsAdapter.getCallScreeningTimeoutMillis(mContext.getContentResolver()));
    }

    /**
     * This helper takes all the call filters that were added to the graph, checks if filters have
     * finished, and combines the results.
     *
     * @param filtersList   all the CallFilters that were added to the call
     * @param currentResult the current call filter result
     * @return CallFilterResult of the combined finished Filters.
     */
    private CallFilteringResult onTimeoutCombineFinishedFilters(
            List<CallFilter> filtersList,
            CallFilteringResult currentResult) {
        if (!mFeatureFlags.checkCompletedFiltersOnTimeout()) {
            return currentResult;
        }
        for (CallFilter filter : filtersList) {
            if (filter.result != null) {
                currentResult = currentResult.combine(filter.result);
            }
        }
        return currentResult;
    }

    private void scheduleFilter(CallFilter filter) {
        CallFilteringResult result = new CallFilteringResult.Builder()
                .setShouldAllowCall(true)
                .setShouldReject(false)
                .setShouldSilence(false)
                .setShouldAddToCallLog(true)
                .setShouldShowNotification(true)
                .setDndSuppressed(false)
                .build();
        for (CallFilter dependencyFilter : filter.getDependencies()) {
            // When sequential nodes are completed, they are combined progressively.
            // ex.) node_a --> node_b  --> node_c
            // node_a will combine with node_b before starting node_c
            result = result.combine(dependencyFilter.getResult());
        }
        mCurrentResult = result;
        final CallFilteringResult input = result;

        CompletableFuture<CallFilteringResult> startFuture =
                CompletableFuture.completedFuture(input);
        PostFilterTask postFilterTask = new PostFilterTask(filter);

        // TODO: improve these filter logging names to be more reflective of the filters that are
        // executing
        startFuture.thenComposeAsync(filter::startFilterLookup,
                new LoggedHandlerExecutor(mHandler, "ICFG.sF", null))
                .thenApplyAsync(postFilterTask::whenDone,
                        new LoggedHandlerExecutor(mHandler, "ICFG.sF", null))
                .exceptionally((t) -> {
                    Log.e(filter, t, "Encountered exception running filter");
                    return null;
                });
        Log.i(TAG, "Filter %s scheduled.", filter);
    }

    public static void addEdge(CallFilter before, CallFilter after) {
        before.addFollowings(after);
        after.addDependency(before);
    }

    public HandlerThread getHandlerThread() {
        return mHandlerThread;
    }
}
