package com.example.unzi.findalert.ui;

import android.content.Context;
import android.content.Intent;

import com.example.unzi.findalert.data.Alert;
import com.example.unzi.findalert.data.TokenStore;
import com.example.unzi.findalert.interfaces.OnAlert;
import com.example.unzi.findalert.interfaces.OnRegisterComplete;

import java.util.ArrayList;


/**
 * Created by unzi on 19/05/2016.
 */
public class RegisterInFind {

    private static RegisterInFind mSharedInstance;
    private Context mContext;
    private ArrayList<OnRegisterComplete> registerObservers;
    private ArrayList<OnAlert> alertObservers;

    private RegisterInFind (Context context){
        mContext=context;
        alertObservers = new ArrayList<OnAlert>();
        registerObservers = new ArrayList<OnRegisterComplete>();

    }

    public static RegisterInFind sharedInstance (Context context){
        if(mSharedInstance==null)
            mSharedInstance= new RegisterInFind(context);
        return  mSharedInstance;
    }

    public void register(){
        boolean sentToken = TokenStore.isRegistered(mContext);
        if(!sentToken) {
            Intent myIntent = new Intent(mContext,MainActivity.class);
            myIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            mContext.startActivity(myIntent);
        }
    }

    public void observeOnAlert(OnAlert onAlert){
         alertObservers.add(onAlert);
    }
    public void unregisterOnAlert(OnAlert onAlert){
        alertObservers.remove(onAlert);
    }

    public void observeOnRegisterComplete(OnRegisterComplete onRegisterComplete){
        registerObservers.add(onRegisterComplete);
    }
    public void unregisterOnRegisterComplete(OnRegisterComplete onRegisterComplete){
        registerObservers.remove(onRegisterComplete);
    }

    public void receivedAlert(Alert mAlert, boolean isInside){
        for(OnAlert alert :alertObservers)
            alert.onAlertReceived(mAlert,isInside);
    }
    public void startAlert(int alertID){
        for(OnAlert alert :alertObservers)
            alert.onAlertStart(alertID);
    }
    public void stopAlert(int alertID){
        for(OnAlert alert :alertObservers)
            alert.onAlertStop(alertID);
    }

    public void registerCompleted(){
        for(OnRegisterComplete observer :registerObservers)
            observer.OnRegisterComplete();
    }
}
