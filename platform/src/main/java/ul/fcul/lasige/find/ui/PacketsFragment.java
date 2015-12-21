package ul.fcul.lasige.find.ui;

import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import ul.fcul.lasige.find.R;
import ul.fcul.lasige.find.data.DbController;
import ul.fcul.lasige.find.data.FullContract;
import ul.fcul.lasige.find.lib.data.Neighbor;
import ul.fcul.lasige.find.lib.data.Packet;
import ul.fcul.lasige.find.network.NetworkManager;

/**
 * Fragment that shows the platform's packets (incoming and outgoing).
 * Created by hugonicolau on 04/11/2015.
 */
public class PacketsFragment extends Fragment {
    private final static String TAG = PacketsFragment.class.getSimpleName();

    // adapter
    private PacketListAdapter mAdapter;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_packets, container, false);

        // get data
        Cursor cursor = getActivity().getContentResolver().query(FullContract.Packets.URI_ALL, null, null, null, null);

        // create adapter (data)
        mAdapter = new PacketListAdapter(getActivity(), cursor);
        cursor.close();

        // set adapter and populate view
        ListView listView = (ListView) view.findViewById(R.id.packetslistview);
        listView.setAdapter(mAdapter);

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();

        Cursor cursor = getActivity().getContentResolver().query(FullContract.Packets.URI_ALL, null, null, null, null);
        // update adapter
        mAdapter.changeCursor(cursor);
    }

    /**
     * Auxiliary class that extends from {@link BaseAdapter} and populates a list view with a set of packets.
     */
    private static class PacketListAdapter extends BaseAdapter {
        private final LayoutInflater mInflater;
        // list of packets
        private List<PacketViewModel> mList;

        public PacketListAdapter(Context context, Cursor cursor) {
            super();
            mList = buildListFromCursor(cursor);
            mInflater = LayoutInflater.from(context);
        }

        @Override
        public int getCount() { return mList.size(); }

        @Override
        public Object getItem(int position) { return mList.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        /**
         * Returns a View that represents a packet. It show the packet's data, the queues it is in, and its
         * protocol.
         * @param position Position in list.
         * @param convertView Packet view.
         * @param parent Parent view.
         * @return Packet view.
         */
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            convertView = mInflater.inflate(R.layout.list_item_packet, null);

            PacketViewModel packet = mList.get(position);
            String title = new String(packet.getData());

            final StringBuilder sb = new StringBuilder();
            sb.append(TextUtils.join("|", packet.getPacketQueues()));
            sb.append(" on ");
            sb.append(packet.getProtocolAsHex().substring(0, 20).toLowerCase(Locale.US));
            sb.append("…");

            TextView titleView = (TextView) convertView.findViewById(R.id.packetTitle);
            titleView.setText(title);
            TextView detailsView = (TextView) convertView.findViewById(R.id.packetDetails);
            detailsView.setText(sb.toString());

            return convertView;
        }

        public void changeCursor(Cursor newCursor) {

            mList.clear();
            mList = buildListFromCursor(newCursor);
            notifyDataSetChanged();
        }

        /**
         * Returns an array list from a given data cursor.
         * @param cursor Data cursor
         * @return List of packets.
         */
        private ArrayList<PacketViewModel> buildListFromCursor(Cursor cursor) {
            ArrayList<PacketViewModel> packets = new ArrayList<>();
            while (cursor.moveToNext()) {
                final PacketViewModel packet = PacketViewModel.fromCursor(cursor);
                packets.add(packet);
            }
            return packets;
        }
    }

}
