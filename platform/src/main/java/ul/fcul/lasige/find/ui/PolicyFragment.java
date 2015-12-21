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
 * Fragment that shows the platform's policies and state (ON/OFF).
 *
 * Created by hugonicolau on 04/11/2015.
 */
public class PolicyFragment extends Fragment implements SupervisorService.Callback {
    private final static String TAG = PolicyFragment.class.getSimpleName();

    // policy list
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

        // get UI items
        mListView = (ListView) view.findViewById(R.id.policieslistview);
        mApplyButton = (Button) view.findViewById(R.id.button_policy_apply);

        // get current policy
        mCurrentPolicy = Policy.getCurrentPolicy(context);
        // policy receiver to update interface when a change to a policy occurs
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
        // register policy receiver
        Policy.registerPolicyChangedReceiver(getActivity(), mPolicyChangedReceiver);

        // populate policy list
        mAdapter = new PolicyListAdapter(context);
        mListView.setAdapter(mAdapter);

        // button labels
        mApplyButtonLabels = getResources().getStringArray(R.array.policy_button_labels);

        return view;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // update current policy view
        mListView.setItemChecked(mCurrentPolicy.ordinal(), true);

        // update button ui
        mApplyButton.getBackground().setColorFilter(Color.GREEN, PorterDuff.Mode.MULTIPLY);
        // set button behavior
        mApplyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // change policy to selected one
                mSupervisor.changePolicy(getSelectedPolicy());
                if (!mSupervisor.isActivated()) {
                    // platform is not yet activated, so activate it
                    mSupervisor.activateFIND();
                }
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();

        // get supervisor service connection
        mSupervisorConnection = new ServiceConnection() {
            @Override
            public void onServiceDisconnected(ComponentName name) {
                mSupervisor = null;
            }

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                // get service
                final SupervisorService.SupervisorBinder binder = (SupervisorService.SupervisorBinder) service;
                mSupervisor = binder.getSupervisor();
                // add callbacks for supervisor state (ON/OFF) changes
                mSupervisor.addCallback(PolicyFragment.this);
                // trigger callback to update view is current state
                onActivationStateChanged(mSupervisor.isActivated());
            }
        };
        // bind
        SupervisorService.bindSupervisorService(getActivity(), mSupervisorConnection);
    }

    @Override
    public void onStop() {
        super.onStop();

        if (mSupervisor != null) {
            // unregister
            Policy.unregisterPolicyChangedReceiver(getActivity(), mPolicyChangedReceiver);
            // remove callback
            mSupervisor.removeCallback(this);
            // unbind
            getActivity().unbindService(mSupervisorConnection);
            mSupervisorConnection = null;
        }
    }

    /**
     * Callback triggered by {@link SupervisorService} when its state changes.
     * @param activated Indicates whether the platform is active.
     */
    @Override
    public void onActivationStateChanged(final boolean activated) {
        // update UI
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                final int buttonLabelIndex = activated ? 1 : 0;
                mApplyButton.setText(mApplyButtonLabels[buttonLabelIndex].toUpperCase(Locale.US));

                toggleActiveFlag(activated);
            }
        });
    }

    /**
     * Update UI based on supervisor state.
     * @param show Indicates whether the platform is active.
     */
    private void toggleActiveFlag(boolean show) {
        // get all policy items
        final int listCount = mListView.getChildCount();
        final int first = mListView.getFirstVisiblePosition();
        // get current policy
        final int currentPolicyPosition = mCurrentPolicy.ordinal();

        PolicyViewHolder viewHolder;
        for (int i = 0; i < listCount; i++) {
            int visibilityState = View.INVISIBLE;
            if (show && (first + i == currentPolicyPosition)) {
                // only shows label 'active' on current policy
                visibilityState = View.VISIBLE;
            }

            viewHolder = (PolicyViewHolder) mListView.getChildAt(i).getTag();
            // set visibility
            viewHolder.activeLabel.setVisibility(visibilityState);
        }
    }

    /**
     * Retrieves the currently selected policy.
     * @return Current policy
     * @see Policy
     */
    public Policy getSelectedPolicy() {
        final int selectedPosition = mListView.getCheckedItemPosition();
        return mAdapter.getItem(selectedPosition);
    }

    /**
     * Auxiliary class extending from {@link ArrayAdapter} to populate a list view with a set of policies.
     */
    private static class PolicyListAdapter extends ArrayAdapter<Policy> {
        private final LayoutInflater mInflater;
        // policies
        private final String[] mPolicyTitles;
        // policies' descriptions
        private final String[] mPolicyDescriptions;

        /**
         * Constructor.
         * @param context Application context
         */
        public PolicyListAdapter(Context context) {
            super(context, R.layout.fragment_policy, new ArrayList<>(Arrays.asList(Policy.values())));

            mInflater = LayoutInflater.from(context);

            Resources resources = context.getResources();
            mPolicyTitles = resources.getStringArray(R.array.policy_names);
            mPolicyDescriptions = resources.getStringArray(R.array.policy_descriptions);
        }

        /**
         * Returns a View that represents a policy and its description. It is composed of a title
         * and description.
         * @param position Position.
         * @param convertView Policy view.
         * @param parent Parent view.
         * @return Policy view.
         */
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

    /**
     * Represents a policy view.
     */
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
