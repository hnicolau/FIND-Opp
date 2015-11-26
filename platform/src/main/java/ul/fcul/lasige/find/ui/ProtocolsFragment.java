package ul.fcul.lasige.find.ui;

import android.database.Cursor;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.widget.SimpleCursorAdapter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

import ul.fcul.lasige.find.R;
import ul.fcul.lasige.find.data.DbController;
import ul.fcul.lasige.find.data.FullContract;

/**
 * Created by hugonicolau on 04/11/2015.
 */
public class ProtocolsFragment extends Fragment {

    private SimpleCursorAdapter mAdapter;
    private DbController mDbController;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_protocols, container, false);

        // get cursor
        mDbController = new DbController(getActivity());
        Cursor cursor = mDbController.getImplementations(null, null);

        // get adapter (data)
        int layout = android.R.layout.simple_list_item_1;
        int[] to = { android.R.id.text1 };
        String[] from = { FullContract.Protocols.COLUMN_IDENTIFIER };

        mAdapter = new SimpleCursorAdapter(getActivity(), layout, cursor, from, to, 0);

        // set adapter
        ListView listView = (ListView) view.findViewById(R.id.protocolslistview);
        listView.setAdapter(mAdapter);

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();

        // update adapter
        Cursor c = mDbController.getImplementations(null, null);
        mAdapter.changeCursor(c);
        mAdapter.notifyDataSetChanged();
    }
}
