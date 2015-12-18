package ul.fcul.lasige.find.beaconing;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.v4.content.LocalBroadcastManager;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import ul.fcul.lasige.find.data.ConfigurationStore;

/**
 * Represents a set of permission for the FIND platform, particularly for network usage and switching.
 *
 * Created by hugonicolau on 12/11/15.
 */
public enum Policy {

    /**
     * This is the baseline policy, which uses just what's available anyways to find neighbors. It
     * does not by itself alter the networking state or anything else, it does not even send beacons
     * on a regular basis (only on network changes or as answer to received beacons). This policy is
     * designed to always run in the background without significant battery drain.
     */
    PASSIVE(BeaconingInterval.OFF),

    /**
     * This policy tries to increase connectivity even further by using Bluetooth. As setting the
     * device to be discoverable over Bluetooth will require user interaction, this policy will only
     * use Bluetooth to listen to other devices making themselves discoverable (i.e. devices which
     * are in disaster mode), or to try and connect to known neighbors if there is no open WLAN
     * around.
     */
    LOW_POWER(BeaconingInterval.SLOW,
            Feature.WIFI_CLIENT,
            Feature.BLUETOOTH),
    /**
     * This policy uses both WiFi modes to reach as many neighbors as possible. Since the AP mode
     * has a significant impact on battery life, this policy tries to save power by aggressively
     * switching networks in WiFi infrastructure mode and more frequent beaconing.
     */
    HIGH_CONNECTIVITY(BeaconingInterval.MEDIUM,
            Feature.WIFI_CLIENT,
            Feature.WIFI_AP),
    /**
     * This policy uses all available features to maximize the likelihood of finding neighbors as
     * fast as possible. It also drains the battery the fastest, and therefore it should only be
     * enabled with the user's consent.
     */
    DISASTER(BeaconingInterval.RANDOM,
            Feature.values()),

    /**
     * This policy uses all available features to ONLY listen for neighbors by staying in AP mode.
     * It is a battery draining policy to be used in very specific cases (e.g. debug).
     */
    LISTENER (BeaconingInterval.FAST,
              Feature.FOREGROUND,
              Feature.WIFI_AP),

    /**
     * This policy uses all available features to ONLY search for neighbors by never going into AP mode.
     */
    SEEKER(BeaconingInterval.FAST,
              Feature.WIFI_CLIENT,
              Feature.FOREGROUND);


    // Policy variables
    private final BeaconingInterval mBeaconInterval;
    private final Set<Feature> mSupportedFeatures;

    /**
     * Constructor. Received a {@link BeaconingInterval} and array of features.
     * @param beaconInterval Beaconing interval.
     * @param features array of features.
     * @see Feature
     */
    Policy(BeaconingInterval beaconInterval, Feature... features) {
        mBeaconInterval = beaconInterval;

        // convert array to list
        final List<Feature> featureList = Arrays.asList(features);
        // build set
        mSupportedFeatures =
                featureList.size() > 0 ?
                        EnumSet.copyOf(featureList) : EnumSet.noneOf(Feature.class);
    }

    /**
     * Retrieves beaconing interval.
     * @return A {@link BeaconingInterval} object.
     */
    public BeaconingInterval getBeaconingInterval() {
        return mBeaconInterval;
    }

    /**
     * Checks whether a given feature is supported by the policy.
     * @param feature Feature.
     * @return true if it is allowed, false otherwise.
     */
    public boolean allows(Feature feature) {
        return mSupportedFeatures.contains(feature);
    }

    /**
     * Broadcast action when the current policy has changed.
     */
    public static final String ACTION_POLICY_CHANGED = "ul.fcul.lasige.find.action.POLICY_CHANGED";

    /**
     * The lookup key for the string extra in the POLICY_CHANGED broadcast intent containing the
     * name of the new policy (after the change).
     *
     * @see Intent#getStringExtra(String)
     */
    public static final String EXTRA_NEW_POLICY = "ul.fcul.lasige.find.extra.NEW_POLICY";

    /**
     * The default policy, if none has been set before.
     */
    public static final Policy DEFAULT_POLICY = Policy.DISASTER;

    /**
     * Returns the currently active policy.
     *
     * @param context Application context.
     * @return the currently active policy.
     */
    public static Policy getCurrentPolicy(Context context) {
        return ConfigurationStore.getCurrentPolicy(context);
    }

