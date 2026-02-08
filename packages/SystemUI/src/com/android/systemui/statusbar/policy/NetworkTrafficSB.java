package com.android.systemui.statusbar.policy;

import static com.android.systemui.statusbar.StatusBarIconView.STATE_DOT;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_ICON;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.Gravity;

import com.android.internal.statusbar.NetworkTraffic;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.StatusIconDisplayable;

import java.util.ArrayList;

public class NetworkTrafficSB extends NetworkTraffic implements DarkReceiver, StatusIconDisplayable {

    public static final String SLOT = "networktraffic";
    private int mVisibleState = -1;
    private boolean mSystemIconVisible = true;

    public NetworkTrafficSB(Context context) {
        this(context, null);
    }

    public NetworkTrafficSB(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NetworkTrafficSB(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

       setGravity(Gravity.CENTER);

       setMaxLines(2);

       int paddingPx = (int) (2 * context.getResources().getDisplayMetrics().density);
       setPadding(paddingPx, 0, 0, 0);        
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        mIconTint = DarkIconDispatcher.getTint(areas, this, tint);
        setTextColor(mIconTint);
    }

    @Override
    public String getSlot() {
        return SLOT;
    }

    @Override
    public boolean isIconVisible() {
        return true;
    }

    @Override
    public int getVisibleState() {
        return mVisibleState;
    }

    @Override
    public void setVisibleState(int state, boolean animate) {
        if (state == mVisibleState) {
            return;
        }
        mVisibleState = state;

        switch (state) {
            case STATE_ICON:
                mSystemIconVisible = true;
                break;
            case STATE_DOT:
            case STATE_HIDDEN:
            default:
                mSystemIconVisible = false;
                break;
        }
        
        setTrafficVisible(mSystemIconVisible);
        
        if (!mSystemIconVisible) {
            setVisibility(View.GONE);
        } else {
            updateViewState();
        }
    }

    @Override
    public void setStaticDrawableColor(int color) {
        mIconTint = color;
        setTextColor(mIconTint);
    }

    @Override
    public void setDecorColor(int color) {
        mIconTint = color;
        setTextColor(mIconTint);
    }
}