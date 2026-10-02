/*
 * Copyright (C) 2026 The a6l-eink-oss authors
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

package org.a6leink.epd;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.util.Log;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Observes audio playback for the automatic video lock (spec 05 section 6.3,
 * new on 18.1). The service runs as uid system, which AudioService treats as
 * holding {@code MODIFY_AUDIO_ROUTING}, so the configurations carry the
 * player's uid. Reports the uids with "video-like" playback (see
 * {@link #isVideoLike}); the service compares them with the front app.
 * Registering does not report the current state, so it is read once at
 * start (after a restart of the service). Handler thread only.
 */
final class PlaybackWatcher extends AudioManager.AudioPlaybackCallback {
    private static final String TAG = "A6lEpdPlayback";

    private final Context mContext;
    private final Handler mHandler;
    private final Consumer<Set<Integer>> mSink;
    private Set<Integer> mLast = new TreeSet<>();
    private boolean mRegistered;
    private boolean mAnonymized;

    PlaybackWatcher(Context context, Handler handler, Consumer<Set<Integer>> sink) {
        mContext = context;
        mHandler = handler;
        mSink = sink;
    }

    void start() {
        try {
            AudioManager am = mContext.getSystemService(AudioManager.class);
            am.registerAudioPlaybackCallback(this, mHandler);
            mRegistered = true;
            onPlaybackConfigChanged(am.getActivePlaybackConfigurations());
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot watch audio playback; automatic video switch is off", e);
        }
    }

    @Override
    public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
        Set<Integer> uids = new TreeSet<>();
        boolean anonymized = false;
        if (configs != null) {
            for (AudioPlaybackConfiguration c : configs) {
                if (c.getPlayerState() != AudioPlaybackConfiguration.PLAYER_STATE_STARTED) {
                    continue;
                }
                if (!isVideoLike(c.getAudioAttributes())) continue;
                int uid = c.getClientUid();
                if (uid < 0) {
                    anonymized = true;
                    continue;
                }
                uids.add(uid);
            }
        }
        if (anonymized && !mAnonymized) {
            Log.w(TAG, "Playback configurations are anonymized; video switch cannot work");
        }
        mAnonymized = anonymized;
        mLast = uids;
        mSink.accept(uids);
    }

    /**
     * Heuristic (own decision): content type "movie" always counts; speech
     * (audio books, text to speech in reading apps), music and sonification
     * never; otherwise media, game and unknown usages count, because many video
     * players leave the content type unknown.
     */
    static boolean isVideoLike(AudioAttributes attrs) {
        if (attrs == null) return false;
        switch (attrs.getContentType()) {
            case AudioAttributes.CONTENT_TYPE_MOVIE:
                return true;
            case AudioAttributes.CONTENT_TYPE_SPEECH:
            case AudioAttributes.CONTENT_TYPE_MUSIC:
            case AudioAttributes.CONTENT_TYPE_SONIFICATION:
                return false;
            default:
                break;
        }
        switch (attrs.getUsage()) {
            case AudioAttributes.USAGE_MEDIA:
            case AudioAttributes.USAGE_GAME:
            case AudioAttributes.USAGE_UNKNOWN:
                return true;
            default:
                return false;
        }
    }

    String dumpState() {
        return "watching=" + mRegistered + " video-like uids=" + mLast
                + (mAnonymized ? " (some anonymized)" : "");
    }
}
