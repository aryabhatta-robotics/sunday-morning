package com.sundaymorning.slideshowremotecontrol.activity;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.databinding.DataBindingUtil;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.gms.common.AccountPicker;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.gdata.client.photos.PicasawebService;
import com.google.gdata.data.photos.AlbumEntry;
import com.google.gdata.data.photos.GphotoEntry;
import com.google.gdata.data.photos.GphotoFeed;
import com.google.gdata.data.photos.UserFeed;
import com.google.gdata.util.ServiceException;
import com.google.gdata.util.ServiceForbiddenException;
import com.sundaymorning.slideshowremotecontrol.R;
import com.sundaymorning.slideshowremotecontrol.databinding.ActivityMainBinding;
import com.sundaymorning.slideshowremotecontrol.model.GooglePhotosInfo;
import com.sundaymorning.slideshowremotecontrol.service.BluetoothChatService;
import com.sundaymorning.slideshowremotecontrol.util.Constants;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    ActivityMainBinding mActivityMainBinding;
    private static final String TAG = "SlideshowRemoteControl";
    private static final int PERMISSIONS_REQUEST_CODE = 1;
    private static final int PERMISSIONS_REQUEST_CODE_ACCESS_COARSE_LOCATION = 2;
    private static final int REQUEST_CONNECT_DEVICE_SECURE = 3;
    private static final int REQUEST_CONNECT_DEVICE_INSECURE = 4;
    private static final int REQUEST_ENABLE_BT = 5;
    private static final int REQUEST_PICK_ACCOUNT = 6;
    private static final int REQUEST_AUTHENTICATE = 7;
    private static final String API_PREFIX = "https://picasaweb.google.com/data/feed/api/user/";

    private BluetoothAdapter mBluetoothAdapter = null;
    private BluetoothChatService mChatService = null;

    private WifiManager mWifiManager;
    private WifiReceiver mWifiReceiver;

    private FirebaseAuth mFirebaseAuth;
    private FirebaseDatabase mFirebaseDatabase;
    private String mirrorAppUID;
    private String controlAppUID;

    private PicasawebService picasawebService;
    private int albumIndex = -1;

    private Boolean slideshowEnabled = true;

    private AccountManager accountManager;
    private Account[] accounts;
    private String selectedAccountName;
    private Account selectedAccount;

    private boolean isConnected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mActivityMainBinding = DataBindingUtil.setContentView(this, R.layout.activity_main);
        setSupportActionBar(mActivityMainBinding.toolbar);

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        mActivityMainBinding.btnConnectBluetooth.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (isConnected) {
                    if (mChatService != null) {
                        mChatService.stop();
                    }
                    mActivityMainBinding.btnConnectBluetooth.setText("Connect via Bluetooth");
                    isConnected = false;
                } else {
                    if (mBluetoothAdapter == null) {
                        Log.d(TAG, "Bluetooth is not available");
                        Toast.makeText(MainActivity.this, "Bluetooth is not available", Toast.LENGTH_LONG).show();
                    } else {
                        if (!mBluetoothAdapter.isEnabled()) {
                            Intent intentEnableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                            intentEnableBluetooth.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivityForResult(intentEnableBluetooth, 0);
                        } else {
                            Intent serverIntent = new Intent(MainActivity.this, DeviceListActivity.class);
                            startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE_INSECURE);
                        }
                    }
                }
            }
        });

        mActivityMainBinding.btnSetWifiSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mWifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (!mWifiManager.isWifiEnabled()) {
                    mWifiManager.setWifiEnabled(true);
                }
                mWifiReceiver = new WifiReceiver();
                registerReceiver(mWifiReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
                mWifiManager.startScan();
            }
        });

        mFirebaseAuth = FirebaseAuth.getInstance();
        if (mFirebaseAuth.getCurrentUser() == null) {
            mFirebaseAuth.signInAnonymously().addOnCompleteListener(this, new OnCompleteListener<AuthResult>() {
                @Override
                public void onComplete(@NonNull Task<AuthResult> task) {
                    if (task.isSuccessful()) {
                        // Sign in success
                        Log.d(TAG, "signInAnonymously:success");
                        if (mFirebaseAuth.getCurrentUser() != null) {
                            controlAppUID = mFirebaseAuth.getCurrentUser().getUid();
                        }
                    } else {
                        // If sign in fails
                        Log.w(TAG, "signInAnonymously:failure", task.getException());
                        Toast.makeText(MainActivity.this, "Authentication failed.", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } else {
            controlAppUID = mFirebaseAuth.getCurrentUser().getUid();
        }

        mFirebaseDatabase = FirebaseDatabase.getInstance();
        final DatabaseReference controlsRef = mFirebaseDatabase.getReference("controls");
        controlsRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                // This method is called once with the initial value and again
                // whenever data at this location is updated.
                if (!TextUtils.isEmpty(controlAppUID)) {
                    mirrorAppUID = dataSnapshot.child(controlAppUID).child("mirror_app_uid").getValue(String.class);
                    Log.d(TAG, "Value is: " + mirrorAppUID);
                    if (!TextUtils.isEmpty(mirrorAppUID)) {
                        mActivityMainBinding.btnChooseAlbum.setVisibility(View.VISIBLE);
                        mActivityMainBinding.btnEnabledSlideshow.setVisibility(View.VISIBLE);
                    } else {
                        mActivityMainBinding.btnChooseAlbum.setVisibility(View.GONE);
                        mActivityMainBinding.btnEnabledSlideshow.setVisibility(View.GONE);
                    }
                    slideshowEnabled = dataSnapshot.child("slideshow_enabled").getValue(Boolean.class);
                    if (slideshowEnabled == null) {
                        slideshowEnabled = true;
                    }
                    if (slideshowEnabled) {
                        mActivityMainBinding.btnEnabledSlideshow.setText("Disable slideshow");
                    } else {
                        mActivityMainBinding.btnEnabledSlideshow.setText("Enable slideshow");
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                // Failed to read value
                Log.w(TAG, "Failed to read value.", error.toException());
            }
        });

        mActivityMainBinding.btnChooseAlbum.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                accountManager = (AccountManager) getSystemService(ACCOUNT_SERVICE);
                accounts = accountManager.getAccounts();
                Log.d(TAG, "Got " + accounts.length + " accounts");
                for (Account a: accounts) {
                    Log.d(TAG, a.name + " " + a.type);
                }

                Intent intent = AccountPicker.newChooseAccountIntent(
                        null,
                        null,
                        new String[]{"com.google"},
                        false,
                        null,
                        null,
                        null,
                        null);
                startActivityForResult(intent, REQUEST_PICK_ACCOUNT);
            }
        });

        mActivityMainBinding.btnEnabledSlideshow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (slideshowEnabled) {
                    DatabaseReference mirrorsRef = mFirebaseDatabase.getReference("mirrors");
                    if (!TextUtils.isEmpty(mirrorAppUID)) {
                        mirrorsRef.child(mirrorAppUID).child("slideshow_enabled").setValue(false);
                    }
                    mActivityMainBinding.btnEnabledSlideshow.setText("Enable slideshow");
                    slideshowEnabled = false;
                } else {
                    DatabaseReference mirrorsRef = mFirebaseDatabase.getReference("mirrors");
                    if (!TextUtils.isEmpty(mirrorAppUID)) {
                        mirrorsRef.child(mirrorAppUID).child("slideshow_enabled").setValue(true);
                    }
                    mActivityMainBinding.btnEnabledSlideshow.setText("Disable slideshow");
                    slideshowEnabled = true;
                }
            }
        });

        String[] PERMISSIONS = {Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.GET_ACCOUNTS};
        if (!hasPermissions(this, PERMISSIONS)){
            ActivityCompat.requestPermissions(this, PERMISSIONS, PERMISSIONS_REQUEST_CODE);
        }
    }

    public <T extends GphotoFeed> T getFeed(String feedHref, Class<T> feedClass) throws IOException, ServiceException {
        Log.d(TAG, "Get Feed URL: " + feedHref);
        return picasawebService.getFeed(new URL(feedHref), feedClass);
    }

    public List<AlbumEntry> getAlbums(String userId) throws IOException, ServiceException {
        String albumUrl = API_PREFIX + userId;
        UserFeed userFeed = getFeed(albumUrl, UserFeed.class);
        List<GphotoEntry> entries = userFeed.getEntries();
        List<AlbumEntry> albums = new ArrayList<>();
        for (GphotoEntry entry : entries) {
            AlbumEntry ae = new AlbumEntry(entry);
            Log.d(TAG, "Album name " + ae.getName());
            Log.d(TAG, "Album title " + ae.getTitle().getPlainText());
            albums.add(ae);
        }
        return albums;
    }

    class WifiReceiver extends BroadcastReceiver {
        public void onReceive(Context c, Intent intent) {
            List<ScanResult> wifiList;
            wifiList = mWifiManager.getScanResults();
            final CharSequence[] connections = new CharSequence[wifiList.size()];
            for (int i = 0; i < wifiList.size(); i++) {
                connections[i] = wifiList.get(i).SSID;
            }

            AlertDialog.Builder alertDialogSSID = new AlertDialog.Builder(MainActivity.this);
            alertDialogSSID.setTitle("Make WiFi Network");
            alertDialogSSID.setItems(connections, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, final int item) {
                    AlertDialog.Builder alertDialogPassword = new AlertDialog.Builder(MainActivity.this);
                    alertDialogPassword.setTitle("Enter password from \"" + connections[item] + "\" WiFi");

                    final EditText input = new EditText(MainActivity.this);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.MATCH_PARENT);
                    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    input.setLayoutParams(lp);
                    alertDialogPassword.setView(input);

                    alertDialogPassword.setPositiveButton("OK",
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    String password = input.getText().toString();
                                    if (!password.isEmpty()) {
                                        String message = "WiFi: " + connections[item] + " " + password;
                                        sendMessage(message);
                                    }
                                }
                            });

                    alertDialogPassword.setNegativeButton("Cancel", null);

                    alertDialogPassword.show();
                }

            });
            alertDialogSSID.show();

            unregisterReceiver(mWifiReceiver);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case PERMISSIONS_REQUEST_CODE: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                } else {

                }
                return;
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        // If BT is not on, request that it be enabled.
        // setupChat() will then be called during onActivityResult
        if (mBluetoothAdapter != null && !mBluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
            // Otherwise, setup the chat session
        } else if (mChatService == null) {
            setupChat();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mChatService != null) {
            mChatService.stop();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        // Performing this check in onResume() covers the case in which BT was
        // not enabled during onStart(), so we were paused to enable it...
        // onResume() will be called when ACTION_REQUEST_ENABLE activity returns.
        if (mChatService != null) {
            // Only if the state is STATE_NONE, do we know that we haven't started already
            if (mChatService.getState() == BluetoothChatService.STATE_NONE) {
                // Start the Bluetooth chat services
                mChatService.start();
            }
        }
    }

    public static boolean hasPermissions(Context context, String... permissions) {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context != null && permissions != null) {
            for (String permission : permissions) {
                if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
        }
        return true;
    }

    private void setupChat() {
        Log.d(TAG, "setupChat()");
        // Initialize the BluetoothChatService to perform bluetooth connections
        mChatService = new BluetoothChatService(this, mHandler);
    }

    /**
     * Sends a message.
     *
     * @param message A string of text to send.
     */
    private void sendMessage(String message) {
        // Check that we're actually connected before trying anything
        if (mChatService != null) {
            if (mChatService.getState() != BluetoothChatService.STATE_CONNECTED) {
                Toast.makeText(this, "You are not connected to a device", Toast.LENGTH_SHORT).show();
                return;
            }

            // Check that there's actually something to send
            if (message.length() > 0) {
                // Get the message bytes and tell the BluetoothChatService to write
                byte[] send = message.getBytes();
                mChatService.write(send);
            }
        }
    }

    /**
     * The Handler that gets information back from the BluetoothChatService
     */
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case Constants.MESSAGE_STATE_CHANGE:
                    switch (msg.arg1) {
                        case BluetoothChatService.STATE_CONNECTED:
                            isConnected = true;
                            mActivityMainBinding.btnConnectBluetooth.setText("Disconnect");
                            mActivityMainBinding.btnSetWifiSettings.setVisibility(View.VISIBLE);
                            if (!TextUtils.isEmpty(controlAppUID)) {
                                String message = "ControlUID: " + controlAppUID;
                                MainActivity.this.sendMessage(message);
                            }
                            break;
                        case BluetoothChatService.STATE_CONNECTING:
                            break;
                        case BluetoothChatService.STATE_LISTEN:
                            break;
                        case BluetoothChatService.STATE_NONE:
                            isConnected = false;
                            mActivityMainBinding.btnConnectBluetooth.setText("Connect via Bluetooth");
                            mActivityMainBinding.btnSetWifiSettings.setVisibility(View.GONE);
                            break;
                    }
                    break;
                case Constants.MESSAGE_WRITE:
                    byte[] writeBuf = (byte[]) msg.obj;
                    // construct a string from the buffer
                    String writeMessage = new String(writeBuf);
                    break;
                case Constants.MESSAGE_READ:
                    byte[] readBuf = (byte[]) msg.obj;
                    // construct a string from the valid bytes in the buffer
                    String readMessage = new String(readBuf, 0, msg.arg1);
                    if (readMessage.equals("WiFi connected")) {
                        Toast.makeText(MainActivity.this, "WiFi on mirror connected", Toast.LENGTH_SHORT).show();
                        mActivityMainBinding.btnChooseAlbum.setVisibility(View.VISIBLE);
                        mActivityMainBinding.btnEnabledSlideshow.setVisibility(View.VISIBLE);
                    } else if (readMessage.startsWith("MirrorUID: ")) {
                        mirrorAppUID = readMessage.substring(11, 39);
                    }
                    if (readMessage.contains("isOnline")) {
                        mActivityMainBinding.btnChooseAlbum.setVisibility(View.VISIBLE);
                        mActivityMainBinding.btnEnabledSlideshow.setVisibility(View.VISIBLE);
                    }
                    break;
                case Constants.MESSAGE_DEVICE_NAME:
                    // save the connected device's name
                    String mConnectedDeviceName = msg.getData().getString(Constants.DEVICE_NAME);
                    Toast.makeText(MainActivity.this, "Connected to " + mConnectedDeviceName, Toast.LENGTH_SHORT).show();
                    isConnected = true;
                    mActivityMainBinding.btnConnectBluetooth.setText("Disconnect");
                    mActivityMainBinding.btnSetWifiSettings.setVisibility(View.VISIBLE);
                    break;
                case Constants.MESSAGE_TOAST:
                    Toast.makeText(MainActivity.this, msg.getData().getString(Constants.TOAST), Toast.LENGTH_SHORT).show();
                    break;
            }
        }
    };

    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        switch(requestCode) {
            case REQUEST_CONNECT_DEVICE_SECURE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    connectDevice(data, true);
                }
                break;
            case REQUEST_CONNECT_DEVICE_INSECURE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    connectDevice(data, false);
                }
                break;
            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                if (resultCode == Activity.RESULT_OK) {
                    // Bluetooth is now enabled, so set up a chat session
                    setupChat();
                } else {
                    // User did not enable Bluetooth or an error occurred
                    Log.d(TAG, "BT not enabled");
                    Toast.makeText(this, "Bluetooth was not enabled", Toast.LENGTH_SHORT).show();
                }
                break;
            case REQUEST_PICK_ACCOUNT:
                if (resultCode == RESULT_OK) {
                    String accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
                    Log.d(TAG, "Selected Account " + accountName);
                    selectedAccount =  null;
                    for (Account a: accounts) {
                        if (a.name.equals(accountName)) {
                            selectedAccount = a;
                            break;
                        }
                    }
                    selectedAccountName = accountName;

                    accountManager.getAuthToken(
                            selectedAccount,        // Account retrieved using getAccountsByType()
                            "lh2",                  // Auth scope may be http://picasaweb.google.com/data/
                            null,                   // Authenticator-specific options
                            this,                   // Your activity
                            new OnTokenAcquired(),  // Callback called when a token is successfully acquired
                            null);                  // Callback called if an error occ
                }
                break;
            case REQUEST_AUTHENTICATE:
                if (resultCode == RESULT_OK) {
                    accountManager.getAuthToken(
                            selectedAccount,        // Account retrieved using getAccountsByType()
                            "lh2",                  // Auth scope may be http://picasaweb.google.com/data/
                            null,                   // Authenticator-specific options
                            this,                   // Your activity
                            new OnTokenAcquired(),  // Callback called when a token is successfully acquired
                            null);                  // Callback called if an error occ
                }
                break;
        }
    }

    private class OnTokenAcquired implements AccountManagerCallback<Bundle> {
        @Override
        public void run(AccountManagerFuture<Bundle> result) {
            try {
                Bundle b = result.getResult();

                if (b.containsKey(AccountManager.KEY_INTENT)) {
                    Intent intent = b.getParcelable(AccountManager.KEY_INTENT);
                    int flags = intent.getFlags();
                    flags &= ~Intent.FLAG_ACTIVITY_NEW_TASK;
                    intent.setFlags(flags);
                    startActivityForResult(intent, REQUEST_AUTHENTICATE);
                    return;
                }

                if (b.containsKey(AccountManager.KEY_AUTHTOKEN)) {
                    final String authToken = b.getString(AccountManager.KEY_AUTHTOKEN);
                    Log.d(TAG, "Auth token " + authToken);
                    picasawebService = new PicasawebService("slideshow");
                    picasawebService.setUserToken(authToken);

                    new AsyncTask<Void, Void, List<AlbumEntry>>() {
                        @Override
                        protected List<AlbumEntry> doInBackground(Void... voids) {
                            List<AlbumEntry> albums;
                            try {
                                albums = getAlbums(selectedAccountName);
                                Log.d(TAG, "Got " + albums.size() + " albums");
                                return albums;
                            } catch (ServiceForbiddenException e) {
                                Log.e(TAG, "Token expired, invalidating");
                                accountManager.invalidateAuthToken("com.google", authToken);
                            } catch (IOException e) {
                                e.printStackTrace();
                            } catch (ServiceException e) {
                                e.printStackTrace();
                            }
                            return null;
                        }
                        protected void onPostExecute(List<AlbumEntry> result) {
                            AlertDialog.Builder adb = new AlertDialog.Builder(MainActivity.this);
                            CharSequence[] items = new CharSequence[result.size()];
                            for (int i = 0; i < result.size(); i++) {
                                items[i] = result.get(i).getTitle().getPlainText();
                            }
                            adb.setSingleChoiceItems(items, 0, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {
                                    albumIndex = i;
                                }
                            });
                            adb.setNegativeButton("Cancel", null);
                            adb.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {
                                    DatabaseReference mirrorsRef = mFirebaseDatabase.getReference("mirrors");
                                    if (!TextUtils.isEmpty(mirrorAppUID)) {
                                        mirrorsRef.child(mirrorAppUID).child("google_photos").setValue(new GooglePhotosInfo(true, authToken, selectedAccountName, albumIndex));
                                    }
                                }
                            });
                            adb.setTitle("Choose album");
                            adb.show();
                        }
                    }.execute(null, null, null);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Establish connection with other device
     *
     * @param data   An {@link Intent} with {@link DeviceListActivity#EXTRA_DEVICE_ADDRESS} extra.
     * @param secure Socket Security type - Secure (true) , Insecure (false)
     */
    private void connectDevice(Intent data, boolean secure) {
        // Get the device MAC address
        String address = data.getExtras()
                .getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
        // Get the BluetoothDevice object
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        // Attempt to connect to the device
        mChatService.connect(device, secure);
    }
}