package ul.fcul.lasige.findvictim.gcm;

import android.content.Context;

import com.example.unzi.findalert.data.Alert;
import com.example.unzi.findalert.interfaces.OnAlert;
import com.example.unzi.findalert.interfaces.OnRegisterComplete;
import com.example.unzi.findalert.ui.RegisterInFind;

import ul.fcul.lasige.findvictim.app.VictimApp;

/**
 * Created by unzi on 20/05/2016.
 */
public class ReceiverGCM implements OnAlert, OnRegisterComplete {
    private Context mContext;

    public ReceiverGCM(Context context){
        RegisterInFind registerInFind = RegisterInFind.sharedInstance(context);
        registerInFind.observeOnAlert(this);
        registerInFind.observeOnRegisterComplete(this);
        mContext=context;
    }

    @Override
    public void onAlertReceived(Alert mAlert, boolean isInside) {

    }

    @Override
    public void onAlertStart(int mAlert) {
        VictimApp app = (VictimApp) mContext.getApplicationContext();
        app.starSensors();
    }

    @Override
    public void onAlertStop(int mAlert) {
        VictimApp app = (VictimApp) mContext.getApplicationContext();
        app.stopSensors();
    }

    @Override
    public void OnRegisterComplete() {

    }
}
