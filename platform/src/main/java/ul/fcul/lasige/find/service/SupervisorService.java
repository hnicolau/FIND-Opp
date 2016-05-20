package ul.fcul.lasige.find.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import ul.fcul.lasige.find.beaconing.BeaconingIntervalHandler;
import ul.fcul.lasige.find.beaconing.BeaconingManager;
import ul.fcul.lasige.find.beaconing.Policy;
import ul.fcul.lasige.find.data.ConfigurationStore;

/**
 * Background service that controls the FIND platform's execution. The service handles the platform
 * duty-cycle (lifecycle) and therefore managing it's energy consumption. A client app may request
 * that the platform should start looking for neighbors, but it is the Supervisor own choice when
 * this happens.
 *
 * <p>The main purpose of the Supervisor is to make sure that the rest of the platform is only fully
 * awake during a 1-minute discovery period, which is initiated by the Supervisor itself in wake-up
 * intervals defined by the current {@link Policy}.</p>
 *
 * @see BeaconingManager
 * @see Policy
 *
 * Created by hugonicolau on 04/11/2015.
 */
public class SupervisorService extends Service {
    // action to start Supervisor service
    public static final String ACTION_START = "ul.fcul.lasige.find.action.START_SUPERVISOR";
    // action to wakeup Supervisor service
    public static final String ACTION_WAKE_UP = "ul.fcul.lasige.find.action.WAKE_UP_SUPERVISOR";

    private static final String TAG = SupervisorService.class.getSimpleName();

    // default state
    public static final SupervisorState DEFAULT_STATE = SupervisorState.IDLE;
    // active states
    private static final Set<SupervisorState> ACTIVE_STATES = EnumSet.of(
            SupervisorState.RUNNING,
            SupervisorState.SLEEPING);

    // state handlers
    private HandlerThread mHandlerThread; // thread with queue and looper
    private Handler mStateHandler; // processes state changes
    private volatile SupervisorState mState = SupervisorState.STOPPED; // current state

    // callbacks for state changes
    private Set<Callback> mCallbacks = new HashSet<>();

    // wakelock to force CPU to keep running even when screen is off
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;

    // alarm to transition from sleep to running state
    private AlarmManager mAlarmManager;
    private PendingIntent mWakeUpIntent; // intent to wakeup Supervisor

    // beaconing
    private BeaconingManager mBeaconingManager; // singleton
    private BroadcastReceiver mBeaconingFinishedReceiver; // receiver of beaconing finish
    private int mBeaconingRoundId; // stores the id of this beaconing round

    // policy that influences beaconing interval
    private volatile Policy mPolicy; // current policy
    private BroadcastReceiver mPolicyChangedReceiver; // reveiver of policy change