    /**
     * Replaces the currently active policy with a new one. If the new one is a different one, a
     * local broadcast is sent out to inform other components of the platform about the change.
     *
     * @param context Application context.
     * @param newPolicy the new policy to set as currently active.
     * @return true if the policy has changed (new policy is not the current one), false otherwise.
     */
    public static synchronized boolean setCurrentPolicy(Context context, Policy newPolicy) {
        final Policy currentPolicy = getCurrentPolicy(context);

        if (!newPolicy.equals(currentPolicy)) {
            ConfigurationStore.saveCurrentPolicy(context, newPolicy);

            // Send local broadcast that the current policy changed
            // BeaconingManager will receive this
            LocalBroadcastManager.getInstance(context).sendBroadcast(
                    new Intent(ACTION_POLICY_CHANGED).putExtra(EXTRA_NEW_POLICY, newPolicy));

            return true;
        }
        return false;
    }

    /**
     * Convenience function to register a receiver for the
     * {@link Policy#ACTION_POLICY_CHANGED POLICY_CHANGED} local broadcast.
     *
     * @param context Application context.
     * @param receiver Broadcast receiver.
     */
    public static void registerPolicyChangedReceiver(Context context, BroadcastReceiver receiver) {
        LocalBroadcastManager.getInstance(context).registerReceiver(
                receiver, new IntentFilter(ACTION_POLICY_CHANGED));
    }

    /**
     * Convenience function to unregister a previously registered local broadcast receiver.
     *
     * @param context Application context.
     * @param receiver Broadcast receiver.
     * @see Policy#registerPolicyChangedReceiver(Context, BroadcastReceiver)
     */
    public static void unregisterPolicyChangedReceiver(Context context, BroadcastReceiver receiver) {
        LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver);
    }

    /**
     * Represents the interval between two beaconing periods. In between, the platform is supposed
     * to sleep to save power. Setting the BeaconingInterval to {@link BeaconingInterval#OFF OFF}
     * means that no regular beaconing will take place.
     *
     */
     public enum BeaconingInterval {
        /**
         * No periodic beaconing. Beacons are only sent upon network changes or answering to other
         * beacons.
         */
        OFF(-1),
        /**
         * Periodic beaconing every 10 minutes. Lets the platform sleep the longest, therefore
         * saving the most power.
         */
        SLOW(10),
        /**
         * Periodic beaconing every 5 minutes. Trades power consumption for connectivity.
         */
        MEDIUM(5),
        /**
         * Periodic beaconing every 2 minutes. Platform will rarely be able to sleep.
         */
        FAST(1),
        /**
         * Periodic beaconing is every 1-3 minutes. Platform will be able to sleep between 1-5 minutes.
         */
        RANDOM(-2);

        // beaconing interval
        private final int mInterval;

        BeaconingInterval(int minutes) {
            mInterval = Math.max(-2, minutes * 60 * 1000); //-2 is a random interval; -1 is off
        }

        public int getIntervalMillis() {
            return mInterval;
        }

        /**
         * Calculates the start timestamp for the next beaconing period.
         * Throws an {@link IllegalStateException} when beaconing is turned off.
         *
         * @return a long indicating the timestamp in milliseconds when to schedule the next round
         *         of beaconing
         */
        public long getNextBeaconTimeMillis() {
            final long currentTime = System.currentTimeMillis();
            if (mInterval > 0) {
                return ((currentTime / mInterval) + 1) * mInterval;
            }
            else if(mInterval == -2) {
                Random r = new Random();
                int randomInterval = r.nextInt(3) + 1; // generates int between 1 (inclusive) and 4 (exclusive)
                randomInterval = randomInterval * 60 * 1000; // convert o millis
                return ((currentTime / randomInterval) + 1) * randomInterval;
            }
            throw new IllegalStateException("Beaconing is turned off.");
        }
    }

    /**
     * Represents a feature of the FIND platform which can be dynamically turned on or off to save
     * power or increase overall connectivity.
     *
     */
    public enum Feature {
        /**
         * Allows the platform to enable WLAN in client mode and switch between discovered networks
         * on its own (instead of only using networks which are already connected).
         */
        WIFI_CLIENT,
        /**
         * Allows the platform to enable WLAN in AP mode, turning into an access point for other
         * neighbors to connect when there are no other means of connectivity (i.e., open networks).
         * Incurs a significant penalty on battery life.
         */
        WIFI_AP,
        /**
         * Allows the platform to enable Bluetooth to discover other neighbors. This is an intrusive
         * feature: It will prompt the user for permission to make the device discoverable. Affects
         * battery life, especially while being discoverable.
         */
        BLUETOOTH,
        /**
         * Allows the platform to perform network switching also when the screen is on. The reason
         * this is a special feature is the fact that when a user is actively using the device
         * (hence the screen is on), he usually relies on a stable WLAN connection, which the
         * platform normally can not guarantee.
         */
        FOREGROUND
    }
}
