package com.strava.bottomsheet;

import android.os.Parcel;
import android.os.Parcelable;
import android.view.View;

public abstract class BottomSheetItem implements Parcelable {
    public static final Parcelable.Creator<BottomSheetItem> CREATOR = null;
    public BottomSheetItem(int i, boolean b) {}
    
    public abstract int getF77785d();
    public abstract int mo22731b();
    public abstract void mo22733f(View view);
}
