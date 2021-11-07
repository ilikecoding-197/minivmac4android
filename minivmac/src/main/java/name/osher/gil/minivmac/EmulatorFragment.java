package name.osher.gil.minivmac;

import static android.os.Looper.getMainLooper;

import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.GestureDetectorCompat;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;

public class EmulatorFragment extends Fragment
        implements IOnIOEventListener {
    private static final String TAG = "minivmac.EmulatorFrag";

    private final static int[] keycodeTranslationTable = {-1, -1, -1, -1, -1, -1, -1, 0x1D, 0x12, 0x13, 0x14, 0x15, 0x17, 0x16, 0x1A, 0x1C, 0x19, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0x00, 0x0B, 0x08, 0x02, 0x0E, 0x03, 0x05, 0x04, 0x22, 0x26, 0x28, 0x25, 0x2E, 0x2D, 0x1F, 0x23, 0x0C, 0x0F, 0x01, 0x11, 0x20, 0x09, 0x0D, 0x07, 0x10, 0x06, 0x2B, 0x2F, 0x37, 0x37, 0x38, 0x38, 0x30, 0x31, 0x3A, -1, -1, 0x24, 0x33, 0x32, 0x1B, 0x18, 0x21, 0x1E, 0x2A, 0x29, 0x27, 0x2C, 0x37, 0x3A, -1, -1, 0x45, -1, -1, 0x3A, -1, -1, -1, -1, -1, -1, -1};
    private final static int TRACKBALL_SENSITIVITY = 8;
    private final static int KEYCODE_MAC_SHIFT = 56;

    private ScreenView mScreenView;
    private Boolean onActivity = false;
    private Boolean isLandscape = false;
    private GestureDetectorCompat mGestureDetector;
    private boolean mUIVisible = true;
    private Boolean mEmulatorStarted = false;

    private KeyboardView mKeyboardView;
    private Keyboard mQwertyKeyboard;
    private Keyboard mSymbolsKeyboard;
    private Keyboard mSymbolsShiftedKeyboard;

    private Core mCore;
    private Handler mUIHandler;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        ViewGroup root = (ViewGroup) inflater.inflate(R.layout.screen, container, false);

        onActivity = false;
        mScreenView = root.findViewById(R.id.screen);
        mKeyboardView = root.findViewById(R.id.keyboard);
        mGestureDetector = new GestureDetectorCompat(getContext(), new EmulatorFragment.SingleTapGestureListener());
        mUIHandler = new Handler(getMainLooper());

        isLandscape = (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
        toggleFullscreen(isLandscape);
        initKeyboard();

        updateByPrefs();

        initEmulator();

        return root;
    }

    private void initEmulator() {
        // load ROM
        String romFileName = getString(R.string.romFileName);
        File romFile = FileManager.getInstance().getRomFile(romFileName);
        final ByteBuffer rom = ByteBuffer.allocateDirect((int)romFile.length());
        try {
            FileInputStream romReader = new FileInputStream(romFile);
            romReader.getChannel().read(rom);
            romReader.close();
        } catch (Exception x) {
            //Utils.showAlert(this, String.format(getString(R.string.errNoROM), romFile.getPath(),
            //		getString(R.string.romFileName)),false);
            showSettings();
            return;
        }

        requireActivity().invalidateOptionsMenu();

        Thread emulation = new Thread(() -> {
            mCore = new Core(getContext());

            mCore.setOnInitScreenListener((screenWidth, screenHeight) -> mUIHandler.post(() -> mScreenView.setTargetScreenSize(screenWidth, screenHeight)
            ));

            mScreenView.setOnMouseEventListener(new ScreenView.OnMouseEventListener() {
                @Override
                public void onMouseMove(int x, int y) {
                    mCore.setMousePosition(x, y);
                }

                @Override
                public void onMouseClick(boolean down) {
                    mCore.setMouseBtn(down);
                }
            });

            mCore.setOnUpdateScreenListener((update, top, left, bottom, right) -> mUIHandler.post(() -> mScreenView.updateScreen(update, top, left, bottom, right)));

            mCore.setOnDiskEventListener(new Core.OnDiskEventListener() {

                @Override
                public void onDiskInserted(String path) {
                    requireActivity().invalidateOptionsMenu();
                }

                @Override
                public void onDiskEjected(String path) {
                    if (FileManager.getInstance().isInDownloads(path)) {
                        File f = new File(path);
                        Utils.showShareDialog(getContext(), f, f.getName());
                    }

                    requireActivity().invalidateOptionsMenu();
                }

                @Override
                public void onCreateDisk(int size, String filename) {
                    mCore.makeNewDisk(size, FileManager.getInstance().getDownloadDir().getAbsolutePath(), filename);
                    Core.notifyDiskCreated();
                }
            });

            //mCore.resumeEmulation();
            mCore.initEmulation(mCore, rom);
            System.exit(0);
        });
        mEmulatorStarted = true;
        emulation.setName("EmulationThread");
        emulation.start();
    }

    private void initKeyboard() {
        // Create the Keyboard
        mQwertyKeyboard = new Keyboard(getContext(), R.xml.qwerty);
        mSymbolsKeyboard = new Keyboard(getContext(), R.xml.symbols);
        mSymbolsShiftedKeyboard = new Keyboard(getContext(), R.xml.symbols_shift);

        // Attach the keyboard to the view
        mKeyboardView.setKeyboard(mQwertyKeyboard);
        // Do not show the preview balloons
        mKeyboardView.setPreviewEnabled(false);
        // Install the key handler
        mKeyboardView.setOnKeyboardActionListener(mOnKeyboardActionListener);
    }

    private final KeyboardView.OnKeyboardActionListener mOnKeyboardActionListener = new KeyboardView.OnKeyboardActionListener() {

        @Override public void onKey(int primaryCode, int[] keyCodes) {
            if (mKeyboardView != null) {
                if (primaryCode == Keyboard.KEYCODE_MODE_CHANGE) {
                    Keyboard current = mKeyboardView.getKeyboard();
                    if (current == mSymbolsKeyboard) {
                        mKeyboardView.setKeyboard(mQwertyKeyboard);
                        setShifted(false);
                        mKeyboardView.setShifted(false);
                    } else if (current == mSymbolsShiftedKeyboard) {
                        mKeyboardView.setKeyboard(mQwertyKeyboard);
                        setShifted(true);
                        mKeyboardView.setShifted(true);
                    } else {
                        if (getKey(KEYCODE_MAC_SHIFT).on) {
                            mKeyboardView.setKeyboard(mSymbolsShiftedKeyboard);
                            setShifted(true);
                        } else {
                            mKeyboardView.setKeyboard(mSymbolsKeyboard);
                            setShifted(false);
                        }
                    }
                } else if (primaryCode == KEYCODE_MAC_SHIFT) {
                    Keyboard currentKeyboard = mKeyboardView.getKeyboard();
                    if (mQwertyKeyboard == currentKeyboard) {
                        mKeyboardView.setShifted(!mKeyboardView.isShifted());
                    } else if (currentKeyboard == mSymbolsKeyboard) {
                        setShifted(true);
                        mKeyboardView.setKeyboard(mSymbolsShiftedKeyboard);
                        setShifted(true);
                    } else if (currentKeyboard == mSymbolsShiftedKeyboard) {
                        setShifted(false);
                        mKeyboardView.setKeyboard(mSymbolsKeyboard);
                        setShifted(false);
                    }
                }
            }
        }

        @Override public void onPress(int primaryCode) {
            if (primaryCode >= 0) {
                Keyboard.Key key = getKey(primaryCode);

                if (key != null && (!key.sticky || !key.on)) {
                    mCore.keyDown(primaryCode);
                }
            }
        }

        @Override public void onRelease(int primaryCode) {
            if (primaryCode >= 0) {
                Keyboard.Key key = getKey(primaryCode);

                if (key != null && (!key.sticky || !key.on)) {
                    mCore.keyUp(primaryCode);
                }
            }
        }

        @Override public void onText(CharSequence text) {
        }

        @Override public void swipeDown() {
        }

        @Override public void swipeLeft() {
        }

        @Override public void swipeRight() {
        }

        @Override public void swipeUp() {
        }

        private Keyboard.Key getKey(int primaryCode) {
            List<Keyboard.Key> keys = mKeyboardView.getKeyboard().getKeys();
            for (Keyboard.Key key : keys) {
                if (key.codes.length > 0 && key.codes[0] == primaryCode) {
                    return key;
                }
            }
            return null;
        }

        public void setShifted(boolean shiftState) {
            Keyboard.Key shiftKey = getKey(KEYCODE_MAC_SHIFT);
            if (shiftKey != null) {
                shiftKey.on = shiftState;
            }
        }
    };

    private void updateByPrefs() {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getContext());
        boolean scalePref = sharedPref.getBoolean(SettingsFragment.KEY_PREF_SCALE, true);
        boolean scrollPref = sharedPref.getBoolean(SettingsFragment.KEY_PREF_SCROLL, false);
        mScreenView.setScaled(scalePref);
        mScreenView.setScroll(scrollPref);
    }

    @Override
    public void onPause () {
        if (mCore != null) {
            mCore.pauseEmulation();
        }

        super.onPause();

        if (mCore != null && !mCore.hasDisksInserted() && !onActivity) {
            mCore.requestMacOff();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (mCore != null) {
            mCore.resumeEmulation();
        }
    }

    @Override
    public boolean onKeyDown (int keyCode, @NonNull KeyEvent event) {
        if (mScreenView.isScroll()) {
            switch(keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    mScreenView.scrollScreen(keyCode, 8);
                    return true;
            }
        }

        int macKey = translateKeyCode(keyCode);
        if (macKey >= 0) {
            mCore.keyDown(macKey);
            return true;
        }

        if(keyCode == KeyEvent.KEYCODE_BACK) {
            // letting this through will break on next launch
            // since it will create a new instance instead of resuming
            // this one. I thought singleInstance was for that.

            // Close the keyboard, if it is open
            toggleKeyboard();

            return true;
        }

        return false;
    }

    @Override
    public boolean onKeyUp (int keyCode, @NonNull KeyEvent event) {
        int macKey = translateKeyCode(keyCode);
        if (macKey >= 0) {
            mCore.keyUp(macKey);
            return true;
        }
        return false;
    }

    @Override
    public boolean onTrackballEvent (MotionEvent event) {
        if (event.getX() > 0) mScreenView.scrollScreen(KeyEvent.KEYCODE_DPAD_RIGHT, (int)(TRACKBALL_SENSITIVITY*event.getX()));
        else if (event.getX() < 0) mScreenView.scrollScreen(KeyEvent.KEYCODE_DPAD_LEFT, (int)-(TRACKBALL_SENSITIVITY*event.getX()));
        if (event.getY() > 0) mScreenView.scrollScreen(KeyEvent.KEYCODE_DPAD_DOWN, (int)(TRACKBALL_SENSITIVITY*event.getY()));
        else if (event.getY() < 0) mScreenView.scrollScreen(KeyEvent.KEYCODE_DPAD_UP, (int)-(TRACKBALL_SENSITIVITY*event.getY()));

        return true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (hasFocus) {
            if (mUIVisible) {
                showSystemUI();
            } else {
                hideSystemUI();
            }
        }
    }

    @Override
    public boolean onTouchEvent (@NonNull MotionEvent event) {
        return this.mGestureDetector.onTouchEvent(event);
    }


    public int translateKeyCode (int keyCode) {
        if (keyCode < 0 || keyCode >= keycodeTranslationTable.length) return -1;
        return keycodeTranslationTable[keyCode];
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        isLandscape = (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE);
        toggleFullscreen(isLandscape);
        initKeyboard();
    }

    private void hideSystemUI() {
        // Enables regular immersive mode.
        View decorView = requireActivity().getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE
                        // Set the content to appear under the system bars so that the
                        // content doesn't resize when the system bars hide and show.
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        // Hide the nav bar and status bar
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
        mUIVisible = false;
    }

    // Shows the system bars by removing all the flags
    // except for the ones that make the content appear under the system bars.
    private void showSystemUI() {
        View decorView = requireActivity().getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        mUIVisible = true;
    }

    private void toggleFullscreen(Boolean isFullscreen) {
        if(isFullscreen) {
            requireActivity().getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } else {
            requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.minivmac_actions, menu);
    }

    public void onPrepareOptionsMenu(Menu menu) {
        SubMenu dm = menu.findItem(R.id.action_insert_disk).getSubMenu();
        dm.clear();
        dm.setHeaderIcon(R.drawable.disk_floppy_color);
        // add create disk
        dm.add(0, R.id.action_import_file, 0, R.string.menu_select_disk);
        // add disks
        File[] disks = FileManager.getInstance().getAvailableDisks();
        for (int i = 0; disks != null && i < disks.length; i++) {
            String diskName = disks[i].getName();
            MenuItem m = dm.add(R.id.action_insert_disk, diskName.hashCode(), i+1, diskName.substring(0, diskName.lastIndexOf(".")));
            m.setEnabled(mCore == null || !mCore.isDiskInserted(disks[i]));
        }
    }

    public boolean onOptionsItemSelected (MenuItem item) {
        if (item.getGroupId() == R.id.action_insert_disk) {
            File[] disks = FileManager.getInstance().getAvailableDisks();
            for (File disk : disks) {
                if (disk.getName().hashCode() == item.getItemId()) {
                    mCore.insertDisk(disk);
                    return true;
                }
            }
            // disk not found
            return true;
        }
        switch(item.getItemId()) {
            case R.id.action_keyboard:
                toggleKeyboard();
                break;
            case R.id.action_import_file:
                showSelectDisk();
                break;
            case R.id.action_settings:
                showSettings();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showAbout() {
        Dialog dialog = new AboutDialog(getContext());
        dialog.show();
    }

    private final ActivityResultLauncher<String> _importFile = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
        onActivity = false;

        if (uri != null) {
            InputStream diskFile;
            try {
                diskFile = requireActivity().getContentResolver().openInputStream(uri);
            } catch (FileNotFoundException ex) {
                // Unable to open Disk file.
                Log.e(TAG, String.format("Unable to open file: %s", uri), ex);
                return;
            }
            String diskName = FileManager.getInstance().getFileName(uri);
            File dst = FileManager.getInstance().getCacheFile(diskName);
            try {
                FileManager.getInstance().copy(diskFile, dst);
            } catch (IOException ex) {
                // Unable to copy Disk
                Log.e(TAG, String.format("Unable to copy file: %s", uri), ex);
                return;
            }
            dst.setWritable(false);
            mCore.insertDisk(dst);
        } else {
            Log.i(TAG, "No file was selected.");
        }
    });

    public void showSelectDisk() {
        if (FileManager.getInstance().getDisksDir() == null) return;

        onActivity = true;
        _importFile.launch("*/*");
    }

    private final ActivityResultLauncher<Intent> _settings = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        int resultCode = result.getResultCode();

        onActivity = false;

        if (!mEmulatorStarted) {
            initEmulator();
            return;
        }

        switch (resultCode) {
            case SettingsFragment.RESULT_RESET:
                reset();
                break;
            case SettingsFragment.RESULT_INTERRUPT:
                interrupt();
                break;
            case SettingsFragment.RESULT_ABOUT:
                showAbout();
                break;
        }
        requireActivity().invalidateOptionsMenu();
        updateByPrefs();
    });

    public void showSettings() {
        onActivity = true;
        Intent i = new Intent(getActivity(), SettingsActivity.class);
        _settings.launch(i);
        //((MiniVMac)getActivity()).showSettings();
    }

    public void toggleKeyboard() {
        if (mKeyboardView.getVisibility() == View.VISIBLE) {
            mKeyboardView.setVisibility(View.GONE);
            mKeyboardView.setEnabled(false);
        } else {
            mKeyboardView.setVisibility(View.VISIBLE);
            mKeyboardView.setEnabled(true);
        }
    }

    public void reset() {
        mCore.wantMacReset();
    }

    public void interrupt() {
        mCore.wantMacInterrupt();
    }

    class SingleTapGestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent event) {
            return true;
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            if (mUIVisible) {
                hideSystemUI();
            } else {
                showSystemUI();
            }
            return true;
        }
    }
}

