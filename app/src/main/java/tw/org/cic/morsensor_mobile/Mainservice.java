package tw.org.cic.morsensor_mobile;

import android.app.Activity;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

//------------------------------
import com.ftdi.j2xx.D2xxManager;
import com.ftdi.j2xx.FT_Device;

import tw.org.cic.dataManage.DataTransform;
import tw.org.cic.dataManage.MorSensorParameter;

import static tw.org.cic.dataManage.DataTransform.bytesToHexString;
//------------------------------

public class Mainservice extends Service{

    private static String TAG = "Morservice---------";
    // j2xx
    public static D2xxManager ftD2xx = null;
    static FT_Device ftDev;
    int DevCount = -1;
    int currentPortIndex = -1;
    int portIndex = -1;

    // handler event
    final int UPDATE_TEXT_VIEW_CONTENT = 0;
    final int ACT_ZMODEM_AUTO_START_RECEIVE = 21;

    final byte XON = 0x11;    /* Resume transmission */
    final byte XOFF = 0x13;    /* Pause transmission */

    final int MODE_GENERAL_UART = 0;
    final int MODE_X_MODEM_CHECKSUM_RECEIVE = 1;
    final int MODE_X_MODEM_CHECKSUM_SEND = 2;
    final int MODE_X_MODEM_CRC_RECEIVE = 3;
    final int MODE_X_MODEM_CRC_SEND = 4;
    final int MODE_X_MODEM_1K_CRC_RECEIVE = 5;
    final int MODE_X_MODEM_1K_CRC_SEND = 6;
    final int MODE_Y_MODEM_1K_CRC_RECEIVE = 7;
    final int MODE_Y_MODEM_1K_CRC_SEND = 8;
    final int MODE_Z_MODEM_RECEIVE = 9;
    final int MODE_Z_MODEM_SEND = 10;
    final int MODE_SAVE_CONTENT_DATA = 11;

    int transferMode = MODE_GENERAL_UART;
    int tempTransferMode = MODE_GENERAL_UART;

    final byte SOH = 1;    /* Start Of Header */
    final byte STX = 2;    /* Start Of Header 1K */
    final byte EOT = 4;    /* End Of Transmission */
    final byte ACK = 6;    /* ACKnowlege */
    final byte NAK = 0x15; /* Negative AcKnowlege */
    final byte CAN = 0x18; /* Cancel */
    final byte CHAR_C = 0x43; /* Character 'C' */
    final byte CHAR_G = 0x47; /* Character 'G' */


    final int MODEM_BUFFER_SIZE = 2048;
    int[] modemReceiveDataBytes;
    byte[] modemDataBuffer;
    byte[] zmDataBuffer;

    boolean bModemGetNak = false;
    boolean bModemGetAck = false;
    boolean bModemGetCharC = false;
    boolean bModemGetCharG = false;

    // general data count
    int totalReceiveDataBytes = 0;
    int totalUpdateDataBytes = 0;

    // thread to read the data
    HandlerThread handlerThread; // update data to UI
    ReadThread readThread; // read data from USB

    boolean bContentFormatHex = false;

    // variables
//    final int UI_READ_BUFFER_SIZE = 10240; // Notes: 115K:1440B/100ms, 230k:2880B/100ms
    final int UI_READ_BUFFER_SIZE = 10260; // Notes: 115K:1440B/100ms, 230k:2880B/100ms
    static byte[] writeBuffer;
    static byte[] writeBootloaderBuffer;
    byte[] readBuffer;
    char[] readBufferToChar;
    int actualNumBytes;

    int baudRate; /* baud rate */
    byte stopBit; /* 1:1stop bits, 2:2 stop bits */
    byte dataBit; /* 8:8bit, 7: 7bit */
    byte parity; /* 0: none, 1: odd, 2: even, 3: mark, 4: space */
    byte flowControl; /* 0:none, 1: CTS/RTS, 2:DTR/DSR, 3:XOFF/XON */
    public static Context mContext;
    //public static Activity mMainViewActivity;

    // data buffer
    byte[] writeDataBuffer;
    byte[] readDataBuffer; /* circular buffer */

    int iTotalBytes;
    int iReadIndex;

    final int MAX_NUM_BYTES = 65536;

    static boolean bReadTheadEnable = false;


    ImageView imgInfo;
    //static TextView tv_humi, tv_uv, tv_alcohol;
    //static RelativeLayout relativeBG;

    //------------


    @Override
    public IBinder onBind(Intent intent){
        return null;
    }

