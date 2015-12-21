package ul.fcul.lasige.find.utils;

import android.util.Log;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Represents a command that can be executed within a thread extending from {@link Runnable}.
 * Class performs some checks to guarantee that the thread is still running when it is executed.
 * {@link InterruptibleFailsafeRunnable} object needs to implement {@link InterruptibleFailsafeRunnable#execute()}
 * method. If thread is no longer available, it triggers
 * an {@link InterruptibleFailsafeRunnable#onFailure(Throwable)} method that can be override.
 *
 * Created by hugonicolau on 12/11/15.
 */
public abstract class InterruptibleFailsafeRunnable implements Runnable {

    // tag used to print
    private final String mTag;
    // was theread cancelled?
    private boolean mCancelled = false;
    // reference to thread running the task
    protected Thread mThread;

    /**
     * Constructor.
     * @param tag Tag used in console prints.
     */
    public InterruptibleFailsafeRunnable(String tag) {
        checkNotNull(tag, "Tag can not be null");
        mTag = tag;
    }

    /**
     * Returns the tag used in console prints.
     * @return Tag used in console prints.
     */
    public String getTag() {
        return mTag;
    }

    /**
     * Interrupts the current thread.
     */
    public void interrupt() {
        if (mThread != null) {
            // thread still exists, interrupt!
            mThread.interrupt();
        } else {
            // thread already dead, cancel next execution
            mCancelled = true;
        }
    }

    @Override
    public void run() {
        // get current thread
        mThread = Thread.currentThread();

        if (mCancelled || mThread.isInterrupted()) {
            // thread was cancelled or interrupted
            Log.v(mTag, String.format(
                    "%s %s before being run, but thread has still been started.",
                    getClass().getSimpleName(), (mCancelled ? "cancelled" : "interrupted")));
            return;
        }

        try {
            // it's safe, execute main code!
            execute();
        } catch (Exception e) {
            // failed .. trigger onFailure
            Log.e(mTag, "Runnable " + getClass().getSimpleName() + " failed while executing!", e);
            onFailure(e);
        }
    }

    /**
     * Method called when executing the main thread fails.
     * @param e Exception thrown.
     */
    protected void onFailure(Throwable e) {
        // Override to respond to failure
    }

    /**
     * Abstract method that needs to be implemented. It will be executed when the new thread starts.
     */
    protected abstract void execute();
}
