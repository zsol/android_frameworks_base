/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2009, Code Aurora Forum, Inc. All rights reserved.
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

package com.android.server;

import java.util.Timer;
import java.util.TimerTask;

import android.app.ActivityManagerNative;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.AudioService;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.provider.Contacts.People;
import android.server.BluetoothA2dpService;
import android.server.BluetoothDeviceService;
import android.server.BluetoothFtpService;
import android.server.BluetoothOppService;
import android.server.search.SearchManagerService;
import android.util.EventLog;
import android.util.Log;

import com.android.server.am.ActivityManagerService;
import com.android.server.status.StatusBarService;

import dalvik.system.VMRuntime;

class ServerThread extends Thread {
    private static final String TAG = "SystemServer";
    private final static boolean INCLUDE_DEMO = false;

    private static final int LOG_BOOT_PROGRESS_SYSTEM_RUN = 3010;

    private ContentResolver mContentResolver;

    private class AdbSettingsObserver extends ContentObserver {
        public AdbSettingsObserver() {
            super(null);
        }
        @Override
        public void onChange(boolean selfChange) {
            boolean enableAdb = (Settings.Secure.getInt(mContentResolver,
                Settings.Secure.ADB_ENABLED, 0) > 0);
            // setting this secure property will start or stop adbd
           SystemProperties.set("persist.service.adb.enable", enableAdb ? "1" : "0");
        }
    }

    private class CompcacheSettingsObserver extends ContentObserver {
        public CompcacheSettingsObserver() {
            super(null);
        }
        @Override
        public void onChange(boolean selfChange) {
            boolean enableCompcache = (Settings.Secure.getInt(mContentResolver,
                Settings.Secure.COMPCACHE_ENABLED, 0) > 0);
            // setting this secure property will start or stop compcache
            SystemProperties.set("persist.service.compcache", enableCompcache ? "1" : "0");
        }
    }
    
    @Override
    public void run() {
        EventLog.writeEvent(LOG_BOOT_PROGRESS_SYSTEM_RUN,
            SystemClock.uptimeMillis());

        ActivityManagerService.prepareTraceFile(false);     // create dir

        Looper.prepare();

        android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_FOREGROUND);

        String factoryTestStr = SystemProperties.get("ro.factorytest");
        int factoryTest = "".equals(factoryTestStr) ? SystemServer.FACTORY_TEST_OFF
                : Integer.parseInt(factoryTestStr);

        HardwareService hardware = null;
        PowerManagerService power = null;
        BatteryService battery = null;
        ConnectivityService connectivity = null;
        IPackageManager pm = null;
        Context context = null;
        WindowManagerService wm = null;
        BluetoothDeviceService bluetooth = null;
        BluetoothA2dpService bluetoothA2dp = null;
        BluetoothFtpService bluetoothFtp = null;
        BluetoothOppService bluetoothOpp = null;
        HeadsetObserver headset = null;

        // Critical services...
        try {
            Log.i(TAG, "Entropy Service");
            ServiceManager.addService("entropy", new EntropyService());

            Log.i(TAG, "Power Manager");
            power = new PowerManagerService();
            ServiceManager.addService(Context.POWER_SERVICE, power);

            Log.i(TAG, "Activity Manager");
            context = ActivityManagerService.main(factoryTest);

            Log.i(TAG, "Telephony Registry");
            ServiceManager.addService("telephony.registry", new TelephonyRegistry(context));

            AttributeCache.init(context);

            Log.i(TAG, "Package Manager");
            pm = PackageManagerService.main(context,
                    factoryTest != SystemServer.FACTORY_TEST_OFF);

            ActivityManagerService.setSystemProcess();

            mContentResolver = context.getContentResolver();

            Log.i(TAG, "Content Manager");
            ContentService.main(context,
                    factoryTest == SystemServer.FACTORY_TEST_LOW_LEVEL);

            Log.i(TAG, "System Content Providers");
            ActivityManagerService.installSystemProviders();

            Log.i(TAG, "Battery Service");
            battery = new BatteryService(context);
            ServiceManager.addService("battery", battery);

            Log.i(TAG, "Hardware Service");
            hardware = new HardwareService(context);
            ServiceManager.addService("hardware", hardware);

            // only initialize the power service after we have started the
            // hardware service, content providers and the battery service.
            power.init(context, hardware, ActivityManagerService.getDefault(), battery);

            Log.i(TAG, "Alarm Manager");
            AlarmManagerService alarm = new AlarmManagerService(context);
            ServiceManager.addService(Context.ALARM_SERVICE, alarm);

            Log.i(TAG, "Init Watchdog");
            Watchdog.getInstance().init(context, battery, power, alarm,
                    ActivityManagerService.self());

            // Sensor Service is needed by Window Manager, so this goes first
            Log.i(TAG, "Sensor Service");
            ServiceManager.addService(Context.SENSOR_SERVICE, new SensorService(context));

            Log.i(TAG, "Window Manager");
            wm = WindowManagerService.main(context, power,
                    factoryTest != SystemServer.FACTORY_TEST_LOW_LEVEL);
            ServiceManager.addService(Context.WINDOW_SERVICE, wm);

            ((ActivityManagerService)ServiceManager.getService("activity"))
                    .setWindowManager(wm);

            // Skip Bluetooth if we have an emulator kernel
            // TODO: Use a more reliable check to see if this product should
            // support Bluetooth - see bug 988521
            if (SystemProperties.get("ro.kernel.qemu").equals("1")) {
                Log.i(TAG, "Registering null Bluetooth Service (emulator)");
                ServiceManager.addService(Context.BLUETOOTH_SERVICE, null);
            } else if (factoryTest == SystemServer.FACTORY_TEST_LOW_LEVEL) {
                Log.i(TAG, "Registering null Bluetooth Service (factory test)");
                ServiceManager.addService(Context.BLUETOOTH_SERVICE, null);
            } else {
                Log.i(TAG, "Starting Bluetooth Service.");
                bluetooth = new BluetoothDeviceService(context);
                bluetooth.init();
                ServiceManager.addService(Context.BLUETOOTH_SERVICE, bluetooth);
                bluetoothA2dp = new BluetoothA2dpService(context);
                ServiceManager.addService(BluetoothA2dpService.BLUETOOTH_A2DP_SERVICE,
                                          bluetoothA2dp);
                bluetoothFtp = new BluetoothFtpService(context);
                ServiceManager.addService(BluetoothFtpService.BLUETOOTH_FTP_SERVICE,
                						  bluetoothFtp);
                bluetoothOpp = new BluetoothOppService(context);
                ServiceManager.addService(BluetoothOppService.BLUETOOTH_OPP_SERVICE,
                		                  bluetoothOpp);
                
                int bluetoothOn = Settings.Secure.getInt(mContentResolver,
                    Settings.Secure.BLUETOOTH_ON, 0);
                if (bluetoothOn > 0) {
                    bluetooth.enable();
                }
            }


        } catch (RuntimeException e) {
            Log.e("System", "Failure starting core service", e);
        }

