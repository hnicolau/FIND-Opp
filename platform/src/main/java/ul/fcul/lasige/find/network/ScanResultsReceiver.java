package ul.fcul.lasige.find.network;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;

import ul.fcul.lasige.find.utils.SafeBroadcastReceiver;

/**
 * Class extends from {@link SafeBroadcastReceiver} and provides a {@link ScanResultsListener}.
 *
 * Created by hugonicolau on 13/11/15.
 */
public class ScanResultsReceiver extends SafeBroadcastReceiver {

    /**
     * Scan results listener that consists of three callbacks: {@link ScanResultsListener#onWifiScanCompleted()},
     * {@link ScanResultsListener#onBluetoothScanCompleted()}, and {@link ScanResultsListener#onBluetoothDeviceFound(BluetoothDevice)}
     */
    public interface ScanResultsListener {
        void onWifiScanCompleted();
        void onBluetoothScanCompleted();
        void onBluetoothDeviceFound(BluetoothDevice btDevice);
    }

    // listener
    private final ScanResultsListener mListener;

    /**
     * Constructor.
     * @param listener Scan results listener
     */
    public ScanResultsReceiver(ScanResultsListener listener) {
        mListener = listener;
    }

    /**
     * Returns an {@link IntentFilter} with {@link WifiManager#SCAN_RESULTS_AVAILABLE_ACTION SCAN_RESULTS_AVAILABLE_ACTION} action.
     * @return An {@link IntentFilter} object.
     */
    @Override
    protected IntentFilter getIntentFilter() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        // TODO filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        // TODO filter.addAction(BluetoothDevice.ACTION_FOUND);
        return filter;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }

        switch (intent.getAction()) {
            case WifiManager.SCAN_RESULTS_AVAILABLE_ACTION: {
                // notifies listener that scan was finished
                mListener.onWifiScanCompleted();
                break;
            }

            case BluetoothAdapter.ACTION_DISCOVERY_FINISHED: {
                //TODO mListener.onBluetoothScanCompleted();
                break;
            }

            case BluetoothDevice.ACTION_FOUND: {
                final BluetoothDevice device =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                //TODO mListener.onBluetoothDeviceFound(device);
                break;
            }
        }
    }
}
