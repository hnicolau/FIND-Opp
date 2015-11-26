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
 * Created by hugonicolau on 16/11/15.
 */
public class NeighborsFragment extends Fragment {
    private final static String TAG = NeighborsFragment.class.getSimpleName();

    private NeighborListAdapter mAdapter;
    private DbController mDbController;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_neighbors, container, false);

        // get data
        mDbController = new DbController(getActivity());
        Set<Neighbor> list = mDbController.getNeighbors(0);

        // get adapter (data)
        mAdapter = new NeighborListAdapter(getActivity(), list);

        // set adapter
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

    /*
     * NeighborListAdapter
     */
    private static class NeighborListAdapter extends BaseAdapter {
        private final LayoutInflater mInflater;
        private List<Neighbor> mList;

        public NeighborListAdapter(Context context, Set<Neighbor> list) {
            super();
            mList = new ArrayList<Neighbor>(list);
            mInflater = LayoutInflater.from(context);
        }

        @Override
        public int getCount() { return mList.size(); }

        @Override
        public Object getItem(int position) { return mList.get(position); }

        @Override
        public long getItemId(int position) { return position; }

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
                mList = new ArrayList<Neighbor>(newList);
            }
            notifyDataSetChanged();
        }
    }

}
