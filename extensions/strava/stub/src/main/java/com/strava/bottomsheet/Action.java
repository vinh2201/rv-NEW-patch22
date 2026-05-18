package com.strava.bottomsheet;

import android.os.Parcel;
import android.os.Parcelable;
import android.view.View;
import java.io.Serializable;

public class Action extends BottomSheetItem {
    public static final Parcelable.Creator<Action> CREATOR = null;
    public final int f77785d;
    public final String f77786e;
    public final int f77787f;
    public int f77788w;
    public final int f77789x;
    public final int f77790y;
    public final Serializable f77791z;

    public Action(int i10, String str, int i11, int i12, int i13, int i14, Serializable serializable) {
        super(i10, true);
        this.f77785d = i10;
        this.f77786e = str;
        this.f77787f = i11;
        this.f77788w = i12;
        this.f77789x = i13;
        this.f77790y = i14;
        this.f77791z = serializable;
    }

    @Override
    public int getF77785d() { return f77785d; }

    @Override
    public int mo22731b() { return 0; }

    @Override
    public void mo22733f(View view) {}

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {}
}
