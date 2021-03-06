package com.sundaymorning.activity;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.databinding.DataBindingUtil;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageSwitcher;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.widget.ViewSwitcher;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;
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
import com.google.gdata.data.photos.AlbumFeed;
import com.google.gdata.data.photos.GphotoEntry;
import com.google.gdata.data.photos.GphotoFeed;
import com.google.gdata.data.photos.PhotoEntry;
import com.google.gdata.data.photos.UserFeed;
import com.google.gdata.util.ServiceException;
import com.google.gdata.util.ServiceForbiddenException;
import com.sundaymorning.R;
import com.sundaymorning.databinding.ActivityMainBinding;
import com.sundaymorning.model.GooglePhotosInfo;
import com.sundaymorning.service.BluetoothChatService;
import com.sundaymorning.util.Constants;
import com.sundaymorning.util.NetworkUtil;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {

    ActivityMainBinding mActivityMainBinding;
    private static final String TAG = "SlideshowRemoteControl";
    private static final int PERMISSIONS_REQUEST_CODE = 1;
    private static final String API_PREFIX = "https://picasaweb.google.com/data/feed/api/user/";

    private List<ScanResult> wifiScanResultList;
    private WifiManager mWifiManager;
    private NetworkChangeReceiver mNetworkChangeReceiver;

    private PicasawebService picasawebService;

    private GooglePhotosInfo mGooglePhotosInfo;
    private List<PhotoEntry> googlePhotos = new ArrayList<>();
    private int googlePhotosImageIndex = -1;
    private int googlePhotosImagesCount = 0;
    private Drawable currentDrawable = null;

    private Cursor mCursor;
    private int columnIndex;
    private int localImageIndex = -1;
    private int localImagesCount = 0;

    private Boolean slideshowEnabled = true;

    private BluetoothAdapter mBluetoothAdapter = null;
    private BluetoothChatService mChatService = null;
    private static final int REQUEST_ENABLE_BT = 5;

    private Handler clockHandler;
    private Runnable clockRunnable;

    private FirebaseAuth mFirebaseAuth;
    private FirebaseDatabase mFirebaseDatabase;
    private ValueEventListener mValueEventListener;
    private String mirrorAppUID;
    private String controlAppUID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mActivityMainBinding = DataBindingUtil.setContentView(this, R.layout.activity_main);

        mActivityMainBinding.imageSwitcherSlideshow.setFactory(new ViewSwitcher.ViewFactory() {
            @Override
            public View makeView() {
                ImageView imageView = new ImageView(MainActivity.this);
                imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                imageView.setLayoutParams(new
                        ImageSwitcher.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
                return imageView;
            }
        });

        Animation slideInRightAnimation = AnimationUtils.loadAnimation(this, R.anim.slide_in_right);
        slideInRightAnimation.setInterpolator(this, android.R.interpolator.linear);
        Animation slideOutLeftAnimation = AnimationUtils.loadAnimation(this, R.anim.slide_out_left);
        slideOutLeftAnimation.setInterpolator(this, android.R.interpolator.linear);
        mActivityMainBinding.imageSwitcherSlideshow.setInAnimation(slideInRightAnimation);
        mActivityMainBinding.imageSwitcherSlideshow.setOutAnimation(slideOutLeftAnimation);

        mWifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (!mWifiManager.isWifiEnabled()) {
            mWifiManager.setWifiEnabled(true);
        }

        mNetworkChangeReceiver = new NetworkChangeReceiver();
        IntentFilter filters = new IntentFilter();
        filters.addAction("android.net.wifi.WIFI_STATE_CHANGED");
        filters.addAction("android.net.conn.CONNECTIVITY_CHANGE");
        registerReceiver(mNetworkChangeReceiver, filters);

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            Log.d(TAG, "Bluetooth is not available");
            Toast.makeText(MainActivity.this, "Bluetooth is not available", Toast.LENGTH_LONG).show();
        }

        clockHandler = new Handler(getMainLooper());
        clockRunnable = new Runnable() {
            @Override
            public void run() {
                mActivityMainBinding.textViewClock.setText(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
                clockHandler.postDelayed(this, 1000);
            }
        };

        mValueEventListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                mGooglePhotosInfo = dataSnapshot.child("google_photos").getValue(GooglePhotosInfo.class);
                slideshowEnabled = dataSnapshot.child("slideshow_enabled").getValue(Boolean.class);
                if (slideshowEnabled == null) {
                    slideshowEnabled = true;
                }

                if (mGooglePhotosInfo != null && mGooglePhotosInfo.googlePhotosEnabled) {

                    picasawebService = new PicasawebService("slideshow");
                    picasawebService.setUserToken(mGooglePhotosInfo.googleToken);

                    new AsyncTask<Void, Void, List<PhotoEntry>>() {
                        @Override
                        protected List<PhotoEntry> doInBackground(Void... voids) {
                            List<AlbumEntry> albums;
                            try {
                                albums = getAlbums(mGooglePhotosInfo.googleAccount);
                                Log.d(TAG, "Got " + albums.size() + " albums");
                                if (mGooglePhotosInfo.googlePhotoAlbumIndex >= albums.size()) {
                                    mGooglePhotosInfo.googlePhotoAlbumIndex = 0;
                                }
                                AlbumEntry album = albums.get(mGooglePhotosInfo.googlePhotoAlbumIndex);
                                List<PhotoEntry> photos = getPhotos(album);
                                return photos;
                            } catch (ServiceForbiddenException e) {
                                Log.e(TAG, "Token expired, invalidating");
                            } catch (IOException e) {
                                e.printStackTrace();
                            } catch (ServiceException e) {
                                e.printStackTrace();
                            }
                            return null;
                        }

                        protected void onPostExecute(List<PhotoEntry> result) {
                            currentDrawable = null;
                            googlePhotosImageIndex = 0;
                            googlePhotosImagesCount = result.size();
                            googlePhotos.clear();
                            googlePhotos.addAll(result);
                        }
                    }.execute(null, null, null);
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                // Failed to read value
                Log.w(TAG, "Failed to read value.", error.toException());
            }
        };

        mFirebaseAuth = FirebaseAuth.getInstance();
        if (mFirebaseAuth.getCurrentUser() == null) {
            mFirebaseAuth.signInAnonymously().addOnCompleteListener(this, new OnCompleteListener<AuthResult>() {
                @Override
                public void onComplete(@NonNull Task<AuthResult> task) {
                    if (task.isSuccessful()) {
                        // Sign in success
                        Log.d(TAG, "signInAnonymously:success");
                        if (mFirebaseAuth.getCurrentUser() != null) {
                            mirrorAppUID = mFirebaseAuth.getCurrentUser().getUid();

                            mFirebaseDatabase = FirebaseDatabase.getInstance();
                            final DatabaseReference mirrorsRef = mFirebaseDatabase.getReference("mirrors");
                            //mirrorsRef.addValueEventListener(new ValueEventListener() {
                            mirrorsRef.child(mirrorAppUID).addValueEventListener(mValueEventListener);
                        }
                    } else {
                        // If sign in fails
                        Log.w(TAG, "signInAnonymously:failure", task.getException());
                    }
                }
            });
        } else {
            mirrorAppUID = mFirebaseAuth.getCurrentUser().getUid();

            mFirebaseDatabase = FirebaseDatabase.getInstance();
            final DatabaseReference mirrorsRef = mFirebaseDatabase.getReference("mirrors");
            //mirrorsRef.addValueEventListener(new ValueEventListener() {
            mirrorsRef.child(mirrorAppUID).addValueEventListener(mValueEventListener);
        }


        String[] PERMISSIONS = {Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.WRITE_EXTERNAL_STORAGE};
        if (!hasPermissions(this, PERMISSIONS)){
            ActivityCompat.requestPermissions(this, PERMISSIONS, PERMISSIONS_REQUEST_CODE);
        } else {
            File slideshowFolder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Slideshow");
            if (!slideshowFolder.exists()) {
                slideshowFolder.mkdirs();
            } else {
                String[] projection = {MediaStore.Images.Media._ID};
                // Create the mCursor pointing to the SDCard
                mCursor = getContentResolver().query( MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        MediaStore.Images.Media.DATA + " like ? ",
                        new String[] {"%Slideshow%"},
                        null);
                if (mCursor != null && mCursor.getCount() > 0) {
                    localImageIndex = 0;
                    localImagesCount = mCursor.getCount();
                    // Get the column index of the image ID
                    columnIndex = mCursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                    mCursor.moveToPosition(0);
                    int imageID = mCursor.getInt(columnIndex);
                    Uri uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Integer.toString(imageID));

                    mActivityMainBinding.imageSwitcherSlideshow.setImageURI(uri);
                }
            }

            Timer timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showNextImage();
                        }
                    });
                }
            }, 3000, 3000);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case PERMISSIONS_REQUEST_CODE: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    File slideshowFolder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Slideshow");
                    if (!slideshowFolder.exists()) {
                        slideshowFolder.mkdirs();
                    } else {
                        String[] projection = {MediaStore.Images.Media._ID};
                        // Create the mCursor pointing to the SDCard
                        mCursor = getContentResolver().query( MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                projection,
                                MediaStore.Images.Media.DATA + " like ? ",
                                new String[] {"%Slideshow%"},
                                null);
                        if (mCursor != null && mCursor.getCount() > 0) {
                            localImageIndex = 0;
                            localImagesCount = mCursor.getCount();
                            // Get the column index of the image ID
                            columnIndex = mCursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                            mCursor.moveToPosition(0);
                            int imageID = mCursor.getInt(columnIndex);
                            Uri uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Integer.toString(imageID));

                            mActivityMainBinding.imageSwitcherSlideshow.setImageURI(uri);
                        }
                    }

                    Timer timer = new Timer();
                    timer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    showNextImage();
                                }
                            });
                        }
                    }, 3000, 3000);

                }
                break;
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
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mChatService != null) {
            mChatService.stop();
        }
        unregisterReceiver(mNetworkChangeReceiver);
    }

    @Override
    protected void onPause() {
        super.onPause();

        clockHandler.removeCallbacks(clockRunnable);
    }

    @Override
    public void onResume() {
        super.onResume();

        // Performing this check in onResume() covers the case in which BT was
        // not enabled during onStart(), so we were paused to enable it...
        // onResume() will be called when ACTION_REQUEST_ENABLE activity returns.
        if (mBluetoothAdapter != null && mChatService != null) {
            // Only if the state is STATE_NONE, do we know that we haven't started already
            if (mChatService.getState() == BluetoothChatService.STATE_NONE) {
                // Start the Bluetooth chat services
                mChatService.start();
            }
        }

        clockHandler.postDelayed(clockRunnable, 10);

        hideSystemUI();
    }

    // This snippet hides the system bars.
    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void showNextImage() {
        if (slideshowEnabled) {
            if (mGooglePhotosInfo != null && mGooglePhotosInfo.googlePhotosEnabled && !googlePhotos.isEmpty()) {
                if (currentDrawable != null) {
                    mActivityMainBinding.imageSwitcherSlideshow.setVisibility(View.VISIBLE);
                    mActivityMainBinding.imageSwitcherSlideshow.setImageDrawable(currentDrawable);
                    googlePhotosImageIndex++;
                    if (googlePhotosImageIndex > googlePhotosImagesCount - 1) {
                        googlePhotosImageIndex = 0;
                    }
                }
                String url = googlePhotos.get(googlePhotosImageIndex).getMediaContents().get(0).getUrl();
                Glide.with(this)
                        .asDrawable()
                        .load(url)
                        .into(new SimpleTarget<Drawable>() {
                            @Override
                            public void onResourceReady(Drawable resource, Transition<? super Drawable> transition) {
                                currentDrawable = resource;
                            }
                        });
            } else if (mCursor != null && mCursor.getCount() > 0) {
                mActivityMainBinding.imageSwitcherSlideshow.setVisibility(View.VISIBLE);
                localImageIndex++;
                if (localImageIndex > localImagesCount - 1) {
                    localImageIndex = 0;
                }
                mCursor.moveToPosition(localImageIndex);
                int imageID = mCursor.getInt(columnIndex);
                Uri uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Integer.toString(imageID));
                mActivityMainBinding.imageSwitcherSlideshow.setImageURI(uri);
            } else {
                mActivityMainBinding.imageSwitcherSlideshow.setVisibility(View.GONE);
            }
        } else {
            mActivityMainBinding.imageSwitcherSlideshow.setVisibility(View.GONE);
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

    public List<PhotoEntry> getPhotos(AlbumEntry album) throws IOException, ServiceException {
        AlbumFeed feed = album.getFeed();
        List<PhotoEntry> photos = new ArrayList<>();
        for (GphotoEntry entry : feed.getEntries()) {
            PhotoEntry pe = new PhotoEntry(entry);
            photos.add(pe);
        }
        Log.d(TAG, "Album " + album.getName() + " has " + photos.size() + " photos");
        return photos;
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
                            String message = "";
                            if (!TextUtils.isEmpty(mirrorAppUID)) {
                                message = "MirrorUID: " + mirrorAppUID;
                            }
                            if (NetworkUtil.isOnline(MainActivity.this)) {
                                message += " isOnline";
                            }
                            MainActivity.this.sendMessage(message);
                            break;
                        case BluetoothChatService.STATE_CONNECTING:
                            break;
                        case BluetoothChatService.STATE_LISTEN:
                        case BluetoothChatService.STATE_NONE:
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
                    if (readMessage.startsWith("WiFi: ")) {
                        String[] strParams = readMessage.substring(6).split("\\s+");
                        String wifiSSID = strParams[0];
                        String wifiPassword = strParams[1];
                        if (!mWifiManager.isWifiEnabled()) {
                            mWifiManager.setWifiEnabled(true);
                        }
                        WifiInfo info = mWifiManager.getConnectionInfo();
                        String ssid  = info.getSSID();
                        if (!("\"" + wifiSSID + "\"").equals(ssid)) {
                            wifiScanResultList = mWifiManager.getScanResults();
                            connectToAP(wifiSSID, wifiPassword);
                        }
                    } else if (readMessage.startsWith("ControlUID: ")) {
                        controlAppUID = readMessage.substring(12);
                        mFirebaseDatabase = FirebaseDatabase.getInstance();
                        DatabaseReference mirrorsRef = mFirebaseDatabase.getReference("mirrors");
                        DatabaseReference controlsRef = mFirebaseDatabase.getReference("controls");
                        if (!TextUtils.isEmpty(mirrorAppUID)) {
                            mirrorsRef.child(mirrorAppUID).child("control_app_uid").setValue(controlAppUID);
                            controlsRef.child(controlAppUID).child("mirror_app_uid").setValue(mirrorAppUID);
                        }
                    }
                    break;
                case Constants.MESSAGE_DEVICE_NAME:
                    // save the connected device's name
                    String mConnectedDeviceName = msg.getData().getString(Constants.DEVICE_NAME);
                    break;
                case Constants.MESSAGE_TOAST:
                    break;
            }
        }
    };

    public void connectToAP(String ssid, String password) {
        Log.i(TAG, "* connectToAP");

        WifiConfiguration wifiConfiguration = new WifiConfiguration();

        Log.d(TAG, "# password " + password);

        for (ScanResult result : wifiScanResultList) {
            if (result.SSID.equals(ssid)) {

                String securityMode = getWiFIScanResultSecurity(result);

                if (securityMode.equalsIgnoreCase("OPEN")) {

                    wifiConfiguration.SSID = "\"" + ssid + "\"";
                    wifiConfiguration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                    int res = mWifiManager.addNetwork(wifiConfiguration);
                    Log.d(TAG, "# add Network returned " + res);

                    boolean b = mWifiManager.enableNetwork(res, true);
                    Log.d(TAG, "# enableNetwork returned " + b);

                    mWifiManager.setWifiEnabled(true);

                } else if (securityMode.equalsIgnoreCase("WEP")) {

                    wifiConfiguration.SSID = "\"" + ssid + "\"";
                    wifiConfiguration.wepKeys[0] = "\"" + password + "\"";
                    wifiConfiguration.wepTxKeyIndex = 0;
                    wifiConfiguration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                    wifiConfiguration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
                    int res = mWifiManager.addNetwork(wifiConfiguration);
                    Log.d(TAG, "### 1 ### add Network returned " + res);

                    boolean b = mWifiManager.enableNetwork(res, true);
                    Log.d(TAG, "# enableNetwork returned " + b);

                    mWifiManager.setWifiEnabled(true);
                }

                wifiConfiguration.SSID = "\"" + ssid + "\"";
                wifiConfiguration.preSharedKey = "\"" + password + "\"";
                wifiConfiguration.hiddenSSID = true;
                wifiConfiguration.status = WifiConfiguration.Status.ENABLED;
                wifiConfiguration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
                wifiConfiguration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
                wifiConfiguration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                wifiConfiguration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
                wifiConfiguration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
                wifiConfiguration.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
                wifiConfiguration.allowedProtocols.set(WifiConfiguration.Protocol.WPA);

                int res = mWifiManager.addNetwork(wifiConfiguration);
                Log.d(TAG, "### 2 ### add Network returned " + res);

                mWifiManager.enableNetwork(res, true);

                boolean changeHappen = mWifiManager.saveConfiguration();

                if (res != -1 && changeHappen){
                    Log.d(TAG, "### Change happen");
                } else {
                    Log.d(TAG, "*** Change NOT happen");
                }

                mWifiManager.setWifiEnabled(true);
            }
        }
    }

    public String getWiFIScanResultSecurity(ScanResult scanResult) {

        final String cap = scanResult.capabilities;
        final String[] securityModes = { "WEP", "PSK", "EAP" };

        for (int i = securityModes.length - 1; i >= 0; i--) {
            if (cap.contains(securityModes[i])) {
                return securityModes[i];
            }
        }

        return "OPEN";
    }

    class NetworkChangeReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(final Context context, final Intent intent) {

            int status = NetworkUtil.getConnectivityStatusString(context);
            if ("android.net.conn.CONNECTIVITY_CHANGE".equals(intent.getAction())) {
                if (status != NetworkUtil.NETWORK_STATUS_NOT_CONNECTED) {
                    if (mFirebaseAuth.getCurrentUser() == null) {
                        mFirebaseAuth.signInAnonymously().addOnCompleteListener(MainActivity.this, new OnCompleteListener<AuthResult>() {
                            @Override
                            public void onComplete(@NonNull Task<AuthResult> task) {
                                if (task.isSuccessful()) {
                                    // Sign in success
                                    Log.d(TAG, "signInAnonymously:success");
                                    if (mFirebaseAuth.getCurrentUser() != null) {
                                        mirrorAppUID = mFirebaseAuth.getCurrentUser().getUid();

                                        mFirebaseDatabase = FirebaseDatabase.getInstance();
                                        DatabaseReference mirrorsRef = mFirebaseDatabase.getReference("mirrors");
                                        DatabaseReference controlsRef = mFirebaseDatabase.getReference("controls");
                                        mirrorsRef.child(mirrorAppUID).addValueEventListener(mValueEventListener);

                                        if (!TextUtils.isEmpty(mirrorAppUID) && !TextUtils.isEmpty(controlAppUID)) {
                                            mirrorsRef.child(mirrorAppUID).child("control_app_uid").setValue(controlAppUID);
                                            controlsRef.child(controlAppUID).child("mirror_app_uid").setValue(mirrorAppUID);
                                        }
                                    }
                                } else {
                                    // If sign in fails
                                    Log.w(TAG, "signInAnonymously:failure", task.getException());
                                }
                            }
                        });
                    }
                    if (status == NetworkUtil.NETWORK_STATUS_WIFI) {
                        String message = "WiFi connected";
                        MainActivity.this.sendMessage(message);
                    }
                }

            }
        }
    }
}
