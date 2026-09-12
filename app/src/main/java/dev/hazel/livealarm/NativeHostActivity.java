package dev.hazel.livealarm;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.media.*;
import android.net.*;
import android.os.*;
import android.provider.*;
import android.view.*;
import androidx.activity.ComponentActivity;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public class NativeHostActivity extends ComponentActivity {
    private static final int AUDIO=50,EXPORT=51,IMPORT=52,NOTIFICATION_REPORT=53,COMPONENT_REPORT=54,BACKGROUND=55,NOTIFICATIONS=80;
    protected Prefs prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private final Runnable stateRefresh=()->{if(this.resumed&&!isFinishing())onNativeState(state());};
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private String pendingExport="";private boolean refreshing=false;private long refreshAt=0;
    private final Handler permissionHandler=new Handler(Looper.getMainLooper());
    private boolean resumed=false,notificationRequestInFlight=false;
    private final Runnable permissionRefresh=()->{if(resumed){GuardianService.refreshNotifications();push();}};
    private static java.lang.ref.WeakReference<NativeHostActivity> visible=new java.lang.ref.WeakReference<>(null);
    private boolean alarmOpening=false;
    private long notificationRequestBegan=0;
    // Only an already-visible activity can reveal the alarm. No background activity launch.
    static boolean revealAlarm(){
        NativeHostActivity a=visible.get();
        if(a==null||!a.resumed||a.isFinishing()||!a.hasWindowFocus())return false;
        GuardianService.hideAlarmOverlay();a.push();
        if(GuardianService.ringing&&!a.alarmPage()&&!a.alarmOpening){
            a.alarmOpening=true;
            try{a.startActivity(new Intent(a,AlarmActivity.class));}catch(RuntimeException e){a.alarmOpening=false;}
        }
        return true;
    }
    protected boolean alarmPage(){return false;}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);prefs=new Prefs(this);GuardianService.channels(this);
        if(alarmPage()){
            if(Build.VERSION.SDK_INT>=27){setShowWhenLocked(true);setTurnScreenOn(true);}
            else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        preferenceListener=(db,key)->{
            if(key!=null&&Arrays.asList("config","enabled","snapshot","networkError","serviceError","nextCheck","alarmUntil","alarmTitle","alarmTest","testAt","snoozeAt","customName","backgroundPath","backgroundName","recoveryAt","lastSuccess","serviceHeartbeatAt","history").contains(key))push();
        };
        prefs.raw().registerOnSharedPreferenceChangeListener(preferenceListener);
        applyRecents();
    }
    protected void onNativeState(JSONObject value){}
    public JSONObject readNativeState(){return state();}
    public Object performAction(String action,JSONObject data)throws Exception{
        Object result=dispatch(action,data);push();return result;
    }
    protected void applyTheme(){applyRecents();push();}
    public void applyRecents(){
        if(prefs==null)return;
        try{
            ActivityManager manager=(ActivityManager)getSystemService(ACTIVITY_SERVICE);
            for(ActivityManager.AppTask task:manager.getAppTasks()){
                ActivityManager.RecentTaskInfo info=task.getTaskInfo();
                boolean alarm=info.baseActivity!=null&&info.baseActivity.getClassName().equals(AlarmActivity.class.getName());
                task.setExcludeFromRecents(alarm||prefs.config().optBoolean("hideRecents",false));
            }
        }catch(RuntimeException e){toast("系统未能更新最近任务显示，请重新打开应用后重试");}
    }
    @Override protected void onResume(){
        super.onResume();resumed=true;visible=new java.lang.ref.WeakReference<>(this);alarmOpening=false;
        if(prefs!=null){
            NotificationAccess.record(this,prefs,"activity_resume",true);
            if(!alarmPage()&&prefs.enabled()&&!GuardianService.running)GuardianService.send(this,"CHECK");
            if(prefs.enabled())WatchRecovery.schedule(this,false);
        }
        applyRecents();refreshPermissionsAfterReturn();
    }
    @Override protected void onPause(){
        resumed=false;if(visible.get()==this)visible.clear();alarmOpening=false;
        permissionHandler.removeCallbacks(permissionRefresh);permissionHandler.removeCallbacks(stateRefresh);super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused&&resumed){refreshPermissionsAfterReturn();if(GuardianService.ringing)revealAlarm();}}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);applyRecents();push();}
    @Override protected void onDestroy(){
        resumed=false;permissionHandler.removeCallbacksAndMessages(null);
        if(prefs!=null&&preferenceListener!=null)prefs.raw().unregisterOnSharedPreferenceChangeListener(preferenceListener);
        io.shutdownNow();super.onDestroy();
    }
    private void push(){
        permissionHandler.removeCallbacks(stateRefresh);permissionHandler.postDelayed(stateRefresh,40);
    }
    private void refreshPermissionsAfterReturn(){
        permissionHandler.removeCallbacks(permissionRefresh);if(!resumed)return;
        GuardianService.refreshNotifications();push();
        permissionHandler.postDelayed(permissionRefresh,350);
        permissionHandler.postDelayed(permissionRefresh,1200);
        permissionHandler.postDelayed(permissionRefresh,2500);
    }
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private JSONObject state(){
        JSONObject j=new JSONObject();Prefs.put(j,"config",prefs.config());Prefs.put(j,"enabled",prefs.enabled());Prefs.put(j,"running",GuardianService.running);
        Prefs.put(j,"ringing",GuardianService.ringing);Prefs.put(j,"alarmTest",prefs.raw().getBoolean("alarmTest",false));Prefs.put(j,"alarmTitle",prefs.raw().getString("alarmTitle",""));Prefs.put(j,"alarmUntil",prefs.raw().getLong("alarmUntil",0));
        Prefs.put(j,"snapshot",Prefs.obj(prefs.raw().getString("snapshot","{}")));Prefs.put(j,"networkError",prefs.raw().getString("networkError",""));Prefs.put(j,"serviceError",prefs.raw().getString("serviceError",""));
        Prefs.put(j,"nextCheck",prefs.raw().getLong("nextCheck",0));Prefs.put(j,"snoozeAt",prefs.raw().getLong("snoozeAt",0));Prefs.put(j,"testAt",prefs.raw().getLong("testAt",0));Prefs.put(j,"inside",prefs.allowed(System.currentTimeMillis()));
        JSONObject c=prefs.config();Prefs.put(j,"zone",TimeRules.zone(c.optString("timezone")).getId());Prefs.put(j,"deviceZone",ZoneId.systemDefault().getId());
        Prefs.put(j,"now",System.currentTimeMillis());Prefs.put(j,"nextBoundary",TimeRules.nextBoundary(System.currentTimeMillis(),c.optBoolean("allDay"),prefs.windows(c),TimeRules.zone(c.optString("timezone"))));
        Prefs.put(j,"permissions",permissions());Prefs.put(j,"permissionsCheckedAt",System.currentTimeMillis());
        Prefs.put(j,"notificationRequestResult",prefs.raw().getString("notificationRequestResult","not_requested"));
        Prefs.put(j,"notificationRequestAt",prefs.raw().getLong("notificationRequestAt",0));
        Prefs.put(j,"alertMode",NotificationAccess.alertMode(this,prefs).name());Prefs.put(j,"overlayVisible",GuardianService.overlayVisible);
        Prefs.put(j,"watchNotification",NotificationAccess.watchState(this,prefs));
        Prefs.put(j,"backgroundPath",prefs.raw().getString("backgroundPath",""));
        Prefs.put(j,"backgroundName",prefs.raw().getString("backgroundName",""));
        Prefs.put(j,"recoveryAt",prefs.raw().getLong("recoveryAt",0));
        Prefs.put(j,"lastRecovery",prefs.raw().getLong("lastRecovery",0));
        Prefs.put(j,"serviceHeartbeatAt",prefs.raw().getLong("serviceHeartbeatAt",0));
        Prefs.put(j,"version",BuildConfig.VERSION_NAME);Prefs.put(j,"android",Build.VERSION.RELEASE);Prefs.put(j,"manufacturer",Build.MANUFACTURER);Prefs.put(j,"xiaomi",NotificationAccess.isXiaomi());Prefs.put(j,"preview",false);return j;
    }
    private JSONObject permissions(){
        JSONObject p=new JSONObject();NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
        boolean runtimeGranted=NotificationAccess.runtimeGranted(this);
        boolean appEnabled=n.areNotificationsEnabled();
        Prefs.put(p,"notificationRuntime",runtimeGranted);Prefs.put(p,"notificationAppEnabled",appEnabled);
        Prefs.put(p,"notificationMismatch",runtimeGranted!=appEnabled);Prefs.put(p,"notifications",runtimeGranted&&appEnabled);
        String policy=NotificationAccess.policyStatus(this);Prefs.put(p,"notificationPolicy",policy);
        NotificationAccess.record(this,prefs,"state_changed",false);
        NotificationChannel channel=n.getNotificationChannel(GuardianService.ALARM_CHANNEL);Prefs.put(p,"alarmChannel",channel!=null&&channel.getImportance()>=NotificationManager.IMPORTANCE_HIGH);
        Prefs.put(p,"alarmChannelImportance",channel==null?-1:channel.getImportance());
        NotificationChannel watchChannel=n.getNotificationChannel(GuardianService.WATCH_CHANNEL);
        Prefs.put(p,"watchChannel",watchChannel!=null&&watchChannel.getImportance()>NotificationManager.IMPORTANCE_NONE);
        String detail="系统通知授权："+(runtimeGranted?"已允许":"未允许")+"；应用通知总开关："+(appEnabled?"已开启":"未开启")+"；策略检查："+policy+"；强提醒通道等级："+(channel==null?-1:channel.getImportance());
        if(!detail.equals(prefs.raw().getString("notificationState",""))){
            prefs.raw().edit().putString("notificationState",detail).apply();
            prefs.log(runtimeGranted&&appEnabled?"system":"warning","通知权限检查",detail);
        }
        Prefs.put(p,"battery",power.isIgnoringBatteryOptimizations(getPackageName()));Prefs.put(p,"fullScreen",Build.VERSION.SDK_INT<34||n.canUseFullScreenIntent());Prefs.put(p,"exact",AlarmScheduler.exact(this));
        Prefs.put(p,"dnd",n.getCurrentInterruptionFilter()!=NotificationManager.INTERRUPTION_FILTER_ALL);Prefs.put(p,"powerSave",power.isPowerSaveMode());
        Prefs.put(p,"overlay",Settings.canDrawOverlays(this));Prefs.put(p,"accessibility",WatchAccessibilityService.enabled(this));Prefs.put(p,"accessibilityConnected",WatchAccessibilityService.connected);
        AudioManager a=(AudioManager)getSystemService(AUDIO_SERVICE);Prefs.put(p,"alarmVolume",a.getStreamVolume(AudioManager.STREAM_ALARM));Prefs.put(p,"alarmMax",a.getStreamMaxVolume(AudioManager.STREAM_ALARM));return p;
    }
    private void requireAlarmAccess()throws Exception{
        if(NotificationAccess.alertMode(this,prefs)==AlertPolicy.Mode.BLOCKED)
            throw new Exception("请允许通知，或在设置中开启“通知异常时仍响铃”后继续");
    }
    private Object dispatch(String action,JSONObject data)throws Exception{
        switch(action){
            case "state": return state();
            case "notificationDiagnostics": return NotificationAccess.diagnostics(this,prefs);
            case "notificationProbe":{
                if(!permissions().optBoolean("notifications"))throw new Exception("系统尚未允许通知，请先完成通知授权");
                NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
                NotificationChannel probeChannel=manager.getNotificationChannel(GuardianService.WATCH_CHANNEL);
                if(probeChannel==null||probeChannel.getImportance()==NotificationManager.IMPORTANCE_NONE)throw new Exception("请在通知设置中开启“后台守候”通道，再发送测试通知");
                PendingIntent open=PendingIntent.getActivity(this,701,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                Notification probe=new Notification.Builder(this,GuardianService.WATCH_CHANNEL).setSmallIcon(R.drawable.ic_bell)
                    .setContentTitle("满区闹钟 · 通知验证").setContentText("看到这条通知后，可返回应用测试正式响铃。")
                    .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS).build();
                ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(701,probe);
                prefs.log("system","已提交验证通知","请下拉通知栏确认是否看见；提交成功不代表系统一定展示");return true;
            }
            case "zones":{
                JSONArray zones=new JSONArray();Instant now=Instant.now();
                for(String id:new TreeSet<String>(ZoneId.getAvailableZoneIds())){ZoneId zone=ZoneId.of(id);JSONObject z=new JSONObject();Prefs.put(z,"id",id);Prefs.put(z,"offset",zone.getRules().getOffset(now).toString().replace("Z","+00:00"));Prefs.put(z,"time",now.atZone(zone).toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));zones.put(z);}return zones;
            }
            case "history": return prefs.history();
            case "save":{
                prefs.update(data);applyTheme();
                boolean schedule=data.has("windows")||data.has("allDay")||data.has("timezone")||data.has("catchUp");
                if(schedule)AlarmScheduler.boundaries(this);
                if(GuardianService.ringing&&NotificationAccess.alertMode(this,prefs)==AlertPolicy.Mode.BLOCKED)GuardianService.send(this,"DISMISS");
                if(prefs.enabled()&&(schedule||data.has("pollSeconds")||data.has("reliable")||data.has("soundWithoutNotifications")))GuardianService.send(this,"CHECK");
                if(data.has("recovery")){if(prefs.config().optBoolean("recovery",true))WatchRecovery.schedule(this,true);else WatchRecovery.cancel(this);}
                return prefs.config();
            }
            case "pickBackground":startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),BACKGROUND);return true;
            case "removeBackground":BackgroundStore.remove(this,prefs);return true;
            case "toggle":{
                boolean enable=data.getBoolean("enabled");if(enable)requireAlarmAccess();prefs.setEnabled(enable);
                if(!GuardianService.send(this,enable?"CHECK":"STOP_WATCH")){if(enable)prefs.setEnabled(false);throw new Exception("系统限制了后台启动，请检查权限后重试");}
                prefs.log("system",enable?"已开启守候":"已停止守候",enable?"开始按你的时间规则检测灰泽满的直播":"不再自动检测或响铃");return state();
            }
            case "refresh":refresh();return "正在检测直播状态";
            case "test":requireAlarmAccess();if(!GuardianService.send(this,"TEST"))throw new Exception("无法启动测试");startActivity(new Intent(this,AlarmActivity.class));return true;
            case "testLater":{
                requireAlarmAccess();if(!AlarmScheduler.exact(this))throw new Exception("锁屏定时测试需要先允许“精确闹钟”");
                long at=System.currentTimeMillis()+15000;prefs.raw().edit().putLong("testAt",at).commit();AlarmScheduler.at(this,AlarmScheduler.TEST,at);return at;
            }
            case "cancelTest":AlarmScheduler.cancel(this,AlarmScheduler.TEST);prefs.raw().edit().putLong("testAt",0).apply();if(GuardianService.running)GuardianService.send(this,"CANCEL_TEST");return true;
            case "dismiss":if(GuardianService.running)GuardianService.send(this,"DISMISS");if(alarmPage())finish();return true;
            case "snooze":if(GuardianService.running)GuardianService.send(this,"SNOOZE");if(alarmPage())finish();return true;
            case "cancelSnooze":AlarmScheduler.cancel(this,AlarmScheduler.SNOOZE);prefs.raw().edit().putLong("snoozeAt",0).putString("snoozeSession","").apply();return true;
            case "openAlarm":if(GuardianService.ringing)startActivity(new Intent(this,AlarmActivity.class));return true;
            case "openLive":if(alarmPage()&&GuardianService.running)GuardianService.send(this,"DISMISS");openLive();if(alarmPage())finish();return true;
            case "openProfile":startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://space.bilibili.com/"+Prefs.UID)));return true;
            case "permission":permission(data.getString("kind"));return true;
            case "pickAudio":startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"),AUDIO);return true;
            case "clearHistory":prefs.clearHistory();return true;
            case "export":{
                JSONObject payload=new JSONObject();Prefs.put(payload,"schema",1);Prefs.put(payload,"app","HazelAlarm");Prefs.put(payload,"exportedAt",System.currentTimeMillis());Prefs.put(payload,"settings",prefs.config());Prefs.put(payload,"history",prefs.history());Prefs.put(payload,"device",state());Prefs.put(payload,"notificationDiagnostics",NotificationAccess.diagnostics(this,prefs));pendingExport=payload.toString(2);
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"ManquAlarm-backup.json"),EXPORT);return true;
            }
            case "exportNotificationReport":{
                boolean components=data.optBoolean("includeComponents",false);
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip").putExtra(Intent.EXTRA_TITLE,"ManquAlarm-notification-report.zip"),components?COMPONENT_REPORT:NOTIFICATION_REPORT);return true;
            }
            case "import":startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json"),IMPORT);return true;
            case "minimize":moveTaskToBack(true);return true;
            case "closeAlarm":if(alarmPage())finish();return true;
            default:throw new Exception("暂不支持此操作");
        }
    }
    private void openLive(){
        Uri uri=Uri.parse("https://live.bilibili.com/"+Prefs.ROOM);
        try{startActivity(new Intent(Intent.ACTION_VIEW,uri).setPackage("tv.danmaku.bili"));}
        catch(ActivityNotFoundException e){try{startActivity(new Intent(Intent.ACTION_VIEW,uri));}catch(ActivityNotFoundException missing){toast("请安装 B 站或浏览器后打开直播间");}}
    }
    private void refresh(){
        if(prefs.enabled()){GuardianService.send(this,"CHECK");return;}
        if(refreshing||System.currentTimeMillis()-refreshAt<15000)return;refreshing=true;refreshAt=System.currentTimeMillis();
        io.execute(()->{try{BiliApi.Snapshot s=new BiliApi().fetch();prefs.raw().edit().putString("snapshot",s.json().toString()).putString("networkError","").putLong("lastSuccess",s.checkedAt).apply();}
        catch(Exception e){prefs.raw().edit().putString("networkError","暂时无法连接 B 站，请检查网络后刷新").apply();}finally{runOnUiThread(()->{refreshing=false;push();});}});
    }
    private void permission(String kind){
        Intent i;
        switch(kind){
            case "notifications":
                if(notificationRequestInFlight)return;
                if("revoked".equals(NotificationAccess.policyStatus(this))){
                    NotificationAccess.record(this,prefs,"request_blocked_by_policy",true);
                    toast("系统策略正在拒绝通知授权，请查看“通知授权帮助”并导出排查包");push();return;
                }
                if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
                    // Each explicit tap may retry the public runtime request. Cached rejection is not authority.
                    prefs.raw().edit().putBoolean("notificationRequested",true).apply();
                    notificationRequestInFlight=true;notificationRequestBegan=SystemClock.elapsedRealtime();
                    NotificationAccess.record(this,prefs,"before_runtime_request",true);
                    try{requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATIONS);return;}
                    catch(RuntimeException e){notificationRequestInFlight=false;}
                }
                // A rejected/non-displayable runtime prompt needs a user-accessible settings route.
                if(!NotificationAccess.open(this,false,false,prefs))toast("请从手机设置打开本应用的通知权限");return;
            case "notificationSettings":if(!NotificationAccess.open(this,false,false,prefs))toast("请从手机设置打开本应用的通知权限");return;
            case "standardNotifications":if(!NotificationAccess.open(this,false,true,prefs))toast("请从手机设置打开本应用的通知权限");return;
            case "appPermissions":if(!NotificationAccess.open(this,true,false,prefs))toast("请从手机设置打开本应用的权限管理");return;
            case "alarmChannel":i=new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID,GuardianService.ALARM_CHANNEL);break;
            case "watchChannel":i=new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID,GuardianService.WATCH_CHANNEL);break;
            case "battery":i=new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+getPackageName()));break;
            case "fullScreen":if(Build.VERSION.SDK_INT<34){toast("此系统版本无需单独授权全屏提醒");return;}i=new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:"+getPackageName()));break;
            case "exact":if(Build.VERSION.SDK_INT<31){toast("此系统版本已允许精确闹钟");return;}i=new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName()));break;
            case "dnd":i=new Intent("android.settings.ZEN_MODE_SETTINGS");break;
            case "overlay":i=new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()));break;
            case "accessibility":i=new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);break;
            default:i=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()));
        }
        try{startActivity(i);}catch(Exception e){try{startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}catch(Exception ignored){toast("请在手机设置中打开满区闹钟的应用设置");}}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==NOTIFICATIONS){
            notificationRequestInFlight=false;
            String result="cancelled";
            for(int k=0;k<permissions.length&&k<grants.length;k++)
                if(Manifest.permission.POST_NOTIFICATIONS.equals(permissions[k]))result=grants[k]==PackageManager.PERMISSION_GRANTED?"granted":"denied";
            prefs.raw().edit().putString("notificationRequestResult",result).putLong("notificationRequestAt",System.currentTimeMillis()).apply();
            long elapsed=notificationRequestBegan>0?SystemClock.elapsedRealtime()-notificationRequestBegan:-1;
            prefs.raw().edit().putLong("notificationRequestDurationMs",elapsed).apply();
            prefs.log("system","通知授权返回结果",result+" · "+elapsed+" ms");
            NotificationAccess.record(this,prefs,"runtime_callback_"+result,true);
            if(!"granted".equals(result))toast("revoked".equals(NotificationAccess.policyStatus(this))
                ?"系统策略拒绝了通知授权，请在“通知授权帮助”中导出排查包"
                :"通知尚未获准；可打开系统通知设置，或在应用设置中启用“通知异常时仍响铃”");
        }
        refreshPermissionsAfterReturn();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        if(request==NOTIFICATION_REPORT||request==COMPONENT_REPORT){
            NotificationAccess.record(this,prefs,"report_export",true);
            JSONObject report=new JSONObject();Prefs.put(report,"schema",1);Prefs.put(report,"app","满区闹钟");
            Prefs.put(report,"exportedAt",System.currentTimeMillis());Prefs.put(report,"notificationDiagnostics",NotificationAccess.diagnostics(this,prefs));
            String diagnostic=report.toString();boolean components=request==COMPONENT_REPORT;
            toast("正在保存通知排查包…");
            io.execute(()->{try{
                NotificationReport.write(getApplicationContext(),getContentResolver().openOutputStream(uri,"wt"),diagnostic,components);
                runOnUiThread(()->toast("通知排查包已保存，请把 ZIP 文件发回；不会自动发送"));
            }catch(Exception e){
                try{DocumentsContract.deleteDocument(getContentResolver(),uri);}catch(Exception ignored){}
                runOnUiThread(()->toast("排查包未完整保存，请重新导出："+e.getClass().getSimpleName()));
            }});return;
        }
        if(request==BACKGROUND){
            toast("正在处理背景图片…");
            io.execute(()->{try{BackgroundStore.importImage(this,prefs,uri);runOnUiThread(()->{toast("背景已保存，原图片移动后仍可使用");push();});}
                catch(Exception e){runOnUiThread(()->toast("背景导入失败："+e.getMessage()));}});return;
        }
        if(request==AUDIO){toast("正在导入铃声…");io.execute(()->importAudio(uri));}
        if(request==EXPORT){String text=pendingExport;io.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException();out.write(text.getBytes("UTF-8"));runOnUiThread(()->toast("备份已导出；自选铃声文件不包含在备份中"));}catch(Exception e){runOnUiThread(()->toast("导出失败，请检查保存位置"));}});}
        if(request==IMPORT)io.execute(()->{
            try{String text=readBounded(uri,1048576);JSONObject backup=new JSONObject(text);if(!"HazelAlarm".equals(backup.optString("app"))||backup.optInt("schema")!=1)throw new IOException("不是有效的满区闹钟备份");JSONObject settings=backup.getJSONObject("settings");if("custom".equals(settings.optString("ringtone"))&&prefs.raw().getString("customPath","").isEmpty())settings.put("ringtone","starlight");
                runOnUiThread(()->new AlertDialog.Builder(this).setTitle("恢复提醒设置？").setMessage("将替换当前时段和声音设置，不恢复旧通知记录。自选铃声需要另行导入。"+(settings.optBoolean("soundWithoutNotifications")?"此备份会开启通知异常时仍响铃，即使通知未获准也按所选设置播放闹铃。":"")).setNegativeButton("取消",null).setPositiveButton("恢复",(dialog,which)->{try{prefs.update(settings);AlarmScheduler.boundaries(this);if(prefs.enabled())GuardianService.send(this,"CHECK");applyTheme();if(prefs.enabled())WatchRecovery.schedule(this,true);push();toast("设置已恢复");}catch(Exception e){toast("恢复失败："+e.getMessage());}}).show());
            }catch(Exception e){runOnUiThread(()->toast("备份读取失败："+e.getMessage()));}
        });
    }
    private String readBounded(Uri uri,int cap)throws IOException{try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in==null)throw new IOException("文件不可读");byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>cap)throw new IOException("文件过大");}return out.toString("UTF-8");}}
    private void importAudio(Uri uri){
        File dest=new File(getFilesDir(),"tone-"+UUID.randomUUID()+".audio");
        try{
            String name="自选铃声";try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())name=cursor.getString(0);}
            try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(dest)){if(in==null)throw new IOException("无法读取音频");byte[] b=new byte[8192];long size=0;int n;while((n=in.read(b))!=-1){size+=n;if(size>30L*1024*1024)throw new IOException("请选择小于 30 MB 的音频");out.write(b,0,n);}if(size==0)throw new IOException("音频文件为空");}
            MediaMetadataRetriever metadata=new MediaMetadataRetriever();try{metadata.setDataSource(dest.getAbsolutePath());String duration=metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);if(duration==null||Long.parseLong(duration)<=0)throw new IOException("此音频无法播放，请选择 MP3、M4A、OGG 或 WAV");}finally{metadata.release();}
            String old=prefs.raw().getString("customPath","");prefs.raw().edit().putString("customPath",dest.getAbsolutePath()).putString("customName",name.length()>100?name.substring(0,100):name).commit();JSONObject change=new JSONObject();change.put("ringtone","custom");prefs.update(change);
            if(!old.isEmpty()){File oldFile=new File(old);if(oldFile.getParentFile().equals(getFilesDir()))oldFile.delete();}
            runOnUiThread(()->{toast("铃声已导入，原文件移动后仍可使用");push();});
        }catch(Exception e){dest.delete();runOnUiThread(()->toast("导入失败："+e.getMessage()));}
    }
}

