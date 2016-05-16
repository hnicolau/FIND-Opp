package ul.fcul.lasige.findvictim.ui;

import android.content.Intent;
import android.database.Cursor;
import android.support.v4.app.FragmentActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import com.example.unzi.offlinemaps.TilesProvider;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;

import ul.fcul.lasige.findvictim.R;
import ul.fcul.lasige.findvictim.data.Alert;
import ul.fcul.lasige.findvictim.data.DatabaseHelper;

public class AlertActivity extends FragmentActivity implements OnMapReadyCallback {

    private GoogleMap mMap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alert);



        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);

        //offline maps
        mMap = mapFragment.getMap();

        startTileProvider(mMap);

        Alert alert = (Alert) getIntent().getSerializableExtra("Alert");
        if(alert==null)
            alert = getActiveAlert();
        if(alert!=null)
            setAlertParameters(alert);
    }

    //get our tile database provider
    private void startTileProvider(GoogleMap googleMap) {
        String path =getFilesDir()+ "/mapapp/world.sqlitedb";
        TilesProvider tilesProvider = new TilesProvider(mMap, path);
    }

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Sydney, Australia.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    @Override
    public void onMapReady(GoogleMap googleMap) {


        // Add a marker in Sydney and move the camera
        LatLng sydney = new LatLng(-34, 151);
        mMap.addMarker(new MarkerOptions().position(sydney).title("Marker in Sydney"));
        mMap.moveCamera(CameraUpdateFactory.newLatLng(sydney));
    }

    public void setAlertParameters(Alert alertParameters) {
        TextView alertName = (TextView) findViewById(R.id.alertName);
        TextView alertType = (TextView) findViewById(R.id.alertType);
        TextView alertDescription = (TextView) findViewById(R.id.alertDescription);
        TextView alertDate = (TextView) findViewById(R.id.alertStartDate);

        alertName.setText(alertParameters.getName()+ " -");
        alertDescription.setText(alertParameters.getDescription());
        alertType.setText( alertParameters.getType());
        alertDate.setText(alertParameters.getDate()+"");

        Log.d("test", "teste");
        PolylineOptions rectOptions = new PolylineOptions()
                .add(new LatLng(alertParameters.getLatStart(), alertParameters.getLonStart()))
                .add(new LatLng(alertParameters.getLatEnd(), alertParameters.getLonStart()))
                .add(new LatLng(alertParameters.getLatEnd(), alertParameters.getLonEnd()))
                .add(new LatLng(alertParameters.getLatStart(), alertParameters.getLonEnd()))
                .add(new LatLng(alertParameters.getLatStart(), alertParameters.getLonStart()));

        LatLng focus = new LatLng(alertParameters.getLatStart(),  alertParameters.getLonEnd());

        mMap.moveCamera(CameraUpdateFactory.newLatLng(focus));

        // Get back the mutable Polyline
        Polyline polyline = mMap.addPolyline(rectOptions);
        polyline.setZIndex(100);
    }

    public Alert getActiveAlert() {
        Cursor cursor = Alert.Store.fetchAlerts(
                DatabaseHelper.getInstance(getApplicationContext()).getReadableDatabase(),
                Alert.STATUS.ONGOING);

        if (!cursor.moveToFirst()) {
            // no current ongoing alerts, stop SensorsService
            cursor.close();
            return null;
        }
        // check whether we are inside the alert area
        return  Alert.fromCursor(cursor);
    }
}
