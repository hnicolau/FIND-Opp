package com.example.unzi.findalert.ui;

import android.content.Context;
import android.content.Intent;

import com.example.unzi.findalert.data.TokenStore;
import com.example.unzi.findalert.interfaces.OnAlertReceived;
import com.example.unzi.findalert.interfaces.OnRegisterComplete;

import java.util.ArrayList;


/**
 * Created by unzi on 19/05/2016.
 */
public class RegisterInFind {

    private static RegisterInFind mSharedInstance;
    private Context mContext;
    private ArrayList<OnRegisterComplete> registerObservers;
    private ArrayList<OnAlertReceived> alertObservers;

    private RegisterInFind (Context context){
        mContext=context;
        alertObservers = new ArrayList<OnAlertReceived>();
    }

    public static RegisterInFind sharedInstance (Context context){
        if(mSharedInstance==null)
            mSharedInstance= new RegisterInFind(context);
        return  mSharedInstance;
    }

    public void register(){
        boolean sentToken = TokenStore.isRegistered(mContext);
        if(!sentToken) {
            mContext.startActivity(new Intent(mContext,MainActivity.class));
        }
    }

    public void observeOnAlert(OnAlertReceived onAlertReceived){
         alertObservers.add(onAlertReceived);
    }
    public void unregisterOnAlert(OnAlertReceived onAlertReceived){
        alertObservers.remove(onAlertReceived);
    }

    public void observeOnRegisterComplete(OnRegisterComplete onRegisterComplete){
        registerObservers.add(onRegisterComplete);
    }
    public void unregisterOnRegisterComplete(OnRegisterComplete onRegisterComplete){
        registerObservers.remove(onRegisterComplete);
    }

    public void receivedAlert(){
        for(OnAlertReceived alert :alertObservers)
            alert.onAlertReceived();
    }

    public void registerCompleted(){
        for(OnRegisterComplete observer :registerObservers)
            observer.OnRegisterComplete();
    }
}
