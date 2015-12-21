package ul.fcul.lasige.find.ui;

import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.widget.SimpleCursorAdapter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FilterQueryProvider;
import android.widget.ListView;

import ul.fcul.lasige.find.R;
import ul.fcul.lasige.find.data.DbController;
import ul.fcul.lasige.find.data.DbHelper;
import ul.fcul.lasige.find.data.FullContract;


/**
 * Fragment that shows the platform's registered client applications.
 *
 * Created by hugonicolau on 04/11/2015.
 */
public class AppsFragment extends Fragment {

    // data cursor adapter
    private SimpleCursorAdapter mAdapter;
    // database controller
    private DbController mDbController;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_apps, container, false);

        // get cursor
        mDbController = new DbController(getActivity());
        Cursor cursor = mDbController.getApplications();

        // get adapter (data)
        int layout = android.R.layout.simple_list_item_1;
        int[] to = { android.R.id.text1 };
        String[] from = { FullContract.Apps.COLUMN_PACKAGE_NAME };

        // create adapter
        mAdapter = new SimpleCursorAdapter(getActivity(), layout, cursor, from, to, 0);

        // set adapter and populate view
        ListView listView = (ListView) view.findViewById(R.id.appslistview);
        listView.setAdapter(mAdapter);

        return view;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();

        // update adapter
        Cursor c = mDbController.getApplications();
        mAdapter.changeCursor(c);
        mAdapter.notifyDataSetChanged();
    }
}