    /*
     * SERVICE
     */
    @Override
    public void onCreate() {
        // state transition thread
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mStateHandler = new Handler(mHandlerThread.getLooper());

        // initializes beaconing manager
        mBeaconingManager = BeaconingManager.getInstance(this);

        // get alarm manager to schedule wakeups
        mAlarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        // get power manager to acquire wake lock
        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FindSupervisorWakeLock");
        mWakeLock.setReferenceCounted(false);

        // get current policy
        mPolicy = ConfigurationStore.getCurrentPolicy(this);
        // set policy change receiver
        mPolicyChangedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mPolicy = (Policy) intent.getSerializableExtra(Policy.EXTRA_NEW_POLICY);
                if (isActivated()) {
                    scheduleStateTransition(SupervisorState.RUNNING);
                }
            }
        };
        Policy.registerPolicyChangedReceiver(this, mPolicyChangedReceiver);

        // set beaconing finish receiver
        mBeaconingFinishedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // get id of beaconing round
                final int finishedBeaconingRound = intent.getIntExtra(BeaconingManager.EXTRA_BEACONING_ID, -1);

                if (mBeaconingRoundId != finishedBeaconingRound) {
                    // not what we were expecting
                    Log.w(TAG, String.format("Got notification that beaconing round %d finished,"
                                    + " but was expecting notification for beaconing round %d",
                                    finishedBeaconingRound, mBeaconingRoundId));
                    return;
                }

                // after a beaconing round transition to sleeping state
                scheduleStateTransition(SupervisorState.SLEEPING);
            }
        };

        // reset service to previous state
        final SupervisorState previousState = ConfigurationStore.getSupervisorState(this);
        setState(previousState);
    }

    /**
     * Overrides Service OnStartCommand. This is called by the system every time a client explicitly
     * starts the service by calling startService(Intent). It can also be called to wakeup the
     * Supervisor and thus transition to running state.
     *
     * @param intent Itent of start command.
     * @param flags Flags of start command.
     * @param startId Unique integer token representing the start request.
     * @return The returned value indicates what semantics the system should use for service's current
     * started state.
     *
     * @see Service
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction().equals(ACTION_WAKE_UP)) {
            // it's a wake-up call
            scheduleStateTransition(SupervisorState.RUNNING);
        }
        // tries to keep the process and stores state
        return START_STICKY;
    }

    /**
     * Called by the system when the process is no longer being used. It unregisters all receivers,
     * quit threads, and release wake lock.
     */
    @Override
    public void onDestroy() {
        if (!mState.equals(SupervisorState.SLEEPING)) {
            Log.w(TAG, "Supervisor is shut down extraordinarily!");
        }

        Policy.unregisterPolicyChangedReceiver(this, mPolicyChangedReceiver);
        mHandlerThread.quit();
        releaseWakeLock();
    }

    @Override
    public IBinder onBind(Intent intent) { return new SupervisorBinder(); }
    /*
     * END SERVICE
     */

    /*
     * EXTERNAL API TO OTHER SERVICES / CLASSES
     */
    /**
     * Convenience method to start the supervisor service.
     *
     * @param context Application context
     */
    public static void startSupervisorService(Context context) {
        sendStartCommand(context, ACTION_START);
    }

    /**
     * Convenience method to wakeup Supervisor and transition to running state.
     * @param context Application context.
     */
    public static void wakeupSupervisorService(Context context) {
        final ComponentName supervisor = sendStartCommand(context, ACTION_WAKE_UP);
        Log.v(TAG, "Waking up supervisor: " + (supervisor != null));
    }

    /**
     * Starts Supervisor service with given Intent action.
     *
     * @param context Application context.
     * @param action Action to start Supervisor service.
     * @return If the service is being started or already running, the {@link ComponentName} of the actual
     * Service is returned; if the service does not exist, null is returned.
     * @see ComponentName
     */
    private static ComponentName sendStartCommand(Context context, String action) {
        final Intent startIntent = new Intent(context, SupervisorService.class);
        startIntent.setAction(action);
        return context.startService(startIntent);
    }

    /**
     * Receiver for WAKE_UP_SUPERVISOR and BOOT_COMPLETE broadcasts, which start/reactivate the
     * supervisor service.
     */
    public static class WakeUpReceiver extends BroadcastReceiver {
        public WakeUpReceiver() { }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                    startSupervisorService(context);
                } else {
                    wakeupSupervisorService(context);
                }
            }
        }
    }

    /**
     * Convinience method to bind Supervisor service with a given {@link Context} and {@link ServiceConnection}.
     * @param context Application context.
     * @param connection Service connection.
     * @return True if the bind is successful, false otherwise (you will not receive the service object).
     */
    public static boolean bindSupervisorService(Context context, ServiceConnection connection) {
        final Intent bindIntent = new Intent(context, SupervisorService.class);
        return context.bindService(bindIntent, connection, Context.BIND_AUTO_CREATE);
    }

    /**
     * Custom binder which allows retrieving the current supervisor service instance.
     */
    public class SupervisorBinder extends Binder {
        public SupervisorService getSupervisor() {
            return SupervisorService.this;
        }
    }

    /**
     * Callback interface to receive {@link SupervisorService Supervisor} state changes.
     */
    public interface Callback { void onActivationStateChanged(boolean activated); }

    /**
     * Register for {@link SupervisorService Supervisor} state changes.
     * @param callback Callback.
     */
    public void addCallback(Callback callback) {
        mCallbacks.add(callback);
    }

    /**
     * Unregister for {@link SupervisorService Supervisor} state changes.
     * @param callback Callback previously registered.
     */
    public void removeCallback(Callback callback) { mCallbacks.remove(callback); }

    /**
     * Notify all registered callbacks about a state change.
     * @param activated {@link SupervisorService Supervisor} state.
     */
    private void notifyCallbacks(boolean activated) {
        // for all registered callbacks
        for (Callback callback : mCallbacks) {
            // call callback
            callback.onActivationStateChanged(activated);
        }
    }

    // TODO delete this method
    /**
     * Retrieves the {@link BeaconingManager}.
     * @return Beaconing manager.
     * @see BeaconingManager
     */
    public BeaconingManager getBeaconingManager() { return mBeaconingManager; }

    /**
     * Returns whether the {@link SupervisorService Supervisor} is in an active state.
     *
     * @return True if the {@link SupervisorService Supervisor} is either in
     * {@link SupervisorState#RUNNING RUNNING} or {@link SupervisorState#SLEEPING SLEEPING} state.
     */
    public boolean isActivated() {
        return ACTIVE_STATES.contains(mState);
    }

    /**
     * Activates the {@link SupervisorService Supervisor} and force to transition to the
     * {@link SupervisorState#RUNNING RUNNING} state. If it is already in an activated state
     * ({@link SupervisorState#RUNNING RUNNING} or {@link SupervisorState#SLEEPING SLEEPING}),
     * ignores the request.
     */
    public void activateFIND() {
        if (!isActivated()) {
            if (mPolicy.allows(Policy.Feature.BLUETOOTH)) {
                Log.d(TAG, "Requesting bluetooth discoverable mode upon supervisor activation");
                //Enabling bluetooth
                // TODO mNetManager.requestBluetoothDiscoverable();

            }
            scheduleStateTransition(SupervisorState.RUNNING);
        }
    }

    /**
     * Change the current {@link Policy}.
     * @param newPolicy New policy.
     * @see Policy
     */
    public void changePolicy(Policy newPolicy) {
        mPolicy = newPolicy;
        Policy.setCurrentPolicy(this, newPolicy);
    }

    /**
     * Attemps to request beaconing. If {@link SupervisorService Supervisor} is in an active state,
     * it sends a single beacon to find neighbors.
     * @return True if the platform sent a beacon; false otherwise.
     */
    public boolean requestBeaconing() {
        if (isActivated()) {
            mBeaconingManager.sendSingleBeacon();
            return true;
        }
        return false;
    }

    /**
     * Deactivates the {@link SupervisorService Supervisor} and forces a transition to an
     * {@link SupervisorState#IDLE IDLE} state. If the {@link SupervisorService Supervisor} is
     * already deactivated then it ignores this request.
     */
    public void deactivateFIND() {
        if (isActivated()) {
            // clear scheduled state transitions and instead go to IDLE next
            mStateHandler.removeCallbacksAndMessages(null);
            scheduleStateTransition(SupervisorState.IDLE);
        }
    }
    /*
     * END EXTERNAL API TO OTHER SERVICES / CLASSES
     */

    /*
     * SUPERVISOR STATE TRANSITION: STOPPED, IDLE, SLEEPING or RUNNING
     */

    /**
     * Transition to a new state.
     * @param newState New state.
     * @see SupervisorState
     */
    private synchronized void setState(SupervisorState newState) {
        Log.v(TAG, "Transitioning from " + mState + " to " + newState);

        // leave old state
        mState.onLeave(this);

        // save new state
        mState = newState;
        ConfigurationStore.saveSupervisorState(this, newState);

        // enter new state
        newState.onEnter(this);
    }

    /**
     * Schedule new state transition in a different thread. Scheduling a state transition to the same state is ignored.
     * Throws an {@link IllegalStateException} when transition is not allowed.
     *
     * @param newState New state.
     * @see SupervisorState
     */
    private synchronized void scheduleStateTransition(final SupervisorState newState) {
        mStateHandler.post(new Runnable() {
            @Override
            public void run() {
                if (newState.equals(mState)) {
                    // the supervisor is already in the target state.
                    return;
                } else if (!newState.isPossibleOrigin(mState)) {
                    // the transition is not possible
                    throw new IllegalStateException("Can not transition from " + mState + " to " + newState);
                }

                // perform state transition (onLeave -> onEnter) and execute main body of new state
                setState(newState);
                newState.execute(SupervisorService.this);
            }
        });
    }

    /**
     * Acquires wake lock to keep CPU running even when scree is off. Lock is not referenced count; that is,
     * locking it multiple times is safe.
     */
    private void acquireWakeLock() {
        if (!mWakeLock.isHeld()) {
            mWakeLock.acquire();
        }
    }

    /**
     * Releases wake lock. Lock is not referenced count; that is, releasing it multiple times is safe. Also,
     * all locks are released in the first call.
     */
    private void releaseWakeLock() {
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    }

    /**
     * Starts listening for beacons from neighbors. This means that the {@link BeaconingManager} is on
     * {@link ul.fcul.lasige.find.beaconing.BeaconingManager.BeaconingState#PASSIVE PASSIVE} state
     * (listening)
     *
     * @see BeaconingManager
     */
    private void startListeningForBeacons() {
        mBeaconingManager.setPassive();
    }

    /**
     * Stop listening for beacons from neighbors. This means that the {@link BeaconingManager} is on
     * {@link ul.fcul.lasige.find.beaconing.BeaconingManager.BeaconingState#STOPPED STOPPED} state
     * (not listening or discovering)
     *
     * @see BeaconingManager
     */
    private void stopListeningForBeacons() {
        mBeaconingManager.setStopped();
    }

    /**
     * Starts a beaconing round, generating its corresponding ID. Sets the {@link BeaconingManager}
     * to active, meaning that it is trying to send beacons and find neighbors. It also registers
     * a broadcast receiver to be notified when the beaconing round finishes.
     */
    private void startSendingBeacons() {
        // generate id
        final int intervalDuration = BeaconingIntervalHandler.MAX_BEACONING_DURATION;
        mBeaconingRoundId = (int) SystemClock.elapsedRealtime() / intervalDuration;

        // register receiver for beaconing finish
        LocalBroadcastManager.getInstance(this).registerReceiver(
                mBeaconingFinishedReceiver,
                new IntentFilter(BeaconingManager.ACTION_BEACONING_FINISHED));

        // start beaconing
        mBeaconingManager.setActive(mBeaconingRoundId);
    }

    /**
     * Stops beaconing round. It sets the {@link BeaconingManager} to passive state (just listening for
     * beacons) and unregisters the beaconing finished receiver.
     */
    private void stopSendingBeacons() {
        mBeaconingManager.setPassive();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBeaconingFinishedReceiver);
        mBeaconingRoundId = -1;
    }

    /**
     * Schedule next {@link SupervisorService Supervisor} wakeup based on current policy.
     *
     * @see Policy
     */
    private void scheduleNextWakeUp() {
        // get policy interval
        final Policy.BeaconingInterval interval = mPolicy.getBeaconingInterval();

        if (!interval.equals(Policy.BeaconingInterval.OFF)) {
            // policy allows for beaconing

            // build wakeup intent
            mWakeUpIntent = PendingIntent.getBroadcast(
                    this, 0, new Intent(this, WakeUpReceiver.class),
                    PendingIntent.FLAG_UPDATE_CURRENT);
            // get next beaconing time
            final long triggerTimeMillis = interval.getNextBeaconTimeMillis();
            // set alarm
            mAlarmManager.set(AlarmManager.RTC_WAKEUP, triggerTimeMillis, mWakeUpIntent);
            Log.d(TAG, String.format("Set alarm to wake up again in %d seconds",
                    ((triggerTimeMillis - System.currentTimeMillis()) / 1000)));
        }
    }

    /**
     * Cancel next wakeup alarm.
     */
    private void cancelWakeUp() {
        if (mWakeUpIntent != null) {
            mAlarmManager.cancel(mWakeUpIntent);
            mWakeUpIntent = null;
            Log.v(TAG, "Cancelled wake up intent");
        }
    }

    /**
     * Supervisor state representation: {@link SupervisorState#STOPPED}, {@link SupervisorState#IDLE},
     * {@link SupervisorState#SLEEPING}, and {@link SupervisorState#RUNNING}
     */
    public enum SupervisorState {
        /**
         * The supervisor service is stopped (not instantiated).
         */
        STOPPED {
            @Override
            public boolean isPossibleOrigin(SupervisorState state) {
                // STOPPED can not be reached from anywhere else
                return false;
            }
        },
        /**
         * The supervisor service is up and running, but doing nothing yet. From here, an activation
         * request (e.g. the user switches FIND "on" in the UI) is needed to transition further to
         * the {@link SupervisorState#RUNNING RUNNING} state.
         * <p>
         * This state is also transitioned to in case of a deactivation request.
         */
        IDLE {
            @Override
            public void onEnter(SupervisorService sv) {
                // cancel wakeup
                sv.cancelWakeUp();
                // stop listening for beacons
                sv.stopListeningForBeacons();
                // notify Supervisor state change callbacks with false (not running)
                sv.notifyCallbacks(false);
                // set BeaconingManager as stopped
                sv.mBeaconingManager.setStopped();
            }

            @Override
            public void onLeave(SupervisorService sv) {
                // notify Supervisor state change callbacks with true (running)
                sv.notifyCallbacks(true);
            }
        },
        /**
         * The platform is enabled. Depending on the policy, the supervisor now activates different
         * platform features (i.e. beaconing). After all work is done, the supervisor automatically
         * transitions into the {@link SupervisorState#SLEEPING SLEEPING} state.
         * <p>
         * When in this state, the supervisor holds a partial wake lock.
         *
         * @see PowerManager.WakeLock
         */
        RUNNING {
            @Override
            public void onEnter(SupervisorService sv) {
                // acquire wake lock
                sv.acquireWakeLock();
                // start listening for beacons
                sv.startListeningForBeacons();
                // start sending beacons
                sv.startSendingBeacons();
            }

            @Override
            public void onLeave(SupervisorService sv) {
                // stop sending beacons; notice that we are still listening for beacons
                sv.stopSendingBeacons();
                // release wake lock
                sv.releaseWakeLock();
            }
        },
        /**
         * The platform is enabled, but sleeping: An alarm is set to wake up the service again later
         * on. When woken up, the supervisor transitions back to the {@link SupervisorState#RUNNING
         * RUNNING} state.
         * <p>
         * When in this state, the supervisor keeps network sockets open to listen for beacons,
         * and holds on to other network-related locks.
         *
         * @see WifiManager.WifiLock
         * @see WifiManager.MulticastLock
         */
        SLEEPING {
            @Override
            public void onEnter(SupervisorService sv) {
                // start listening for beacons
                sv.startListeningForBeacons();
                // schedule next wakeup to transition to RUNNING state
                sv.scheduleNextWakeUp();
            }

            @Override
            public boolean isPossibleOrigin(SupervisorState state) {
                return (state.equals(STOPPED) || state.equals(RUNNING));
            }
        };

        /**
         * Executes when entering this state. By default, this method does nothing.
         *
         * @param sv {@link SupervisorService} object.
         */
        public void onEnter(SupervisorService sv) { }

        /**
         * Executes the main function of this state. By default, this method does nothing.
         *
         * @param sv {@link SupervisorService} object.
         */
        public void execute(SupervisorService sv) { }

        /**
         * Executes when leaving this state. By default, this method does nothing.
         *
         * @param sv {@link SupervisorService} object.
         */
        public void onLeave(SupervisorService sv) { }

        /**
         * Checks if this state can be transitioned to from the specified state. By default, this
         * method returns {@code true}, except the specified state is the same as this one.
         *
         * @param state the state from which the transition would occur
         * @return true if the transition is possible, false otherwise
         */
        public boolean isPossibleOrigin(SupervisorState state) {
            return !state.equals(this);
        }
    }
    /*
     * END SUPERVISOR STATE TRANSITION
     */
}
