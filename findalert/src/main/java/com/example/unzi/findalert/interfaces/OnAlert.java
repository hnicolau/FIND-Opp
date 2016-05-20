package com.example.unzi.findalert.interfaces;

import com.example.unzi.findalert.data.Alert;

/**
 * Created by unzi on 19/05/2016.
 */
public interface OnAlert {

    public void onAlertReceived(Alert mAlert, boolean isInside) ;
    public void onAlertStart(int mAlert) ;
    public void onAlertStop(int mAlert) ;

}
