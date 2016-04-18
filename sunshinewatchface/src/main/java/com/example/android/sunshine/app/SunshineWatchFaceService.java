/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.LocalBroadcastManager;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/*
Digital watch face that displays weather data.
 */

public class SunshineWatchFaceService extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);
    private static final long TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);
    public static final String LOG_TAG = "SunshineWatchFace";

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }



    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFaceService.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredReceivers = false;
        Paint mBackgroundPaint;
        Paint mTimeTextPaint;
        Paint mDateTextPaint;
        Paint mBoldTextPaint;
        Paint mLowTextPaint;
        Paint mHighTextPaint;
        boolean mAmbient;
        Calendar mCalendar;
        SimpleDateFormat mDateFormat;
        final Typeface BOLD_TYPEFACE =
                Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
        final Typeface NORMAL_TYPEFACE =
                Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(LOG_TAG, "got broadcast:  " + intent.getAction());
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            }
        };

        final BroadcastReceiver mWeatherUpdateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(LOG_TAG, "Got broadcast event:  " + intent.getAction());
                if (intent.hasExtra(ICON)) {
                    weatherIcon = intent.getParcelableExtra(ICON);
                }
                if (intent.hasExtra(HIGH)) {
                    high = intent.getStringExtra(HIGH);
                }
                if (intent.hasExtra(LOW)) {
                    low = intent.getStringExtra(LOW);
                }
                if (intent.hasExtra(DESC)) {
                    mWeatherString = intent.getStringExtra(DESC);
                }
                Log.d(LOG_TAG, "Updated from weather data!");
                invalidate();
            }
        };
        int mTapCount;

        float mTimeXOffset;
        float mTimeYOffset;
        float mDateYOffset;
        float mWeatherYOffset;
        float mLineHeight;
        float mHighXOffset;
        float mLowXOffset;
        float mDateXOffset;
        String mWeatherString;
        String high;
        String low;
        Bitmap weatherIcon;
        Rect weatherIconRect;

        private static final String DESC = "com.example.android.sunshine.app.desc";
        private static final String HIGH = "com.example.android.sunshine.app.high";
        private static final String LOW = "com.example.android.sunshine.app.low";
        private static final String ICON = "com.example.android.sunshine.app.icon";

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            Resources resources = SunshineWatchFaceService.this.getResources();
            mTimeYOffset = resources.getDimension(R.dimen.time_y_offset);
            mWeatherYOffset = resources.getDimension(R.dimen.weather_y_offset);
            mLineHeight = resources.getDimension(R.dimen.time_line_height);
            mDateYOffset = resources.getDimension(R.dimen.date_y_offset);
            mHighXOffset = resources.getDimension(R.dimen.weather_y_offset);
            mLowXOffset = resources.getDimension(R.dimen.weather_y_offset);
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));
            mTimeTextPaint = createTextPaint(resources.getColor(R.color.digital_text), NORMAL_TYPEFACE);
            mDateTextPaint = createTextPaint(resources.getColor(R.color.digital_text), NORMAL_TYPEFACE);
            mBoldTextPaint = createTextPaint(resources.getColor(R.color.digital_text), BOLD_TYPEFACE);
            mLowTextPaint = createTextPaint(resources.getColor(R.color.digital_text), NORMAL_TYPEFACE);
            mHighTextPaint = createTextPaint(resources.getColor(R.color.digital_text), BOLD_TYPEFACE);
            int weatherIconSize = 60;
            int weatherIconLeft = 55;
            int weatherIconTop = 210;
            weatherIconRect = new Rect(
                    weatherIconLeft,
                    weatherIconTop,
                    weatherIconLeft + weatherIconSize,
                    weatherIconTop + weatherIconSize);
            mCalendar = Calendar.getInstance();
            initFormats();

            IntentFilter mFilter = new IntentFilter(SunshineWearableListenerService.UPDATE_ACTION);
            LocalBroadcastManager.getInstance(getApplicationContext())
                    .registerReceiver(mWeatherUpdateReceiver, mFilter);
        }

        private void initFormats() {
            mDateFormat = new SimpleDateFormat("EE, MMM dd yyyy", Locale.getDefault());
            mDateFormat.setCalendar(mCalendar);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredReceivers) {
                return;
            }
            mRegisteredReceivers = true;
            IntentFilter timezoneFilter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            timezoneFilter.addAction(Intent.ACTION_LOCALE_CHANGED);
            SunshineWatchFaceService.this.registerReceiver(mTimeZoneReceiver, timezoneFilter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredReceivers) {
                return;
            }
            mRegisteredReceivers = false;
            SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();
            mTimeXOffset = resources.getDimension(isRound
                    ? R.dimen.time_x_offset_round : R.dimen.time_x_offset_square);
            mHighXOffset = resources.getDimension(isRound
                    ? R.dimen.high_x_offset_round : R.dimen.high_x_offset_square);
            mLowXOffset = resources.getDimension(isRound
                    ? R.dimen.low_x_offset_round : R.dimen.low_x_offset_square);
            mDateXOffset = resources.getDimension(isRound
                    ? R.dimen.date_x_offset_round : R.dimen.date_x_offset_square);
            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.time_text_size_round : R.dimen.time_text_size_square);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.date_text_size_round : R.dimen.date_text_size_square);
            float temperatureTextSize = resources.getDimension(isRound
                    ? R.dimen.temperature_text_size_round : R.dimen.temperature_text_size_square);

            mTimeTextPaint.setTextSize(timeTextSize);
            mDateTextPaint.setTextSize(dateTextSize);
            mHighTextPaint.setTextSize(temperatureTextSize);
            mLowTextPaint.setTextSize(temperatureTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimeTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = SunshineWatchFaceService.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }
            canvas.drawRect(0, 0, 5, 5, mTimeTextPaint);

            int hour = mCalendar.get(Calendar.HOUR);
            int minute = mCalendar.get(Calendar.MINUTE);
            if (hour == 0) {
                hour = 12;
            }
            String timeText = String.format(Locale.getDefault(), "%d:%02d", hour, minute);
            canvas.drawText(timeText, mTimeXOffset, mTimeYOffset, mTimeTextPaint);
            String calendarText = mDateFormat.format(mCalendar.getTime());
            canvas.drawText(calendarText, mDateXOffset, mDateYOffset, mDateTextPaint);

            if (getPeekCardPosition().isEmpty()) {
                if (weatherIcon != null) {
                    canvas.drawBitmap(weatherIcon, null, weatherIconRect, mTimeTextPaint);
                }
                if (high != null && low != null) {
                    canvas.drawText(high, mHighXOffset, mWeatherYOffset, mHighTextPaint);
                    canvas.drawText(low, mLowXOffset, mWeatherYOffset, mLowTextPaint);
                }
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
