package org.kivy.android;

import android.os.SystemClock;

import java.io.InputStream;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.lang.System;

import android.view.ViewGroup;
import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.PowerManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.widget.ImageView;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.view.KeyEvent;
import android.content.DialogInterface;
import android.app.AlertDialog;
import android.view.WindowManager;
import android.view.Window;
import android.net.Uri;
import android.os.Build;

import android.Manifest;
import android.app.DownloadManager;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.widget.Toast;

import android.widget.AbsoluteLayout;
import android.view.ViewGroup.LayoutParams;

import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.DownloadListener;
import android.webkit.WebView;
import android.webkit.ValueCallback;
import android.webkit.JsResult;

import org.renpy.android.ResourceManager;

public class PythonActivity extends Activity {
    // This activity is modified from a mixture of the SDLActivity and
    // PythonActivity in the SDL2 bootstrap, but removing all the SDL2
    // specifics.

    private static final int CODE_UPLOAD = 9990;
    private static final String TAG = "PythonActivity";

    public static PythonActivity mActivity = null;

    /** If shared libraries (e.g. SDL or the native application) could not be loaded. */
    public static boolean mBrokenLibraries;

    protected static ViewGroup mLayout;
    protected static WebView mWebView;
    protected static ValueCallback<Uri[]> mFilePathCallback;
    protected static ValueCallback mUploadMessage;

    protected static Thread mPythonThread;

    private ResourceManager resourceManager = null;
    private Bundle mMetaData = null;
    private PowerManager.WakeLock mWakeLock = null;

    public String getAppRoot() {
        String app_root =  getFilesDir().getAbsolutePath() + "/app";
        return app_root;
    }

    public String getEntryPoint(String search_dir) {
        /* Get the main file (.pyc|.pyo|.py) depending on if we
         * have a compiled version or not.
        */
        List<String> entryPoints = new ArrayList<String>();
        entryPoints.add("main.pyo");  // python 2 compiled files
        entryPoints.add("main.pyc");  // python 3 compiled files
		for (String value : entryPoints) {
            File mainFile = new File(search_dir + "/" + value);
            if (mainFile.exists()) {
                return value;
            }
        }
        return "main.py";
    }

    public static void initialize() {
        // The static nature of the singleton and Android quirkyness force us to initialize everything here
        // Otherwise, when exiting the app and returning to it, these variables *keep* their pre exit values
        mWebView = null;
        mLayout = null;
        mBrokenLibraries = false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.v(TAG, "My oncreate running");
        resourceManager = new ResourceManager(this);
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.getDecorView().setBackgroundColor(Color.parseColor("#2f343f"));
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.parseColor("#262b35"));

