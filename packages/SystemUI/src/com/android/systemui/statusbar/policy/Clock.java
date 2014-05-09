/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.format.DateFormat;
import android.text.style.CharacterStyle;
import android.text.style.RelativeSizeSpan;
import android.widget.TextView;
import android.view.View;

import com.android.systemui.DemoMode;
import com.android.systemui.R;
import com.android.systemui.cm.UserContentObserver;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TimeZone;

import libcore.icu.LocaleData;

/**
 * Digital clock for the status bar.
 */
public class Clock implements DemoMode {

    private static final int AM_PM_STYLE_NORMAL  = 0;
    private static final int AM_PM_STYLE_SMALL   = 1;
    private static final int AM_PM_STYLE_GONE    = 2;

    public static final int STYLE_HIDE_CLOCK    = 0;
    public static final int STYLE_CLOCK_RIGHT   = 1;
    public static final int STYLE_CLOCK_CENTER  = 2;
    public static final int STYLE_CLOCK_LEFT    = 3;

    public static final int CLOCK_DATE_DISPLAY_GONE = 0;
    public static final int CLOCK_DATE_DISPLAY_SMALL = 1;
    public static final int CLOCK_DATE_DISPLAY_NORMAL = 2;

    public static final int CLOCK_DATE_STYLE_REGULAR = 0;
    public static final int CLOCK_DATE_STYLE_LOWERCASE = 1;
    public static final int CLOCK_DATE_STYLE_UPPERCASE = 2;

    public static final int FONT_BOLD = 0;
    public static final int FONT_CONDENSED = 1;
    public static final int FONT_LIGHT = 2;
    public static final int FONT_LIGHT_ITALIC = 3;
    public static final int FONT_NORMAL = 4;

    private static final char MAGIC1 = '\uEF00';
    private static final char MAGIC2 = '\uEF01';

    Context mContext;
    private TextView mClockView;
    private Calendar mCalendar;
    private Locale mLocale;
    private String mClockFormatString;
    private SimpleDateFormat mClockFormat;
    private SettingsObserver settingsObserver;

    private int mAmPmStyle = AM_PM_STYLE_GONE;
    private int mClockDateDisplay = CLOCK_DATE_DISPLAY_GONE;
    private int mClockDateStyle = CLOCK_DATE_STYLE_REGULAR;
    private int mClockFontStyle = FONT_NORMAL;
    private boolean mDemoMode;
    private boolean mAttached;

