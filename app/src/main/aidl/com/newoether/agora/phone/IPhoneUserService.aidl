package com.newoether.agora.phone;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/** Bounded privileged operations used by Shizuku's user service. */
interface IPhoneUserService {
    ParcelFileDescriptor command(String command) = 0;
    String paste(String text) = 1;
    /** Creates the one background display on request["surface"]; returns its display id. */
    int openDisplay(in Bundle request) = 2;
    oneway void closeDisplay() = 3;
    void destroy() = 16777114;
}
