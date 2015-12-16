package ul.fcul.lasige.find.service;

import android.app.Application;
import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import ul.fcul.lasige.find.apps.AppRegistrationService;
import ul.fcul.lasige.find.apps.ApplicationRegistry;
import ul.fcul.lasige.find.apps.ProtocolRegistry;
import ul.fcul.lasige.find.beaconing.Policy;
import ul.fcul.lasige.find.crypto.CryptoHelper;
import ul.fcul.lasige.find.crypto.MasterKeyUtil;
import ul.fcul.lasige.find.crypto.PRNGFixes;
import ul.fcul.lasige.find.data.ConfigurationStore;
import ul.fcul.lasige.find.data.DbCleanupTasks;
import ul.fcul.lasige.find.packetcomm.PacketRegistry;

/**
 * Custom application class that extends from Application. It maintains a global state with references to
 * the ApplicationRegistry, ProtocolRegistry, and PacketRegistry.
 *
 * @see android.app.Application
 * Created by hugonicolau on 04/11/2015.
 */
public class FindApp extends Application {
    private static final String TAG = FindApp.class.getSimpleName();

    // service that clears expired packets from database
    private ScheduledExecutorService mAsyncExecutorService;
    // handler for exceptions
    private Thread.UncaughtExceptionHandler mOldDefaultHandler;

    // application registry
    private ApplicationRegistry mApplicationRegistry;
    // protocol registry
    private ProtocolRegistry mProtocolRegistry;
    // packet registry
    private PacketRegistry mPacketRegistry;

    /**
     * OnCreate method of FindApp. It starts the supervisor service, which controls the app state (i.e. running / idle),
     * initializes registries, starts async task to frequently check for expired packets, and handles application's.
     *
     * If this is the first time the application is running, then it creates it's private key (for encryption) and issues
     * API keys for all installed applications.
     *
     */
    @Override
    public void onCreate() {
        // required fixes for encryption functions
        PRNGFixes.apply();
        Log.v(TAG, "Native code is included correctly? " + CryptoHelper.testNativeLibrary());

        // supervisor is the first service to be ran
        SupervisorService.startSupervisorService(this);

        mApplicationRegistry = ApplicationRegistry.getInstance(this);
        mProtocolRegistry = ProtocolRegistry.getInstance(this);
        mPacketRegistry = PacketRegistry.getInstance(this);

        // start scheduler to clear expired packets in every 30m
        mAsyncExecutorService = Executors.newScheduledThreadPool(2);
        mAsyncExecutorService.scheduleAtFixedRate(
               new DbCleanupTasks.ExpiredPacketsCleanupTask(this),
               1, 30, TimeUnit.MINUTES);

        if (ConfigurationStore.isFirstRun(this)) {
            // Create initial master identity (i.e. private key)
            MasterKeyUtil.create(this);

            // Issue API keys for existing FIND client apps
            AppRegistrationService.startBulkIssueApiKeys(this);

            // Save default policy
            ConfigurationStore.saveCurrentPolicy(this, Policy.DEFAULT_POLICY);
        }

        // catches app exceptions
        mOldDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                Thread.setDefaultUncaughtExceptionHandler(mOldDefaultHandler);
                try {
                    Log.e(TAG, "Unhandled error!", e);

                    if (mOldDefaultHandler != null) {
                        mOldDefaultHandler.uncaughtException(t, e);
                    }
                } catch (Throwable fatal) {
                    if (mOldDefaultHandler != null) {
                        mOldDefaultHandler.uncaughtException(t, e);
                    }
                }
            }
        });

        super.onCreate();
    }

    /**
     * Retrieves the Application Registry.
     * @return Application registry.
     * @see ApplicationRegistry
     */
    public ApplicationRegistry getApplicationRegistry() { return mApplicationRegistry; }

    /**
     * Retrieves the Protocol Registry.
     * @return Protocol registry.
     * @see ProtocolRegistry
     */
    public ProtocolRegistry getProtocolRegistry() { return mProtocolRegistry; }

    /**
     * Retrieves the PacketRegistry.
     * @return Packet registry.
     * @see PacketRegistry
     */
    public PacketRegistry getPacketRegistry() { return mPacketRegistry; }
}