        StatusBarService statusBar = null;
        InputMethodManagerService imm = null;
        AppWidgetService appWidget = null;
        NotificationManagerService notification = null;

        if (factoryTest != SystemServer.FACTORY_TEST_LOW_LEVEL) {
            try {
                Log.i(TAG, "Status Bar");
                statusBar = new StatusBarService(context);
                ServiceManager.addService("statusbar", statusBar);
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting StatusBarService", e);
            }

            try {
                Log.i(TAG, "Clipboard Service");
                ServiceManager.addService("clipboard", new ClipboardService(context));
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting Clipboard Service", e);
            }

            try {
                Log.i(TAG, "Input Method Service");
                imm = new InputMethodManagerService(context, statusBar);
                ServiceManager.addService(Context.INPUT_METHOD_SERVICE, imm);
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting Input Manager Service", e);
            }

            try {
                Log.i(TAG, "NetStat Service");
                ServiceManager.addService("netstat", new NetStatService(context));
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting NetStat Service", e);
            }

            try {
                Log.i(TAG, "Connectivity Service");
                connectivity = ConnectivityService.getInstance(context);
                ServiceManager.addService(Context.CONNECTIVITY_SERVICE, connectivity);
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting Connectivity Service", e);
            }

            try {
              Log.i(TAG, "Accessibility Manager");
              ServiceManager.addService(Context.ACCESSIBILITY_SERVICE,
                      new AccessibilityManagerService(context));
            } catch (Throwable e) {
              Log.e(TAG, "Failure starting Accessibility Manager", e);
            }

            try {
                Log.i(TAG, "Notification Manager");
                notification = new NotificationManagerService(context, statusBar, hardware);
                ServiceManager.addService(Context.NOTIFICATION_SERVICE, notification);
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting Notification Manager", e);
            }

            try {
                // MountService must start after NotificationManagerService
                Log.i(TAG, "Mount Service");
                ServiceManager.addService("mount", new MountService(context));
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting Mount Service", e);
            }

            try {
                Log.i(TAG, "Device Storage Monitor");
                ServiceManager.addService(DeviceStorageMonitorService.SERVICE,
                        new DeviceStorageMonitorService(context));
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting DeviceStorageMonitor service", e);
            }

            try {
                Log.i(TAG, "Location Manager");
                ServiceManager.addService(Context.LOCATION_SERVICE, new LocationManagerService(context));
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting Location Manager", e);
            }

            try {
                Log.i(TAG, "Search Service");
                ServiceManager.addService( Context.SEARCH_SERVICE, new SearchManagerService(context) );
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting Search Service", e);
            }

            if (INCLUDE_DEMO) {
                Log.i(TAG, "Installing demo data...");
                (new DemoThread(context)).start();
            }

            try {
                Log.i(TAG, "Checkin Service");
                Intent intent = new Intent().setComponent(new ComponentName(
                        "com.google.android.server.checkin",
                        "com.google.android.server.checkin.CheckinService"));
                if (context.startService(intent) == null) {
                    Log.w(TAG, "Using fallback Checkin Service.");
                    ServiceManager.addService("checkin", new FallbackCheckinService(context));
                }
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting Checkin Service", e);
            }

            try {
                Log.i(TAG, "Starting Wallpaper Service");
                ServiceManager.addService(Context.WALLPAPER_SERVICE, new WallpaperService(context));
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting Wallpaper Service", e);
            }

            try {
                Log.i(TAG, "Audio Service");
                ServiceManager.addService(Context.AUDIO_SERVICE, new AudioService(context));
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting Audio Service", e);
            }

            try {
                Log.i(TAG, "Headset Observer");
                // Listen for wired headset changes
                headset = new HeadsetObserver(context);
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting HeadsetObserver", e);
            }

            /*
            try {
                Log.i(TAG, "Backup Service");
                ServiceManager.addService(Context.BACKUP_SERVICE, new BackupManagerService(context));
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting Backup Service", e);
            }
            */
            
            try {
                Log.i(TAG, "AppWidget Service");
                appWidget = new AppWidgetService(context);
                ServiceManager.addService(Context.APPWIDGET_SERVICE, appWidget);
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting AppWidget Service", e);
            }

            try {
                com.android.server.status.StatusBarPolicy.installIcons(context, statusBar);
            } catch (Throwable e) {
                Log.e(TAG, "Failure installing status bar icons", e);
            }
        }

