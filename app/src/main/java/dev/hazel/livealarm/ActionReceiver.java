package dev.hazel.livealarm;

import android.content.*;

public class ActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        String action=intent.getAction();Prefs p=new Prefs(context);
        if("STOP_WATCH".equals(action)){GuardianService.stopWatching(context);p.log("system","已从通知停止守候","取消检测、暂缓和锁屏测试，不会自动重新开启");return;}
        if("CHECK_NOW".equals(action)){if(p.enabled())GuardianService.send(context,"CHECK");return;}
        if("DISMISS".equals(action)||"SNOOZE".equals(action)){if(GuardianService.running)GuardianService.send(context,action);return;}
        if(AlarmScheduler.BOUNDARY.equals(action)){if(p.enabled())GuardianService.send(context,"CHECK");AlarmScheduler.boundaries(context);return;}
        if(AlarmScheduler.SNOOZE.equals(action)){if(p.enabled()&&p.raw().getLong("snoozeAt",0)>0)GuardianService.send(context,action);return;}
        if(AlarmScheduler.TEST.equals(action)&&p.raw().getLong("testAt",0)>0){p.raw().edit().putLong("testAt",0).apply();GuardianService.send(context,"TEST");}
    }
}
