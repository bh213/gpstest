/*
 * Copyright (C) 2008-2013 The Android Open Source Project,
 * Sean J. Barbeau
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.gpstest;

import android.support.v4.app.ActivityCompat;
import com.android.gpstest.util.GpsTestUtil;
import com.android.gpstest.util.MathUtils;
import com.android.gpstest.util.PreferenceUtils;
import com.android.gpstest.view.ViewPagerMapBevelScroll;

import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.location.GnssNavigationMessage;
import android.location.GnssStatus;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.location.OnNmeaMessageListener;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.annotation.RequiresApi;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.view.MenuItemCompat;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.View;
import android.view.Window;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

import static com.android.gpstest.util.GpsTestUtil.writeGnssMeasurementToLog;
import static com.android.gpstest.util.GpsTestUtil.writeNavMessageToLog;
import static com.android.gpstest.util.GpsTestUtil.writeNmeaToLog;

public class GpsTestActivity extends AppCompatActivity
        implements LocationListener, android.support.v7.app.ActionBar.TabListener, ActivityCompat.OnRequestPermissionsResultCallback,
        SensorEventListener {

    private static final String TAG = "GpsTestActivity";


    private static final int SECONDS_TO_MILLISECONDS = 1000;

    private static GpsTestActivity sInstance;

    // Holds sensor data
    private static float[] mRotationMatrix = new float[16];

    private static float[] mRemappedMatrix = new float[16];

    private static float[] mValues = new float[3];

    private static float[] mTruncatedRotationVector = new float[4];

    private static boolean mTruncateVector = false;

    boolean mStarted;

    boolean mFaceTrueNorth;

    boolean mWriteGnssMeasurementToLog;

    boolean mLogNmea;

    boolean mWriteNmeaTimestampToLog;

    String mTtff;

    org.jraf.android.backport.switchwidget.Switch mSwitch;  //GPS on/off switch

    SectionsPagerAdapter mSectionsPagerAdapter;

    ViewPagerMapBevelScroll mViewPager;

    private LocationManager mLocationManager;

    private LocationProvider mProvider;




    /**
     * Android N (7.0) and above status and listeners
     */
    private GnssStatus mGnssStatus;

    private GnssStatus.Callback mGnssStatusListener;

    private GnssMeasurementsEvent.Callback mGnssMeasurementsListener; // For SNRs

    private OnNmeaMessageListener mOnNmeaMessageListener;

    private GnssNavigationMessage.Callback mGnssNavMessageListener;

    // Listeners for Fragments
    private ArrayList<GpsTestListener> mGpsTestListeners = new ArrayList<GpsTestListener>();

    private Location mLastLocation;

    private GeomagneticField mGeomagneticField;

    private long minTime; // Min Time between location updates, in milliseconds

    private float minDistance; // Min Distance between location updates, in meters

    private SensorManager mSensorManager;

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        //requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        super.onCreate(savedInstanceState);
        sInstance = this;


        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 77);
        }

        // Set the default values from the XML file if this is the first
        // execution of the app
        init(savedInstanceState);
    }




    private void init(Bundle savedInstanceState) {
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);


        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        mProvider = mLocationManager.getProvider(LocationManager.GPS_PROVIDER);
        if (mProvider == null) {
            Log.e(TAG, "Unable to get GPS_PROVIDER");
            Toast.makeText(this, getString(R.string.gps_not_supported),
                    Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        setContentView(R.layout.activity_main);

        final android.support.v7.app.ActionBar actionBar = getSupportActionBar();
        actionBar.hide();


        //  page adapter contains all the fragment registrations
        mSectionsPagerAdapter = new SectionsPagerAdapter(getSupportFragmentManager());

        // Set up the ViewPager with the sections adapter.
        mViewPager = (ViewPagerMapBevelScroll) findViewById(R.id.pager);
        mViewPager.setAdapter(mSectionsPagerAdapter);
        mViewPager.setOffscreenPageLimit(3);


        SharedPreferences settings = Application.getPrefs();

        double tempMinTime = Double.valueOf(
                settings.getString(getString(R.string.pref_key_gps_min_time),
                        getString(R.string.pref_gps_min_time_default_sec))
        );
        minTime = (long) (tempMinTime * SECONDS_TO_MILLISECONDS);
        minDistance = Float.valueOf(
                settings.getString(getString(R.string.pref_key_gps_min_distance),
                        getString(R.string.pref_gps_min_distance_default_meters))
        );


        gpsStart();



    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    protected void onResume() {
        super.onResume();

        addStatusListener();

        addOrientationSensorListener();

        addNmeaListener();

        if (!mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            promptEnableGps();
        }

        /**
         * Check preferences to see how these componenets should be initialized
         */
        SharedPreferences settings = Application.getPrefs();

        checkKeepScreenOn(settings);

        checkTimeAndDistance(settings);

        checkTrueNorth(settings);

        checkNmeaLog(settings);

        if (GpsTestUtil.isGnssStatusListenerSupported()) {
            checkGnssMeasurementOutput(settings);
        }

        if (GpsTestUtil.isGnssStatusListenerSupported()) {
            checkNavMessageOutput(settings);
        }
    }

    @Override
    protected void onPause() {
        mSensorManager.unregisterListener(this);

        // Remove status listeners
        removeStatusListener();

        if (GpsTestUtil.isGnssStatusListenerSupported()) {
            removeNavMessageListener();
        }
        if (GpsTestUtil.isGnssStatusListenerSupported()) {
            removeGnssMeasurementsListener();
        }
        super.onPause();
    }

    static GpsTestActivity getInstance() {
        return sInstance;
    }

    void addListener(GpsTestListener listener) {
        mGpsTestListeners.add(listener);
    }

    private synchronized void gpsStart() {
        if (!mStarted) {
            mLocationManager
                    .requestLocationUpdates(mProvider.getName(), minTime, minDistance, this);
            mStarted = true;

            // Show Toast only if the user has set minTime or minDistance to something other than default values
            if (minTime != (long) (Double.valueOf(getString(R.string.pref_gps_min_time_default_sec))
                    * SECONDS_TO_MILLISECONDS) ||
                    minDistance != Float
                            .valueOf(getString(R.string.pref_gps_min_distance_default_meters))) {
                Toast.makeText(this, String.format(getString(R.string.gps_set_location_listener),
                        String.valueOf((double) minTime / SECONDS_TO_MILLISECONDS),
                        String.valueOf(minDistance)), Toast.LENGTH_SHORT).show();
            }

            // Show the indeterminate progress bar on the action bar until first GPS status is shown
            setSupportProgressBarIndeterminateVisibility(Boolean.TRUE);

            // Reset the options menu to trigger updates to action bar menu items
            invalidateOptionsMenu();
        }
        for (GpsTestListener listener : mGpsTestListeners) {
            listener.gpsStart();
        }
    }

    private synchronized void gpsStop() {
        if (mStarted) {
            mLocationManager.removeUpdates(this);
            mStarted = false;
            // Stop progress bar
            setSupportProgressBarIndeterminateVisibility(Boolean.FALSE);

            // Reset the options menu to trigger updates to action bar menu items
            invalidateOptionsMenu();
        }
        for (GpsTestListener listener : mGpsTestListeners) {
            listener.gpsStop();
        }
    }

    private boolean sendExtraCommand(String command) {
        return mLocationManager.sendExtraCommand(LocationManager.GPS_PROVIDER, command, null);
    }

    private void addOrientationSensorListener() {
        if (GpsTestUtil.isRotationVectorSensorSupported(this)) {
            // Use the modern rotation vector sensors
            Sensor vectorSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            mSensorManager.registerListener(this, vectorSensor, 16000); // ~60hz
        } else {
            // Use the legacy orientation sensors
            Sensor sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION);
            if (sensor != null) {
                mSensorManager.registerListener(this, sensor,
                        SensorManager.SENSOR_DELAY_GAME);
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void addStatusListener() {
        addGnssStatusListener();
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private void addGnssStatusListener() {
        mGnssStatusListener = new GnssStatus.Callback() {
            @Override
            public void onStarted() {
                for (GpsTestListener listener : mGpsTestListeners) {
                    listener.onGnssStarted();
                }
            }

            @Override
            public void onStopped() {
                for (GpsTestListener listener : mGpsTestListeners) {
                    listener.onGnssStopped();
                }
            }

            @Override
            public void onFirstFix(int ttffMillis) {
                if (ttffMillis == 0) {
                    mTtff = "";
                } else {
                    ttffMillis = (ttffMillis + 500) / 1000;
                    mTtff = Integer.toString(ttffMillis) + " sec";
                }
                for (GpsTestListener listener : mGpsTestListeners) {
                    listener.onGnssFirstFix(ttffMillis);
                }
            }

            @Override
            public void onSatelliteStatusChanged(GnssStatus status) {
                mGnssStatus = status;

                Log.e("XXX", String.valueOf(status.getSatelliteCount()));
                // Stop progress bar after the first status information is obtained
                setSupportProgressBarIndeterminateVisibility(Boolean.FALSE);

                for (GpsTestListener listener : mGpsTestListeners) {
                    listener.onSatelliteStatusChanged(mGnssStatus);
                }
            }
        };
        mLocationManager.registerGnssStatusCallback(mGnssStatusListener);
}

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void addGnssMeasurementsListener() {
        mGnssMeasurementsListener = new GnssMeasurementsEvent.Callback() {
            @Override
            public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
                for (GpsTestListener listener : mGpsTestListeners) {
                    listener.onGnssMeasurementsReceived(event);
                }
                if (mWriteGnssMeasurementToLog) {
                    for (GnssMeasurement m : event.getMeasurements()) {
                        writeGnssMeasurementToLog(m);
                    }
                }
            }

            @Override
            public void onStatusChanged(int status) {

            }
        };
        mLocationManager.registerGnssMeasurementsCallback(mGnssMeasurementsListener);
    }


    @RequiresApi(api = Build.VERSION_CODES.N)
    private void removeStatusListener() {
        removeGnssStatusListener();
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private void removeGnssStatusListener() {
        mLocationManager.unregisterGnssStatusCallback(mGnssStatusListener);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void removeGnssMeasurementsListener() {
        if (mLocationManager != null && mGnssMeasurementsListener != null) {
            mLocationManager.unregisterGnssMeasurementsCallback(mGnssMeasurementsListener);
        }
    }


    private void addNmeaListener() {
        addNmeaListenerAndroidN();
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void addNmeaListenerAndroidN() {
        if (mOnNmeaMessageListener == null) {
            mOnNmeaMessageListener = new OnNmeaMessageListener() {
                @Override
                public void onNmeaMessage(String message, long timestamp) {
                    for (GpsTestListener listener : mGpsTestListeners) {
                        listener.onNmeaMessage(message, timestamp);
                    }
                    if (mLogNmea) {
                        writeNmeaToLog(message,
                                mWriteNmeaTimestampToLog ? timestamp : Long.MIN_VALUE);
                    }
                }
            };
        }
        mLocationManager.addNmeaListener(mOnNmeaMessageListener);
    }



    @RequiresApi(api = Build.VERSION_CODES.N)
    private void addNavMessageListener() {
        if (mGnssNavMessageListener == null) {
            mGnssNavMessageListener = new GnssNavigationMessage.Callback() {
                @Override
                public void onGnssNavigationMessageReceived(GnssNavigationMessage event) {
                    writeNavMessageToLog(event);
                }

                @Override
                public void onStatusChanged(int status) {

                }
            };
        }
        mLocationManager.registerGnssNavigationMessageCallback(mGnssNavMessageListener);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void removeNavMessageListener() {
        if (mLocationManager != null && mGnssNavMessageListener != null) {
            mLocationManager.unregisterGnssNavigationMessageCallback(mGnssNavMessageListener);
        }
    }

    /**
     * Ask the user if they want to enable GPS
     */
    private void promptEnableGps() {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.enable_gps_message))
                .setPositiveButton(getString(R.string.enable_gps_positive_button),
                        new DialogInterface.OnClickListener() {

                            public void onClick(DialogInterface dialog, int which) {
                                Intent intent = new Intent(
                                        Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                                startActivity(intent);
                            }
                        }
                )
                .setNegativeButton(getString(R.string.enable_gps_negative_button),
                        new DialogInterface.OnClickListener() {

                            public void onClick(DialogInterface dialog, int which) {
                            }
                        }
                )
                .show();
    }

    private void checkTimeAndDistance(SharedPreferences settings) {
        double tempMinTimeDouble = Double
                .valueOf(settings.getString(getString(R.string.pref_key_gps_min_time), "1"));
        long minTimeLong = (long) (tempMinTimeDouble * SECONDS_TO_MILLISECONDS);

        if (minTime != minTimeLong ||
                minDistance != Float.valueOf(
                        settings.getString(getString(R.string.pref_key_gps_min_distance), "0"))) {
            // User changed preference values, get the new ones
            minTime = minTimeLong;
            minDistance = Float.valueOf(
                    settings.getString(getString(R.string.pref_key_gps_min_distance), "0"));
            // If the GPS is started, reset the location listener with the new values
            if (mStarted) {
                mLocationManager
                        .requestLocationUpdates(mProvider.getName(), minTime, minDistance, this);
                Toast.makeText(this, String.format(getString(R.string.gps_set_location_listener),
                        String.valueOf(tempMinTimeDouble), String.valueOf(minDistance)),
                        Toast.LENGTH_SHORT
                ).show();
            }
        }
    }

    private void checkKeepScreenOn(SharedPreferences settings) {

            if (settings.getBoolean(getString(R.string.pref_key_keep_screen_on), true)) {
                mViewPager.setKeepScreenOn(true);
            } else {
                mViewPager.setKeepScreenOn(false);
            }
    }

    private void checkTrueNorth(SharedPreferences settings) {
        mFaceTrueNorth = settings.getBoolean(getString(R.string.pref_key_true_north), true);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void checkGnssMeasurementOutput(SharedPreferences settings) {
        mWriteGnssMeasurementToLog = settings
                .getBoolean(getString(R.string.pref_key_measurement_output), false);

        if (mWriteGnssMeasurementToLog) {
            addGnssMeasurementsListener();
        }
    }

    private void checkNmeaLog(SharedPreferences settings) {
        mLogNmea = settings.getBoolean(getString(R.string.pref_key_nmea_output), true);
        mWriteNmeaTimestampToLog = settings
                .getBoolean(getString(R.string.pref_key_nmea_timestamp_output), true);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void checkNavMessageOutput(SharedPreferences settings) {
        boolean logNavMessage = settings
                .getBoolean(getString(R.string.pref_key_navigation_message_output), false);

        if (logNavMessage) {
            addNavMessageListener();
        } else {
            removeNavMessageListener();
        }
    }

    @Override
    protected void onDestroy() {
        mLocationManager.removeUpdates(this);
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.gps_menu, menu);
        initGpsSwitch(menu);
        return true;
    }

    private void initGpsSwitch(Menu menu) {
        MenuItem item = menu.findItem(R.id.gps_switch);
        if (item != null) {
            mSwitch = (org.jraf.android.backport.switchwidget.Switch) MenuItemCompat
                    .getActionView(item);
            if (mSwitch != null) {
                // Initialize state of GPS switch before we set the listener, so we don't double-trigger start or stop
                mSwitch.setChecked(mStarted);

                // Set up listener for GPS on/off switch, since custom menu items on Action Bar don't play
                // well with ABS and we can't handle in onOptionsItemSelected()
                mSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        // Turn GPS on or off
                        if (!isChecked && mStarted) {
                            gpsStop();
                        } else {
                            if (isChecked && !mStarted) {
                                gpsStart();
                            }
                        }
                    }
                });
            }
        }
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem item;

        item = menu.findItem(R.id.send_location);
        if (item != null) {
            item.setVisible(mLastLocation != null);
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        boolean success;
        // Handle menu item selection
        switch (item.getItemId()) {
            case R.id.gps_switch:
                // Do nothing - this is handled by a separate listener added in onCreateOptionsMenu()
                return true;
            case R.id.delete_aiding_data:
                // If GPS is currently running, stop it
                boolean lastStartState = mStarted;
                if (mStarted) {
                    gpsStop();
                }
                success = sendExtraCommand(getString(R.string.delete_aiding_data_command));
                if (success) {
                    Toast.makeText(this, getString(R.string.delete_aiding_data_success),
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, getString(R.string.delete_aiding_data_failure),
                            Toast.LENGTH_SHORT).show();
                }

                if (lastStartState) {
                    Handler h = new Handler();
                    // Restart the GPS, if it was previously started, with a slight delay,
                    // to refresh the assistance data
                    h.postDelayed(new Runnable() {
                        public void run() {
                            gpsStart();
                        }
                    }, 500);
                }
                return true;
            case R.id.send_location:
                sendLocation();
                return true;
            case R.id.force_time_injection:
                success = sendExtraCommand(getString(R.string.force_time_injection_command));
                if (success) {
                    Toast.makeText(this, getString(R.string.force_time_injection_success),
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, getString(R.string.force_time_injection_failure),
                            Toast.LENGTH_SHORT).show();
                }
                return true;
            case R.id.force_xtra_injection:
                success = sendExtraCommand(getString(R.string.force_xtra_injection_command));
                if (success) {
                    Toast.makeText(this, getString(R.string.force_xtra_injection_success),
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, getString(R.string.force_xtra_injection_failure),
                            Toast.LENGTH_SHORT).show();
                }
                return true;
            case R.id.menu_settings:
                // Show settings menu
                startActivity(new Intent(this, Preferences.class));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    public void onLocationChanged(Location location) {
        mLastLocation = location;

        updateGeomagneticField();

        // Reset the options menu to trigger updates to action bar menu items
        invalidateOptionsMenu();

        for (GpsTestListener listener : mGpsTestListeners) {
            listener.onLocationChanged(location);
        }
    }

    public void onStatusChanged(String provider, int status, Bundle extras) {
        for (GpsTestListener listener : mGpsTestListeners) {
            listener.onStatusChanged(provider, status, extras);
        }
    }

    public void onProviderEnabled(String provider) {
        for (GpsTestListener listener : mGpsTestListeners) {
            listener.onProviderEnabled(provider);
        }
    }

    public void onProviderDisabled(String provider) {
        for (GpsTestListener listener : mGpsTestListeners) {
            listener.onProviderDisabled(provider);
        }
    }

    @TargetApi(Build.VERSION_CODES.GINGERBREAD)
    @Override
    public void onSensorChanged(SensorEvent event) {

        double orientation = Double.NaN;
        double tilt = Double.NaN;

        switch (event.sensor.getType()) {
            case Sensor.TYPE_ROTATION_VECTOR:
                // Modern rotation vector sensors
                if (!mTruncateVector) {
                    try {
                        SensorManager.getRotationMatrixFromVector(mRotationMatrix, event.values);
                    } catch (IllegalArgumentException e) {
                        // On some Samsung devices, an exception is thrown if this vector > 4 (see #39)
                        // Truncate the array, since we can deal with only the first four values
                        Log.e(TAG, "Samsung device error? Will truncate vectors - " + e);
                        mTruncateVector = true;
                        // Do the truncation here the first time the exception occurs
                        getRotationMatrixFromTruncatedVector(event.values);
                    }
                } else {
                    // Truncate the array to avoid the exception on some devices (see #39)
                    getRotationMatrixFromTruncatedVector(event.values);
                }

                int rot = getWindowManager().getDefaultDisplay().getRotation();
                switch (rot) {
                    case Surface.ROTATION_0:
                        // No orientation change, use default coordinate system
                        SensorManager.getOrientation(mRotationMatrix, mValues);
                        // Log.d(TAG, "Rotation-0");
                        break;
                    case Surface.ROTATION_90:
                        // Log.d(TAG, "Rotation-90");
                        SensorManager.remapCoordinateSystem(mRotationMatrix, SensorManager.AXIS_Y,
                                SensorManager.AXIS_MINUS_X, mRemappedMatrix);
                        SensorManager.getOrientation(mRemappedMatrix, mValues);
                        break;
                    case Surface.ROTATION_180:
                        // Log.d(TAG, "Rotation-180");
                        SensorManager
                                .remapCoordinateSystem(mRotationMatrix, SensorManager.AXIS_MINUS_X,
                                        SensorManager.AXIS_MINUS_Y, mRemappedMatrix);
                        SensorManager.getOrientation(mRemappedMatrix, mValues);
                        break;
                    case Surface.ROTATION_270:
                        // Log.d(TAG, "Rotation-270");
                        SensorManager
                                .remapCoordinateSystem(mRotationMatrix, SensorManager.AXIS_MINUS_Y,
                                        SensorManager.AXIS_X, mRemappedMatrix);
                        SensorManager.getOrientation(mRemappedMatrix, mValues);
                        break;
                    default:
                        // This shouldn't happen - assume default orientation
                        SensorManager.getOrientation(mRotationMatrix, mValues);
                        // Log.d(TAG, "Rotation-Unknown");
                        break;
                }
                orientation = Math.toDegrees(mValues[0]);  // azimuth
                tilt = Math.toDegrees(mValues[1]);
                break;
            case Sensor.TYPE_ORIENTATION:
                // Legacy orientation sensors
                orientation = event.values[0];
                break;
            default:
                // A sensor we're not using, so return
                return;
        }

        // Correct for true north, if preference is set
        if (mFaceTrueNorth && mGeomagneticField != null) {
            orientation += mGeomagneticField.getDeclination();
            // Make sure value is between 0-360
            orientation = MathUtils.mod((float) orientation, 360.0f);
        }

        for (GpsTestListener listener : mGpsTestListeners) {
            listener.onOrientationChanged(orientation, tilt);
        }
    }

    @TargetApi(Build.VERSION_CODES.GINGERBREAD)
    private void getRotationMatrixFromTruncatedVector(float[] vector) {
        System.arraycopy(vector, 0, mTruncatedRotationVector, 0, 4);
        SensorManager.getRotationMatrixFromVector(mRotationMatrix, mTruncatedRotationVector);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void updateGeomagneticField() {
        mGeomagneticField = new GeomagneticField((float) mLastLocation.getLatitude(),
                (float) mLastLocation.getLongitude(), (float) mLastLocation.getAltitude(),
                mLastLocation.getTime());
    }

    private void sendLocation() {
        if (mLastLocation != null) {
            Intent intent = new Intent(Intent.ACTION_SENDTO,
                    Uri.fromParts("mailto", "", null));
            String location = "http://maps.google.com/maps?geocode=&q=" +
                    Double.toString(mLastLocation.getLatitude()) + "," +
                    Double.toString(mLastLocation.getLongitude());
            intent.putExtra(Intent.EXTRA_TEXT, location);
            startActivity(intent);
        }
    }

    @Override
    public void onTabSelected(android.support.v7.app.ActionBar.Tab tab, FragmentTransaction ft) {
        // When the given tab is selected, switch to the corresponding page in
        // the ViewPager.
        if (mViewPager != null) {
            mViewPager.setCurrentItem(tab.getPosition());
        }
    }

    @Override
    public void onTabUnselected(android.support.v7.app.ActionBar.Tab tab, FragmentTransaction ft) {
    }

    @Override
    public void onTabReselected(android.support.v7.app.ActionBar.Tab tab, FragmentTransaction ft) {
    }






}
