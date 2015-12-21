package ul.fcul.lasige.find.ui;

import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.widget.CursorAdapter;
import android.support.v4.widget.SimpleCursorAdapter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import ul.fcul.lasige.find.R;
import ul.fcul.lasige.find.beaconing.BeaconingManager;
import ul.fcul.lasige.find.data.DbController;
import ul.fcul.lasige.find.lib.data.Neighbor;
import ul.fcul.lasige.find.network.NetworkManager;

/**
 * Fragment that shows the platform's neighbors that were seen at some point in time.
 *
 * Created by hugonicolau on 16/11/15.
 */
public class NeighborsFragment extends Fragment {
    private final static String TAG = NeighborsFragment.class.getSimpleName();

    // data cursor adapter
    private NeighborListAdapter mAdapter;
    private DbController mDbController;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_neighbors, container, false);

        // get data
        mDbController = new DbController(getActivity());
        // get all neighbors
        Set<Neighbor> list = mDbController.getNeighbors(0);

        // create adapter (data)
        mAdapter = new NeighborListAdapter(getActivity(), list);

        // set adapter and populate view
        ListView listView = (ListView) view.findViewById(R.id.neighborslistview);
        listView.setAdapter(mAdapter);

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();

        // update adapter
        Set<Neighbor> list = mDbController.getNeighbors(0);
        mAdapter.changeList(list);
    }

    /**
     * Adapter that extends from {@link BaseAdapter} and represents a list of neighbors.
     */
    private static class NeighborListAdapter extends BaseAdapter {
        private final LayoutInflater mInflater;
        private List<Neighbor> mList;

        public NeighborListAdapter(Context context, Set<Neighbor> list) {
            super();
            mList = new ArrayList<>(list);
            mInflater = LayoutInflater.from(context);
        }

        @Override
        public int getCount() { return mList.size(); }

        @Override
        public Object getItem(int position) { return mList.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        /**
         * Returns a view that represents a neighbor. It is composed of the neighbor's node id (public key),
         * network it was seen and elapsed time since it was seen last time.
         * @param position Position in list.
         * @param convertView Neighbor view.
         * @param parent parent view.
         * @return Neighbor view.
         */
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            convertView = mInflater.inflate(R.layout.list_item_neighbor, null);

            Neighbor neighbor = mList.get(position);
            String title = neighbor.getNodeIdAsHex().substring(0, 20).toLowerCase(Locale.US);

            final String network;
            final String address;
            if (neighbor.hasAnyIpAddress()) {
                network = neighbor.getLastSeenNetwork();
                address = neighbor.getAnyIpAddress().getHostAddress();
            } else {
                network = "bluetooth";
                address = NetworkManager.unparseMacAddress(neighbor.getBluetoothAddress());
            }
            String details = String.format(Locale.US,
                    "Last seen %d seconds ago on network '%s' with address %s",
                    (System.currentTimeMillis() / 1000) - neighbor.getTimeLastSeen(),
                    network, address);

            TextView titleView = (TextView) convertView.findViewById(R.id.neighborTitle);
            titleView.setText(title);
            TextView detailsView = (TextView) convertView.findViewById(R.id.neighborDetails);
            detailsView.setText(details);

            return convertView;
        }

        public void changeList(Set<Neighbor> newList) {
            if (newList == mList) {
                // The list has already been read before
                return;
            }

            mList.clear();
            if (newList != null) {
                mList = new ArrayList<>(newList);
            }
            notifyDataSetChanged();
        }
    }

}
