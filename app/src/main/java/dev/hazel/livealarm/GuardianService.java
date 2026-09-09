package dev.hazel.livealarm;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.net.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;

public class GuardianService extends Service {
    public static final String WATCH_CHANNEL="hazel_watch_v1", ALARM_CHANNEL="hazel_alarm_v1";
    public static final int WATCH_ID=101, ALARM_ID=102;
    public static volatile boolean running=false, ringing=false, overlayVisible=false;
    private static java.lang.ref.WeakReference<GuardianService> active=new java.lang.ref.WeakReference<>(null);
    private AlarmOverlay overlay;
    static void hideAlarmOverlay(){GuardianService service=active.get();if(service!=null&&service.overlay!=null)service.overlay.hide();}
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private Prefs prefs;private NotificationManager notifications;private AudioManager audio;
    private PowerManager.WakeLock watchLock, soundLock;
    private MediaPlayer player;private Vibrator vibrator;private AudioFocusRequest focus;
    private ConnectivityManager connectivity;private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver notificationChanges;
    private String watchDetail="正在准备守候",lastNoticeKey="";
    private boolean busy=false, destroyed=false, testing=false, snoozeCheck=false;
    private int failures=0,generation=0;private long soundBegan=0;private String soundTitle="";
    private final Runnable poll=()->check(false);
    private final Runnable stopSound=()->finishAlarm("timeout",false);
    private final Runnable heartbeat=new Runnable(){@Override public void run(){
        if(destroyed)return;
        prefs.raw().edit().putLong("serviceHeartbeatAt",System.currentTimeMillis()).apply();
        syncPower();refreshNotices(false);
        handler.postDelayed(this,30000);
    }};
    private final Runnable ramp=new Runnable(){@Override public void run(){
        if(!ringing||player==null)return;
        float v=prefs.config().optBoolean("ramp")?Math.min(1f,0.15f+(SystemClock.elapsedRealtime()-soundBegan)/5000f):1f;
        try{player.setVolume(v,v);}catch(Exception ignored){}
        if(v<1f)handler.postDelayed(this,200);
    }};