    @Override
    public void onCreate(){

        try {
            ftD2xx = D2xxManager.getInstance(this);
            ftD2xx.setVIDPID(1027, 515);
        } catch (D2xxManager.D2xxException e) {
            Log.e("FTDI_HT", "getInstance fail!!");
        }

        super.onCreate();
        /*
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main_view);
        */
        mContext = this;
        //mMainViewActivity = this;
        /*
        tv_humi = (TextView) this.findViewById(R.id.tv_humi);
        tv_uv = (TextView) this.findViewById(R.id.tv_uv);
        tv_alcohol = (TextView) this.findViewById(R.id.tv_alcohol);
         */
        // init modem variables
        modemReceiveDataBytes = new int[1];
        modemReceiveDataBytes[0] = 0;
        modemDataBuffer = new byte[MODEM_BUFFER_SIZE];
        zmDataBuffer = new byte[MODEM_BUFFER_SIZE];

        /* allocate buffer */
        writeBuffer = new byte[20];
        writeBootloaderBuffer = new byte[UI_READ_BUFFER_SIZE];
        readBuffer = new byte[UI_READ_BUFFER_SIZE];
        readBufferToChar = new char[UI_READ_BUFFER_SIZE];
        readDataBuffer = new byte[MAX_NUM_BYTES];
        actualNumBytes = 0;

        // start main text area read thread
        //handler will be defined
        handlerThread = new Mainservice.HandlerThread(handler);
        handlerThread.start();

//		/* setup the baud rate list*/
        baudRate = 115200;
        stopBit = 1;
        dataBit = 8;
        parity = 0;
        flowControl = 0;
        portIndex = 0;

    }

