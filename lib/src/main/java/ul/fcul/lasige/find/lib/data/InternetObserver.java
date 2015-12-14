package ul.fcul.lasige.find.lib.data;

/**
 * Created by hugonicolau on 09/12/15.
 */
public class InternetObserver {
    private static final String TAG = InternetObserver.class.getSimpleName();

    private static Boolean mHasInternet = null;
    private InternetCallback mCallback;

    public InternetObserver(InternetCallback callback) {
        mCallback = callback;
    }

    public void onChange(boolean active) {
        if(mHasInternet == null || active != mHasInternet) {
            mHasInternet = active;
            mCallback.onInternetConnection(mHasInternet);
        }
    }

    public void register() { mHasInternet = null; }
    public void unregister() { mHasInternet = null; }

    /*
     * CALLBACK INTERFACE FOR FIND CLIENT
     */
    public static interface InternetCallback {
        public void onInternetConnection(boolean active);
    }
}
