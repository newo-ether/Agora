package com.newoether.agora.phone;

import android.os.ParcelFileDescriptor;

/** Bounded privileged operations used by Shizuku's user service. */
interface IPhoneUserService {
    ParcelFileDescriptor command(String command) = 0;
    String paste(String text) = 1;
    void destroy() = 16777114;
}
