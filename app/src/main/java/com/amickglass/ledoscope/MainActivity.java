package com.amickglass.ledoscope;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAdapter.LeScanCallback;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.view.MotionEventCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

//bluetooth le stuff


public class MainActivity extends AppCompatActivity {
    // UUIDs for UAT service and associated characteristics.
    public static UUID UART_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    public static UUID TX_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    public static UUID RX_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    // UUID for the BTLE client characteristic which is necessary for notifications.
    public static UUID CLIENT_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int PERMISSION_REQUEST_COARSE_LOCATION = 456;

    private static final String DEBUG_TAG = "MyActivity";


    // UI elements
    private TextView messages;

    private EditText input;

    // BTLE state
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic tx;
    private BluetoothGattCharacteristic rx;

    // Main BTLE device callback where much of the logic occurs.
    private BluetoothGattCallback callback = new BluetoothGattCallback() {
        // Called whenever the device connection state changes, i.e. from disconnected to connected.
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                writeLine("Connected!");
                // Discover services.
                if (!gatt.discoverServices()) {
                    writeLine("Failed to start discovering services!");
                }
            }
            else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                writeLine("Disconnected!");
            }
            else {
                writeLine("Connection state changed.  New state: " + newState);
            }
        }

        // Called when services have been discovered on the remote device.
        // It seems to be necessary to wait for this discovery to occur before
        // manipulating any services or characteristics.
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                writeLine("Service discovery completed!");
            }
            else {
                writeLine("Service discovery failed with status: " + status);
            }
            // Save reference to each characteristic.
            tx = gatt.getService(UART_UUID).getCharacteristic(TX_UUID);
            rx = gatt.getService(UART_UUID).getCharacteristic(RX_UUID);
            // Setup notifications on RX characteristic changes (i.e. data received).
            // First call setCharacteristicNotification to enable notification.
            if (!gatt.setCharacteristicNotification(rx, true)) {
                writeLine("Couldn't set notifications for RX characteristic!");
            }
            // Next update the RX characteristic's client descriptor to enable notifications.
            if (rx.getDescriptor(CLIENT_UUID) != null) {
                BluetoothGattDescriptor desc = rx.getDescriptor(CLIENT_UUID);
                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (!gatt.writeDescriptor(desc)) {
                    writeLine("Couldn't write RX client descriptor value!");
                }
            }
            else {
                writeLine("Couldn't get RX client descriptor!");
            }
        }

        // Called when a remote characteristic changes (like the RX characteristic).
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            writeLine("Received: " + characteristic.getStringValue(0));
        }
    };

    // BTLE device scanning callback.
    private LeScanCallback scanCallback = new LeScanCallback() {
        // Called when a device is found.
        @Override
        public void onLeScan(BluetoothDevice bluetoothDevice, int i, byte[] bytes) {
            writeLine("Found device: " + bluetoothDevice.getAddress());
            // Check if the device has the UART service.
            if (parseUUIDs(bytes).contains(UART_UUID)) {
                // Found a device, stop the scan.
                adapter.stopLeScan(scanCallback);
                writeLine("Found UART service!");
                // Connect to the device.
                // Control flow will now go to the callback functions when BTLE events occur.
                gatt = bluetoothDevice.connectGatt(getApplicationContext(), false, callback);
            }
        }
    };

    // OnCreate, called once to initialize the activity.
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_COARSE_LOCATION);
        }

        // Grab references to UI elements.
        messages = (TextView) findViewById(R.id.messages);

        //Commented out to remove Edit Text thing
        // input = (EditText) findViewById(R.id.input);

        adapter = BluetoothAdapter.getDefaultAdapter();
    }

    // OnResume, called right before UI is displayed.  Start the BTLE connection.
    @Override
    protected void onResume() {
        super.onResume();
        // Scan for all BTLE devices.
        // The first one with the UART service will be chosen--see the code in the scanCallback.
        writeLine("Scanning for devices...");
        adapter.startLeScan(scanCallback);
    }

    // OnStop, called right before the activity loses foreground focus.  Close the BTLE connection.
    @Override
    protected void onStop() {
        super.onStop();
        disconnect();
    }

    //Function to close BTLE connection
    public void disconnect() {
        if (gatt != null) {
            // For better reliability be careful to disconnect and close the connection.
            gatt.disconnect();
            gatt.close();
            gatt = null;
            tx = null;
            rx = null;
        }
    }

    // Handler for mouse click on the send button.
    public void sendClick(View view) {
        String message = input.getText().toString();
        if (tx == null || message == null || message.isEmpty()) {
            // Do nothing if there is no device or message to send.
            return;
        }
        // Update TX characteristic value.  Note the setValue overload that takes a byte array must be used.
        tx.setValue(message.getBytes(Charset.forName("UTF-8")));
        if (gatt.writeCharacteristic(tx)) {
            writeLine("Sent: " + message);
        }
        else {
            writeLine("Couldn't write TX characteristic!");
        }
    }

    // Write some text to the messages text view.
    // Care is taken to do this on the main UI thread so writeLine can be called
    // from any thread (like the BTLE callback).
    private void writeLine(final CharSequence text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                messages.append(text);
                messages.append("\n");
            }
        });
    }

    // Filtering by custom UUID is broken in Android 4.3 and 4.4, see:
    //   http://stackoverflow.com/questions/18019161/startlescan-with-128-bit-uuids-doesnt-work-on-native-android-ble-implementation?noredirect=1#comment27879874_18019161
    // This is a workaround function from the SO thread to manually parse advertisement data.
    private List<UUID> parseUUIDs(final byte[] advertisedData) {
        List<UUID> uuids = new ArrayList<UUID>();

        int offset = 0;
        while (offset < (advertisedData.length - 2)) {
            int len = advertisedData[offset++];
            if (len == 0)
                break;

            int type = advertisedData[offset++];
            switch (type) {
                case 0x02: // Partial list of 16-bit UUIDs
                case 0x03: // Complete list of 16-bit UUIDs
                    while (len > 1) {
                        int uuid16 = advertisedData[offset++];
                        uuid16 += (advertisedData[offset++] << 8);
                        len -= 2;
                        uuids.add(UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", uuid16)));
                    }
                    break;
                case 0x06:// Partial list of 128-bit UUIDs
                case 0x07:// Complete list of 128-bit UUIDs
                    // Loop through the advertised 128-bit UUID's.
                    while (len >= 16) {
                        try {
                            // Wrap the advertised bits and order them.
                            ByteBuffer buffer = ByteBuffer.wrap(advertisedData, offset++, 16).order(ByteOrder.LITTLE_ENDIAN);
                            long mostSignificantBit = buffer.getLong();
                            long leastSignificantBit = buffer.getLong();
                            uuids.add(new UUID(leastSignificantBit,
                                    mostSignificantBit));
                        } catch (IndexOutOfBoundsException e) {
                            // Defensive programming.
                            //Log.e(LOG_TAG, e.toString());
                            continue;
                        } finally {
                            // Move the offset to read the next uuid.
                            offset += 15;
                            len -= 16;
                        }
                    }
                    break;
                default:
                    offset += (len - 1);
                    break;
            }
        }
        return uuids;
    }

    // Boilerplate code from the activity creation:

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.

        switch (item.getItemId()) {
            case R.id.action_scan:
                writeLine("Scanning for devices...");
                adapter.startLeScan(scanCallback);
                return true;

            case R.id.action_stop_scan:
                writeLine("Stopping scan...");
                adapter.stopLeScan(scanCallback);
                return true;
            case R.id.action_disconnect:
                writeLine("Disconnecting...");
                disconnect();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_REQUEST_COARSE_LOCATION: {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted, yay! Start the Bluetooth device scan.
                } else {
                    // Alert the user that this application requires the location permission to perform the scan.
                }
            }
        }
    }

    public boolean onTouchEvent(MotionEvent event){

        int action = MotionEventCompat.getActionMasked(event);

        float x = event.getX();
        float y = event.getY();

        switch(action) {
            case (MotionEvent.ACTION_MOVE) :
               // Log.d(DEBUG_TAG,"x: " + String.valueOf(x));
               // Log.d(DEBUG_TAG,"y: " + String.valueOf(y));
                getRotation(event);
                return true;
            case (MotionEvent.ACTION_CANCEL) :
                Log.d(DEBUG_TAG,"Action was CANCEL");
                return true;
            case (MotionEvent.ACTION_OUTSIDE) :
                Log.d(DEBUG_TAG,"Movement occurred outside bounds " +
                        "of current screen element");
                return true;
            default :
                return super.onTouchEvent(event);
        }

    }

    public void writeBTLE(String value) {

        if (tx == null ) {
            // Do nothing if there is no device or message to send.
            return;
        }
        // Update TX characteristic value.  Note the setValue overload that takes a byte array must be used.
        tx.setValue( value);

        if (gatt.writeCharacteristic(tx)) {
            //writeLine(value);
        }
        else {
            writeLine("Couldn't write TX characteristic!");
        }
    }

    public void getRotation(MotionEvent event){

        //center
        int yC = 1400;
        int xC = 700;

        if(event.getAction() == MotionEvent.ACTION_MOVE) {

            int action = MotionEventCompat.getActionMasked(event);

            int historySize = event.getHistorySize();

            int x = Math.round(event.getX());
            int y = Math.round(event.getY());

            if(historySize > 1){
                int xH = Math.round(event.getHistoricalX(historySize - 1));
                int yH = Math.round(event.getHistoricalY(historySize - 1));

                int isClockwise = isClockwise( xC, yC,  xH, yH, x, y);
                if(isClockwise != 0) {

                    if (isClockwise < 0) {
                        writeBTLE("1");
                       // Log.d(DEBUG_TAG, "D: CounterClockwise x: " + String.valueOf(x) + " y: " + String.valueOf(y) + " " + String.valueOf(isClockwise));
                        writeLine("D: CounterClockwise x: " + String.valueOf(x) + " y: " + String.valueOf(y) + " " + String.valueOf(isClockwise));

                    } else {
                        writeBTLE("2");
                        writeLine( "D: Clockwise x: " + String.valueOf(x) + " y: " + String.valueOf(y) + " " + String.valueOf(isClockwise));
                    }
                }
            }

        }

    }

    public int isClockwise(int xC, int yC, int xH, int yH, int x, int y) {

        int isClockwise = ((xH - xC)*(y - yC) - (yH - yC)*(x - xC));

        return isClockwise;

    }



}



