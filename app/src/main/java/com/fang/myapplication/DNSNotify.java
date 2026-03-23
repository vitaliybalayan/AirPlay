package com.fang.myapplication;

import android.util.Log;

import com.apple.dnssd.DNSSD;
import com.apple.dnssd.DNSSDRegistration;
import com.apple.dnssd.DNSSDService;
import com.apple.dnssd.RegisterListener;
import com.apple.dnssd.TXTRecord;

import java.util.concurrent.locks.ReentrantLock;

public class DNSNotify {

    public static String TAG = "DNSNotify";

    private Register mAirplayRegister;
    private Register mRaopRegister;
    private String mDeviceName;
    private String mMacAddress;
    private String mPublicKey;

    // Listener for forwarding logs to UI
    public interface LogListener {
        void onLog(String message);
    }
    private LogListener mLogListener;

    public DNSNotify(String deviceName, String macAddress, String publicKey) {
        mMacAddress = macAddress;
        mDeviceName = deviceName;
        mPublicKey = publicKey;
    }

    public void setLogListener(LogListener listener) {
        mLogListener = listener;
    }

    private void log(String msg) {
        Log.d(TAG, msg);
        if (mLogListener != null) {
            mLogListener.onLog("[mDNS] " + msg);
        }
    }

    public String getDeviceName() {
        return mDeviceName;
    }

    public void registerAirplay(int port) {
        log("registerAirplay port=" + port + " mac=" + mMacAddress + " pk=" + mPublicKey.substring(0, Math.min(16, mPublicKey.length())) + "...");
        TXTRecord txtRecord = new TXTRecord();
        txtRecord.set("deviceid", mMacAddress);
        txtRecord.set("features", "0x5A7FFFF7,0x1E");
        txtRecord.set("srcvers", "220.68");
        txtRecord.set("flags", "0x4");
        txtRecord.set("vv", "2");
        txtRecord.set("model", "AppleTV2,1");
        txtRecord.set("pw", "false");
        txtRecord.set("rhd", "5.6.0.0");
        txtRecord.set("pk", mPublicKey);
        txtRecord.set("pi", "2e388006-13ba-4041-9a67-25dd4a43d536");
        this.mAirplayRegister = new Register(txtRecord, this.mDeviceName, "_airplay._tcp", "local.", "", port);
    }

    public void registerRaop(int port) {
        log("registerRaop port=" + port);
        TXTRecord txtRecord = new TXTRecord();
        txtRecord.set("ch", "2");
        txtRecord.set("cn", "0,1,2,3");
        txtRecord.set("da", "true");
        txtRecord.set("et", "0,3,5");
        txtRecord.set("vv", "2");
        txtRecord.set("ft", "0x5A7FFFF7,0x1E");
        txtRecord.set("am", "AppleTV2,1");
        txtRecord.set("md", "0,1,2");
        txtRecord.set("rhd", "5.6.0.0");
        txtRecord.set("pw", "false");
        txtRecord.set("sr", "44100");
        txtRecord.set("ss", "16");
        txtRecord.set("sv", "false");
        txtRecord.set("tp", "UDP");
        txtRecord.set("txtvers", "1");
        txtRecord.set("sf", "0x4");
        txtRecord.set("vs", "220.68");
        txtRecord.set("vn", "65537");
        txtRecord.set("pk", mPublicKey);
        this.mRaopRegister = new Register(txtRecord, mMacAddress.replace(":", "") + "@" + this.mDeviceName, "_raop._tcp", "local.", "", port);
    }

    public void stop() {
        if (mAirplayRegister != null) {
            mAirplayRegister.stop();
        }
        if (mRaopRegister != null) {
            mRaopRegister.stop();
        }
    }

    class Register implements RegisterListener {
        protected DNSSDRegistration mDNSSDRegistration = null;
        private final ReentrantLock synlock = new ReentrantLock();

        public Register(TXTRecord txtRecord, String serviceName, String regType, String domain, String host, int port) {
            this.synlock.lock();
            try {
                this.mDNSSDRegistration = DNSSD.register(0, 0, serviceName, regType, domain, host, port, txtRecord, this);
            } catch (Throwable e) {
                log("DNSSD.register FAILED: " + e.getMessage());
                e.printStackTrace();
            } finally {
                this.synlock.unlock();
            }
        }

        @Override
        public void serviceRegistered(DNSSDRegistration registration, int flags, String serviceName, String regType, String domain) {
            log("Registered OK: " + serviceName + " " + regType + " " + domain);
        }

        @Override
        public void operationFailed(DNSSDService service, int errorCode) {
            log("DNSSD operationFailed: " + errorCode);
        }

        public void stop() {
            this.synlock.lock();
            if (this.mDNSSDRegistration != null) {
                this.mDNSSDRegistration.stop();
                this.mDNSSDRegistration = null;
            }
            this.synlock.unlock();
        }
    }
}
