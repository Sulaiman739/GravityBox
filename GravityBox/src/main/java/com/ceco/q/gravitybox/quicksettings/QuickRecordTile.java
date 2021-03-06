/*
 * Copyright (C) 2018 Peter Gregus for GravityBox Project (C3C076@xda)
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

package com.ceco.q.gravitybox.quicksettings;

import com.ceco.q.gravitybox.R;
import com.ceco.q.gravitybox.GravityBox;
import com.ceco.q.gravitybox.GravityBoxResultReceiver;
import com.ceco.q.gravitybox.GravityBoxSettings;
import com.ceco.q.gravitybox.RecordingService;
import com.ceco.q.gravitybox.managers.BroadcastMediator;
import com.ceco.q.gravitybox.managers.SysUiManagers;

import de.robv.android.xposed.XSharedPreferences;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.net.Uri;
import android.os.Handler;

public class QuickRecordTile extends QsTile {
    public static final class Service extends QsTileServiceBase {
        static final String KEY = QuickRecordTile.class.getSimpleName()+"$Service";
    }

    private static final int STATE_IDLE = 0;
    private static final int STATE_PLAYING = 1;
    private static final int STATE_RECORDING = 2;
    private static final int STATE_JUST_RECORDED = 3;
    private static final int STATE_NO_RECORDING = 4;

    private Uri mAudioFileUri;
    private int mRecordingState = STATE_NO_RECORDING;
    private MediaPlayer mPlayer;
    private Handler mHandler;
    private int mAudioQuality;
    private long mAutoStopDelay;
    private GravityBoxResultReceiver mCurrentStateReceiver;
    private boolean mIsReceiving;

    public QuickRecordTile(Object host, String key, Object tile, XSharedPreferences prefs,
            QsTileEventDistributor eventDistributor) throws Throwable {
        super(host, key, tile, prefs, eventDistributor);

        mHandler = new Handler();

        mCurrentStateReceiver = new GravityBoxResultReceiver(mHandler);
        mCurrentStateReceiver.setReceiver((resultCode, resultData) -> {
            final int oldState = mRecordingState;
            final int newState = resultData.getInt(RecordingService.EXTRA_RECORDING_STATUS);
            if (resultData.containsKey(RecordingService.EXTRA_AUDIO_URI)) {
                mAudioFileUri = Uri.parse(resultData.getString(RecordingService.EXTRA_AUDIO_URI));
            }
            switch(newState) {
            case RecordingService.RECORDING_STATUS_IDLE:
                mRecordingState = mAudioFileUri == null ?
                        STATE_NO_RECORDING : STATE_IDLE;
                break;
            case RecordingService.RECORDING_STATUS_STARTED:
                mRecordingState = STATE_RECORDING;
                break;
            case RecordingService.RECORDING_STATUS_STOPPED:
                mRecordingState = STATE_JUST_RECORDED;
                break;
            default:
                mRecordingState = STATE_NO_RECORDING;
                break;
            }
            if (DEBUG) log(getKey() + ": received current state: " + mRecordingState);
            if (mRecordingState != oldState) {
                refreshState();
            }
        });
    }

    private BroadcastMediator.Receiver mRecordingStatusReceiver = new BroadcastMediator.Receiver() {
        @Override
        public void onBroadcastReceived(Context context, Intent intent) {
            int recordingStatus = intent.getIntExtra(
                    RecordingService.EXTRA_RECORDING_STATUS, RecordingService.RECORDING_STATUS_IDLE);
            if (DEBUG) log(getKey() + ": Broadcast received: recordingStatus = " + recordingStatus);
            switch (recordingStatus) {
                case RecordingService.RECORDING_STATUS_IDLE:
                    mRecordingState = STATE_IDLE;
                    mHandler.removeCallbacks(autoStopRecord);
                    break;
                case RecordingService.RECORDING_STATUS_STARTED:
                    mRecordingState = STATE_RECORDING;
                    if (mAutoStopDelay > 0) {
                        mHandler.postDelayed(autoStopRecord, mAutoStopDelay);
                    }
                    if (DEBUG) log(getKey() + ": Audio recording started;");
                    break;
                case RecordingService.RECORDING_STATUS_STOPPED:
                    mRecordingState = STATE_JUST_RECORDED;
                    mHandler.removeCallbacks(autoStopRecord);
                    if (intent.hasExtra(RecordingService.EXTRA_AUDIO_URI)) {
                        mAudioFileUri = Uri.parse(intent.getStringExtra(RecordingService.EXTRA_AUDIO_URI));
                    }
                    if (DEBUG) log(getKey() + ": Audio recording stopped; mAudioFileUri=" + mAudioFileUri.toString());
                    break;
                case RecordingService.RECORDING_STATUS_ERROR:
                default:
                    mRecordingState = STATE_NO_RECORDING;
                    mHandler.removeCallbacks(autoStopRecord);
                    String statusMessage = intent.getStringExtra(RecordingService.EXTRA_STATUS_MESSAGE);
                    log(getKey() + ": Audio recording error: " + statusMessage);
                    break;
            }
            refreshState();
        }
    };

    @Override
    public String getSettingsKey() {
        return "gb_tile_quickrecord";
    }

    @Override
    public void initPreferences() {
        super.initPreferences();

        mAudioQuality = Integer.valueOf(mPrefs.getString(GravityBoxSettings.PREF_KEY_QUICKRECORD_QUALITY,
                String.valueOf(RecordingService.DEFAULT_SAMPLING_RATE)));
        mAutoStopDelay = mPrefs.getInt(GravityBoxSettings.PREF_KEY_QUICKRECORD_AUTOSTOP, 1) * 3600000;
    }

    @Override
    public void onBroadcastReceived(Context context, Intent intent) {
        super.onBroadcastReceived(context, intent);

        if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_QUICKSETTINGS_CHANGED)) {
            if (intent.hasExtra(GravityBoxSettings.EXTRA_QR_QUALITY)) {
                mAudioQuality = intent.getIntExtra(GravityBoxSettings.EXTRA_QR_QUALITY,
                        RecordingService.DEFAULT_SAMPLING_RATE);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_QR_AUTOSTOP)) {
                mAutoStopDelay = intent.getIntExtra(GravityBoxSettings.EXTRA_QR_AUTOSTOP, 1) * 3600000;
            }
        }
    }

    private void registerRecordingStatusReceiver() {
        if (!mIsReceiving) {
            SysUiManagers.BroadcastMediator.subscribe(mRecordingStatusReceiver,
                    RecordingService.ACTION_RECORDING_STATUS_CHANGED);
            mIsReceiving = true;
            if (DEBUG) log(getKey() + ": registerRecrodingStatusReceiver");
        }
    }

    private void unregisterRecordingStatusReceiver() {
        if (mIsReceiving) {
            SysUiManagers.BroadcastMediator.unsubscribe(mRecordingStatusReceiver);
            mIsReceiving = false;
            if (DEBUG) log(getKey() + ": unregisterRecrodingStatusReceiver");
        }
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            registerRecordingStatusReceiver();
            getCurrentState();
        } else {
            unregisterRecordingStatusReceiver();
        }
    }

    private void getCurrentState() {
        try {
            Intent si = new Intent(mGbContext, RecordingService.class);
            si.setAction(RecordingService.ACTION_RECORDING_GET_STATUS);
            si.putExtra("receiver", mCurrentStateReceiver);
            mGbContext.startService(si);
        } catch (Throwable t) {
            GravityBox.log(TAG, getKey() + ": Error getting current state: ", t);
        }
    }

    final Runnable autoStopRecord = () -> {
        if (mRecordingState == STATE_RECORDING) {
            stopRecording();
        }
    };

    final OnCompletionListener stoppedPlaying = mp -> {
        mRecordingState = STATE_IDLE;
        refreshState();
    };

    private void startPlaying() {
        mPlayer = new MediaPlayer();
        try {
            mPlayer.setDataSource(mContext, mAudioFileUri);
            mPlayer.prepare();
            mPlayer.start();
            mRecordingState = STATE_PLAYING;
            refreshState();
            mPlayer.setOnCompletionListener(stoppedPlaying);
        } catch (Exception e) {
            GravityBox.log(TAG, getKey() + ": startPlaying failed: ", e);
        }
    }

    private void stopPlaying() {
        mPlayer.release();
        mPlayer = null;
        mRecordingState = STATE_IDLE;
        refreshState();
    }

    private void startRecording() {
        Intent si = new Intent(mGbContext, RecordingService.class);
        si.setAction(RecordingService.ACTION_RECORDING_START);
        si.putExtra(RecordingService.EXTRA_SAMPLING_RATE, mAudioQuality);
        mGbContext.startService(si);
    }

    private void stopRecording() {
        Intent si = new Intent(mGbContext, RecordingService.class);
        si.setAction(RecordingService.ACTION_RECORDING_STOP);
        mGbContext.startService(si);
    }

    @Override
    public void handleUpdateState(Object state, Object arg) {
        final Resources res = mGbContext.getResources();

        if (DEBUG) log("Handle update state: mRecordingState=" + mRecordingState);

        switch (mRecordingState) {
            case STATE_PLAYING:
                mState.label = res.getString(R.string.quick_settings_qr_playing);
                mState.icon = iconFromResId(R.drawable.ic_qs_qr_playing);
                break;
            case STATE_RECORDING:
                mState.label = res.getString(R.string.quick_settings_qr_recording);
                mState.icon = iconFromResId(R.drawable.ic_qs_qr_recording);
                break;
            case STATE_JUST_RECORDED:
                mState.label = res.getString(R.string.quick_settings_qr_recorded);
                mState.icon = iconFromResId(R.drawable.ic_qs_qr_recorded);
                break;
            case STATE_NO_RECORDING:
                mState.label = res.getString(R.string.quick_settings_qr_record);
                mState.icon = iconFromResId(R.drawable.ic_qs_qr_record);
                break;
            case STATE_IDLE:
            default:
                mState.label = res.getString(R.string.qs_tile_quickrecord);
                mState.icon = iconFromResId(R.drawable.ic_qs_qr_record);
                break;
        }

        super.handleUpdateState(state, arg);
    }

    @Override
    public boolean supportsHideOnChange() {
        return false;
    }

    @Override
    public void handleClick() {
        switch (mRecordingState) {
            case STATE_RECORDING:
                stopRecording();
                break;
            case STATE_NO_RECORDING:
                return;
            case STATE_IDLE:
            case STATE_JUST_RECORDED:
                startPlaying();
                break;
            case STATE_PLAYING:
                stopPlaying();
                break;
        }
        super.handleClick();
    }

    @Override
    public boolean handleLongClick() {
        if (!isLocked()) {
            switch (mRecordingState) {
                case STATE_NO_RECORDING:
                case STATE_IDLE:
                case STATE_JUST_RECORDED:
                    startRecording();
                    break;
            }
        }
        return true;
    }

    @Override
    public void handleDestroy() {
        autoStopRecord.run();
        super.handleDestroy();
        mHandler = null;
        mCurrentStateReceiver = null;
        mRecordingStatusReceiver = null;
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
    }
}