    private void checkDevceConnection() {
        if (null == ftDev || false == ftDev.isOpen()) {
            DLog.e(TAG, "checkDeviceConnection first sentence - reconnect - null");
            requestSensorData = false;
            if (ftDev != null)
                DLog.e(TAG, "checkDeviceConnection - reconnect:" + ftDev.isOpen());
            createDeviceList();
            if (DevCount > 0) {
                connectFunction();
//                setUARTInfoString();
                setConfig(baudRate, dataBit, stopBit, parity, flowControl);

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        sendCommand(MorSensorParameter.SEND_MORSENSOR_BLE_SENSOR_DATA_ALL);
                    }
                }).start();
            } else {
            }
        } else {
            DLog.e(TAG, "checkDeviceConnection onResume - reconnect - "+ftDev.isOpen());
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    sendCommand(MorSensorParameter.SEND_MORSENSOR_BLE_SENSOR_DATA_ALL);
                }
            }).start();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId){

        DLog.d(TAG, "onStartCommand");

        createDeviceList();
        if (DevCount > 0) {
            connectFunction();
            setConfig(baudRate, dataBit, stopBit, parity, flowControl);
        }
        else{
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try{
                        while(DevCount<0){
                            checkDevceConnection();
                            Thread.sleep(2500);
                        }

                    }
                    catch(Exception e){
                        Log.v("MS on startcommand","err"+e);
                    }


                }
            }).start();
        }

        //Toast.makeText(mContext, "Service is startoncommand!", Toast.LENGTH_LONG).show();
        Log.v(TAG,"Service is startoncommand");
        return START_NOT_STICKY;
    }
    /*
    protected void onResume() {
        super.onResume();
        DLog.d(TAG, "onResume");
        DLog.e(TAG, "onResume - reconnect");
        checkDevceConnection();
    }
    */
    //check should stopservice exist

    @Override
    public void onDestroy(){
        Log.d(TAG, "--- onDestroy ---");
        disconnectFunction();
        super.onDestroy();
    }

    public static void sendCommand(int sendCommand) {
        try {
            switch (sendCommand) {
                case MorSensorParameter.SEND_MORSENSOR_BLE_SENSOR_DATA_ALL:
                    writeBuffer[0] = (byte) 0xF3;
                    writeBuffer[1] = (byte) 0x00;
                    break;
            }

            sendData(writeBuffer.length, writeBuffer);
            requestSensorData = true;

        } catch (IllegalArgumentException e) {
            // midToast("Incorrect input for HEX format."
            // + "\nAllowed charater: 0~9, a~f and A~F", Toast.LENGTH_SHORT);
            Toast.makeText(mContext, "Incorrect input for HEX format.", Toast.LENGTH_LONG).show();
            DLog.e(TAG, "Illeagal HEX input.");
        }
    }

    /*
    // call this API to show message
    static void midToast(String str, int showTime) {
        Toast toast = Toast.makeText(mContext, str, showTime);
        toast.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL, 0, 0);

        //not sure if it will be displayed
        TextView v = (TextView) toast.getView().findViewById(android.R.id.message);
        toast.show();
    }
    */

    //-----------------
    // j2xx functions +
    public void createDeviceList() {
        Log.d(TAG, "createDeviceList");
        int tempDevCount = ftD2xx.createDeviceInfoList(mContext);

        if (tempDevCount > 0) {
            if (DevCount != tempDevCount) {
                DevCount = tempDevCount;
            }
        } else {
            DevCount = -1;
            currentPortIndex = -1;
        }
        Log.d(TAG, "createDeviceList  DevCount:" + DevCount + "  currentPortIndex:" + currentPortIndex);
    }

    public void disconnectFunction() {
        Log.d(TAG, "disconnectFunction");
        DevCount = -1;
        currentPortIndex = -1;
        bReadTheadEnable = false;
        requestSensorData = false;
//        readThread.interrupt();
//        handlerThread.interrupt();
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        if (ftDev != null) {
            if (ftDev.isOpen()) {
                ftDev.close();
                DLog.d(TAG, "disconnectFunction: close ok.");
            } else {
                DLog.d(TAG, "disconnectFunction: FT is not open.");
            }
        }
    }

    public void connectFunction() {
        Log.d(TAG, "-----------connectFunction-----------");
        if (portIndex + 1 > DevCount) {
            portIndex = 0;
        }

        if (currentPortIndex == portIndex
                && ftDev != null
                && ftDev.isOpen()) {
//            Toast.makeText(mContext, "Port(" + portIndex + ") is already opened.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (bReadTheadEnable) {
            bReadTheadEnable = false;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        if (null == ftDev) {
            ftDev = ftD2xx.openByIndex(mContext, portIndex);
        } else {
            ftDev = ftD2xx.openByIndex(mContext, portIndex);
        }

        if (ftDev == null) {
//            midToast("Open port(" + portIndex + ") NG!", Toast.LENGTH_LONG);
            return;
        }

        if (ftDev.isOpen()) {
            currentPortIndex = portIndex;
//            Toast.makeText(mContext, "open device port(" + portIndex + ") OK", Toast.LENGTH_SHORT).show();

            if (!bReadTheadEnable) {

                readThread = new ReadThread(handler);
                readThread.start();
            }
        } else {
            //midToast("Open port(" + portIndex + ") NG!", Toast.LENGTH_LONG);
            Toast.makeText(mContext, "Open port(" + portIndex + ") NG!", Toast.LENGTH_LONG).show();
            //Toast.makeText(mContext, "Service is startoncommand!", Toast.LENGTH_LONG).show();
        }
    }


    void setConfig(int baud, byte dataBits, byte stopBits, byte parity, byte flowControl) {
        // configure port
        // reset to UART mode for 232 devices
        ftDev.setBitMode((byte) 0, D2xxManager.FT_BITMODE_RESET);

        ftDev.setBaudRate(baud);

        switch (dataBits) {
            case 7:
                dataBits = D2xxManager.FT_DATA_BITS_7;
                break;
            case 8:
                dataBits = D2xxManager.FT_DATA_BITS_8;
                break;
            default:
                dataBits = D2xxManager.FT_DATA_BITS_8;
                break;
        }

        switch (stopBits) {
            case 1:
                stopBits = D2xxManager.FT_STOP_BITS_1;
                break;
            case 2:
                stopBits = D2xxManager.FT_STOP_BITS_2;
                break;
            default:
                stopBits = D2xxManager.FT_STOP_BITS_1;
                break;
        }

        switch (parity) {
            case 0:
                parity = D2xxManager.FT_PARITY_NONE;
                break;
            case 1:
                parity = D2xxManager.FT_PARITY_ODD;
                break;
            case 2:
                parity = D2xxManager.FT_PARITY_EVEN;
                break;
            case 3:
                parity = D2xxManager.FT_PARITY_MARK;
                break;
            case 4:
                parity = D2xxManager.FT_PARITY_SPACE;
                break;
            default:
                parity = D2xxManager.FT_PARITY_NONE;
                break;
        }

        ftDev.setDataCharacteristics(dataBits, stopBits, parity);

        short flowCtrlSeTAGing;
        switch (flowControl) {
            case 0:
                flowCtrlSeTAGing = D2xxManager.FT_FLOW_NONE;
                break;
            case 1:
                flowCtrlSeTAGing = D2xxManager.FT_FLOW_RTS_CTS;
                break;
            case 2:
                flowCtrlSeTAGing = D2xxManager.FT_FLOW_DTR_DSR;
                break;
            case 3:
                flowCtrlSeTAGing = D2xxManager.FT_FLOW_XON_XOFF;
                break;
            default:
                flowCtrlSeTAGing = D2xxManager.FT_FLOW_NONE;
                break;
        }

        ftDev.setFlowControl(flowCtrlSeTAGing, XON, XOFF);
    }

    static void sendData(int numBytes, byte[] buffer) {
        if (!ftDev.isOpen()) {
            DLog.e(TAG, "SendData: device not open");

            /*mMainViewActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(mContext, "Device not open!", Toast.LENGTH_SHORT).show();
                }
            });*/

            Toast.makeText(mContext, "Device not open!", Toast.LENGTH_SHORT).show();

            return;
        }

        if (numBytes > 0) {
            try {
                ftDev.write(buffer, numBytes);
            } catch (Exception e) {
                DLog.e(TAG, "ftDev.write Error");
            }

            DLog.e(TAG, "sendData:" + bytesToHexString(buffer));
        }

    }


    byte readData(int numBytes, byte[] buffer) {
        byte intstatus = 0x00; /* success by default */

        /* should be at least one byte to read */
        if ((numBytes < 1) || (0 == iTotalBytes)) {
            actualNumBytes = 0;
            intstatus = 0x01;
            return intstatus;
        }

        if (numBytes > iTotalBytes) {
            numBytes = iTotalBytes;
        }

        /* update the number of bytes available */
        iTotalBytes -= numBytes;
        actualNumBytes = numBytes;

        /* copy to the user buffer */
        for (int count = 0; count < numBytes; count++) {
            buffer[count] = readDataBuffer[iReadIndex];
            iReadIndex++;
            iReadIndex %= MAX_NUM_BYTES;
        }

        return intstatus;
    }

    static boolean requestSensorData = false;

    final Handler handler = new Handler() {
        public void handleMessage(Message msg) {
            Log.v("Mainservice","handlemessage");
            switch (msg.what) {
                case UPDATE_TEXT_VIEW_CONTENT: //USB Receive Data
                    int rawDataLength = actualNumBytes;
                    Log.d(TAG, "UPDATE_TEXT_VIEW_CONTENT readBuffer:" + readBuffer.length + " actualNumBytes:" + actualNumBytes + " totalUpdateDataBytes:" + totalUpdateDataBytes);
                    if (actualNumBytes > 0) {

                        String data = bytesToHexString(readBuffer).substring(0, rawDataLength * 2);
                        Log.e(TAG, "UPDATE_TEXT_VIEW_CONTENT_120:" + data);
                        totalUpdateDataBytes += actualNumBytes;
                        for (int i = 0; i < actualNumBytes; i++) {
                            readBufferToChar[i] = (char) readBuffer[i];
                        }

                        switch (readBuffer[0]) {
                            case MorSensorParameter.IN_BLE_SENSOR_DATA:
                                if(readBuffer[1] != 0) {
                                    DataTransform.TransformTempHumi(readBuffer);
                                    DataTransform.TransformUV(readBuffer);
                                    DataTransform.TransformAlcohol(readBuffer);

                                    displaySensorData();
                                }
                                break;
                        }
                    }
                    break;
                default:
                    //midToast("NG CASE", Toast.LENGTH_LONG);
                    Toast.makeText(mContext, "NG CASE", Toast.LENGTH_SHORT).show();
                    break;
            }
        }
    };


    // Update UI content
    class HandlerThread extends Thread {
        Handler mHandler;

        HandlerThread(Handler h) {
            mHandler = h;
        }

        public void run() {
            byte status;
            Message msg;

            while (true) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                if (true == bContentFormatHex) // consume input data at hex content format
                {
                    status = readData(UI_READ_BUFFER_SIZE, readBuffer);
                } else if (MODE_GENERAL_UART == transferMode) {
                    status = readData(UI_READ_BUFFER_SIZE, readBuffer);

                    if (0x00 == status) {
                        msg = mHandler.obtainMessage(UPDATE_TEXT_VIEW_CONTENT);
                        mHandler.sendMessage(msg);
                    }
                }
            }
        }
    }

    class ReadThread extends Thread {
        final int USB_DATA_BUFFER = 8192;

        int index=0;

        Handler mHandler;

        ReadThread(Handler h) {
            mHandler = h;
            this.setPriority(MAX_PRIORITY);
        }

        public void run() {
            Log.d(TAG, "ReadThread");
            byte[] usbdata = new byte[USB_DATA_BUFFER];
            int readcount = 0;
            int iWriteIndex = 0;
            bReadTheadEnable = true;

            while (bReadTheadEnable) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                while (iTotalBytes > (MAX_NUM_BYTES - (USB_DATA_BUFFER + 1))) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                //------------
                if(ftDev==null){
                    Log.v("readthread","ftdev is null");
                }
                if(null==ftDev){
                    Log.v("readthreadnull","ftdev is null");
                }
                //------------
                readcount = ftDev.getQueueStatus();
                //Log.e(">>@@","iavailable:" + iavailable);
                if (readcount > 0) {
                    Log.d(TAG, "readcount:" + readcount);
                    if (readcount > USB_DATA_BUFFER) {
                        readcount = USB_DATA_BUFFER;
                    }
                    ftDev.read(usbdata, readcount);

                    if ((MODE_X_MODEM_CHECKSUM_SEND == transferMode)
                            || (MODE_X_MODEM_CRC_SEND == transferMode)
                            || (MODE_X_MODEM_1K_CRC_SEND == transferMode)) {
                        Log.d(TAG, "transferMode:" + transferMode);
                        for (int i = 0; i < readcount; i++) {
                            modemDataBuffer[i] = usbdata[i];
                            DLog.e(TAG, "RT usbdata[" + i + "]:(" + usbdata[i] + ")");
                        }

                        if (NAK == modemDataBuffer[0]) {
                            DLog.e(TAG, "get response - NAK");
                            bModemGetNak = true;
                        } else if (ACK == modemDataBuffer[0]) {
                            DLog.e(TAG, "get response - ACK");
                            bModemGetAck = true;
                        } else if (CHAR_C == modemDataBuffer[0]) {
                            DLog.e(TAG, "get response - CHAR_C");
                            bModemGetCharC = true;
                        }
                        if (CHAR_G == modemDataBuffer[0]) {
                            DLog.e(TAG, "get response - CHAR_G");
                            bModemGetCharG = true;
                        }
                    } else {
                        Log.d(TAG, "transferMode:" + transferMode);
                        totalReceiveDataBytes += readcount;
                        //DLog.e(TAG,"totalReceiveDataBytes:"+totalReceiveDataBytes);

                        //DLog.e(TAG,"readcount:"+readcount);
                        for (int count = 0; count < readcount; count++) {
                            readDataBuffer[iWriteIndex] = usbdata[count];
                            iWriteIndex++;
                            iWriteIndex %= MAX_NUM_BYTES;
                        }

                        if (iWriteIndex >= iReadIndex) {
                            iTotalBytes = iWriteIndex - iReadIndex;
                        } else {
                            iTotalBytes = (MAX_NUM_BYTES - iReadIndex) + iWriteIndex;
                        }

                        //DLog.e(TAG,"iTotalBytes:"+iTotalBytes);
                        if ((MODE_X_MODEM_CHECKSUM_RECEIVE == transferMode)
                                || (MODE_X_MODEM_CRC_RECEIVE == transferMode)
                                || (MODE_X_MODEM_1K_CRC_RECEIVE == transferMode)
                                || (MODE_Y_MODEM_1K_CRC_RECEIVE == transferMode)
                                || (MODE_Z_MODEM_RECEIVE == transferMode)
                                || (MODE_Z_MODEM_SEND == transferMode)) {
                            modemReceiveDataBytes[0] += readcount;
                            DLog.e(TAG, "modemReceiveDataBytes:" + modemReceiveDataBytes[0]);
                        }
                    }
                }
            }
            DLog.e(TAG, "read thread terminate...");
        }
    }


    public static void displaySensorData() {
        float[] data = DataTransform.getData();
        Log.i(TAG, "Humi:" + data[1] + " UV:" + data[2] + " Alcohol:" + data[3]);
        MorSensorParameter.humi_data = (int) data[1];
        MorSensorParameter.uv_data = ((int) (data[2] * 100) / 100f);
        MorSensorParameter.alcohol_data = ((int) (data[3] * 1000) / 1000f);
        //tv_humi.setText("Humidity: " + MorSensorParameter.humi_data);
        //tv_uv.setText("UV: " + MorSensorParameter.uv_data);
        //tv_alcohol.setText("Alcohol: " + MorSensorParameter.alcohol_data);//((int) (data[3] * 1000) / 1000f) + "\n" + ((int) (data[4] * 1000) / 1000f)
        Log.i(TAG, "Humi:" + data[1] + " UV:" + data[2] + " Alcohol:" + data[3]);

    }

}

