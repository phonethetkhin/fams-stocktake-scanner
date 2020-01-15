package com.example.oemscandemo.ui;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.Toast;

import com.example.oemscandemo.R;
import com.example.oemscandemo.adapter.RemainDataAdapter;
import com.example.oemscandemo.db.DBHelper;
import com.example.oemscandemo.model.AssetBean;
import com.example.oemscandemo.scan.OemScanService;
import com.example.oemscandemo.scan.ScanListener;
import com.example.oemscandemo.utils.Event;
import com.hsm.barcode.DecoderException;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class RemainActivity extends AppCompatActivity implements ScanListener {

    List<AssetBean> assetList = new ArrayList<>();

    DBHelper helper;
    RemainDataAdapter adapter;
    RecyclerView recyclerView;

    private SharedPreferences prefs;
    private String prefName = "user";

    private static final String TAG = "OemScanDemo";

    private static final int SCAN_KEY = 0;        // Scan key default

    // Decode timeout 10 seconds
    private static int g_ScanKey = 0;                        // Scan Key value // FIXME: make -1?

    Button m_ScanButton;
    private Intent service;

    private static boolean g_bContinuousScanEnabled = false;        // Continuous scan option
    private static boolean g_bContinuousScanStarted = false;  // Continuous scan started (TODO: test me)

    private static int g_nTotalDecodeTime = 0;    // Used to capture total decode time (for averaging)
    private static int g_nNumberOfDecodes = 0;    // Used to capture total decodes

    public static RemainActivity instance = null;
    private int g_nMultiReadResultCount = 0;        // For tracking # of multiread results

    protected boolean bind = false;
    protected OemScanService scanService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remain);
        helper = new DBHelper(this);

        prefs = getSharedPreferences(prefName, MODE_PRIVATE);
        int locationId = prefs.getInt("locationId", 0);

        if (locationId == -1) {

            assetList = helper.getRemain();

            Collections.sort(assetList, (fa1, fa2) -> {
                if (fa1.getFaNumber() != null && fa2.getFaNumber() != null) {
                    return fa1.getFaNumber().compareTo(fa2.getFaNumber());
                }
                return 0;
            });

            recyclerView = findViewById(R.id.recycler_remain_list);
            adapter = new RemainDataAdapter(getApplicationContext(), assetList);
            recyclerView.setAdapter(adapter);
            recyclerView.setLayoutManager(new LinearLayoutManager(getApplication()));

        } else {

            assetList = helper.getRemainByLocation(locationId);
            if (assetList.size() != 0) {

                Collections.sort(assetList, (fa1, fa2) -> {
                    if (fa1.getFaNumber() != null && fa2.getFaNumber() != null) {
                        return fa1.getFaNumber().compareTo(fa2.getFaNumber());
                    }
                    return 0;
                });

                recyclerView = findViewById(R.id.recycler_remain_list);
                adapter = new RemainDataAdapter(getApplicationContext(), assetList);
                recyclerView.setAdapter(adapter);
                recyclerView.setLayoutManager(new LinearLayoutManager(getApplication()));
                adapter.notifyDataSetChanged();

            } else if (assetList.size() == 0) {

                List<AssetBean> assetEmptyList = new ArrayList<>();
                recyclerView = findViewById(R.id.recycler_remain_list);
                adapter = new RemainDataAdapter(getApplicationContext(), assetEmptyList);
                recyclerView.setAdapter(adapter);
                recyclerView.setLayoutManager(new LinearLayoutManager(getApplication()));
                adapter.notifyDataSetChanged();

            }
        }

        // Create instance
        instance = this;

        // Scan key
        g_ScanKey = SCAN_KEY;
        Log.d(TAG, "g_ScanKey=" + g_ScanKey);
        if (g_ScanKey == -1) // not initialized
            Log.d(TAG, "Please define SCAN_KEY");

        //scanService.m_decResult = new DecodeResult();
        service = new Intent(this, OemScanService.class);
        startService(service);
        bindService(service, serviceConnection, BIND_AUTO_CREATE);

        m_ScanButton = findViewById(R.id.scanButton);
        m_ScanButton.setOnClickListener(v -> scanService.onClickScan(v));

        EventBus.getDefault().register(this);
    }

    ServiceConnection serviceConnection = new ServiceConnection() {

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bind = false;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            bind = true;
            OemScanService.MyBinder myBinder = (OemScanService.MyBinder) service;
            scanService = myBinder.getService();
            scanService.setOnScanListener(RemainActivity.this);
        }
    };

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d(TAG, "onKeyDown" + "keyCode=" + keyCode + "(g_ScanKey=" + g_ScanKey + ")");

        if (keyCode == g_ScanKey) {
            if (g_bContinuousScanEnabled) {
                // Start scanning only if it has not started (or was stopped)
                if (!g_bContinuousScanStarted) {
                    Toast.makeText(getApplicationContext(), "Press scan key to stop continous scanning.", Toast.LENGTH_LONG).show();

                    g_bContinuousScanStarted = true;
                    processScanButtonPress();
                } else {
                    g_bContinuousScanStarted = false;
                    StopScanning();
                }
            } else {
                // Process normally...
                processScanButtonPress();
            }
        } else {
            return super.onKeyDown(keyCode, event);
        }
        return false;
    }

    /**
     * Called when key is up
     */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {

        Log.d(TAG, "onKeyUp" + "keyCode=" + keyCode + "(g_ScanKey=" + g_ScanKey + ")");

        if (keyCode == g_ScanKey) {
            if (!g_bContinuousScanEnabled)
                StopScanning();
        } else {
            return super.onKeyUp(keyCode, event);
        }

        return false;
    }

    /**
     * Called when screen tap (TODO: future enhancements)
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int eventaction = event.getAction();
        int pointerCount = event.getPointerCount();
        if (pointerCount == 2) // TODO: detect a swipe to bring up setting menu?
        {
            switch (eventaction) {
                case MotionEvent.ACTION_DOWN:
                    // finger touches the screen
                    Log.d(TAG, "finger touched screen");
                    break;

                case MotionEvent.ACTION_MOVE:
                    // finger moves on the screen
                    //Log. d(TAG, "finger moves on screen");
                    break;

                case MotionEvent.ACTION_UP:
                    // finger leaves the screen
                    Log.d(TAG, "finger leaves screen");
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    // finger leaves the screen
                    Log.d(TAG, "pointer down action");
                    break;
            }
        }
        return true;
    }

    /**
     * Called when application gets focus
     */
    @Override
    public void onResume() {
        super.onResume();  // Always call the superclass method first
        Log.d(TAG, "onResume");
    }

    /**
     * Called when application loses focus
     */
    @Override
    public void onPause() {
        super.onPause();  // Always call the superclass method first
        Log.d(TAG, "onPause");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

//        if (null != f4Receiver) {
//            this.unregisterReceiver(f4Receiver);
//        }

        unbindService(serviceConnection);
    }

    /**
     * Displays results when reading mulitple barcodes
     */
    public void DisplayMultireadResults() {
        runOnUiThread(() -> {

            try {
                if (g_nMultiReadResultCount == 0)

                    g_nMultiReadResultCount++;
                // pull the data manually:
                if (scanService.m_Decoder.getBarcodeLength() > 0) {

                    Log.d(TAG, "Additional Multiread Results");
                    Log.d(TAG, "  AimID:" + scanService.m_Decoder.getBarcodeAimID());
                    Log.d(TAG, "  AimModifier:" + scanService.m_Decoder.getBarcodeAimModifier());
                    Log.d(TAG, "  CodeID:" + scanService.m_Decoder.getBarcodeCodeID());
                    Log.d(TAG, "  Length:" + scanService.m_Decoder.getBarcodeLength());
                }
            } catch (DecoderException e) {
                HandleDecoderException(e);
            }

        });
    }

    /**
     * Displays the decoded results (note: called from thread)
     */
    @Override
    public void DisplayDecodeResults() {
        runOnUiThread(() -> {
            if (scanService.m_decResult.length > 0) {
                try {

                    if (scanService.m_decResult.barcodeData.length() != 0) {

                        final String data = scanService.m_decResult.barcodeData;

                        for (int i = 0; i < assetList.size(); i++) {

                            AssetBean asset = assetList.get(i);

                            if (data.equals(asset.getFaNumber())) {
                                asset.setScannedStatus(1);
                                Calendar c = Calendar.getInstance();
                                final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss a", Locale.getDefault());
                                String scanTime = dateFormat.format(c.getTime());
                                asset.setStockTakeTime(scanTime);
                                asset.setTaken(1);
                                asset.setScannedStatus(1);
                                helper.updateAsset(asset);
                                helper.close();

                                EventBus.getDefault().post(Event.Request_Data);

                                Toast.makeText(getApplicationContext(), "Scan Result:" + data, Toast.LENGTH_SHORT).show();
                            }
                        }
                        prefs = getSharedPreferences(prefName, MODE_PRIVATE);
                        final int locationId = prefs.getInt("locationId", 0);
                        if (!helper.checkAssetNumberExists(data) && locationId != -1) {
                            String stockValue = "AST";
                            if (data.matches("^(" + stockValue + "-[0-9]{4}-[0-9]{2}-[0-9]{6})$")) {
                                AlertDialog.Builder builder;
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                    builder = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
                                } else {
                                    builder = new AlertDialog.Builder(this);
                                }
                                builder.setTitle("Scan Result")
                                        .setMessage("Scanned result is - " + data)
                                        .setCancelable(false)
                                        .setPositiveButton("Ok", (dialog, which) -> {
                                            Intent intent = new Intent(getApplicationContext(), UnknownRemarkActivity.class);
                                            intent.putExtra("locationId", locationId);
                                            intent.putExtra("faNo", data);
                                            startActivity(intent);
                                            finish();
                                        })
                                        .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
                                builder.show();
                            }
                        }

                    } else {
                        String m_strDecodedData = "";
                        scanService.m_decResult.byteBarcodeData = scanService.m_Decoder.getBarcodeByteData();

                        int BytesInLabel = 0;
                        do {
                            m_strDecodedData += String.format("%02x", scanService.m_decResult.byteBarcodeData[BytesInLabel] & 0xff);
                            BytesInLabel++;
                        } while (BytesInLabel < scanService.m_decResult.byteBarcodeData.length);
                    }

                    Log.d(TAG, "TTR " + scanService.decodeTime + " ms [" + scanService.m_Decoder.getLastDecodeTime() + "ms]");
                } catch (DecoderException e) {
                    HandleDecoderException(e);
                }

                if (g_bContinuousScanEnabled) {
                    g_nNumberOfDecodes++;
                    g_nTotalDecodeTime += scanService.decodeTime;
                }

            }
        });
    }

    /**
     * Processes the scan button press
     */
    void processScanButtonPress() {
        StartScanning();
    }

    /**
     * Starts scanning - enables flag to start scanning (decoding)
     */
    void StartScanning() {
        scanService.StartScanning();
    }

    /**
     * Stops scanning - disables flag to stop scanning / cancel decode (decoding)
     */
    void StopScanning() {
        scanService.StopScanning();
    }

    /**
     * Handles the DecoderException by displaying error in log and printing the stack trace
     */
    public void HandleDecoderException(final DecoderException e) {
        runOnUiThread(() -> {
            Log.d(TAG, "HandleDecoderException++");

            if (true) {
                //	Toast.makeText(getApplicationContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                e.printStackTrace();
            } else // more user friendly?
            {
                switch (e.getErrorCode()) {

                    case DecoderException.ResultID.RESULT_ERR_NOTCONNECTED:
                        Toast.makeText(getApplicationContext(), "Error: Engine not connected to perform this operation (" + e.getErrorCode() + ")", Toast.LENGTH_LONG).show();
                        break;
                    case DecoderException.ResultID.RESULT_ERR_NOIMAGE:
                        Toast.makeText(getApplicationContext(), "Error: No image (" + e.getErrorCode() + ")", Toast.LENGTH_LONG).show();
                        break;
                    default:
                        Toast.makeText(getApplicationContext(), "Unknown Error (" + e.getErrorCode() + ")", Toast.LENGTH_LONG).show();
                }
            }

            Log.d(TAG, "HandleDecoderException--");
        });
    }

    @Override
    public void result(String content) {
        // TODO Auto-generated method stub

    }

    @Override
    public void henResult(String codeType, String context) {
        // TODO Auto-generated method stub

    }

    @Subscribe
    public void onEvent(Event event) {
        if (event.equals(Event.Request_Data)) {
            prefs = getSharedPreferences(prefName, MODE_PRIVATE);
            int locationId = prefs.getInt("locationId", 0);

            if (locationId == -1) {

                assetList = helper.getRemain();

                Collections.sort(assetList, (fa1, fa2) -> {
                    if (fa1.getFaNumber() != null && fa2.getFaNumber() != null) {
                        return fa1.getFaNumber().compareTo(fa2.getFaNumber());
                    }
                    return 0;
                });

                recyclerView = findViewById(R.id.recycler_remain_list);
                adapter = new RemainDataAdapter(getApplicationContext(), assetList);
                recyclerView.setAdapter(adapter);
                recyclerView.setLayoutManager(new LinearLayoutManager(getApplication()));
                adapter.notifyDataSetChanged();

            } else {

                assetList = helper.getRemainByLocation(locationId);

                if (assetList.size() != 0) {

                    Collections.sort(assetList, (fa1, fa2) -> {
                        if (fa1.getFaNumber() != null && fa2.getFaNumber() != null) {
                            return fa1.getFaNumber().compareTo(fa2.getFaNumber());
                        }
                        return 0;
                    });

                    recyclerView = findViewById(R.id.recycler_remain_list);
                    adapter = new RemainDataAdapter(getApplicationContext(), assetList);
                    recyclerView.setAdapter(adapter);
                    recyclerView.setLayoutManager(new LinearLayoutManager(getApplication()));
                    adapter.notifyDataSetChanged();

                } else if (assetList.size() == 0) {

                    List<AssetBean> assetEmptyList = new ArrayList<>();
                    recyclerView = findViewById(R.id.recycler_remain_list);
                    adapter = new RemainDataAdapter(getApplicationContext(), assetEmptyList);
                    recyclerView.setAdapter(adapter);
                    recyclerView.setLayoutManager(new LinearLayoutManager(getApplication()));
                    adapter.notifyDataSetChanged();

                }
            }
        }
    }

    @Override
    public void onBackPressed() {
        this.getParent().onBackPressed();
        finish();
    }
}
