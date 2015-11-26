package ul.fcul.lasige.find.ui;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

import ul.fcul.lasige.find.R;
import ul.fcul.lasige.find.beaconing.Policy;
import ul.fcul.lasige.find.service.SupervisorService;

/**
 * Created by hugonicolau on 04/11/2015.
 */
public class PolicyFragment extends Fragment implements SupervisorService.Callback {
    private final static String TAG = PolicyFragment.class.getSimpleName();

    private ListView mListView;
    private Button mApplyButton;

    // variables to populate the listview
    private PolicyListAdapter mAdapter;
    private String[] mApplyButtonLabels;

    // monitor changes to policy in order to update UI
    private Policy mCurrentPolicy;
    private BroadcastReceiver mPolicyChangedReceiver;

    // we need this to access SupervisorService and add callback
    private ServiceConnection mSupervisorConnection;
    private SupervisorService mSupervisor;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_policy, container, false);
        final Context context = getActivity();

        mListView = (ListView) view.findViewById(R.id.policieslistview);
        mApplyButton = (Button) view.findViewById(R.id.button_policy_apply);

        // get current policy
        mCurrentPolicy = Policy.getCurrentPolicy(context);
        mPolicyChangedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCurrentPolicy = (Policy) intent.getSerializableExtra(Policy.EXTRA_NEW_POLICY);
                Log.d(TAG, "received broadcast policy changed");
                if (mSupervisor.isActivated()) {
                    toggleActiveFlag(true);
                }
            }
        };
        Policy.registerPolicyChangedReceiver(getActivity(), mPolicyChangedReceiver);

        mAdapter = new PolicyListAdapter(context);
        mListView.setAdapter(mAdapter);

        mApplyButtonLabels = getResources().getStringArray(R.array.policy_button_labels);

        return view;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mListView.setItemChecked(mCurrentPolicy.ordinal(), true);

        mApplyButton.getBackground().setColorFilter(Color.GREEN, PorterDuff.Mode.MULTIPLY);
        mApplyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mSupervisor.changePolicy(getSelectedPolicy());
                if (!mSupervisor.isActivated()) {
                    // Not yet activated
                    mSupervisor.activateFIND();
                }
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();

        mSupervisorConnection = new ServiceConnection() {
            @Override
            public void onServiceDisconnected(ComponentName name) {
                mSupervisor = null;
            }

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                final SupervisorService.SupervisorBinder binder = (SupervisorService.SupervisorBinder) service;
                mSupervisor = binder.getSupervisor();
                mSupervisor.addCallback(PolicyFragment.this);
                onActivationStateChanged(mSupervisor.isActivated());
            }
        };
        SupervisorService.bindSupervisorService(getActivity(), mSupervisorConnection);
    }

    @Override
    public void onStop() {
        super.onStop();

        if (mSupervisor != null) {
            Policy.unregisterPolicyChangedReceiver(getActivity(), mPolicyChangedReceiver);
            mSupervisor.removeCallback(this);
            getActivity().unbindService(mSupervisorConnection);
            mSupervisorConnection = null;
        }
    }

    @Override
    public void onActivationStateChanged(final boolean activated) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                final int buttonLabelIndex = activated ? 1 : 0;
                mApplyButton.setText(mApplyButtonLabels[buttonLabelIndex].toUpperCase(Locale.US));

                toggleActiveFlag(activated);
            }
        });
    }

    private void toggleActiveFlag(boolean show) {
        final int listCount = mListView.getChildCount();
        final int first = mListView.getFirstVisiblePosition();
        final int currentPolicyPosition = mCurrentPolicy.ordinal();

        PolicyViewHolder viewHolder;
        for (int i = 0; i < listCount; i++) {
            int visibilityState = View.INVISIBLE;
            if (show && (first + i == currentPolicyPosition)) {
                visibilityState = View.VISIBLE;
            }

            viewHolder = (PolicyViewHolder) mListView.getChildAt(i).getTag();
            viewHolder.activeLabel.setVisibility(visibilityState);
        }
    }

    public Policy getSelectedPolicy() {
        final int selectedPosition = mListView.getCheckedItemPosition();
        return mAdapter.getItem(selectedPosition);
    }

    /*
     * PolicyListAdapter
     */
    private static class PolicyListAdapter extends ArrayAdapter<Policy> {
        private final LayoutInflater mInflater;
        private final String[] mPolicyTitles;
        private final String[] mPolicyDescriptions;

        public PolicyListAdapter(Context context) {
            super(context, R.layout.fragment_policy, new ArrayList<Policy>(Arrays.asList(Policy.values())));

            mInflater = LayoutInflater.from(context);

            Resources resources = context.getResources();
            mPolicyTitles = resources.getStringArray(R.array.policy_names);
            mPolicyDescriptions = resources.getStringArray(R.array.policy_descriptions);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            PolicyViewHolder viewHolder;

            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.list_item_policy, null);
                viewHolder = new PolicyViewHolder(convertView);
                convertView.setTag(viewHolder);
            } else {
                viewHolder = (PolicyViewHolder) convertView.getTag();
            }

            final ListView list = (ListView) parent;
            final CheckableRelativeLayout row = (CheckableRelativeLayout) convertView;
            final boolean isChecked = list.isItemChecked(position);

            row.setChecked(isChecked);
            viewHolder.title.setText(mPolicyTitles[position]);
            viewHolder.description.setText(mPolicyDescriptions[position]);

            return convertView;
        }
    }

    public static class PolicyViewHolder {
        public PolicyViewHolder(View listItem) {
            title = (TextView) listItem.findViewById(R.id.policyTitle);
            description = (TextView) listItem.findViewById(R.id.policyDescription);
            activeLabel = (TextView) listItem.findViewById(R.id.policyActiveFlag);
        }

        public TextView title;
        public TextView description;
        public TextView activeLabel;
    }
}
