package dev.hazel.livealarm;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;

public final class AlarmScheduler {
    public static final String BOUNDARY="BOUNDARY", SNOOZE="SNOOZE_FIRE", TEST="TEST_FIRE";
    static int id(String action){return BOUNDARY.equals(action)?201:SNOOZE.equals(action)?202:203;}
    static PendingIntent intent(Context c,String action){return PendingIntent.getBroadcast(c,id(action),new Intent(c,ActionReceiver.class).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    public static boolean exact(Context c){return Build.VERSION.SDK_INT<31||((AlarmManager)c.getSystemService(Context.ALARM_SERVICE)).canScheduleExactAlarms();}
    public static void cancel(Context c,String action){((AlarmManager)c.getSystemService(Context.ALARM_SERVICE)).cancel(intent(c,action));}
    public static void at(Context c,String action,long time){
        AlarmManager a=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);PendingIntent p=intent(c,action);
        try{if(exact(c))a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,p);else a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,p);}
        catch(SecurityException e){a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,p);}
    }
    public static void boundaries(Context context){
        Prefs p=new Prefs(context);cancel(context,BOUNDARY);if(!p.enabled())return;JSONObject c=p.config();
        long next=TimeRules.nextBoundary(System.currentTimeMillis(),c.optBoolean("allDay"),p.windows(c),TimeRules.zone(c.optString("timezone")));
        if(next>0)at(context,BOUNDARY,next);
    }
}
