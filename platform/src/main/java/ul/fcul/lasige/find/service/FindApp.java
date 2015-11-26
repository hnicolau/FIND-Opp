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
 * Created by hugonicolau on 04/11/2015.
 */
public class FindApp extends Application {
    private static final String TAG = FindApp.class.getSimpleName();

    private ScheduledExecutorService mAsyncExecutorService;
    private Thread.UncaughtExceptionHandler mOldDefaultHandler;

    private ApplicationRegistry mApplicationRegistry;
    private ProtocolRegistry mProtocolRegistry;
    private PacketRegistry mPacketRegistry;

    @Override
    public void onCreate() {
        PRNGFixes.apply();
        Log.v(TAG, "Native code is included correctly? " + CryptoHelper.testNativeLibrary());

        SupervisorService.startSupervisorService(this);

        mApplicationRegistry = ApplicationRegistry.getInstance(this);
        mProtocolRegistry = ProtocolRegistry.getInstance(this);
        mPacketRegistry = PacketRegistry.getInstance(this);

        mAsyncExecutorService = Executors.newScheduledThreadPool(2);
        mAsyncExecutorService.scheduleAtFixedRate(
               new DbCleanupTasks.ExpiredPacketsCleanupTask(this),
               1, 30, TimeUnit.MINUTES);

        if (ConfigurationStore.isFirstRun(this)) {
            // Create initial master identity
            MasterKeyUtil.create(this);

            // Issue API keys for existing FIND client apps
            AppRegistrationService.startBulkIssueApiKeys(this);

            // Save default policy
            ConfigurationStore.saveCurrentPolicy(this, Policy.DEFAULT_POLICY);
        }

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

    public ApplicationRegistry getApplicationRegistry() { return mApplicationRegistry; }
    public ProtocolRegistry getProtocolRegistry() { return mProtocolRegistry; }
    public PacketRegistry getPacketRegistry() { return mPacketRegistry; }
}