    public static boolean send(Context c,String action){
        if("STOP_WATCH".equals(action)){stopWatching(c);return true;}
        try{c.startForegroundService(new Intent(c,GuardianService.class).setAction(action));return true;}
        catch(RuntimeException e){Prefs p=new Prefs(c);p.raw().edit().putString("serviceError","后台启动被系统限制，请打开应用重新开启守候").apply();p.log("warning","守候未能启动","请打开应用，并检查电池和自启动设置");return false;}
    }
    /** Explicit stop never starts a new foreground service merely to stop it. */
    public static void stopWatching(Context c){
        Prefs p=new Prefs(c);p.setEnabled(false);
        p.raw().edit().putLong("testAt",0).putLong("snoozeAt",0).putString("snoozeSession","").putLong("nextCheck",0).apply();
        AlarmScheduler.cancel(c,AlarmScheduler.BOUNDARY);AlarmScheduler.cancel(c,AlarmScheduler.SNOOZE);AlarmScheduler.cancel(c,AlarmScheduler.TEST);
        c.stopService(new Intent(c,GuardianService.class));
    }
    /** Refresh only a service that already exists; permission checks do not start monitoring. */
    static void refreshNotifications(){GuardianService s=active.get();if(s!=null)s.handler.post(()->s.refreshNotices(false));}
    public static void channels(Context c){
        NotificationManager n=(NotificationManager)c.getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel watch=new NotificationChannel(WATCH_CHANNEL,"后台守候状态",NotificationManager.IMPORTANCE_LOW);
        watch.setDescription("持续显示直播监测、最近检查和响铃状态；可直接停止守候");watch.setSound(null,null);watch.setShowBadge(false);watch.enableVibration(false);n.createNotificationChannel(watch);
        NotificationChannel alarm=new NotificationChannel(ALARM_CHANNEL,"开播强提醒",NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription("开播与测试闹铃；声音由应用内的铃声设置控制");alarm.setSound(null,null);alarm.enableVibration(false);alarm.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);n.createNotificationChannel(alarm);
    }
    @Override public void onCreate(){
        super.onCreate();prefs=new Prefs(this);notifications=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        channels(this);
        try{foreground(WATCH_ID,watchNotification("正在准备守候"));}
        catch(RuntimeException e){
            // A temporary system start failure must not erase the user's watch preference.
            destroyed=true;running=false;
            prefs.raw().edit().putString("serviceError","系统未允许启动守候，请打开应用检查后台权限").apply();
            prefs.log("warning","前台守候启动失败",e.getClass().getSimpleName());stopSelf();return;
        }
        running=true;active=new java.lang.ref.WeakReference<>(this);overlay=new AlarmOverlay(this,prefs);
        prefs.raw().edit().putString("serviceError","").putLong("serviceStartedAt",System.currentTimeMillis()).putLong("serviceHeartbeatAt",System.currentTimeMillis()).apply();
        PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
        watchLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Hazel:Watch");watchLock.setReferenceCounted(false);
        soundLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Hazel:Ring");soundLock.setReferenceCounted(false);
        vibrator=(Vibrator)getSystemService(VIBRATOR_SERVICE);recoverVolume();
        connectivity=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        networkCallback=new ConnectivityManager.NetworkCallback(){@Override public void onAvailable(Network network){handler.post(()->{if(prefs.enabled()){failures=0;check(false);}});}};
        try{connectivity.registerDefaultNetworkCallback(networkCallback);}catch(Exception ignored){}
        notificationChanges=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
            NotificationAccess.record(c,prefs,"notification_setting_broadcast",true);refreshNotices(true);
        }};
        IntentFilter filter=new IntentFilter(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED);
        filter.addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED);
        filter.addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED);
        try{if(Build.VERSION.SDK_INT>=33)registerReceiver(notificationChanges,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(notificationChanges,filter);}
        catch(RuntimeException e){notificationChanges=null;prefs.log("warning","通知状态监听不可用","返回应用及守候检查时仍会重新读取权限");}
        handler.postDelayed(heartbeat,30000);
    }
    private void foreground(int id,Notification n){if(Build.VERSION.SDK_INT>=34)startForeground(id,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(id,n);}
    private PendingIntent activity(Class<?> cls,int request){
        Intent i=new Intent(this,cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if(cls==AlarmActivity.class)i.putExtra("alarm",true);
        if(Build.VERSION.SDK_INT>=35){ActivityOptions o=ActivityOptions.makeBasic();o.setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);return PendingIntent.getActivity(this,request,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE,o.toBundle());}
        return PendingIntent.getActivity(this,request,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private PendingIntent action(String name,int id){return PendingIntent.getBroadcast(this,id,new Intent(this,ActionReceiver.class).setAction(name),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private Notification watchNotification(String detail){
        String title=ringing?(testing?"铃声测试中":"开播响铃中"):prefs.enabled()?"正在守候灰泽满":"正在准备铃声测试";
        java.time.ZoneId zone=TimeRules.zone(prefs.config().optString("timezone"));
        long checked=prefs.raw().getLong("lastSuccess",0);
        String latest=checked>0?java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(zone).format(java.time.Instant.ofEpochMilli(checked)):"尚未成功检测";
        String body=detail+(prefs.enabled()?"\n最近检测："+latest+" · "+zone.getId():"");
        Notification.Builder b=new Notification.Builder(this,WATCH_CHANNEL).setSmallIcon(R.drawable.ic_bell).setColor(0xff536b81)
            .setContentTitle("满区闹钟 · "+title).setContentText(detail).setStyle(new Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_SERVICE).setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true).setShowWhen(false)
            .setContentIntent(activity(ringing?AlarmActivity.class:MainActivity.class,1));
        if(ringing){
            b.addAction(new Notification.Action.Builder(null,"关闭响铃",action("DISMISS",11)).build());
            if(!testing)b.addAction(new Notification.Action.Builder(null,"稍后提醒",action("SNOOZE",12)).build());
        }else if(prefs.enabled())b.addAction(new Notification.Action.Builder(null,"立即检查",action("CHECK_NOW",13)).build());
        b.addAction(new Notification.Action.Builder(null,prefs.enabled()?"停止守候":"结束测试",action("STOP_WATCH",10)).build());
        return b.build();
    }
    private Notification alarmNotification(){
        Notification.Builder b=new Notification.Builder(this,ALARM_CHANNEL).setSmallIcon(R.drawable.ic_bell).setColor(0xff536b81)
            .setContentTitle(testing?"响铃测试 · 满区闹钟":"灰泽满 Hazel 开播了！").setContentText(soundTitle)
            .setStyle(new Notification.BigTextStyle().bigText(soundTitle)).setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true)
            .setContentIntent(activity(AlarmActivity.class,2));
        if(Build.VERSION.SDK_INT<34||notifications.canUseFullScreenIntent())b.setFullScreenIntent(activity(AlarmActivity.class,2),true);
        b.addAction(new Notification.Action.Builder(null,"关闭响铃",action("DISMISS",11)).build());
        if(!testing)b.addAction(new Notification.Action.Builder(null,prefs.config().optInt("snoozeMinutes",5)+" 分钟后再提醒",action("SNOOZE",12)).build());
        return b.build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(destroyed)return START_NOT_STICKY;
        String action=intent==null?"CHECK":intent.getAction();if(action==null)action="CHECK";
        if(intent==null)prefs.log("system","系统恢复前台守候","重新读取保存的开关和时段，不恢复过期响铃");
        if("STOP_WATCH".equals(action)){
            stopWatching(this);return START_NOT_STICKY;
        }
        if("DISMISS".equals(action)){finishAlarm("dismiss",false);return prefs.enabled()?START_STICKY:START_NOT_STICKY;}
        if("SNOOZE".equals(action)){snoozeAlarm();return prefs.enabled()?START_STICKY:START_NOT_STICKY;}
        if("TEST".equals(action)){cancelTest();beginAlarm("请确认锁屏、音量和振动是否符合预期",true);if(prefs.enabled()){AlarmScheduler.boundaries(this);check(false);}return prefs.enabled()?START_STICKY:START_NOT_STICKY;}
        if("CANCEL_TEST".equals(action)){cancelTest();if(testing)finishAlarm("dismiss",false);if(!prefs.enabled()&&!ringing)stopSelf();return prefs.enabled()?START_STICKY:START_NOT_STICKY;}
        if("SNOOZE_FIRE".equals(action)){snoozeCheck=true;check(true);return prefs.enabled()?START_STICKY:START_NOT_STICKY;}
        if(prefs.enabled()){syncPower();AlarmScheduler.boundaries(this);refreshNotices(false);check(false);return START_STICKY;}
        if(!ringing)stopSelf();return START_NOT_STICKY;
    }
    private void snoozeAlarm(){
        if(ringing&&!testing){
            long when=System.currentTimeMillis()+prefs.config().optInt("snoozeMinutes",5)*60000L;
            prefs.raw().edit().putLong("snoozeAt",when).putString("snoozeSession",prefs.gate().session).commit();
            AlarmScheduler.at(this,AlarmScheduler.SNOOZE,when);
            prefs.log("snooze","已暂缓提醒","到时会再次确认正在直播且处于提醒时段");finishAlarm("snooze",true);
        }
    }
    private void updateWatch(String message){
        watchDetail=message;refreshNotices(false);
    }
    private void refreshNotices(boolean force){
        if(destroyed)return;
        boolean canPost=NotificationAccess.runtimeGranted(this)&&notifications.areNotificationsEnabled();
        NotificationChannel watch=notifications.getNotificationChannel(WATCH_CHANNEL),alarm=notifications.getNotificationChannel(ALARM_CHANNEL);
        String detail=ringing?(testing?"铃声测试中 · 可在这里关闭":"检测到灰泽满开播 · 可关闭或稍后提醒"):watchDetail;
        String key=canPost+"/"+(watch==null?-1:watch.getImportance())+"/"+(alarm==null?-1:alarm.getImportance())+"/"+ringing+"/"+prefs.enabled()+"/"+detail+"/"+prefs.raw().getLong("lastSuccess",0)+"/"+prefs.config().optString("timezone");
        if(!force&&key.equals(lastNoticeKey))return;
        lastNoticeKey=key;
        NotificationAccess.record(this,prefs,"service_notification_refresh",false);
        // WATCH_ID remains the foreground-service notification for its whole lifetime.
        // Never create new channels or alter user permission choices to restore visibility.
        try{
            notifications.notify(WATCH_ID,watchNotification(detail));
            if(ringing&&NotificationAccess.alertMode(this,prefs)==AlertPolicy.Mode.NORMAL)notifications.notify(ALARM_ID,alarmNotification());
            else notifications.cancel(ALARM_ID);
            prefs.raw().edit().putLong("watchNotificationSubmittedAt",System.currentTimeMillis()).apply();
        }catch(RuntimeException e){lastNoticeKey="";prefs.log("warning","守候通知更新未成功","检测仍继续，请检查系统通知设置");}
    }
    private void syncPower(){
        boolean hold=prefs.enabled()&&prefs.allowed(System.currentTimeMillis())&&prefs.config().optBoolean("reliable");
        if(hold){watchLock.acquire(600000);}else release(watchLock);
    }
    private boolean hasNetwork(){try{Network n=connectivity.getActiveNetwork();NetworkCapabilities caps=connectivity.getNetworkCapabilities(n);return caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);}catch(Exception e){return true;}}
    private void check(boolean snooze){
        if(destroyed||!prefs.enabled()){if(!ringing)stopSelf();return;}
        if(snooze)snoozeCheck=true;if(busy)return;
        handler.removeCallbacks(poll);syncPower();
        if(!hasNetwork()){failed("网络未连接，联网后会自动重试",false);return;}
        busy=true;final int expected=generation;
        io.execute(()->{
            PowerManager.WakeLock brief=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Hazel:Check");
            try{brief.acquire(25000);BiliApi.Snapshot s=new BiliApi().fetch();handler.post(()->{busy=false;if(destroyed||expected!=generation||!prefs.enabled())return;received(s);});}
            catch(Exception e){String message=e instanceof java.net.SocketTimeoutException?"网络连接超时，稍后自动重试":e.getMessage();boolean limited=e instanceof BiliApi.ApiException&&((BiliApi.ApiException)e).rateLimited;handler.post(()->{busy=false;if(destroyed||expected!=generation||!prefs.enabled())return;failed(message==null?"暂时无法连接 B 站":message,limited);});}
            finally{release(brief);}
        });
    }
    private void received(BiliApi.Snapshot s){
        if(failures>0)prefs.log("system","连接已恢复","已重新获取直播状态");failures=0;
        JSONObject previous=Prefs.obj(prefs.raw().getString("snapshot","{}"));if(s.title.isEmpty())s.title=previous.optString("title","灰泽满的直播间");
        prefs.raw().edit().putString("snapshot",s.json().toString()).putString("networkError","").putLong("lastSuccess",s.checkedAt).apply();
        LiveGate.State state=prefs.gate();JSONObject cfg=prefs.config();
        LiveGate.Decision d=LiveGate.observe(state,s.status==1,s.start,s.checkedAt,prefs.raw().getLong("armedAt",s.checkedAt),cfg.optBoolean("catchUp"),t->prefs.allowed(t));
        boolean explicitlySnoozed=false;
        if(snoozeCheck){
            snoozeCheck=false;String expected=prefs.raw().getString("snoozeSession","");clearSnooze();
            explicitlySnoozed=s.status==1&&prefs.allowed(s.checkedAt)&&state.session.equals(expected);
            if(!explicitlySnoozed)prefs.log("skip","暂缓提醒已取消","本场已结束、场次变化或当前处于提醒时段之外");
        }
        if((d.ring||explicitlySnoozed)&&!ringing){
            if(beginAlarm(s.title,false)){
                state.notified=state.session;prefs.saveGate(state);clearSnooze();
                prefs.log("live",explicitlySnoozed?"再次提醒你看直播":"检测到灰泽满开播",s.title+(s.start==0?" · 开播时间缺失，按首次检测时间判断":""));
            }else{prefs.raw().edit().putString("serviceError","请允许通知，或启用“通知异常时仍响铃”").apply();if(!state.logged.equals(state.session+"permission")){state.logged=state.session+"permission";prefs.log("warning","发现开播，但尚未允许提醒","请允许通知或在应用设置中选择兼容响铃");}}
        }else if(s.status==1&&!d.reason.equals("duplicate")&&!state.logged.equals(state.session+":"+d.reason)){
            state.logged=state.session+":"+d.reason;
            if(d.reason.equals("outside")||d.reason.equals("outside_start"))prefs.log("skip","本场未响铃","开播或检测时间不在所选时段内");
            else if(d.reason.equals("already_live"))prefs.log("skip","开启守候时已在播","当前关闭了“补报已开播”，等待下一场直播");
        }
        prefs.saveGate(state);
        if(ringing&&!testing&&state.offlineSamples>=2){finishAlarm("ended",false);prefs.log("system","直播已结束","已停止本次响铃");}
        if(s.status!=1&&state.offlineSamples>=2)clearSnooze();
        updateWatch(prefs.allowed(s.checkedAt)?(s.status==1?"灰泽满正在直播 · 本场自动去重":"等待开播 · 每 "+cfg.optInt("pollSeconds")+" 秒检查"):"当前不在提醒时段 · 低频检查中");
        scheduleNext(0);
    }
    private void failed(String message,boolean limited){
        failures=Math.min(7,failures+1);String old=prefs.raw().getString("networkError","");
        prefs.raw().edit().putString("networkError",message).putLong("lastAttempt",System.currentTimeMillis()).apply();
        if(!message.equals(old))prefs.log("warning","暂时无法确认直播状态",message+"；保留上一状态，不当作下播");
        int delay=(int)Math.min(300,Math.max(limited?120:15,prefs.config().optInt("pollSeconds",30))*Math.pow(2,Math.min(failures-1,4)));
        updateWatch(message);scheduleNext(delay);
    }
    private void scheduleNext(int backoff){
        handler.removeCallbacks(poll);if(!prefs.enabled())return;
        int base=prefs.allowed(System.currentTimeMillis())?prefs.config().optInt("pollSeconds",30):180;
        int seconds=backoff>0?backoff:base;long delay=seconds*1000L+(long)(Math.random()*900);
        prefs.raw().edit().putLong("nextCheck",System.currentTimeMillis()+delay).apply();handler.postDelayed(poll,delay);
    }
    private boolean beginAlarm(String title,boolean test){
        if(ringing)return false;
        AlertPolicy.Mode mode=NotificationAccess.alertMode(this,prefs);
        if(mode==AlertPolicy.Mode.BLOCKED){
            if(test){prefs.log("warning","响铃测试未开始","请允许通知或在应用设置中选择兼容响铃");if(!prefs.enabled())stopSelf();}
            return false;
        }
        ringing=true;testing=test;soundTitle=title;soundBegan=SystemClock.elapsedRealtime();
        JSONObject c=prefs.config();long duration=c.optInt("duration",60)*1000L;
        prefs.raw().edit().putBoolean("alarmTest",test).putString("alarmTitle",title).putLong("alarmUntil",System.currentTimeMillis()+duration).apply();
        prefs.raw().edit().putString("lastAlarmMode",mode.name()).putString("serviceError","").apply();
        refreshNotices(true);
        if(mode!=AlertPolicy.Mode.NORMAL){
            // Keep the mandatory WATCH foreground notification. The OS controls its visibility.
            prefs.log("system","使用通知异常兼容响铃","你已允许通知未获准时仍响铃；系统通知授权未被修改");
        }
        soundLock.acquire(duration+15000);
        handler.removeCallbacks(stopSound);handler.postDelayed(stopSound,duration);
        boolean quiet=c.optBoolean("quietCalls",true)&&(audio.getMode()==AudioManager.MODE_IN_CALL||audio.getMode()==AudioManager.MODE_IN_COMMUNICATION);
        if(quiet)prefs.log("warning","通话中，改为振动提醒","声音设置中的“通话时不强响铃”已生效");
        try{if(c.optBoolean("vibrate")&&vibrator!=null&&vibrator.hasVibrator())vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0,500,250,500,1000},0),new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());}
        catch(RuntimeException e){prefs.log("warning","系统未能启动振动","继续尝试播放铃声");}
        if(!quiet){
            try{
                int old=audio.getStreamVolume(AudioManager.STREAM_ALARM),max=audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                int target=Math.max(1,(int)Math.ceil(c.optInt("volume",85)*max/100.0));
                prefs.raw().edit().putBoolean("restoreVolume",true).putInt("oldVolume",old).putInt("setVolume",target).commit();
                audio.setStreamVolume(AudioManager.STREAM_ALARM,target,0);
            }catch(Exception e){prefs.log("warning","系统限制调整闹钟音量","继续使用当前系统闹钟音量，请在测试中确认");}
            AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).setAudioAttributes(attributes).setOnAudioFocusChangeListener(change->{
                if(player==null)return;try{if(change==AudioManager.AUDIOFOCUS_GAIN&&ringing)player.start();else if(change<0&&player.isPlaying())player.pause();}catch(Exception ignored){}
            },handler).build();
            try{if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)prefs.log("warning","系统未授予声音焦点","尝试播放闹铃；请检查勿扰与通话状态");}
            catch(RuntimeException e){prefs.log("warning","声音焦点申请异常","请检查系统音频与后台设置");}
            playTone(attributes,false);
        }
        if(test)prefs.log("test","已开始响铃测试","与正式提醒使用相同的声音、音量及提醒模式："+mode.name());
        if(!MainActivity.revealAlarm()&&mode==AlertPolicy.Mode.SOUND_ONLY&&overlay!=null)
            overlay.show(title,test,c.optInt("duration",60),c.optInt("snoozeMinutes",5),()->finishAlarm("dismiss",false),()->snoozeAlarm());
        return true;
    }
    private void playTone(AudioAttributes attrs,boolean fallback){
        if(player!=null){try{player.release();}catch(Exception ignored){}}
        player=new MediaPlayer();player.setAudioAttributes(attrs);player.setLooping(true);
        float initial=prefs.config().optBoolean("ramp")?0.15f:1f;player.setVolume(initial,initial);
        player.setOnPreparedListener(p->{if(!ringing||p!=player){p.release();return;}try{p.start();prefs.raw().edit().putLong("lastAudioStartedAt",System.currentTimeMillis()).apply();handler.post(ramp);}catch(Exception e){toneFailed(attrs,fallback);}});
        player.setOnErrorListener((p,w,e)->{toneFailed(attrs,fallback);return true;});
        try{
            String tone=fallback?"starlight":prefs.config().optString("ringtone","starlight");
            if(tone.equals("custom"))player.setDataSource(prefs.raw().getString("customPath",""));
            else if(tone.equals("system")){
                Uri uri=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);if(uri==null)throw new IOException("系统闹钟铃声为空");player.setDataSource(this,uri);
            }else{
                int id=tone.equals("urgent")?R.raw.urgent:tone.equals("morning")?R.raw.morning:R.raw.starlight;
                try(android.content.res.AssetFileDescriptor fd=getResources().openRawResourceFd(id)){player.setDataSource(fd.getFileDescriptor(),fd.getStartOffset(),fd.getLength());}
            }
            player.prepareAsync();
        }catch(Exception e){toneFailed(attrs,fallback);}
    }
    private void toneFailed(AudioAttributes attrs,boolean fallback){
        if(!ringing)return;
        if(!fallback){prefs.log("warning","所选铃声不可用","自动改用内置星铃");playTone(attrs,true);}
        else{prefs.log("warning","手机未能播放铃声","通知和振动仍保留；请检查系统音频设置");if(player!=null){try{player.release();}catch(Exception ignored){}player=null;}}
    }
    private void finishAlarm(String reason,boolean keepSnooze){
        if(overlay!=null)overlay.hide();
        boolean wasRinging=ringing;ringing=false;handler.removeCallbacks(stopSound);handler.removeCallbacks(ramp);
        if(player!=null){try{player.stop();}catch(Exception ignored){}try{player.release();}catch(Exception ignored){}player=null;}
        try{if(vibrator!=null)vibrator.cancel();}catch(RuntimeException ignored){}
        if(focus!=null){try{audio.abandonAudioFocusRequest(focus);}catch(RuntimeException ignored){}focus=null;}
        release(soundLock);recoverVolume();prefs.raw().edit().putLong("alarmUntil",0).apply();
        if(!keepSnooze)clearSnooze();
        if(wasRinging&&"timeout".equals(reason))prefs.log("system","响铃已自动停止","达到设定时长；本场不会重复自动响铃");
        if(prefs.enabled()&&!destroyed){
            refreshNotices(true);
        }
        else{stopForeground(STOP_FOREGROUND_REMOVE);notifications.cancel(WATCH_ID);notifications.cancel(ALARM_ID);if(!destroyed)stopSelf();}
    }
    private void recoverVolume(){
        if(!prefs.raw().getBoolean("restoreVolume",false))return;
        try{if(audio.getStreamVolume(AudioManager.STREAM_ALARM)==prefs.raw().getInt("setVolume",-1))audio.setStreamVolume(AudioManager.STREAM_ALARM,prefs.raw().getInt("oldVolume",3),0);}catch(Exception ignored){}
        prefs.raw().edit().putBoolean("restoreVolume",false).commit();
    }
    private void clearSnooze(){AlarmScheduler.cancel(this,AlarmScheduler.SNOOZE);prefs.raw().edit().putLong("snoozeAt",0).putString("snoozeSession","").apply();}
    private void cancelTest(){AlarmScheduler.cancel(this,AlarmScheduler.TEST);prefs.raw().edit().putLong("testAt",0).apply();}
    private void release(PowerManager.WakeLock lock){try{if(lock!=null&&lock.isHeld())lock.release();}catch(Exception ignored){}}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onTaskRemoved(Intent rootIntent){prefs.log("system","应用页面已划走",prefs.enabled()?"前台守候继续运行；系统仍可按后台策略限制服务":"守候未开启");super.onTaskRemoved(rootIntent);}
    @Override public void onDestroy(){destroyed=true;running=false;generation++;if(active.get()==this)active.clear();handler.removeCallbacksAndMessages(null);finishAlarm("destroy",true);release(watchLock);if(notificationChanges!=null)try{unregisterReceiver(notificationChanges);}catch(Exception ignored){}if(connectivity!=null&&networkCallback!=null)try{connectivity.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}io.shutdownNow();super.onDestroy();}
}
