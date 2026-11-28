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
    /** Top application package on the background display; empty while it shows nothing. */
    String displayForeground() = 4;
    /** Background display nodes as JSON. */
    String displayNodes() = 5;
    /** Sets text on the background display's focused field; false when none has focus. */
    boolean displaySetText(String text) = 6;
    /** Injects one single-finger MotionEvent action on the background display. */
    oneway void displayTouch(int action, float x, float y, long downTime) = 7;
    /** Sets text on the phone's own focused field; false when no field takes it. */
    boolean phoneSetText(String text) = 8;
    void destroy() = 16777114;
}
