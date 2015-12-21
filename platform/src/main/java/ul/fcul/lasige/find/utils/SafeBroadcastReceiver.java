package ul.fcul.lasige.find.utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;

/**
 * Class extends from {@link BroadcastReceiver} and makes sure it is register/unregister exactly once.
 *
 *  Created by hugonicolau on 13/11/15.
 *
 */
public abstract class SafeBroadcastReceiver extends BroadcastReceiver {
    private boolean mIsRegistered = false;

    /**
     * Method that needs to be implemented by {@link SafeBroadcastReceiver} objects. It configures
     * the {@link IntentFilter} that will be registered by the {@link BroadcastReceiver}.
     * @return IntentFilter object.
     */
    protected abstract IntentFilter getIntentFilter();

    /**
     * Registers {@link IntentFilter} previously configured in {@link SafeBroadcastReceiver#getIntentFilter()}
     * method.
     * @param context Application context.
     */
    public void register(Context context) {
        if (!mIsRegistered) {
            context.registerReceiver(this, getIntentFilter());
            mIsRegistered = true;
        }
    }

    /**
     * Unregisters {@link SafeBroadcastReceiver}.
     * @param context Application context.
     */
    public void unregister(Context context) {
        if (mIsRegistered) {
            context.unregisterReceiver(this);
            mIsRegistered = false;
        }
    }
}
