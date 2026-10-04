/*
 * SPDX-FileCopyrightText: PixelOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import com.android.internal.statusbar.CustomStatusBarItem;
import com.android.systemui.res.R;

import java.text.DecimalFormat;
import java.util.ArrayList;

public class NetworkTraffic extends TextView {
    private static final int KB = 1024;
    private static final int MB = KB * KB;
    private static final int GB = MB * KB;
    private static final int MODE_DISABLED = 0;
    private static final int MODE_UPLOAD_ONLY = 1;
    private static final int MODE_DOWNLOAD_ONLY = 2;
    private static final int MODE_DYNAMIC = 3;
    private static final String REFRESH_INTERVAL = "network_traffic_refresh_interval";
    private static final String AUTOHIDE_THRESHOLD = "network_traffic_autohide_threshold";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ConnectivityManager mConnectivityManager;
    private final ContentResolver mResolver;
    private CustomStatusBarItem.Manager mManager;
    private long mLastRxBytes;
    private long mLastTxBytes;
    private long mLastUpdateTime;
    private int mMode;
    private int mRefreshInterval = 1000;
    private int mAutoHideThreshold;
    private boolean mAttached;
    private boolean mScreenOn;
    private boolean mSystemIconVisible = true;

    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_ON:
                    mScreenOn = true;
                    restartSampling();
                    break;
                case Intent.ACTION_SCREEN_OFF:
                    mScreenOn = false;
                    restartSampling();
                    break;
                case Intent.ACTION_USER_SWITCHED:
                    updateSettings();
                    break;
            }
        }
    };

    private final CustomStatusBarItem.DarkReceiver mDarkReceiver =
            new CustomStatusBarItem.DarkReceiver() {
                @Override
                public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
                    setTextColor(tint);
                }

                @Override
                public void setFillColors(int darkColor, int lightColor) {}
            };

    private final CustomStatusBarItem.VisibilityReceiver mVisibilityReceiver = isVisible -> {
        if (mSystemIconVisible != isVisible) {
            mSystemIconVisible = isVisible;
            restartSampling();
        }
    };

    public NetworkTraffic(Context context, AttributeSet attrs) {
        super(context, attrs);
        mResolver = context.getContentResolver();
        mConnectivityManager = context.getSystemService(ConnectivityManager.class);
        updateAppearance();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAttached = true;
        mScreenOn = getContext().getSystemService(PowerManager.class).isInteractive();
        final CustomStatusBarItem.Manager manager = CustomStatusBarItem.findManager(this);
        if (manager != null && manager != mManager) {
            mManager = manager;
            manager.addDarkReceiver(mDarkReceiver);
            manager.addVisibilityReceiver(mVisibilityReceiver);
        }
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        getContext().registerReceiver(mReceiver, filter, null, mHandler, Context.RECEIVER_EXPORTED);
        for (String key : new String[] {Settings.Secure.NETWORK_TRAFFIC_MODE,
                Settings.Secure.NETWORK_TRAFFIC_AUTOHIDE, REFRESH_INTERVAL, AUTOHIDE_THRESHOLD}) {
            mResolver.registerContentObserver(Settings.Secure.getUriFor(key), false,
                    mObserver, UserHandle.USER_ALL);
        }
        updateSettings();
    }

    @Override
    protected void onDetachedFromWindow() {
        mAttached = false;
        mHandler.removeCallbacks(mRefresh);
        mResolver.unregisterContentObserver(mObserver);
        getContext().unregisterReceiver(mReceiver);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        updateAppearance();
        restartSampling();
    }

    private void updateAppearance() {
        setTypeface(Typeface.create(getResources().getString(
                com.android.internal.R.string.config_headlineFontFamily), Typeface.BOLD));
        setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.status_bar_network_traffic_text_size));
        setIncludeFontPadding(false);
        setLineSpacing(0.75f, 0.75f);
        setGravity(Gravity.CENTER);
        setMaxLines(2);
    }

    private void updateSettings() {
        mMode = Settings.Secure.getIntForUser(mResolver,
                Settings.Secure.NETWORK_TRAFFIC_MODE, MODE_DISABLED, UserHandle.USER_CURRENT);
        final boolean autoHide = Settings.Secure.getIntForUser(mResolver,
                Settings.Secure.NETWORK_TRAFFIC_AUTOHIDE, 0, UserHandle.USER_CURRENT) != 0;
        mAutoHideThreshold = autoHide ? Math.max(1, Settings.Secure.getIntForUser(mResolver,
                AUTOHIDE_THRESHOLD, 1, UserHandle.USER_CURRENT)) : 0;
        mRefreshInterval = 1000 * Math.max(1, Math.min(10, Settings.Secure.getIntForUser(
                mResolver, REFRESH_INTERVAL, 1, UserHandle.USER_CURRENT)));
        restartSampling();
    }

    private boolean isTrafficEnabled() {
        return mAttached && mScreenOn && mSystemIconVisible
                && mMode >= MODE_UPLOAD_ONLY && mMode <= MODE_DYNAMIC;
    }

    private void restartSampling() {
        mHandler.removeCallbacks(mRefresh);
        mLastRxBytes = TrafficStats.getTotalRxBytes();
        mLastTxBytes = TrafficStats.getTotalTxBytes();
        mLastUpdateTime = SystemClock.elapsedRealtime();
        if (isTrafficEnabled()) {
            displaySpeed(0, 0);
            mHandler.postDelayed(mRefresh, mRefreshInterval);
        } else {
            setText("");
            setVisibility(GONE);
        }
    }

    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            if (!isTrafficEnabled()) return;
            final long now = SystemClock.elapsedRealtime();
            final long rxBytes = TrafficStats.getTotalRxBytes();
            final long txBytes = TrafficStats.getTotalTxBytes();
            final long elapsed = now - mLastUpdateTime;
            final long rxSpeed = calculateSpeed(rxBytes, mLastRxBytes, elapsed);
            final long txSpeed = calculateSpeed(txBytes, mLastTxBytes, elapsed);
            mLastRxBytes = rxBytes;
            mLastTxBytes = txBytes;
            mLastUpdateTime = now;
            displaySpeed(rxSpeed, txSpeed);
            mHandler.removeCallbacks(this);
            if (isTrafficEnabled()) mHandler.postDelayed(this, mRefreshInterval);
        }
    };

    private static long calculateSpeed(long bytes, long previousBytes, long elapsed) {
        if (bytes < 0 || previousBytes < 0 || bytes < previousBytes || elapsed <= 0) return 0;
        return (long) ((bytes - previousBytes) * 1000d / elapsed);
    }

    private void displaySpeed(long rxSpeed, long txSpeed) {
        if (mConnectivityManager.getActiveNetwork() == null
                || (rxSpeed / KB < mAutoHideThreshold && txSpeed / KB < mAutoHideThreshold)) {
            setText("");
            setVisibility(GONE);
            return;
        }
        final long speed = mMode == MODE_UPLOAD_ONLY ? txSpeed
                : mMode == MODE_DOWNLOAD_ONLY ? rxSpeed : Math.max(rxSpeed, txSpeed);
        final CharSequence output = formatSpeed(speed);
        if (!TextUtils.equals(output, getText())) setText(output);
        setVisibility(VISIBLE);
    }

    private static CharSequence formatSpeed(long speed) {
        final String value;
        final String unit;
        if (speed >= GB) {
            value = new DecimalFormat("0.00").format(speed / (float) GB);
            unit = "GB/S";
        } else {
            final long divisor = speed >= MB ? MB : KB;
            final String pattern = speed >= 100 * divisor ? "000"
                    : speed >= 10 * divisor ? "00.0" : "0.00";
            value = new DecimalFormat(pattern).format(speed / (float) divisor);
            unit = speed >= MB ? "MB/S" : "KB/S";
        }
        final SpannableString unitSpan = new SpannableString(unit);
        unitSpan.setSpan(new RelativeSizeSpan(13f / 14f), 0, unit.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return TextUtils.concat(value, "\n", unitSpan);
    }
}