        this.mActivity = this;
        new UnpackFilesTask().execute(getAppRoot());
    }

    private class UnpackFilesTask extends AsyncTask<String, Void, String> {
        @Override
        protected String doInBackground(String... params) {
            File app_root_file = new File(params[0]);
            Log.v(TAG, "Ready to unpack");
            PythonActivityUtil pythonActivityUtil = new PythonActivityUtil(mActivity, resourceManager);
            pythonActivityUtil.unpackData("private", app_root_file);
            return null;
        }

        @Override
        protected void onPostExecute(String result) {
            Log.v("Python", "Device: " + android.os.Build.DEVICE);
            Log.v("Python", "Model: " + android.os.Build.MODEL);

            PythonActivity.initialize();

            // Load shared libraries
            String errorMsgBrokenLib = "";
            try {
                loadLibraries();
            } catch(UnsatisfiedLinkError e) {
                System.err.println(e.getMessage());
                mBrokenLibraries = true;
                errorMsgBrokenLib = e.getMessage();
            } catch(Exception e) {
                System.err.println(e.getMessage());
                mBrokenLibraries = true;
                errorMsgBrokenLib = e.getMessage();
            }

            if (mBrokenLibraries)
            {
                AlertDialog.Builder dlgAlert  = new AlertDialog.Builder(PythonActivity.mActivity);
                dlgAlert.setMessage("An error occurred while trying to load the application libraries. Please try again and/or reinstall."
                      + System.getProperty("line.separator")
                      + System.getProperty("line.separator")
                      + "Error: " + errorMsgBrokenLib);
                dlgAlert.setTitle("Python Error");
                dlgAlert.setPositiveButton("Exit",
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog,int id) {
                            // if this button is clicked, close current activity
                            PythonActivity.mActivity.finish();
                        }
                    });
               dlgAlert.setCancelable(false);
               dlgAlert.create().show();

               return;
            }

            //Runtime External storage permission for saving download files
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_DENIED) {
                    Log.d("permission", "permission denied to WRITE_EXTERNAL_STORAGE - requesting it");
                    String[] permissions = {Manifest.permission.WRITE_EXTERNAL_STORAGE};
                    requestPermissions(permissions, CODE_UPLOAD);
                }
            }

            // Set up the webview
            String app_root_dir = getAppRoot();

            WebView.setWebContentsDebuggingEnabled(true);
            mWebView = new WebView(PythonActivity.mActivity);
            mWebView.getSettings().setJavaScriptEnabled(true);
            mWebView.getSettings().setDomStorageEnabled(true);
            mWebView.getSettings().setAppCacheEnabled(true);
            mWebView.getSettings().setAllowContentAccess(true);
            mWebView.getSettings().setAllowFileAccess(true);
            mWebView.getSettings().setBlockNetworkImage(false);
            mWebView.loadUrl("file:///" + app_root_dir + "/_load.html");

            mWebView.setLayoutParams(new LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT));
            mWebView.getSettings().setSupportMultipleWindows(true);
            mWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, String url) {
                        view.loadUrl(url);
                        return false;
                    }
                    @Override
                    public void onPageFinished (WebView view, String url) {
                        view.clearHistory();
                    }
                });
            mWebView.setWebChromeClient(new WebChromeClient() {
                    // Open target="_blank" in browser
                    @Override
                    public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, android.os.Message resultMsg)
                    {
                        WebView.HitTestResult result = view.getHitTestResult();
                        String data = result.getExtra();
                        Context context = view.getContext();
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(data));
                        context.startActivity(browserIntent);
                        return false;
                    }

                    // FILE CHOOSER

                    /**
                     * This is the method used by Android 5.0+ to upload files towards a web form in a Webview
                     *
                     * @param webView
                     * @param filePathCallback
                     * @param fileChooserParams
                     * @return
                     */
                    @Override
                    public boolean onShowFileChooser(
                            WebView webView, ValueCallback<Uri[]> filePathCallback,
                            WebChromeClient.FileChooserParams fileChooserParams) {

                        if (mFilePathCallback != null) {
                            mFilePathCallback.onReceiveValue(null);
                        }
                        mFilePathCallback = filePathCallback;
                        startActivityForResult(fileChooserParams.createIntent(), CODE_UPLOAD);

                        return true;
                    }

                    @Override
                    public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                        Log.d("LogTag", message);
                        result.confirm();
                        return true;
                    }

                    /**
                     * Despite that there is not a Override annotation, this method overrides the open file
                     * chooser function present in Android 3.0+
                     *
                     * @param uploadMsg
                     * @author Tito_Leiva
                     */
                    public void openFileChooser(ValueCallback<Uri> uploadMsg) {
                        mUploadMessage = uploadMsg;
                        Intent i = getGalleryIntent("*/*");
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        startActivityForResult(Intent.createChooser(i, "Select ROM"), CODE_UPLOAD);

                    }

                    public void openFileChooser(ValueCallback uploadMsg, String acceptType) {
                        mUploadMessage = uploadMsg;
                        Intent i = getGalleryIntent(acceptType);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        startActivityForResult(
                                Intent.createChooser(i, "Select ROM"), CODE_UPLOAD);
                    }

                    /**
                     * Despite that there is not a Override annotation, this method overrides the open file
                     * chooser function present in Android 4.1+
                     *
                     * @param uploadMsg
                     * @author Tito_Leiva
                     */
                    public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType, String capture) {
                        mUploadMessage = uploadMsg;
                        Intent i = getGalleryIntent(acceptType);
                        startActivityForResult(Intent.createChooser(i, "Select ROM"), CODE_UPLOAD);

                    }

                    private Intent getGalleryIntent(String type) {
                        // Filesystem.
                        final Intent galleryIntent = new Intent();
                        galleryIntent.setType(type);
                        galleryIntent.addCategory(Intent.CATEGORY_OPENABLE);
                        galleryIntent.setAction(Intent.ACTION_GET_CONTENT);

                        return galleryIntent;
                    }

                    // END FILE CHOOSER

                });
            mWebView.setDownloadListener(new DownloadListener() {
                @Override
                public void onDownloadStart(String url, String userAgent,
                                                String contentDisposition, String mimeType,
                                                long contentLength) {
                    DownloadManager.Request request = new DownloadManager.Request(
                            Uri.parse(url));
                    request.setMimeType(mimeType);
                    String cookies = CookieManager.getInstance().getCookie(url);
                    request.setDescription("Downloading ROM...");
                    request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType));
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(
                                    url, contentDisposition, mimeType));
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    dm.enqueue(request);
                    Toast.makeText(getApplicationContext(), "ROM was saved to your 'Downloads' directory.", Toast.LENGTH_LONG).show();
                }
            });
            mLayout = new AbsoluteLayout(PythonActivity.mActivity);
            mLayout.addView(mWebView);

            setContentView(mLayout);

            String mFilesDirectory = mActivity.getFilesDir().getAbsolutePath();
            String entry_point = getEntryPoint(app_root_dir);

            Log.v(TAG, "Setting env vars for start.c and Python to use");
            PythonActivity.nativeSetenv("ANDROID_ENTRYPOINT", entry_point);
            PythonActivity.nativeSetenv("ANDROID_ARGUMENT", app_root_dir);
            PythonActivity.nativeSetenv("ANDROID_APP_PATH", app_root_dir);
            PythonActivity.nativeSetenv("ANDROID_PRIVATE", mFilesDirectory);
            PythonActivity.nativeSetenv("ANDROID_UNPACK", app_root_dir);
            PythonActivity.nativeSetenv("PYTHONHOME", app_root_dir);
            PythonActivity.nativeSetenv("PYTHONPATH", app_root_dir + ":" + app_root_dir + "/lib");
            PythonActivity.nativeSetenv("PYTHONOPTIMIZE", "2");
            PythonActivity.nativeSetenv("SKYTEMPLE_ARMIPS_EXEC", getApplicationInfo().nativeLibraryDir + "/libarmips.so");

            try {
                Log.v(TAG, "Access to our meta-data...");
                mActivity.mMetaData = mActivity.getPackageManager().getApplicationInfo(
                        mActivity.getPackageName(), PackageManager.GET_META_DATA).metaData;

                PowerManager pm = (PowerManager) mActivity.getSystemService(Context.POWER_SERVICE);
                if ( mActivity.mMetaData.getInt("wakelock") == 1 ) {
                    mActivity.mWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "Screen On");
                    mActivity.mWakeLock.acquire();
                }
            } catch (PackageManager.NameNotFoundException e) {
            }

            final Thread pythonThread = new Thread(new PythonMain(), "PythonThread");
            PythonActivity.mPythonThread = pythonThread;
            pythonThread.start();

            final Thread wvThread = new Thread(new WebViewLoaderMain(), "WvThread");
            wvThread.start();
        }
    }

    @Override
    public void onDestroy() {
        Log.i("Destroy", "end of app");
        super.onDestroy();

        // make sure all child threads (python_thread) are stopped
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    public void loadLibraries() {
        String app_root = new String(getAppRoot());
        File app_root_file = new File(app_root);
        PythonUtil.loadLibraries(app_root_file,
            new File(getApplicationInfo().nativeLibraryDir));
    }

    public static void loadUrl(String url) {
        class LoadUrl implements Runnable {
            private String mUrl;

            public LoadUrl(String url) {
                mUrl = url;
            }

            public void run() {
                mWebView.loadUrl(mUrl);
            }
        }

        Log.i(TAG, "Opening URL: " + url);
        mActivity.runOnUiThread(new LoadUrl(url));
    }

    public static ViewGroup getLayout() {
        return   mLayout;
    }

    long lastBackClick = SystemClock.elapsedRealtime();
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Check if the key event was the Back button and if there's history
        if ((keyCode == KeyEvent.KEYCODE_BACK) && mWebView.canGoBack()) {
            mWebView.goBack();
            return true;
        }
        // If it wasn't the Back key or there's no web page history, bubble up to the default
        // system behavior (probably exit the activity)
        if (SystemClock.elapsedRealtime() - lastBackClick > 2000){
            lastBackClick = SystemClock.elapsedRealtime();
            Toast.makeText(this, "Click again to close the app",
            Toast.LENGTH_LONG).show();
            return true;
        }

        lastBackClick = SystemClock.elapsedRealtime();
        return super.onKeyDown(keyCode, event);
    }

    //----------------------------------------------------------------------------
    // Listener interface for onNewIntent
    //

    public interface NewIntentListener {
        void onNewIntent(Intent intent);
    }

    private List<NewIntentListener> newIntentListeners = null;

    public void registerNewIntentListener(NewIntentListener listener) {
        if ( this.newIntentListeners == null )
            this.newIntentListeners = Collections.synchronizedList(new ArrayList<NewIntentListener>());
        this.newIntentListeners.add(listener);
    }

    public void unregisterNewIntentListener(NewIntentListener listener) {
        if ( this.newIntentListeners == null )
            return;
        this.newIntentListeners.remove(listener);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if ( this.newIntentListeners == null )
            return;
        this.onResume();
        synchronized ( this.newIntentListeners ) {
            Iterator<NewIntentListener> iterator = this.newIntentListeners.iterator();
            while ( iterator.hasNext() ) {
                (iterator.next()).onNewIntent(intent);
            }
        }
    }

    //----------------------------------------------------------------------------
    // Listener interface for onActivityResult
    //

    public interface ActivityResultListener {
        void onActivityResult(int requestCode, int resultCode, Intent data);
    }

    private List<ActivityResultListener> activityResultListeners = null;

    public void registerActivityResultListener(ActivityResultListener listener) {
        if ( this.activityResultListeners == null )
            this.activityResultListeners = Collections.synchronizedList(new ArrayList<ActivityResultListener>());
        this.activityResultListeners.add(listener);
    }

    public void unregisterActivityResultListener(ActivityResultListener listener) {
        if ( this.activityResultListeners == null )
            return;
        this.activityResultListeners.remove(listener);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        processFileChooserActivityResult(requestCode, resultCode, intent);
        if ( this.activityResultListeners == null )
            return;
        this.onResume();
        synchronized ( this.activityResultListeners ) {
            Iterator<ActivityResultListener> iterator = this.activityResultListeners.iterator();
            while ( iterator.hasNext() )
                (iterator.next()).onActivityResult(requestCode, resultCode, intent);
        }
    }

    protected void processFileChooserActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode == CODE_UPLOAD) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                if (resultCode == RESULT_OK) {
                    if (null == mUploadMessage) {
                        super.onActivityResult(requestCode, resultCode, intent);
                        return;
                    }

                    Uri selectedImageUri;

                    mUploadMessage.onReceiveValue(intent.getData());
                } else {
                    mUploadMessage.onReceiveValue(null);
                }
                mUploadMessage = null;

                return;
            } else {
                if (resultCode == RESULT_OK) {
                    Log.i(TAG, "Request Code " + String.valueOf(requestCode) + " Result Code " + String.valueOf(resultCode) + ": " + WebChromeClient.FileChooserParams.parseResult(resultCode, intent)[0].toString());
                    mFilePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, intent));
                } else {
                    mFilePathCallback.onReceiveValue(null);
                }
                mFilePathCallback = null;

                return;
            }
        }
    }

    public static void start_service(
            String serviceTitle,
            String serviceDescription,
            String pythonServiceArgument
            ) {
        _do_start_service(
            serviceTitle, serviceDescription, pythonServiceArgument, true
        );
    }

    public static void start_service_not_as_foreground(
            String serviceTitle,
            String serviceDescription,
            String pythonServiceArgument
            ) {
        _do_start_service(
            serviceTitle, serviceDescription, pythonServiceArgument, false
        );
    }

    public static void _do_start_service(
            String serviceTitle,
            String serviceDescription,
            String pythonServiceArgument,
            boolean showForegroundNotification
            ) {
        Intent serviceIntent = new Intent(PythonActivity.mActivity, PythonService.class);
        String argument = PythonActivity.mActivity.getFilesDir().getAbsolutePath();
        String app_root_dir = PythonActivity.mActivity.getAppRoot();
        String entry_point = PythonActivity.mActivity.getEntryPoint(app_root_dir + "/service");
        serviceIntent.putExtra("androidPrivate", argument);
        serviceIntent.putExtra("androidArgument", app_root_dir);
        serviceIntent.putExtra("serviceEntrypoint", "service/" + entry_point);
        serviceIntent.putExtra("pythonName", "python");
        serviceIntent.putExtra("pythonHome", app_root_dir);
        serviceIntent.putExtra("pythonPath", app_root_dir + ":" + app_root_dir + "/lib");
        serviceIntent.putExtra("serviceStartAsForeground",
            (showForegroundNotification ? "true" : "false")
        );
        serviceIntent.putExtra("serviceTitle", serviceTitle);
        serviceIntent.putExtra("serviceDescription", serviceDescription);
        serviceIntent.putExtra("pythonServiceArgument", pythonServiceArgument);
        PythonActivity.mActivity.startService(serviceIntent);
    }

    public static void stop_service() {
        Intent serviceIntent = new Intent(PythonActivity.mActivity, PythonService.class);
        PythonActivity.mActivity.stopService(serviceIntent);
    }


    public static native void nativeSetenv(String name, String value);
    public static native int nativeInit(Object arguments);

}


class PythonMain implements Runnable {
    @Override
    public void run() {
        PythonActivity.nativeInit(new String[0]);
    }
}

class WebViewLoaderMain implements Runnable {
    @Override
    public void run() {
        WebViewLoader.testConnection();
    }
}
