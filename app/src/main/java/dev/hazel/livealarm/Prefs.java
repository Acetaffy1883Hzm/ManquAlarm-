package dev.hazel.livealarm;

import android.content.*;
import org.json.*;
import java.util.*;
import java.time.*;

public final class Prefs {
    public static final long UID=1298779265L, ROOM=1713546334L;
    public static final String NAME="灰泽满Hazel";
    private final SharedPreferences db;
    public Prefs(Context c){db=c.getSharedPreferences("hazel",Context.MODE_PRIVATE);}
    public SharedPreferences raw(){return db;}
    public static void put(JSONObject j,String key,Object value){try{j.put(key,value);}catch(JSONException e){throw new IllegalArgumentException(e);}}
    public static JSONObject obj(String s){try{return new JSONObject(s);}catch(Exception e){return new JSONObject();}}
    public static JSONArray array(String s){try{return new JSONArray(s);}catch(Exception e){return new JSONArray();}}
    public JSONObject defaults(){
        JSONObject j=new JSONObject();
        put(j,"soundWithoutNotifications",false); put(j,"allDay",true); put(j,"timezone","device"); put(j,"catchUp",false);
        put(j,"pollSeconds",30); put(j,"reliable",true); put(j,"boot",true);
        put(j,"ringtone","starlight"); put(j,"customName",db.getString("customName","未选择"));
        put(j,"volume",85); put(j,"ramp",true); put(j,"vibrate",true); put(j,"duration",60);
        put(j,"snoozeMinutes",5); put(j,"quietCalls",true); put(j,"theme","light");
        JSONArray rules=new JSONArray(); JSONObject w=new JSONObject();
        put(w,"id","night");put(w,"name","凌晨守候");put(w,"start",60);put(w,"end",360);put(w,"days",127);put(w,"enabled",true);
        rules.put(w);put(j,"windows",rules);return j;
    }
    public synchronized JSONObject config(){
        JSONObject base=defaults(), saved=obj(db.getString("config","{}"));
        Iterator<String> it=saved.keys();while(it.hasNext()){String k=it.next();put(base,k,saved.opt(k));}
        put(base,"customName",db.getString("customName","未选择"));return base;
    }
    public synchronized void update(JSONObject patch) throws JSONException {
        JSONObject j=config();
        String[] bools={"allDay","catchUp","reliable","boot","ramp","vibrate","quietCalls","soundWithoutNotifications"};
        for(String k:bools) if(patch.has(k)){if(!(patch.get(k) instanceof Boolean))throw new JSONException("开关值无效");put(j,k,patch.getBoolean(k));}
        intSetting(j,patch,"volume",1,100);choiceInt(j,patch,"pollSeconds",new int[]{15,30,60,120});
        choiceInt(j,patch,"duration",new int[]{15,30,60,120,300});choiceInt(j,patch,"snoozeMinutes",new int[]{3,5,10,15});
        if(patch.has("timezone")){String zone=patch.getString("timezone");try{TimeRules.zone(zone);}catch(Exception e){throw new JSONException("时区无效");}put(j,"timezone",zone);}
        if(patch.has("theme")){String v=patch.getString("theme");if(!Arrays.asList("light","dark","system").contains(v))throw new JSONException("主题无效");put(j,"theme",v);}
        if(patch.has("ringtone")){String v=patch.getString("ringtone");if(!Arrays.asList("starlight","morning","urgent","system","custom").contains(v))throw new JSONException("铃声无效");if("custom".equals(v)&&db.getString("customPath","").isEmpty())throw new JSONException("请先导入音频文件");put(j,"ringtone",v);}
        if(patch.has("windows")){
            JSONArray a=patch.getJSONArray("windows");if(a.length()>32)throw new JSONException("最多支持 32 个时段");
            JSONArray cleaned=new JSONArray();Set<String> ids=new HashSet<>();
            for(int i=0;i<a.length();i++){
                JSONObject w=a.getJSONObject(i);int start=w.getInt("start"),end=w.getInt("end"),days=w.getInt("days");
                String id=w.optString("id",UUID.randomUUID().toString()),name=w.optString("name","自定义时段");
                if(id.length()>80||ids.contains(id))throw new JSONException("时段编号无效");ids.add(id);
                if(name.length()>24)throw new JSONException("时段名称最多 24 个字");
                try{new TimeRules.Window(id,name,start,end,days,w.optBoolean("enabled",true));}catch(Exception e){throw new JSONException(e.getMessage());}
                JSONObject out=new JSONObject();put(out,"id",id);put(out,"name",name);put(out,"start",start);put(out,"end",end);put(out,"days",days);put(out,"enabled",w.optBoolean("enabled",true));cleaned.put(out);
            }
            put(j,"windows",cleaned);
        }
        if(!j.optBoolean("allDay")){
            boolean any=false;JSONArray a=j.optJSONArray("windows");for(int i=0;i<a.length();i++)if(a.optJSONObject(i).optBoolean("enabled"))any=true;
            if(!any)throw new JSONException("请至少启用一个提醒时段，或选择全天提醒");
        }
        db.edit().putString("config",j.toString()).commit();
    }
    private void intSetting(JSONObject j,JSONObject p,String k,int min,int max)throws JSONException{if(p.has(k)){int v=p.getInt(k);if(v<min||v>max)throw new JSONException("数值超出范围");put(j,k,v);}}
    private void choiceInt(JSONObject j,JSONObject p,String k,int[] choices)throws JSONException{if(p.has(k)){int v=p.getInt(k);for(int a:choices)if(a==v){put(j,k,v);return;}throw new JSONException("选项无效");}}
    public List<TimeRules.Window> windows(JSONObject config){
        ArrayList<TimeRules.Window> list=new ArrayList<>();JSONArray a=config.optJSONArray("windows");if(a==null)return list;
        for(int i=0;i<a.length();i++){JSONObject w=a.optJSONObject(i);try{list.add(new TimeRules.Window(w.optString("id"),w.optString("name"),w.optInt("start"),w.optInt("end"),w.optInt("days"),w.optBoolean("enabled")));}catch(Exception ignored){}}
        return list;
    }
    public boolean allowed(long now){JSONObject c=config();return TimeRules.contains(now,c.optBoolean("allDay"),windows(c),TimeRules.zone(c.optString("timezone")));}
    public boolean enabled(){return db.getBoolean("enabled",false);}
    public synchronized void setEnabled(boolean value){
        SharedPreferences.Editor e=db.edit().putBoolean("enabled",value);
        if(value&&!enabled()){e.putLong("armedAt",System.currentTimeMillis());LiveGate.State s=gate();s.baseline=true;saveGate(s);}
        e.commit();
    }
    public LiveGate.State gate(){JSONObject j=obj(db.getString("gate","{}"));LiveGate.State s=new LiveGate.State();s.session=j.optString("session");s.notified=j.optString("notified");s.logged=j.optString("logged");s.started=j.optLong("started");s.firstSeen=j.optLong("firstSeen");s.sourceStart=j.optLong("sourceStart");s.offlineSince=j.optLong("offlineSince");s.offlineSamples=j.optInt("offlineSamples");s.baseline=j.optBoolean("baseline",true);s.unknownBaseline=j.optBoolean("unknownBaseline");return s;}
    public synchronized void saveGate(LiveGate.State s){JSONObject j=new JSONObject();put(j,"session",s.session);put(j,"notified",s.notified);put(j,"logged",s.logged);put(j,"started",s.started);put(j,"firstSeen",s.firstSeen);put(j,"sourceStart",s.sourceStart);put(j,"offlineSince",s.offlineSince);put(j,"offlineSamples",s.offlineSamples);put(j,"baseline",s.baseline);put(j,"unknownBaseline",s.unknownBaseline);db.edit().putString("gate",j.toString()).commit();}
    public synchronized void log(String type,String title,String detail){
        JSONArray old=array(db.getString("history","[]")),a=new JSONArray();JSONObject entry=new JSONObject();
        put(entry,"at",System.currentTimeMillis());put(entry,"type",type);put(entry,"title",title);put(entry,"detail",detail);a.put(entry);
        for(int i=0;i<Math.min(199,old.length());i++)a.put(old.opt(i));db.edit().putString("history",a.toString()).apply();
    }
    public JSONArray history(){return array(db.getString("history","[]"));}
    public void clearHistory(){db.edit().putString("history","[]").apply();}
}
