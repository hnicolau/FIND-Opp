package ul.fcul.lasige.find.ui;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarActivity;
import android.view.Menu;
import android.view.MenuItem;

import ul.fcul.lasige.find.R;
import ul.fcul.lasige.find.service.SupervisorService;

/**
 * Entry point for platform's User Interface. It extends {@link ActionBarActivity} proving a tab navigation.
 * The UI contains 5 tabs (fragments): policy, applications, protocols, neighbors, and packets.
 *
 * Created by hugonicolau on 04/11/2015.
 */
@SuppressWarnings("deprecation")
public class MainActivity extends ActionBarActivity implements android.support.v7.app.ActionBar.TabListener, SupervisorService.Callback {
    private static final String TAG = MainActivity.class.getSimpleName();

    // platform's supervisor
    private ServiceConnection mSupervisorConnection;
    private SupervisorService mSupervisor;

    // ui tabs
    private CustomViewPager mTabsViewPager;
    private TabsAdapter mTabsAdapter;

    // ui switch
    MenuItem mUiSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // configure ui tabs
        configureTabs();
    }

    @Override
    protected void onStart() {
        super.onStart();

        // bind to supervisor
        mSupervisorConnection = new ServiceConnection() {
            @Override
            public void onServiceDisconnected(ComponentName name) {
                mSupervisor = null;
            }

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                final SupervisorService.SupervisorBinder binder = (SupervisorService.SupervisorBinder) service;
                mSupervisor = binder.getSupervisor();
                mSupervisor.addCallback(MainActivity.this);

                if(mUiSwitch != null) mUiSwitch.setChecked(mSupervisor.isActivated());
            }
        };
        bindService(new Intent(this, SupervisorService.class),
                mSupervisorConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();

        // unbind from supervisor
        unbindService(mSupervisorConnection);
        mSupervisorConnection = null;
    }


    /**
     * Configure UI tabs : policy, applications, protocols, neighbors, and packets.
     */
    private void configureTabs(){
        // get tab adapter
        mTabsViewPager = (CustomViewPager) findViewById(R.id.tabspager);
        mTabsAdapter = new TabsAdapter(getSupportFragmentManager());
        mTabsViewPager.setAdapter(mTabsAdapter);

        // configure action bar
        getSupportActionBar().setHomeButtonEnabled(false);
        getSupportActionBar().setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);

        // add tabs
        ActionBar.Tab policyTab = getSupportActionBar().newTab().setText("Policy").setTabListener(this);
        ActionBar.Tab appsTab = getSupportActionBar().newTab().setText("Apps").setTabListener(this);
        ActionBar.Tab protocolsTab = getSupportActionBar().newTab().setText("Protocols").setTabListener(this);
        ActionBar.Tab neighborsTab = getSupportActionBar().newTab().setText("Neighbors").setTabListener(this);
        ActionBar.Tab packetsTab = getSupportActionBar().newTab().setText("Packets").setTabListener(this);
        getSupportActionBar().addTab(policyTab);
        getSupportActionBar().addTab(appsTab);
        getSupportActionBar().addTab(protocolsTab);
        getSupportActionBar().addTab(neighborsTab);
        getSupportActionBar().addTab(packetsTab);

        // this helps in providing swiping effect for v7 compat library
        mTabsViewPager.setOnPageChangeListener(new CustomViewPager.OnPageChangeListener() {

            @Override
            public void onPageSelected(int position) {
                getSupportActionBar().setSelectedNavigationItem(position);
            }

            @Override
            public void onPageScrolled(int arg0, float arg1, int arg2) { }

            @Override
            public void onPageScrollStateChanged(int arg0) { }
        });
    }

    @Override
    public void onTabSelected(ActionBar.Tab tab, FragmentTransaction ft) {
        // update tab position on tap
        mTabsViewPager.setCurrentItem(tab.getPosition());
    }

    @Override
    public void onTabUnselected(ActionBar.Tab tab, FragmentTransaction ft) {

    }

    @Override
    public void onTabReselected(ActionBar.Tab tab, FragmentTransaction ft) {

    }

    /**
     *
     * Settings menu
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // inflate the menu; this adds items to the action bar if it is present
        getMenuInflater().inflate(R.menu.menu_main, menu);
        mUiSwitch = menu.findItem(R.id.action_switch);

        if (mSupervisor != null) {
            mUiSwitch.setChecked(mSupervisor.isActivated());
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml
        int id = item.getItemId();
        switch (id) {
            case R.id.action_switch:
                if(item.isChecked()) {
                    // turn off
                    if(mSupervisor != null) mSupervisor.deactivateFIND();
                }
                else {
                    // turn on
                    if(mSupervisor != null) mSupervisor.activateFIND();
                }

                // update ui
                if(mSupervisor != null) mUiSwitch.setChecked(mSupervisor.isActivated());

                return true;
            case R.id.action_settings:
                // TODO settings activity
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onActivationStateChanged(final boolean activated) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // update UI
                mUiSwitch.setChecked(activated);
            }
        });
    }
}