        // make sure the ADB_ENABLED setting value matches the secure property value
        Settings.Secure.putInt(mContentResolver, Settings.Secure.ADB_ENABLED,
                "1".equals(SystemProperties.get("persist.service.adb.enable")) ? 1 : 0);

        // register observers to listen for settings changes
        mContentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ADB_ENABLED),
                false, new AdbSettingsObserver());

        mContentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.COMPCACHE_ENABLED),
                false, new CompcacheSettingsObserver());
               
        
        // Before things start rolling, be sure we have decided whether
        // we are in safe mode.
        final boolean safeMode = wm.detectSafeMode();
        if (safeMode) {
            try {
                ActivityManagerNative.getDefault().enterSafeMode();
            } catch (RemoteException e) {
            }
        }
        
        // It is now time to start up the app processes...

        if (notification != null) {
            notification.systemReady();
        }

        if (statusBar != null) {
            statusBar.systemReady();
        }
        wm.systemReady();
        power.systemReady();
        try {
            pm.systemReady();
        } catch (RemoteException e) {
        }

        // These are needed to propagate to the runnable below.
        final BatteryService batteryF = battery;
        final ConnectivityService connectivityF = connectivity;
        final AppWidgetService appWidgetF = appWidget;
        final InputMethodManagerService immF = imm;
        
        // We now tell the activity manager it is okay to run third party
        // code.  It will call back into us once it has gotten to the state
        // where third party code can really run (but before it has actually
        // started launching the initial applications), for us to complete our
        // initialization.
        ((ActivityManagerService)ActivityManagerNative.getDefault())
                .systemReady(new Runnable() {
            public void run() {
                Log.i(TAG, "Making services ready");
                
                Watchdog.getInstance().start();

                // It is now okay to let the various system services start their
                // third party code...
                
                if (appWidgetF != null) appWidgetF.systemReady(safeMode);
                if (immF != null) immF.systemReady();
            }
        });
        
        Looper.loop();
        Log.d(TAG, "System ServerThread is exiting!");
    }
}

class DemoThread extends Thread
{
    DemoThread(Context context)
    {
        mContext = context;
    }

    @Override
    public void run()
    {
        try {
            Cursor c = mContext.getContentResolver().query(People.CONTENT_URI, null, null, null, null);
            boolean hasData = c != null && c.moveToFirst();
            if (c != null) {
                c.deactivate();
            }
            if (!hasData) {
                DemoDataSet dataset = new DemoDataSet();
                dataset.add(mContext);
            }
        } catch (Throwable e) {
            Log.e("SystemServer", "Failure installing demo data", e);
        }

    }

    Context mContext;
}

public class SystemServer
{
    private static final String TAG = "SystemServer";

    public static final int FACTORY_TEST_OFF = 0;
    public static final int FACTORY_TEST_LOW_LEVEL = 1;
    public static final int FACTORY_TEST_HIGH_LEVEL = 2;

    static Timer timer;
    static final long SNAPSHOT_INTERVAL = 60 * 60 * 1000; // 1hr

    /**
     * This method is called from Zygote to initialize the system. This will cause the native
     * services (SurfaceFlinger, AudioFlinger, etc..) to be started. After that it will call back
     * up into init2() to start the Android services.
     */
    native public static void init1(String[] args);

    public static void main(String[] args) {

        // The system server has to run all of the time, so it needs to be
        // as efficient as possible with its memory usage.
        VMRuntime.getRuntime().setTargetHeapUtilization(0.8f);

        System.loadLibrary("android_servers");
        init1(args);
    }

    public static final void init2() {
        Log.i(TAG, "Entered the Android system server!");
        Thread thr = new ServerThread();
        thr.setName("android.server.ServerThread");
        thr.start();
    }
}
