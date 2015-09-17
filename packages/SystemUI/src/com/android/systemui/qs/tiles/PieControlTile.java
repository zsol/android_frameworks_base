/*
 * Copyright (C) 2015 The Fusion Project
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

package com.android.systemui.qs.tiles;

import android.app.ActivityManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;

public class PieControlTile extends QSTile<QSTile.BooleanState> {
    private boolean mListening;
    private PieControlObserver mObserver;

    public PieControlTile(Host host) {
        super(host);
        mObserver = new PieControlObserver(mHandler);
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleClick() {
        toggleState();
        refreshState();
        qsCollapsePanel();
    }

    @Override
    protected void handleSecondaryClick() {
        // There are no additional details and we do not want to link this up to Display Settings.
        handleClick();
    }

    @Override
    public void handleLongClick() {
        handleClick();
    }

    protected void toggleState() {
        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.PIE_CONTROLS, !pieControlEnabled() ? 1 : 0, ActivityManager.getCurrentUser());
    }


    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.visible = true;
        state.label = mContext.getString(R.string.quick_settings_slim_pie);
        if (pieControlEnabled()) {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_pie_global_on);
        } else {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_pie_global_off);
        }
    }

    private boolean pieControlEnabled() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.PIE_CONTROLS, 0, UserHandle.USER_CURRENT) == 1;
    }

    @Override
    public void setListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            mObserver.startObserving();
        } else {
            mObserver.endObserving();
        }
    }

    private class PieControlObserver extends ContentObserver {
        public PieControlObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            refreshState();
        }

        public void startObserving() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.PIE_CONTROLS),
                    false, this, UserHandle.USER_ALL);
        }

        public void endObserving() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }
    }
}
