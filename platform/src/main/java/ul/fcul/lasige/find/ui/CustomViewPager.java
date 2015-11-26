package ul.fcul.lasige.find.ui;

import android.content.Context;
import android.support.v4.view.ViewPager;
import android.util.AttributeSet;
import android.view.MotionEvent;

/**
 * Created by hugonicolau on 09/10/15.
 */
public class CustomViewPager extends ViewPager {

    private boolean mIsSwipeable;

    public CustomViewPager(Context context, AttributeSet attrs) {
        super(context, attrs);
        mIsSwipeable = false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event){
        return mIsSwipeable && super.onTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event){
        return mIsSwipeable && super.onTouchEvent(event);
    }

    public void setSwipeable(boolean enable){
        mIsSwipeable = enable;
    }
}
