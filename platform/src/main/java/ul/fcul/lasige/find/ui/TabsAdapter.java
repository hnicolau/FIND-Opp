package ul.fcul.lasige.find.ui;

import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentStatePagerAdapter;

/**
 * Created by hugonicolau on 09/10/15.
 */
public class TabsAdapter extends FragmentStatePagerAdapter {

    private final int TOTAL_TABS = 5;

    public TabsAdapter(FragmentManager fm) {
        super(fm);
    }

    @Override
    public Fragment getItem(int index) {
        switch (index) {
            case 0:
                return new PolicyFragment();
            case 1:
                return new AppsFragment();
            case 2:
                return new ProtocolsFragment();
            case 3:
                return new NeighborsFragment();
            case 4:
                return new PacketsFragment();
        }

        return null;
    }

    @Override
    public int getCount() {
        return TOTAL_TABS;
    }
}