    class SettingsObserver extends UserContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        protected void observe() {
            super.observe();

            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_AM_PM), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_DATE), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_DATE_FORMAT), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_DATE_STYLE), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUSBAR_CLOCK_COLOR), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUSBAR_CLOCK_FONT_STYLE), false, this, UserHandle.USER_ALL);
            updateSettings();
        }

        @Override
        protected void unobserve() {
            super.unobserve();

            mContext.getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void update() {
            updateSettings();
        }
    }

    private final Handler handler = new Handler();
    TimerTask second;

    public Clock(Context context, TextView v) {
        mContext = context;
        mClockView = v;

        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();

            filter.addAction(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
            filter.addAction(Intent.ACTION_USER_SWITCHED);

            mContext.registerReceiverAsUser(mIntentReceiver, UserHandle.ALL, filter,
                    null, new Handler());
        }

        // NOTE: It's safe to do these after registering the receiver since the receiver always runs
        // in the main thread, therefore the receiver can't run before this method returns.

        // The time zone may have changed while the receiver wasn't registered, so update the Time
        mCalendar = Calendar.getInstance(TimeZone.getDefault());

        // Make sure we update to the current time
        if (settingsObserver == null) {
            settingsObserver = new SettingsObserver(new Handler());
            settingsObserver.observe();
        } else {
            updateClock();
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_TIMEZONE_CHANGED)) {
                String tz = intent.getStringExtra("time-zone");
                mCalendar = Calendar.getInstance(TimeZone.getTimeZone(tz));
                if (mClockFormat != null) {
                    mClockFormat.setTimeZone(mCalendar.getTimeZone());
                }
            } else if (action.equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
                final Locale newLocale = mContext.getResources().getConfiguration().locale;
                if (! newLocale.equals(mLocale)) {
                    mLocale = newLocale;
                }
            }
            updateClock();
        }
    };

    private final CharSequence getSmallTime() {
        boolean is24 = DateFormat.is24HourFormat(mContext, ActivityManager.getCurrentUser());
        LocaleData d = LocaleData.get(mContext.getResources().getConfiguration().locale);

        final char MAGIC1 = '\uEF00';
        final char MAGIC2 = '\uEF01';

        SimpleDateFormat sdf;
        String format = is24 ? d.timeFormat24 : d.timeFormat12;
        if (!format.equals(mClockFormatString)) {
            /*
             * Search for an unquoted "a" in the format string, so we can
             * add dummy characters around it to let us find it again after
             * formatting and change its size.
             */
            if (mAmPmStyle != AM_PM_STYLE_NORMAL) {
                int a = -1;
                boolean quoted = false;
                for (int i = 0; i < format.length(); i++) {
                    char c = format.charAt(i);

                    if (c == '\'') {
                        quoted = !quoted;
                    }
                    if (!quoted && c == 'a') {
                        a = i;
                        break;
                    }
                }

                if (a >= 0) {
                    // Move a back so any whitespace before AM/PM is also in the alternate size.
                    final int b = a;
                    while (a > 0 && Character.isWhitespace(format.charAt(a-1))) {
                        a--;
                    }
                    format = format.substring(0, a) + MAGIC1 + format.substring(a, b)
                        + "a" + MAGIC2 + format.substring(b + 1);
                }
            }
            mClockFormat = sdf = new SimpleDateFormat(format);
            mClockFormatString = format;
        } else {
            sdf = mClockFormat;
        }
        String result = sdf.format(mCalendar.getTime());

        CharSequence dateString = null;

        if (Settings.System.getInt(mContext.getContentResolver(), Settings.System.CLOCK_USE_SECOND, 0) == 1) {
            String temp = result;
            result = String.format("%s:%02d", temp, new GregorianCalendar().get(Calendar.SECOND));
        }

        if (mClockDateDisplay != CLOCK_DATE_DISPLAY_GONE) {
            Date now = new Date();

            String clockDateFormat = Settings.System.getString(mContext.getContentResolver(),
                    Settings.System.STATUS_BAR_DATE_FORMAT);

            if (clockDateFormat == null || clockDateFormat.isEmpty()) {
                // Set dateString to short uppercase Weekday (Default for AOKP) if empty
                dateString = DateFormat.format("EEE", now) + " ";
            } else {
                dateString = DateFormat.format(clockDateFormat, now) + " ";
            }
            if (mClockDateStyle == CLOCK_DATE_STYLE_LOWERCASE) {
                // When Date style is small, convert date to uppercase
                result = dateString.toString().toLowerCase() + result;
            } else if (mClockDateStyle == CLOCK_DATE_STYLE_UPPERCASE) {
                result = dateString.toString().toUpperCase() + result;
            } else {
                result = dateString.toString() + result;
            }
        }

        SpannableStringBuilder formatted = new SpannableStringBuilder(result);

        if (mAmPmStyle != AM_PM_STYLE_NORMAL) {
            int magic1 = result.indexOf(MAGIC1);
            int magic2 = result.indexOf(MAGIC2);
            if (magic1 >= 0 && magic2 > magic1) {
                if (mAmPmStyle == AM_PM_STYLE_GONE) {
                    formatted.delete(magic1, magic2+1);
                } else {
                    if (mAmPmStyle == AM_PM_STYLE_SMALL) {
                        CharacterStyle style = new RelativeSizeSpan(0.7f);
                        formatted.setSpan(style, magic1, magic2,
                                          Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
                    }
                    formatted.delete(magic2, magic2 + 1);
                    formatted.delete(magic1, magic1 + 1);
                }
            }
        }

        if (mClockDateDisplay != CLOCK_DATE_DISPLAY_NORMAL) {
            if (dateString != null) {
                int dateStringLen = dateString.length();
                if (mClockDateDisplay == CLOCK_DATE_DISPLAY_GONE) {
                    formatted.delete(0, dateStringLen);
                } else {
                    if (mClockDateDisplay == CLOCK_DATE_DISPLAY_SMALL) {
                        CharacterStyle style = new RelativeSizeSpan(0.7f);
                        formatted.setSpan(style, 0, dateStringLen,
                                          Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
                    }
                }
            }
        }
        return formatted;
    }

    SimpleDateFormat updateFormatString(String format) {
        SimpleDateFormat sdf = mClockFormat;

        if (!format.equals(mClockFormatString)) {

            if (mAmPmStyle != AM_PM_STYLE_NORMAL) {
                int a = -1;
                boolean quoted = false;
                for (int i = 0; i < format.length(); i++) {
                    char c = format.charAt(i);

                    if (c == '\'') {
                        quoted = !quoted;
                    }
                    if (!quoted && c == 'a') {
                        a = i;
                        break;
                    }
                }

                if (a >= 0) {
                    // Move a back so any whitespace before AM/PM is also in the alternate size.
                    final int b = a;
                    while (a > 0 && Character.isWhitespace(format.charAt(a-1))) {
                        a--;
                    }
                    format = format.substring(0, a) + MAGIC1 + format.substring(a, b)
                        + "a" + MAGIC2 + format.substring(b + 1);
                }
            }
            mClockFormat = sdf = new SimpleDateFormat(format);
            mClockFormatString = format;
        } else {
            sdf = mClockFormat;
        }
        return sdf;

    }

    void updateClock() {
        if (mDemoMode || mCalendar == null) return;
        mCalendar.setTimeInMillis(System.currentTimeMillis());
        mClockView.setText(getSmallTime());
    }

    public void updateClockView(TextView v) {
        mClockView = v;
        updateSettings();
    }

    void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();
        mAmPmStyle = (Settings.System.getIntForUser(resolver,
                Settings.System.STATUS_BAR_AM_PM, 2, UserHandle.USER_CURRENT));
        mClockFormatString = "";
        mClockDateDisplay = (Settings.System.getIntForUser(resolver,
                Settings.System.STATUS_BAR_DATE,
                CLOCK_DATE_DISPLAY_GONE, UserHandle.USER_CURRENT));
        mClockDateStyle = Settings.System.getIntForUser(resolver,
                Settings.System.STATUS_BAR_DATE_STYLE, CLOCK_DATE_STYLE_REGULAR,
                UserHandle.USER_CURRENT);
        mClockFontStyle = Settings.System.getIntForUser(resolver,
                Settings.System.STATUSBAR_CLOCK_FONT_STYLE, FONT_NORMAL,
                UserHandle.USER_CURRENT);

        int defaultColor = mContext.getResources().getColor(R.color.status_bar_clock_color);
        int clockColor = Settings.System.getIntForUser(resolver,
                Settings.System.STATUSBAR_CLOCK_COLOR, defaultColor,
                UserHandle.USER_CURRENT);
        if (clockColor == Integer.MIN_VALUE) {
            // flag to reset the color
            clockColor = defaultColor;
        }

        second = new TimerTask()
        {
            @Override
            public void run()
             {
                Runnable updater = new Runnable()
                  {
                   public void run()
                   {
                       updateClock();
                   }
                  };
                handler.post(updater);
             }
        };
        Timer timer = new Timer();
        timer.schedule(second, 0, 1001);

        getFontStyle(mClockFontStyle);
        mClockView.setTextColor(clockColor);
        updateClock();
    }

    public void getFontStyle(int font) {
        switch (font) {
            case FONT_BOLD:
                mClockView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                break;
            case FONT_CONDENSED:
                mClockView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
                break;
            case FONT_LIGHT:
                mClockView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                break;
            case FONT_LIGHT_ITALIC:
                mClockView.setTypeface(Typeface.create("sans-serif-light", Typeface.ITALIC));
                break;
            case FONT_NORMAL:
            default:
                mClockView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                break;
        }
    }

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        if (!mDemoMode && command.equals(COMMAND_ENTER)) {
            mDemoMode = true;
        } else if (mDemoMode && command.equals(COMMAND_EXIT)) {
            mDemoMode = false;
            updateClock();
        } else if (mDemoMode && command.equals(COMMAND_CLOCK)) {
            String millis = args.getString("millis");
            String hhmm = args.getString("hhmm");
            if (millis != null) {
                mCalendar.setTimeInMillis(Long.parseLong(millis));
            } else if (hhmm != null && hhmm.length() == 4) {
                int hh = Integer.parseInt(hhmm.substring(0, 2));
                int mm = Integer.parseInt(hhmm.substring(2));
                mCalendar.set(Calendar.HOUR, hh);
                mCalendar.set(Calendar.MINUTE, mm);
            }
            mClockView.setText(getSmallTime());
        }
    }
}
