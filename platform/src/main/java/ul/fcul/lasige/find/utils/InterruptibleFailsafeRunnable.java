package ul.fcul.lasige.find.utils;

import android.util.Log;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Created by hugonicolau on 12/11/15.
 */
public abstract class InterruptibleFailsafeRunnable implements Runnable {

    private final String mTag;
    private boolean mCancelled = false;
    protected Thread mThread;

    public InterruptibleFailsafeRunnable(String tag) {
        checkNotNull(tag, "Tag can not be null");
        mTag = tag;
    }

    public String getTag() {
        return mTag;
    }

    public void interrupt() {
        if (mThread != null) {
            mThread.interrupt();
        } else {
            mCancelled = true;
        }
    }

    @Override
    public void run() {
        mThread = Thread.currentThread();
        if (mCancelled || mThread.isInterrupted()) {
            Log.v(mTag, String.format(
                    "%s %s before being run, but thread has still been started.",
                    getClass().getSimpleName(), (mCancelled ? "cancelled" : "interrupted")));
            return;
        }

        try {
            execute();
        } catch (Exception e) {
            Log.e(mTag, "Runnable " + getClass().getSimpleName() + " failed while executing!", e);
            onFailure(e);
        }
    }

    protected void onFailure(Throwable e) {
        // Override to respond to failure
    }

    protected abstract void execute();
}
