/*
 * Copyright (C) 2012-2013 Reece H. Dunn
 * Copyright (C) 2009 The Android Open Source Project
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

package com.reecedunn.espeak;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceActivity;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Pair;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ListView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class eSpeakActivity extends Activity {
    private static final String ACTION_TTS_SETTINGS = "com.android.settings.TTS_SETTINGS";

    /** Handler code for TTS initialization hand-off. */
    private static final int TTS_INITIALIZED = 1;

    private static final int REQUEST_CHECK = 1;
    private static final int REQUEST_DOWNLOAD = 2;
    private static final int REQUEST_DEFAULT = 3;

	private static final String TAG = "eSpeakActivity";

    private enum State {
        LOADING,
        DOWNLOAD_FAILED,
        ERROR,
        SUCCESS
    }

    private State mState;
    private boolean mDownloadedVoiceData;
    private ArrayList<String> mVoices;
    private TextToSpeech mTts;
    private List<Pair<String,String>> mInformation;
    private InformationListAdapter mInformationView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.main);

        mInformation = new ArrayList<Pair<String,String>>();
        mInformationView = new InformationListAdapter(this, mInformation);
        ((ListView)findViewById(R.id.properties)).setAdapter(mInformationView);

        setState(State.LOADING);
        checkVoiceData();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (mTts != null) {
            mTts.shutdown();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.options, menu);
        if (Build.VERSION.SDK_INT < 14) {
            // Hide the eSpeak setting menu item on pre-ICS.
            menu.findItem(R.id.espeakSettings).setVisible(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        switch (item.getItemId())
        {
        case R.id.espeakSettings:
            startActivityForResult(new Intent(eSpeakActivity.this, TtsSettingsActivity.class), REQUEST_DEFAULT);
            return true;
        case R.id.ttsSettings:
            launchGeneralTtsSettings();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Sets the UI state.
     *
     * @param state The current state.
     */
    private void setState(State state) {
        mState = state;
        switch (mState)
        {
        case LOADING:
            findViewById(R.id.loading).setVisibility(View.VISIBLE);
            findViewById(R.id.success).setVisibility(View.GONE);
            break;
        default:
            findViewById(R.id.loading).setVisibility(View.GONE);
            findViewById(R.id.success).setVisibility(View.VISIBLE);
            break;
        }
    }

    /**
     * Launcher the voice data verifier.
     */
    private void checkVoiceData() {
        final Intent checkIntent = new Intent(this, CheckVoiceData.class);

        startActivityForResult(checkIntent, REQUEST_CHECK);
    }

    /**
     * Launches the voice data installer.
     */
    private void downloadVoiceData() {
        final Intent checkIntent = new Intent(this, DownloadVoiceData.class);

        startActivityForResult(checkIntent, REQUEST_DOWNLOAD);
    }

    /**
     * Initializes the TTS engine.
     */
    private void initializeEngine() {
        mTts = new TextToSpeech(this, mInitListener);
    }

    private void populateInformationView() {
        mInformation.clear();

        if (mTts != null) {
            Locale language = mTts.getLanguage();
            if (language != null) {
                final String currentLocale = getString(R.string.current_tts_locale);
                mInformation.add(new Pair<String,String>(currentLocale, mTts.getLanguage().getDisplayName()));
            }
        }

        final String availableVoices = getString(R.string.available_voices);
        if (mVoices == null) {
            mInformation.add(new Pair<String,String>(availableVoices, "0"));
        } else {
            mInformation.add(new Pair<String,String>(availableVoices, Integer.toString(mVoices.size())));
        }

        final String version = getString(R.string.tts_version);
        mInformation.add(new Pair<String,String>(version, SpeechSynthesis.getVersion()));

        final String statusText;
        switch (mState) {
        case ERROR:
            statusText = getString(R.string.error_message);
            break;
        case DOWNLOAD_FAILED:
            statusText = getString(R.string.voice_data_failed_message);
            break;
        default:
            if (!getPackageName().equals(mTts.getDefaultEngine())) {
                statusText = getString(R.string.set_default_message);
            } else {
                statusText = null;
            }
            break;
        }
        if (statusText != null) {
            final String statusLabel = getString(R.string.status);
            mInformation.add(new Pair<String,String>(statusLabel, statusText));
        }

        mInformationView.notifyDataSetChanged();
    }

    /**
     * Handles the result of voice data verification. If verification fails
     * following a successful installation, displays an error dialog. Otherwise,
     * either launches the installer or attempts to initialize the TTS engine.
     *
     * @param resultCode The result of voice data verification.
     * @param data The intent containing available voices.
     */
    private void onDataChecked(int resultCode, Intent data) {
        if (resultCode != TextToSpeech.Engine.CHECK_VOICE_DATA_PASS) {
            if (mDownloadedVoiceData) {
            	Log.e(TAG, "Voice data check failed (error code: " + resultCode + ").");
                setState(State.ERROR);
                populateInformationView();
            } else {
                downloadVoiceData();
            }
            return;
        }

        mVoices = data.getStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES);

        initializeEngine();
        populateInformationView();
    }

    /**
     * Handles the result of voice data installation. Either shows a failure
     * dialog or launches the voice data verifier.
     *
     * @param resultCode
     */
    private void onDataDownloaded(int resultCode) {
        if (resultCode != RESULT_OK) {
        	Log.e(TAG, "Voice data download failed.");
            setState(State.DOWNLOAD_FAILED);
            return;
        }

        mDownloadedVoiceData = true;

        checkVoiceData();
    }

    /**
     * Handles the result of TTS engine initialization. Either displays an error
     * dialog or populates the activity's UI.
     *
     * @param status The TTS engine initialization status.
     */
    private void onInitialized(int status) {
        if (status != TextToSpeech.SUCCESS) {
        	Log.e(TAG, "Initialization failed (status: " + status + ").");
            setState(State.ERROR);
        } else {
            setState(State.SUCCESS);
        }

        populateInformationView();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CHECK:
                onDataChecked(resultCode, data);
                break;
            case REQUEST_DOWNLOAD:
                onDataDownloaded(resultCode);
                break;
            case REQUEST_DEFAULT:
                initializeEngine();
                break;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private final TextToSpeech.OnInitListener mInitListener = new TextToSpeech.OnInitListener() {
        @Override
        public void onInit(int status) {
            mHandler.obtainMessage(TTS_INITIALIZED, status, 0).sendToTarget();
        }
    };

    private static class EspeakHandler extends Handler {
    	private WeakReference<eSpeakActivity> mActivity;

    	public EspeakHandler(eSpeakActivity activity)
    	{
    		mActivity = new WeakReference<eSpeakActivity>(activity);
    	}

    	@Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case TTS_INITIALIZED:
                    mActivity.get().onInitialized(msg.arg1);
                    break;
            }
        }
    }
    private final Handler mHandler = new EspeakHandler(this);

    private void launchGeneralTtsSettings()
    {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB && Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH)
        {
            // The Text-to-Speech settings is a Fragment on 3.x:
            intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
            intent.putExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT, "com.android.settings.TextToSpeechSettings");
            intent.putExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT_ARGUMENTS, intent.getExtras());
        }
        else
        {
            // The Text-to-Speech settings is an Activity on 2.x and 4.x:
            intent = new Intent(ACTION_TTS_SETTINGS);
        }
        startActivityForResult(intent, REQUEST_DEFAULT);
    }
}