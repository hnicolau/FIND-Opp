package ul.fcul.lasige.find.lib.data;

/**
 * This class receives callbacks to internet connection status change and keeps a state
 * of it.
 *
 * Created by hugonicolau on 09/12/15.
 */
public class InternetObserver {
    private static final String TAG = InternetObserver.class.getSimpleName();

    // indicates whether we currently have an internet connection
    private static Boolean mHasInternet = null;
    // callback to be called on internet state changes
    private InternetCallback mCallback;

    /**
     * Contructor.
     * @param callback callback to be called on internet state changes.
     */
    public InternetObserver(InternetCallback callback) {
        mCallback = callback;
    }

    /**
     * Method is called when internet connection changes.
     * @param active
     */
    public void onChange(boolean active) {
        if(mHasInternet == null || active != mHasInternet) {
            mHasInternet = active;
            mCallback.onInternetConnection(mHasInternet);
        }
    }

    /**
     * Initializes observer to start listening for internet changes.
     */
    public void register() { mHasInternet = null; }

    /**
     * Initializes observer to stop listening for internet changes.
     */
    public void unregister() { mHasInternet = null; }

    /*
     * CALLBACK INTERFACE FOR FIND CLIENT
     */
    public static interface InternetCallback {
        public void onInternetConnection(boolean active);
    }
}
