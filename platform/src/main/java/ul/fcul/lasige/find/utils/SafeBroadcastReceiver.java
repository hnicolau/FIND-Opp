package ul.fcul.lasige.find.utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;

/**
 * Created by hugonicolau on 13/11/15.
 *
 * Make sure you only register/unregister once
 */
public abstract class SafeBroadcastReceiver extends BroadcastReceiver {
    private boolean mIsRegistered = false;

    protected abstract IntentFilter getIntentFilter();

    public void register(Context context) {
        if (!mIsRegistered) {
            context.registerReceiver(this, getIntentFilter());
            mIsRegistered = true;
        }
    }

    public void unregister(Context context) {
        if (mIsRegistered) {
            context.unregisterReceiver(this);
            mIsRegistered = false;
        }
    }
}
